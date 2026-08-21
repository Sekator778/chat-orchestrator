package com.example.telegramuserbot.service.queue;

import com.example.telegramuserbot.domain.PendingResponse;
import com.example.telegramuserbot.domain.PendingResponseStatus;
import com.example.telegramuserbot.repository.ChatMessageStatsRepository;
import com.example.telegramuserbot.repository.PendingResponseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Service for managing the lifecycle of pending responses.
 *
 * This service handles creating, updating, and checking the status of responses
 * that are waiting for a certain number of human replies before being sent.
 *
 * Architecture notes:
 * - All operations are reactive (return Mono/Flux)
 * - Database operations use timeout pattern for resilience
 * - Status transitions are explicit (PENDING -> ELIGIBLE -> SENT)
 */
@Service
public final class PendingResponseService {

    private static final Logger log = LoggerFactory.getLogger(PendingResponseService.class);
    private static final Duration REPOSITORY_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration DEFAULT_EXPIRATION = Duration.ofHours(12);

    private final PendingResponseRepository repository;
    private final ChatMessageStatsRepository chatMessageStatsRepository;

    public PendingResponseService(PendingResponseRepository repository,
                                  ChatMessageStatsRepository chatMessageStatsRepository) {
        this.repository = repository;
        this.chatMessageStatsRepository = chatMessageStatsRepository;
    }

    /**
     * Creates and enqueues a new pending response.
     *
     * @param chatId the chat ID
     * @param triggeringMessageId the message that triggered this response
     * @param preparedResponse the AI-generated response text
     * @param responseIntent the intent of the response (from ResponseDecisionEngine)
     * @param responseTone the tone of the response
     * @param responseLength the length of the response
     * @param requiredDelta number of additional human messages to wait for
     * @return mono of the created pending response
     */
	    public Mono<PendingResponse> enqueue(
	            Long chatId,
	            Long triggeringMessageId,
                String botInstanceId,
	            String preparedResponse,
	            String responseIntent,
	            String responseTone,
	            String responseLength,
	            Integer requiredDelta,
	            Instant eligibleAt
	    ) {
	        Instant now = Instant.now();
	        Instant expiresAt = now.plus(DEFAULT_EXPIRATION);
	        return repository.upsertActivePending(
	                        chatId,
	                        triggeringMessageId,
	                        preparedResponse,
	                        responseIntent,
	                        responseTone,
	                        responseLength,
	                        requiredDelta != null ? requiredDelta : 0,
	                        eligibleAt,
	                        expiresAt,
	                        now,
                            botInstanceId
	                )
	                .timeout(REPOSITORY_TIMEOUT)
	                .doOnSuccess(saved -> log.info("📥 ENQUEUE OK: Upserted pending response id={} for chat={} triggeringMsg={}",
	                        saved != null ? saved.getId() : null, chatId, triggeringMessageId))
	                .doOnError(error -> log.error("📥 ENQUEUE FAIL: Failed to upsert pending response for chat={}, msgId={}: {}",
	                        chatId, triggeringMessageId, error.getMessage()));
	    }

    /**
     * Atomically claims eligible responses (marks as SENDING) for sending.
     *
     * @return flux of claimed responses
     */
    public Flux<PendingResponse> claimEligibleResponses(int limit) {
        int resolvedLimit = Math.max(1, limit);
        return repository.claimEligibleResponses(Instant.now(), resolvedLimit)
                .timeout(REPOSITORY_TIMEOUT)
                .doOnNext(pending -> log.debug("🔍 ELIGIBLE FOUND: id={}, chat={}, createdAt={}",
                        pending.getId(), pending.getChatId(), pending.getCreatedAt()));
    }

    /**
     * Finds all pending responses that have reached the threshold but are not yet marked as eligible.
     * This is used by the scheduler to update their status.
     *
     * @return flux of responses that should be marked as eligible
     */
    public Flux<PendingResponse> findPendingThatReachedThreshold() {
        Instant now = Instant.now();
        return repository.findPendingThatReachedThreshold(now)
                .timeout(REPOSITORY_TIMEOUT)
                .flatMap(pending ->
                        chatMessageStatsRepository.findCountByChatId(pending.getChatId())
                                .defaultIfEmpty(0L)
                                .flatMapMany(currentCount -> {
                                    boolean delayReached = delayWindowReached(pending, now);
                                    long delta = currentCount - (pending.getBaseCount() == null ? 0 : pending.getBaseCount());
                                    log.debug("🔍 THRESHOLD CHECK: id={} chat={} base={} current={} delta={} requiredDelta={} eligibleAt={} delayMet={}",
                                            pending.getId(),
                                            pending.getChatId(),
                                            pending.getBaseCount(),
                                            currentCount,
                                            delta,
                                            pending.getRequiredDelta(),
                                            pending.getEligibleAt(),
                                            delayReached);

                                    if (delayReached) {
                                        log.info("✅ THRESHOLD MET: pending id={} chat={} delta={} required={}",
                                                pending.getId(),
                                                pending.getChatId(),
                                                delta,
                                                pending.getRequiredDelta());
                                        return markPendingAsEligible(pending);
                                    }
                                    return Flux.empty();
                                }))
                .doOnError(error -> log.error("🔍 THRESHOLD FAIL: Failed to check pending responses: {}", error.getMessage()));
    }

