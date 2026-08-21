package com.example.telegramuserbot.service.tracking;

import com.example.telegramuserbot.domain.LlmQueryPhase;
import com.example.telegramuserbot.domain.LlmQueryStatus;
import com.example.telegramuserbot.service.llm.dto.ApiMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class LlmQueryTracker {

    private static final Logger log = LoggerFactory.getLogger(LlmQueryTracker.class);

    private final long queryId;
    private final LlmQueryTrackingService trackingService;
    private final AtomicInteger sequence = new AtomicInteger(0);
    private final AtomicInteger maxAttempt = new AtomicInteger(0);

    LlmQueryTracker(long queryId, LlmQueryTrackingService trackingService) {
        this.queryId = queryId;
        this.trackingService = trackingService;
    }

    public long queryId() {
        return queryId;
    }

    public Mono<Void> registerAttempt(int attempt) {
        if (attempt <= 0) {
            return Mono.empty();
        }
        maxAttempt.accumulateAndGet(attempt, Math::max);
        return trackingService.updateAttemptCount(queryId, attempt)
                .onErrorResume(error -> {
                    log.debug("Could not update attempt {} for query {}: {}", attempt, queryId, error.getMessage());
                    return Mono.empty();
                });
    }

    public Mono<Void> recordPhase(LlmQueryPhase phase,
                                  int attempt,
                                  List<ApiMessage> requestMessages,
                                  String response,
                                  Map<String, Object> metadata) {
        List<ApiMessage> safeMessages = requestMessages != null ? requestMessages : List.of();
        List<LlmQueryTrackingService.MessageLogEntry> entries = new ArrayList<>(safeMessages.size() + (response != null ? 1 : 0));

        for (ApiMessage message : safeMessages) {
            int seq = sequence.incrementAndGet();
            entries.add(new LlmQueryTrackingService.MessageLogEntry(
                    phase,
                    attempt,
                    seq,
                    nullSafeRole(message.role()),
                    nullSafeContent(message.content()),
                    enrichMetadata(metadata, "request")
            ));
        }

        if (response != null && !response.isBlank()) {
            int seq = sequence.incrementAndGet();
            entries.add(new LlmQueryTrackingService.MessageLogEntry(
                    phase,
                    attempt,
                    seq,
                    "assistant",
                    response,
                    enrichMetadata(metadata, "response")
            ));
        }

        if (entries.isEmpty()) {
            return Mono.empty();
        }

        return trackingService.appendMessages(queryId, entries);
    }

    public Mono<Void> recordDecision(boolean shouldRespond,
                                     String decisionIntent,
                                     String decisionTone,
                                     Double confidence,
                                     String skipReason) {
        return trackingService.updateDecision(queryId, shouldRespond, decisionIntent, decisionTone, confidence, skipReason);
    }

    public Mono<Void> appendMetadata(Map<String, Object> additions) {
        return trackingService.appendMetadata(queryId, additions);
    }

    public Mono<Void> markCompleted(LlmQueryStatus status,
                                    String finalResponse,
                                    String skipReason,
                                    Double confidence,
                                    Boolean shouldRespond) {
        int attemptsUsed = Math.max(maxAttempt.get(), 1);
        return trackingService.completeQuery(queryId, status, finalResponse, skipReason, confidence, shouldRespond, attemptsUsed);
    }

    public Mono<Void> markFailed(String reason) {
        return trackingService.markFailed(queryId, reason);
    }

    public Mono<Void> recordUsage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {
        return trackingService.recordUsage(queryId, promptTokens, completionTokens, totalTokens)
                .onErrorResume(error -> {
                    log.debug("Could not record usage for query {}: {}", queryId, error.getMessage());
                    return Mono.empty();
                });
    }

    public Mono<Void> recordFinalDelivery(String response) {
        if (response == null || response.isBlank()) {
            return Mono.empty();
        }
        int attempt = Math.max(maxAttempt.get(), 1);
        return recordPhase(LlmQueryPhase.FINAL_DELIVERY, attempt, List.of(), response, Map.of("direction", "final"));
    }

    private Map<String, Object> enrichMetadata(Map<String, Object> metadata, String direction) {
        Map<String, Object> map = metadata != null ? new LinkedHashMap<>(metadata) : new LinkedHashMap<>();
        map.put("direction", direction);
        return map;
    }

    private String nullSafeContent(String content) {
        return content != null ? content : "";
    }

    private String nullSafeRole(String role) {
        if (role == null || role.isBlank()) {
            return "system";
        }
        return role;
    }
}
