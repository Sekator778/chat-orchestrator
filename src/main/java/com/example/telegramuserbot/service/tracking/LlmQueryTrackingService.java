package com.example.telegramuserbot.service.tracking;

import com.example.telegramuserbot.domain.LlmQuery;
import com.example.telegramuserbot.domain.LlmQueryMessage;
import com.example.telegramuserbot.domain.LlmQueryPhase;
import com.example.telegramuserbot.domain.LlmQueryStatus;
import com.example.telegramuserbot.repository.LlmQueryMessageRepository;
import com.example.telegramuserbot.repository.LlmQueryRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class LlmQueryTrackingService {

    private static final Logger log = LoggerFactory.getLogger(LlmQueryTrackingService.class);

    private final LlmQueryRepository queryRepository;
    private final LlmQueryMessageRepository messageRepository;
    private final ObjectMapper objectMapper;

    public LlmQueryTrackingService(LlmQueryRepository queryRepository,
                                   LlmQueryMessageRepository messageRepository,
                                   ObjectMapper objectMapper) {
        this.queryRepository = queryRepository;
        this.messageRepository = messageRepository;
        this.objectMapper = objectMapper;
    }

    public Mono<LlmQueryTracker> startTracking(LlmQueryStartParams params) {
        // ... код без изменений ...
        LlmQuery query = new LlmQuery();
        query.setChatId(params.chatId());
        query.setTriggeringMessageId(params.triggeringMessageId());
        query.setSenderId(params.senderId());
        query.setSenderUsername(trimToNull(params.senderUsername()));
        query.setSenderName(trimToNull(params.senderName()));
        query.setTriggerExcerpt(trimToNull(params.triggerExcerpt()));
        query.setTriggeredAt(Optional.ofNullable(params.triggeredAt()).orElse(Instant.now()));
        query.setCreatedAt(Instant.now());
        query.setStatus(LlmQueryStatus.IN_PROGRESS);
        query.setAttemptCount(0);
        query.setMetadata(writeMetadata(params.initialMetadata()));

        return queryRepository.save(query)
                .doOnSuccess(saved -> log.debug("[TRACKER] Started tracking for queryId={}", saved.getId()))
                .map(saved -> new LlmQueryTracker(saved.getId(), this));
    }

    // --- ENTERPRISE REFACTOR: Убираем сокрытие ошибок ---
    Mono<Void> appendMessages(long queryId, List<MessageLogEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return Mono.empty();
        }
        // ... код маппинга ...
        List<LlmQueryMessage> messages = entries.stream().map(entry -> {
            LlmQueryMessage message = new LlmQueryMessage();
            message.setQueryId(queryId);
            message.setPhase(entry.phase());
            message.setAttempt(entry.attempt());
            message.setSequence(entry.sequence());
            message.setRole(entry.role());
            message.setContent(entry.content());
            message.setMetadata(writeMetadata(entry.metadata()));
            message.setCreatedAt(Instant.now());
            return message;
        }).collect(Collectors.toList());

        return messageRepository.saveAll(messages).then(); // Просто .then() для преобразования в Mono<Void>
    }

    Mono<Void> updateDecision(long queryId,
                              boolean shouldRespond,
                              String decisionIntent,
                              String decisionTone,
                              Double confidence,
                              String skipReason) {
        return queryRepository.findById(queryId)
                .flatMap(query -> {
                    query.setShouldRespond(shouldRespond);
                    query.setDecisionIntent(trimToNull(decisionIntent));
                    query.setDecisionTone(trimToNull(decisionTone));
                    query.setDecisionConfidence(confidence);
                    if (skipReason != null) {
                        query.setSkipReason(skipReason);
                    }
                    return queryRepository.save(query);
                })
                .then(); // Просто .then()
    }

    Mono<Void> updateAttemptCount(long queryId, int attempt) {
        return queryRepository.findById(queryId)
                .flatMap(query -> {
                    int current = Optional.ofNullable(query.getAttemptCount()).orElse(0);
                    if (attempt > current) {
                        query.setAttemptCount(attempt);
                        return queryRepository.save(query);
                    }
                    return Mono.just(query);
                })
                .then(); // Просто .then()
    }

    Mono<Void> appendMetadata(long queryId, Map<String, Object> additions) {
        if (additions == null || additions.isEmpty()) {
            return Mono.empty();
        }
        return queryRepository.findById(queryId)
                .flatMap(query -> {
                    // ... логика слияния метаданных ...
                    Map<String, Object> merged = new LinkedHashMap<>();
                    if (query.getMetadata() != null) {
                        try {
                            Map<?, ?> existing = objectMapper.readValue(query.getMetadata(), Map.class);
                            existing.forEach((key, value) -> merged.put(String.valueOf(key), value));
                        } catch (JsonProcessingException e) {
                            log.debug("Unable to read existing metadata for query {}: {}", queryId, e.getMessage());
                        }
                    }
                    additions.forEach(merged::put);
                    query.setMetadata(writeMetadata(merged));
                    return queryRepository.save(query);
                })
                .then(); // Просто .then()
    }

    Mono<Void> completeQuery(long queryId,
                             LlmQueryStatus status,
                             String finalResponse,
                             String skipReason,
                             Double confidence,
                             Boolean shouldRespond,
                             int attemptsUsed) {
        return queryRepository.findById(queryId)
                .flatMap(query -> {
                    // ... логика обновления ...
                    query.setStatus(status);
                    query.setCompletedAt(Instant.now());
                    if (finalResponse != null) {
                        query.setFinalResponse(finalResponse);
                    }
                    if (skipReason != null) {
                        query.setSkipReason(skipReason);
                    }
                    if (confidence != null) {
                        query.setDecisionConfidence(confidence);
                    }
                    if (shouldRespond != null) {
                        query.setShouldRespond(shouldRespond);
                    }
                    int currentAttempts = Optional.ofNullable(query.getAttemptCount()).orElse(0);
                    if (attemptsUsed > currentAttempts) {
                        query.setAttemptCount(attemptsUsed);
                    }
                    return queryRepository.save(query);
                })
                .then(); // Просто .then()
    }

    Mono<Void> markFailed(long queryId, String reason) {
        return completeQuery(queryId, LlmQueryStatus.FAILED, null, reason, null, false, 0);
    }

    Mono<Void> recordUsage(long queryId, Integer promptTokens, Integer completionTokens, Integer totalTokens) {
        if (promptTokens == null && completionTokens == null && totalTokens == null) {
            return Mono.empty();
        }
        return queryRepository.findById(queryId)
                .flatMap(query -> {
                    query.setPromptTokens(promptTokens);
                    query.setCompletionTokens(completionTokens);
                    query.setTotalTokens(totalTokens);
                    return queryRepository.save(query);
                })
                .then();
    }

    // ... остальные приватные методы без изменений ...
    private String writeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            log.debug("Unable to serialize metadata: {}", e.getMessage());
            return null;
        }
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    record MessageLogEntry(
            LlmQueryPhase phase,
            int attempt,
            int sequence,
            String role,
            String content,
            Map<String, Object> metadata
    ) { }

    public record LlmQueryStartParams(
            long chatId,
            long triggeringMessageId,
            Instant triggeredAt,
            Long senderId,
            String senderUsername,
            String senderName,
            String triggerExcerpt,
            Map<String, Object> initialMetadata
    ) { }
}