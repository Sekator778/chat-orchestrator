package com.example.telegramuserbot.service.ranking;

import com.example.telegramuserbot.domain.MessageEntity;
import com.example.telegramuserbot.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Implementation of clustering service using SimHash similarity detection.
 */
@Service
public final class ClusteringServiceImpl implements ClusteringService {

    private static final Logger log = LoggerFactory.getLogger(ClusteringServiceImpl.class);
    private static final int SIMILARITY_THRESHOLD = 10;
    private static final int BATCH_SIZE = 500;
    private static final int CANDIDATE_LIMIT = 1000;
    // Bound the heal pass so a large headless backlog (e.g. first run after a deploy gap)
    // doesn't saturate the shared R2DBC pool with reset/find/update chains.
    private static final int HEAL_CONCURRENCY = 8;
    private final MessageRepository messageRepository;
    private final SimHashService simHashService;

    public ClusteringServiceImpl(MessageRepository messageRepository, SimHashService simHashService) {
        this.messageRepository = messageRepository;
        this.simHashService = simHashService;
    }

    @Override
    public Mono<Integer> clusterRecentMessages(Duration window) {
        Instant since = Instant.now().minus(window);
        log.info("Starting clustering job for messages since {}", since);
        AtomicInteger processedCount = new AtomicInteger(0);
        return messageRepository.findUnclusteredMessages(since, BATCH_SIZE)
                .flatMap(message -> clusterSingleMessage(message, since)
                        .doOnSuccess(clusterId -> {
                            if (clusterId != null) {
                                processedCount.incrementAndGet();
                            }
                        })
                        .onErrorResume(e -> {
                            log.warn("Failed to cluster message {}: {}", message.getId(), e.getMessage());
                            return Mono.empty();
                        }))
                .then(Mono.fromSupplier(processedCount::get))
                .doOnSuccess(count -> log.info("Clustered {} messages", count));
    }

    @Override
    public Mono<String> clusterMessage(Long messageId) {
        return messageRepository.findById(messageId)
                .flatMap(message -> clusterSingleMessage(message, Instant.now().minus(Duration.ofDays(7))));
    }

    @Override
    public Mono<Integer> recalculatePrimaryMessages(Duration window) {
        Instant since = Instant.now().minus(window);
        log.info("Recalculating primary messages for clusters since {}", since);
        AtomicInteger updatedCount = new AtomicInteger(0);
        return messageRepository.findDistinctClusterIds(since)
                .flatMap(clusterId -> updateClusterPrimary(clusterId)
                        .doOnSuccess(updated -> {
                            if (Boolean.TRUE.equals(updated)) {
                                updatedCount.incrementAndGet();
                            }
                        })
                        .onErrorResume(e -> {
                            log.warn("Failed to update primary for cluster {}: {}", clusterId, e.getMessage());
                            return Mono.just(false);
                        }))
                .then(Mono.fromSupplier(updatedCount::get))
                .doOnSuccess(count -> log.info("Updated primary messages for {} clusters", count));
    }

    @Override
    public Mono<Integer> healHeadlessClusters() {
        AtomicInteger healedCount = new AtomicInteger(0);
        return messageRepository.findHeadlessClusterIds()
                .flatMap(clusterId -> updateClusterPrimary(clusterId)
                        .doOnSuccess(updated -> {
                            if (Boolean.TRUE.equals(updated)) {
                                healedCount.incrementAndGet();
                            }
                        })
                        .onErrorResume(e -> {
                            log.warn("Failed to heal headless cluster {}: {}", clusterId, e.getMessage());
                            return Mono.just(false);
                        }), HEAL_CONCURRENCY)
                .then(Mono.fromSupplier(healedCount::get))
                .doOnSuccess(count -> {
                    if (count > 0) {
                        log.info("Healed {} headless clusters (designated a primary)", count);
                    }
                });
    }

    private Mono<String> clusterSingleMessage(MessageEntity message, Instant since) {
        if (message.getContentSimhash() == null || message.getContentSimhash().equals("0".repeat(16))) {
            return Mono.empty();
        }
        return findSimilarCluster(message, since)
                .switchIfEmpty(Mono.defer(() -> createNewCluster(message)))
                .flatMap(clusterId -> assignToCluster(message, clusterId).thenReturn(clusterId));
    }

    private Mono<String> findSimilarCluster(MessageEntity message, Instant since) {
        return messageRepository.findCandidatesForClustering(message.getId(), since, CANDIDATE_LIMIT)
                .filter(candidate -> candidate.getContentSimhash() != null)
                .filter(candidate -> simHashService.similar(
                        message.getContentSimhash(),
                        candidate.getContentSimhash(),
                        SIMILARITY_THRESHOLD))
                .filter(candidate -> candidate.getClusterId() != null)
                .next()
                .map(MessageEntity::getClusterId);
    }

    private Mono<String> createNewCluster(MessageEntity message) {
        String clusterId = generateClusterId(message);
        return Mono.just(clusterId);
    }

    private Mono<Void> assignToCluster(MessageEntity message, String clusterId) {
        return messageRepository.updateClusterAssignment(message.getId(), clusterId, false)
                .doOnSuccess(count -> log.debug("Assigned message {} to cluster {}", message.getId(), clusterId))
                .then();
    }

    private Mono<Boolean> updateClusterPrimary(String clusterId) {
        return messageRepository.resetClusterPrimary(clusterId)
                .then(messageRepository.findByClusterId(clusterId)
                        .reduce((m1, m2) -> {
                            double score1 = m1.getImportance() != null ? m1.getImportance() : 0.0;
                            double score2 = m2.getImportance() != null ? m2.getImportance() : 0.0;
                            return score1 >= score2 ? m1 : m2;
                        }))
                .flatMap(primary -> messageRepository.updateClusterAssignment(
                        primary.getId(), clusterId, true))
                .map(count -> count > 0);
    }

    private String generateClusterId(MessageEntity message) {
        String timestamp = Long.toHexString(message.getDate().toEpochMilli());
        String random = UUID.randomUUID().toString().substring(0, 8);
        return "c" + timestamp + random;
    }
}
