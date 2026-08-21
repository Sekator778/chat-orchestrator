package com.example.telegramuserbot.service;

import com.example.telegramuserbot.domain.ProblematicChat;
import com.example.telegramuserbot.domain.ProblematicChatReason;
import com.example.telegramuserbot.repository.ProblematicChatRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class ProblematicChatService {

    private static final Logger log = LoggerFactory.getLogger(ProblematicChatService.class);
    private static final int DETAILS_MAX_LENGTH = 2000;

    private final ProblematicChatRepository repository;
    private final Set<Long> cachedProblematicChats = ConcurrentHashMap.newKeySet();

    public ProblematicChatService(ProblematicChatRepository repository) {
        this.repository = repository;
        warmCache();
    }

    public Mono<Boolean> shouldProcess(Long channelChatId) {
        Long canonical = normalize(channelChatId);
        if (canonical == null) {
            return Mono.just(true);
        }
        if (cachedProblematicChats.isEmpty()) {
            return refreshCache()
                    .thenReturn(!cachedProblematicChats.contains(canonical))
                    .doOnNext(shouldProcess -> logIfBlocked(canonical, shouldProcess));
        }
        boolean shouldProcess = !cachedProblematicChats.contains(canonical);
        logIfBlocked(canonical, shouldProcess);
        return Mono.just(shouldProcess);
    }

    public Mono<Void> markProblematic(Long channelChatId, ProblematicChatReason reason, String details) {
        Long canonical = normalize(channelChatId);
        if (canonical == null) {
            return Mono.empty();
        }

        Instant now = Instant.now();
        String safeDetails = truncate(details);

        return repository.findById(canonical)
                .flatMap(existing -> {
                    existing.setReason(reason.name());
                    existing.setDetails(safeDetails);
                    existing.setLastDetectedAt(now);
                    existing.setLastAttemptedAt(now);
                    Integer currentFailures = existing.getFailureCount() == null ? 0 : existing.getFailureCount();
                    existing.setFailureCount(currentFailures + 1);
                    return repository.save(existing.markPersisted());
                })
                .switchIfEmpty(Mono.defer(() -> {
                    ProblematicChat entity = new ProblematicChat().markNew();
                    entity.setChannelChatId(canonical);
                    entity.setReason(reason.name());
                    entity.setDetails(safeDetails);
                    entity.setFailureCount(1);
                    entity.setFirstDetectedAt(now);
                    entity.setLastDetectedAt(now);
                    entity.setLastAttemptedAt(now);
                    return repository.save(entity);
                }))
                .doOnNext(saved -> cachedProblematicChats.add(saved.getChannelChatId()))
                .doOnSuccess(ignore -> log.info("Recorded problematic chat {} with reason {}", canonical, reason))
                .then();
    }

    private Long normalize(Long channelChatId) {
        // Using original TDLib ID directly - no normalization needed
        return channelChatId;
    }

    /**
     * Возвращает множество ID проблемных чатов для фильтрации при прогреве кешей.
     */
    public Mono<java.util.Set<Long>> listProblematicChatIds() {
        if (!cachedProblematicChats.isEmpty()) {
            return Mono.just(Collections.unmodifiableSet(cachedProblematicChats));
        }
        return refreshCache().thenReturn(Collections.unmodifiableSet(cachedProblematicChats));
    }

    private String truncate(String details) {
        if (details == null) {
            return null;
        }
        if (details.length() <= DETAILS_MAX_LENGTH) {
            return details;
        }
        return details.substring(0, DETAILS_MAX_LENGTH);
    }

    private void warmCache() {
        repository.findAll()
                .map(ProblematicChat::getChannelChatId)
                .collect(Collectors.toSet())
                .doOnNext(ids -> {
                    cachedProblematicChats.clear();
                    cachedProblematicChats.addAll(ids);
                    log.info("Loaded {} problematic chats into in-memory cache", cachedProblematicChats.size());
                })
                .doOnError(e -> log.warn("Failed to warm problematic chat cache: {}", e.getMessage()))
                .subscribe();
    }

    private Mono<Void> refreshCache() {
        return repository.findAll()
                .map(ProblematicChat::getChannelChatId)
                .collect(Collectors.toSet())
                .doOnNext(ids -> {
                    cachedProblematicChats.clear();
                    cachedProblematicChats.addAll(ids);
                    log.info("Refreshed problematic chat cache, {} entries", cachedProblematicChats.size());
                })
                .doOnError(e -> log.warn("Failed to refresh problematic chat cache: {}", e.getMessage()))
                .then();
    }

    private void logIfBlocked(Long canonical, boolean shouldProcess) {
        if (!shouldProcess) {
            log.debug("Skipping problematic chat {} from automated processing", canonical);
        }
    }
}