    /**
     * Marks a pending response as sent.
     *
     * @param pendingId the pending response ID
     * @return mono of updated pending response
     */
    public Mono<PendingResponse> markAsSent(Long pendingId) {
        Instant now = Instant.now();
        return repository.findById(pendingId)
                .timeout(REPOSITORY_TIMEOUT)
                .flatMap(pending -> repository.markAsSentFromSending(pendingId, now)
                        .thenReturn(pending))
                .timeout(REPOSITORY_TIMEOUT)
                .doOnSuccess(p -> log.info("📤 SENT: Marked pending response id={} as sent", pendingId))
                .doOnError(error -> log.error("📤 SENT FAIL: Failed to mark pending id={} as sent: {}",
                        pendingId, error.getMessage()));
    }

    public Mono<Void> revertToEligible(Long pendingId) {
        return repository.revertSendingToEligible(pendingId)
                .timeout(REPOSITORY_TIMEOUT)
                .then();
    }

    public Mono<Void> markAsExpiredById(Long pendingId) {
        return repository.findById(pendingId)
                .timeout(REPOSITORY_TIMEOUT)
                .flatMap(pending -> {
                    pending.markAsExpired();
                    return repository.save(pending);
                })
                .timeout(REPOSITORY_TIMEOUT)
                .then();
    }

    /**
     * Finds and marks all expired pending responses.
     * This is called periodically by the scheduler.
     *
     * @return mono with count of expired responses
     */
    public Mono<Long> markExpiredResponses() {
        return repository.findExpiredResponses(Instant.now())
                .timeout(REPOSITORY_TIMEOUT)
                .flatMap(pending -> {
                    pending.markAsExpired();
                    log.info("⏰ EXPIRED: Marking pending response id={} as expired (chat={}, created={})",
                            pending.getId(), pending.getChatId(), pending.getCreatedAt());
                    return repository.save(pending);
                })
                .count()
                .doOnSuccess(count -> {
                    if (count > 0) {
                        log.info("⏰ EXPIRED: Marked {} pending responses as expired", count);
                    }
                })
                .onErrorResume(error -> {
                    log.error("⏰ EXPIRED FAIL: Failed to mark expired responses: {}", error.getMessage());
                    return Mono.just(0L);
                });
    }

    /**
     * Cleans up expired responses from the database.
     * This is called periodically to prevent table bloat.
     *
     * @return mono with count of deleted responses
     */
    public Mono<Integer> cleanupExpiredResponses() {
        return repository.deleteExpiredResponses()
                .timeout(REPOSITORY_TIMEOUT)
                .defaultIfEmpty(0)
                .map(count -> count == null ? 0 : count)
                .doOnNext(count -> {
                    if (count > 0) {
                        log.info("🧹 CLEANUP: Deleted {} expired responses from database", count);
                    }
                })
                .onErrorResume(error -> {
                    log.error("🧹 CLEANUP FAIL: Failed to delete expired responses: {}", error.getMessage());
                    return Mono.just(0);
                });
    }

    /**
     * Counts pending responses for a specific chat.
     *
     * @param chatId the chat ID
     * @return mono with count
     */
    public Mono<Long> countPendingForChat(Long chatId, String botInstanceId) {
        return repository.countByChatIdAndStatus(chatId, PendingResponseStatus.PENDING, botInstanceId)
                .timeout(REPOSITORY_TIMEOUT)
                .defaultIfEmpty(0L);
    }

    /**
     * Returns the most recent pending/eligible responses for the chat to be included into the LLM context.
     * These responses were generated earlier but have not been sent to Telegram yet.
     */
    public Mono<List<PendingResponse>> findActiveForChatBeforeMessage(Long chatId, Long beforeMessageId, int limit, String botInstanceId) {
        int resolvedLimit = Math.max(0, limit);
        if (resolvedLimit == 0) {
            return Mono.just(List.of());
        }
        if (botInstanceId == null || botInstanceId.isBlank()) {
            return Mono.just(List.of());
        }
        Long resolvedBefore = beforeMessageId != null ? beforeMessageId : Long.MAX_VALUE;
        return repository.findActiveForChatBeforeMessage(chatId, resolvedBefore, Instant.now(), resolvedLimit, botInstanceId)
                .timeout(REPOSITORY_TIMEOUT)
                .collectList()
                .map(list -> {
                    // query returns newest-first; reverse for chronological readability in prompt
                    java.util.Collections.reverse(list);
                    return list;
                })
                .onErrorReturn(List.of());
    }

    /**
     * Marks the provided pending response as eligible and persists it.
     */
    public Mono<PendingResponse> markPendingAsEligible(PendingResponse pending) {
        pending.markAsEligible();
        return repository.save(pending)
                .timeout(REPOSITORY_TIMEOUT);
    }

    private boolean delayWindowReached(PendingResponse pending, Instant now) {
        Instant eligible = pending.getEligibleAt();
        return eligible == null || !eligible.isAfter(now);
    }
}
