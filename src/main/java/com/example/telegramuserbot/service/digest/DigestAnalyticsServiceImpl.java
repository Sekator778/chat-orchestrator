package com.example.telegramuserbot.service.digest;

import com.example.telegramuserbot.domain.DigestHistory;
import com.example.telegramuserbot.domain.DigestPersona;
import com.example.telegramuserbot.domain.DigestStatus;
import com.example.telegramuserbot.domain.SourceTrust;
import com.example.telegramuserbot.dto.digest.ClusterStatsDto;
import com.example.telegramuserbot.dto.digest.DigestAnalyticsDto;
import com.example.telegramuserbot.dto.digest.SourceStatsDto;
import com.example.telegramuserbot.repository.ChannelRepository;
import com.example.telegramuserbot.repository.DigestHistoryRepository;
import com.example.telegramuserbot.repository.DigestPersonaRepository;
import com.example.telegramuserbot.repository.MessageRepository;
import com.example.telegramuserbot.repository.SourceTrustRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Implementation of DigestAnalyticsService.
 * Aggregates metrics from multiple repositories to provide comprehensive analytics.
 */
@Service
public final class DigestAnalyticsServiceImpl implements DigestAnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(DigestAnalyticsServiceImpl.class);
    private static final Duration OPERATION_TIMEOUT = Duration.ofSeconds(10);
    private static final int DEFAULT_LOOKBACK_HOURS = 24;
    private static final int DEFAULT_ACTIVITY_LIMIT = 20;
    private static final int MAX_CONTENT_PREVIEW = 100;
    private static final double HIGH_TRUST_THRESHOLD = 0.7;
    private static final double LOW_TRUST_THRESHOLD = 0.3;

    private final DigestPersonaRepository personaRepository;
    private final DigestHistoryRepository historyRepository;
    private final MessageRepository messageRepository;
    private final SourceTrustRepository sourceTrustRepository;
    private final ChannelRepository channelRepository;

    public DigestAnalyticsServiceImpl(
            DigestPersonaRepository personaRepository,
            DigestHistoryRepository historyRepository,
            MessageRepository messageRepository,
            SourceTrustRepository sourceTrustRepository,
            ChannelRepository channelRepository) {
        this.personaRepository = Objects.requireNonNull(personaRepository);
        this.historyRepository = Objects.requireNonNull(historyRepository);
        this.messageRepository = Objects.requireNonNull(messageRepository);
        this.sourceTrustRepository = Objects.requireNonNull(sourceTrustRepository);
        this.channelRepository = Objects.requireNonNull(channelRepository);
    }

    @Override
    public Mono<DigestAnalyticsDto> getAnalytics() {
        return getAnalytics(DEFAULT_LOOKBACK_HOURS);
    }

    @Override
    public Mono<DigestAnalyticsDto> getAnalytics(int lookbackHours) {
        log.info("Generating digest analytics for last {} hours", lookbackHours);
        Instant since = Instant.now().minus(Duration.ofHours(lookbackHours));
        Mono<Long> personasCount = countPersonas();
        Mono<Long> activeCount = countActivePersonas();
        Mono<Long> totalDigests = countTotalDigests();
        Mono<Long> publishedDigests = countPublishedDigests();
        Mono<Double> successRate = calculateOverallSuccessRate();
        Mono<Double> avgGenTime = calculateAverageGenerationTime();
        Mono<Long> messagesProcessed = countMessagesProcessed(since);
        Mono<Long> clustersFormed = countClustersFormed(since);
        Mono<Long> digestsPublished = countDigestsPublished(since);
        Mono<List<DigestAnalyticsDto.ActivityEntry>> activity = getRecentActivity(DEFAULT_ACTIVITY_LIMIT);
        Mono<List<DigestAnalyticsDto.PersonaStats>> personaStats = getPersonaStatsList();
        Mono<Object[]> firstGroup = Mono.zip(personasCount, activeCount, totalDigests, publishedDigests, successRate, avgGenTime)
                .map(t -> new Object[]{t.getT1(), t.getT2(), t.getT3(), t.getT4(), t.getT5(), t.getT6()});
        Mono<Object[]> secondGroup = Mono.zip(messagesProcessed, clustersFormed, digestsPublished, activity, personaStats)
                .map(t -> new Object[]{t.getT1(), t.getT2(), t.getT3(), t.getT4(), t.getT5()});
        return Mono.zip(firstGroup, secondGroup)
                .map(tuple -> {
                    Object[] first = tuple.getT1();
                    Object[] second = tuple.getT2();
                    return new DigestAnalyticsDto(
                            ((Long) first[0]).intValue(),
                            ((Long) first[1]).intValue(),
                            (Long) first[2],
                            (Long) first[3],
                            (Double) first[4],
                            (Double) first[5],
                            (Long) second[0],
                            (Long) second[1],
                            (Long) second[2],
                            (List<DigestAnalyticsDto.ActivityEntry>) second[3],
                            (List<DigestAnalyticsDto.PersonaStats>) second[4],
                            Instant.now()
                    );
                })
                .timeout(OPERATION_TIMEOUT)
                .doOnSuccess(dto -> log.info("Analytics generated: personas={}, digests={}, successRate={}%",
                        dto.totalPersonas(), dto.totalDigestsGenerated(), String.format("%.1f", dto.overallSuccessRate())))
                .onErrorResume(e -> {
                    log.error("Failed to generate analytics: {}", e.getMessage());
                    return Mono.just(DigestAnalyticsDto.empty());
                });
    }

    @Override
    public Mono<ClusterStatsDto> getClusterStats() {
        return getClusterStats(DEFAULT_LOOKBACK_HOURS);
    }

    @Override
    public Mono<ClusterStatsDto> getClusterStats(int lookbackHours) {
        log.info("Generating cluster stats for last {} hours", lookbackHours);
        Instant since = Instant.now().minus(Duration.ofHours(lookbackHours));
        return Mono.zip(
                countDistinctClusters(since),
                countClustersFormed(since),
                calculateAverageClusterSize(since),
                calculateDeduplicationRate(since),
                countUnclusteredMessages(since),
                getTopClusters(since, 10)
        ).map(tuple -> new ClusterStatsDto(
                tuple.getT1(),
                tuple.getT2(),
                tuple.getT3(),
                tuple.getT4(),
                tuple.getT5(),
                0.0,
                tuple.getT6(),
                Instant.now()
        )).timeout(OPERATION_TIMEOUT)
                .doOnSuccess(dto -> log.info("Cluster stats generated: total={}, today={}, avgSize={}",
                        dto.totalClusters(), dto.clustersToday(), String.format("%.1f", dto.averageClusterSize())))
                .onErrorResume(e -> {
                    log.error("Failed to generate cluster stats: {}", e.getMessage());
                    return Mono.just(ClusterStatsDto.empty());
                });
    }

    @Override
    public Mono<SourceStatsDto> getSourceStats() {
        log.info("Generating source trust stats");
        return Mono.zip(
                countTotalSources(),
                countHighTrustSources(),
                countLowTrustSources(),
                calculateAverageTrustScore(),
                getSourceDetails(20),
                calculateTrustDistribution()
        ).map(tuple -> new SourceStatsDto(
                tuple.getT1(),
                tuple.getT2(),
                tuple.getT3(),
                tuple.getT4(),
                tuple.getT5(),
                tuple.getT6(),
                Instant.now()
        )).timeout(OPERATION_TIMEOUT)
                .doOnSuccess(dto -> log.info("Source stats generated: total={}, highTrust={}, avgScore={}",
                        dto.totalSources(), dto.highTrustSources(), String.format("%.2f", dto.averageTrustScore())))
                .onErrorResume(e -> {
                    log.error("Failed to generate source stats: {}", e.getMessage());
                    return Mono.just(SourceStatsDto.empty());
                });
    }

    @Override
    public Mono<DigestAnalyticsDto.PersonaStats> getPersonaStats(Long personaId) {
        Objects.requireNonNull(personaId, "personaId must not be null");
        log.debug("Getting stats for persona: {}", personaId);
        return personaRepository.findById(personaId)
                .timeout(OPERATION_TIMEOUT)
                .flatMap(this::buildPersonaStats)
                .doOnSuccess(stats -> log.debug("Persona stats retrieved: id={}, digests={}", personaId, stats.totalDigests()))
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Persona not found: " + personaId)));
    }

    @Override
    public Mono<List<DigestAnalyticsDto.ActivityEntry>> getRecentActivity(int limit) {
        log.debug("Getting recent activity: limit={}", limit);
        return historyRepository.findAllRecent(limit)
                .timeout(OPERATION_TIMEOUT)
                .flatMap(this::historyToActivity)
                .collectList()
                .doOnSuccess(list -> log.debug("Retrieved {} activity entries", list.size()));
    }

    private Mono<Long> countPersonas() {
        return personaRepository.count()
                .timeout(OPERATION_TIMEOUT)
                .onErrorReturn(0L);
    }

    private Mono<Long> countActivePersonas() {
        return personaRepository.findAllEnabled()
                .timeout(OPERATION_TIMEOUT)
                .count()
                .onErrorReturn(0L);
    }

    private Mono<Long> countTotalDigests() {
        return historyRepository.count()
                .timeout(OPERATION_TIMEOUT)
                .onErrorReturn(0L);
    }

    private Mono<Long> countPublishedDigests() {
        return historyRepository.findByStatus(DigestStatus.PUBLISHED.name())
                .timeout(OPERATION_TIMEOUT)
                .count()
                .onErrorReturn(0L);
    }

    private Mono<Double> calculateOverallSuccessRate() {
        return historyRepository.count()
                .timeout(OPERATION_TIMEOUT)
                .flatMap(total -> {
                    if (total == 0) {
                        return Mono.just(0.0);
                    }
                    return historyRepository.findByStatus(DigestStatus.PUBLISHED.name())
                            .timeout(OPERATION_TIMEOUT)
                            .count()
                            .map(published -> (published * 100.0) / total);
                })
                .onErrorReturn(0.0);
    }

    private Mono<Double> calculateAverageGenerationTime() {
        return historyRepository.findAllRecent(100)
                .timeout(OPERATION_TIMEOUT)
                .filter(h -> h.generationTimeMs() != null)
                .map(DigestHistory::generationTimeMs)
                .collect(java.util.stream.Collectors.averagingLong(Long::longValue))
                .onErrorReturn(0.0);
    }

    private Mono<Long> countMessagesProcessed(Instant since) {
        return messageRepository.findPrimaryMessagesForDigest(since, 0L, Integer.MAX_VALUE)
                .timeout(OPERATION_TIMEOUT)
                .count()
                .onErrorReturn(0L);
    }

    private Mono<Long> countClustersFormed(Instant since) {
        return messageRepository.findDistinctClusterIds(since)
                .timeout(OPERATION_TIMEOUT)
                .count()
                .onErrorReturn(0L);
    }

    private Mono<Long> countDigestsPublished(Instant since) {
        return historyRepository.findByStatus(DigestStatus.PUBLISHED.name())
                .timeout(OPERATION_TIMEOUT)
                .filter(h -> h.publishedAt() != null && h.publishedAt().isAfter(since))
                .count()
                .onErrorReturn(0L);
    }

    private Mono<List<DigestAnalyticsDto.PersonaStats>> getPersonaStatsList() {
        return personaRepository.findAll()
                .timeout(OPERATION_TIMEOUT)
                .flatMap(this::buildPersonaStats)
                .collectList()
                .onErrorReturn(List.of());
    }

    private Mono<DigestAnalyticsDto.PersonaStats> buildPersonaStats(DigestPersona persona) {
        return Mono.zip(
                historyRepository.countByPersonaIdAndStatus(persona.id(), DigestStatus.PUBLISHED.name())
                        .timeout(OPERATION_TIMEOUT)
                        .onErrorReturn(0L),
                historyRepository.countByPersonaIdAndStatus(persona.id(), DigestStatus.FAILED.name())
                        .timeout(OPERATION_TIMEOUT)
                        .onErrorReturn(0L),
                historyRepository.calculateSuccessRate(persona.id())
                        .timeout(OPERATION_TIMEOUT)
                        .onErrorReturn(0.0),
                historyRepository.avgGenerationTimeByPersonaId(persona.id())
                        .timeout(OPERATION_TIMEOUT)
                        .onErrorReturn(0.0)
        ).map(tuple -> {
            long published = tuple.getT1();
            long failed = tuple.getT2();
            long total = published + failed;
            return new DigestAnalyticsDto.PersonaStats(
                    persona.id(),
                    persona.name(),
                    Boolean.TRUE.equals(persona.enabled()),
                    total,
                    published,
                    failed,
                    tuple.getT3(),
                    tuple.getT4(),
                    persona.lastRunAt()
            );
        });
    }

    private Mono<DigestAnalyticsDto.ActivityEntry> historyToActivity(DigestHistory history) {
        return personaRepository.findById(history.personaId())
                .timeout(OPERATION_TIMEOUT)
                .map(persona -> {
                    DigestStatus status = history.statusEnum();
                    Instant timestamp = history.publishedAt() != null ? history.publishedAt() : history.createdAt();
                    if (status == DigestStatus.PUBLISHED) {
                        return DigestAnalyticsDto.ActivityEntry.published(
                                timestamp,
                                persona.name(),
                                history.messagesIncluded() != null ? history.messagesIncluded() : 0
                        );
                    } else if (status == DigestStatus.FAILED) {
                        return DigestAnalyticsDto.ActivityEntry.failed(
                                timestamp,
                                persona.name(),
                                history.errorMessage()
                        );
                    } else {
                        return DigestAnalyticsDto.ActivityEntry.generated(
                                timestamp,
                                persona.name(),
                                history.messagesIncluded() != null ? history.messagesIncluded() : 0
                        );
                    }
                })
                .onErrorReturn(DigestAnalyticsDto.ActivityEntry.failed(
                        history.createdAt(),
                        "Unknown",
                        "Failed to fetch persona info"
                ));
    }

    private Mono<Long> countDistinctClusters(Instant since) {
        return messageRepository.findDistinctClusterIds(since)
                .timeout(OPERATION_TIMEOUT)
                .count()
                .onErrorReturn(0L);
    }

    private Mono<Double> calculateAverageClusterSize(Instant since) {
        return messageRepository.findDistinctClusterIds(since)
                .timeout(OPERATION_TIMEOUT)
                .flatMap(clusterId -> messageRepository.findByClusterId(clusterId)
                        .timeout(OPERATION_TIMEOUT)
                        .count())
                .collect(java.util.stream.Collectors.averagingLong(Long::longValue))
                .onErrorReturn(0.0);
    }

    private Mono<Double> calculateDeduplicationRate(Instant since) {
        return Mono.zip(
                messageRepository.findPrimaryMessagesForDigest(since, 0L, Integer.MAX_VALUE)
                        .timeout(OPERATION_TIMEOUT)
                        .count(),
                messageRepository.findUnclusteredMessages(since, Integer.MAX_VALUE)
                        .timeout(OPERATION_TIMEOUT)
                        .count()
        ).map(tuple -> {
            long primary = tuple.getT1();
            long unclustered = tuple.getT2();
            long total = primary + unclustered;
            if (total == 0) {
                return 0.0;
            }
            return ((total - primary) * 100.0) / total;
        }).onErrorReturn(0.0);
    }

    private Mono<Long> countUnclusteredMessages(Instant since) {
        return messageRepository.findUnclusteredMessages(since, Integer.MAX_VALUE)
                .timeout(OPERATION_TIMEOUT)
                .count()
                .onErrorReturn(0L);
    }

    private Mono<List<ClusterStatsDto.ClusterInfo>> getTopClusters(Instant since, int limit) {
        return messageRepository.findDistinctClusterIds(since)
                .timeout(OPERATION_TIMEOUT)
                .take(limit)
                .flatMap(clusterId -> messageRepository.findByClusterId(clusterId)
                        .timeout(OPERATION_TIMEOUT)
                        .collectList()
                        .map(messages -> {
                            var primary = messages.stream()
                                    .filter(m -> Boolean.TRUE.equals(m.getIsPrimaryInCluster()))
                                    .findFirst()
                                    .orElse(messages.isEmpty() ? null : messages.get(0));
                            String preview = "";
                            if (primary != null) {
                                String content = primary.getContent();
                                if (content != null && !content.isBlank()) {
                                    preview = content.length() > MAX_CONTENT_PREVIEW
                                            ? content.substring(0, MAX_CONTENT_PREVIEW) + "..."
                                            : content;
                                }
                            }
                            double avgImportance = messages.stream()
                                    .filter(m -> m.getImportance() != null)
                                    .mapToDouble(m -> m.getImportance())
                                    .average()
                                    .orElse(0.0);
                            Instant created = primary != null && primary.getDate() != null
                                    ? primary.getDate()
                                    : since;
                            return new ClusterStatsDto.ClusterInfo(
                                    clusterId,
                                    messages.size(),
                                    preview,
                                    avgImportance,
                                    created
                            );
                        }))
                .collectList()
                .onErrorReturn(List.of());
    }

    private Mono<Long> countTotalSources() {
        return sourceTrustRepository.count()
                .timeout(OPERATION_TIMEOUT)
                .onErrorReturn(0L);
    }

    private Mono<Long> countHighTrustSources() {
        return sourceTrustRepository.findHighTrustSources(HIGH_TRUST_THRESHOLD)
                .timeout(OPERATION_TIMEOUT)
                .count()
                .onErrorReturn(0L);
    }

    private Mono<Long> countLowTrustSources() {
        return sourceTrustRepository.findAll()
                .timeout(OPERATION_TIMEOUT)
                .filter(st -> st.getTrustScore() != null && st.getTrustScore() < LOW_TRUST_THRESHOLD)
                .count()
                .onErrorReturn(0L);
    }

    private Mono<Double> calculateAverageTrustScore() {
        return sourceTrustRepository.findAll()
                .timeout(OPERATION_TIMEOUT)
                .filter(st -> st.getTrustScore() != null)
                .map(SourceTrust::getTrustScore)
                .collect(java.util.stream.Collectors.averagingDouble(Double::doubleValue))
                .onErrorReturn(0.5);
    }

    private Mono<List<SourceStatsDto.SourceDetail>> getSourceDetails(int limit) {
        return sourceTrustRepository.findAll()
                .timeout(OPERATION_TIMEOUT)
                .take(limit)
                .flatMap(this::buildSourceDetail)
                .collectList()
                .onErrorReturn(List.of());
    }

    private Mono<SourceStatsDto.SourceDetail> buildSourceDetail(SourceTrust trust) {
        return channelRepository.findByChatId(trust.getChannelId())
                .timeout(OPERATION_TIMEOUT)
                .map(channel -> new SourceStatsDto.SourceDetail(
                        trust.getChannelId(),
                        channel.getTitle(),
                        trust.getTrustScore() != null ? trust.getTrustScore() : 0.5,
                        Boolean.TRUE.equals(trust.getIsOfficial()),
                        trust.getCategory(),
                        0L,
                        0L,
                        trust.getLastUpdated()
                ))
                .defaultIfEmpty(new SourceStatsDto.SourceDetail(
                        trust.getChannelId(),
                        "Channel " + trust.getChannelId(),
                        trust.getTrustScore() != null ? trust.getTrustScore() : 0.5,
                        Boolean.TRUE.equals(trust.getIsOfficial()),
                        trust.getCategory(),
                        0L,
                        0L,
                        trust.getLastUpdated()
                ));
    }

    private Mono<SourceStatsDto.TrustDistribution> calculateTrustDistribution() {
        return sourceTrustRepository.findAll()
                .timeout(OPERATION_TIMEOUT)
                .filter(st -> st.getTrustScore() != null)
                .collectList()
                .map(sources -> {
                    long veryHigh = sources.stream().filter(s -> s.getTrustScore() >= 0.9).count();
                    long high = sources.stream().filter(s -> s.getTrustScore() >= 0.7 && s.getTrustScore() < 0.9).count();
                    long medium = sources.stream().filter(s -> s.getTrustScore() >= 0.5 && s.getTrustScore() < 0.7).count();
                    long low = sources.stream().filter(s -> s.getTrustScore() >= 0.3 && s.getTrustScore() < 0.5).count();
                    long veryLow = sources.stream().filter(s -> s.getTrustScore() < 0.3).count();
                    return new SourceStatsDto.TrustDistribution(veryHigh, high, medium, low, veryLow);
                })
                .onErrorReturn(SourceStatsDto.TrustDistribution.empty());
    }
}
