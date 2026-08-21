package com.example.telegramuserbot.repository;

import com.example.telegramuserbot.domain.PendingResponse;
import com.example.telegramuserbot.domain.PendingResponseStatus;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * Repository for managing pending responses in the queue.
 *
 * Provides queries for finding responses by status, checking eligibility,
 * and cleaning up expired entries.
 */
public interface PendingResponseRepository extends R2dbcRepository<PendingResponse, Long> {

    /**
     * Finds all pending responses for a specific chat.
     *
     * @param chatId the chat ID
     * @return flux of pending responses
     */
    @Query("""
            SELECT *
              FROM bot.pending_responses
             WHERE chat_id = :chatId
               AND status = :status
               AND bot_instance_id = :botInstanceId
            """)
    Flux<PendingResponse> findByChatIdAndStatus(@Param("chatId") Long chatId,
                                                @Param("status") PendingResponseStatus status,
                                                @Param("botInstanceId") String botInstanceId);

    /**
     * Atomically claims eligible responses (marks as SENDING) for sending.
     */
    @Query("""
            WITH cte AS (
                SELECT id
                  FROM bot.pending_responses
                 WHERE status = 'ELIGIBLE'
                   AND expires_at > :now
                   AND (eligible_at IS NULL OR eligible_at <= :now)
                 ORDER BY eligible_at ASC NULLS FIRST, id
                 LIMIT :limit
                 FOR UPDATE SKIP LOCKED
            )
            UPDATE bot.pending_responses pr
               SET status = 'SENDING'
              FROM cte
             WHERE pr.id = cte.id
            RETURNING pr.*
            """)
    Flux<PendingResponse> claimEligibleResponses(@Param("now") Instant now, @Param("limit") int limit);

    /**
     * Finds all pending responses that have reached the required human replies threshold
     * but are not yet marked as eligible.
     *
     * @return flux of responses that should be marked as eligible
     */
    @Query("""
            SELECT pr.*
              FROM bot.pending_responses pr
             WHERE pr.status = 'PENDING'
               AND (pr.eligible_at IS NULL OR pr.eligible_at <= :now)
               AND pr.expires_at > :now
               AND (COALESCE(
                        (SELECT human_message_count FROM bot.chat_message_stats cms WHERE cms.chat_id = pr.chat_id),
                        0
                   ) - pr.base_count) >= pr.required_delta
            """)
    Flux<PendingResponse> findPendingThatReachedThreshold(@Param("now") Instant now);

    /**
     * Finds all expired responses that should be cleaned up.
     *
     * @param now current timestamp
     * @return flux of expired responses
     */
    @Query("SELECT * FROM bot.pending_responses " +
           "WHERE status IN ('PENDING', 'ELIGIBLE', 'SENDING') " +
           "AND expires_at <= :now " +
           "")
    Flux<PendingResponse> findExpiredResponses(@Param("now") Instant now);

    /**
     * Finds pending response by chat ID and triggering message ID.
     * Used to increment reply count when new human messages arrive.
     *
     * @param chatId the chat ID
     * @param triggeringMessageId the ID of the message that triggered the response
     * @return mono of pending response if found
     */
    @Query("SELECT * FROM bot.pending_responses " +
           "WHERE chat_id = :chatId " +
           "AND triggering_message_id = :triggeringMessageId " +
           "AND status = 'PENDING' " +
           "AND bot_instance_id = :botInstanceId")
    Mono<PendingResponse> findPendingByChatAndTriggeringMessage(
            @Param("chatId") Long chatId,
            @Param("triggeringMessageId") Long triggeringMessageId,
            @Param("botInstanceId") String botInstanceId);

    /**
     * Finds an active (pending/eligible and not expired) response for the same chat + triggering message.
     * Used for idempotent enqueue to avoid double-sending the same reply.
     */
    @Query("""
            SELECT *
              FROM bot.pending_responses
             WHERE chat_id = :chatId
               AND triggering_message_id = :triggeringMessageId
               AND status IN ('PENDING', 'ELIGIBLE', 'SENDING')
               AND expires_at > :now
               AND bot_instance_id = :botInstanceId
             ORDER BY created_at DESC
             LIMIT 1
            """)
    Mono<PendingResponse> findActiveByChatAndTriggeringMessage(@Param("chatId") Long chatId,
                                                              @Param("triggeringMessageId") Long triggeringMessageId,
                                                              @Param("now") Instant now,
                                                              @Param("botInstanceId") String botInstanceId);

    /**
     * Atomically creates or updates an active (pending/eligible) response for the same chat + triggering message.
     * Relies on partial unique index `uq_pending_responses_active_trigger`.
     */
    @Query("""
            INSERT INTO bot.pending_responses (
                    chat_id,
                    triggering_message_id,
                    prepared_response,
                    response_intent,
                    response_tone,
                    response_length,
                    status,
                    base_count,
                    required_delta,
                    created_at,
                    eligible_at,
                    expires_at,
                    bot_instance_id
            )
            VALUES (
                    :chatId,
                    :triggeringMessageId,
                    :preparedResponse,
                    :responseIntent,
                    :responseTone,
                    :responseLength,
                    'PENDING',
                    COALESCE((SELECT human_message_count FROM bot.chat_message_stats WHERE chat_id = :chatId), 0),
                    :requiredDelta,
                    :now,
                    :eligibleAt,
                    :expiresAt,
                    :botInstanceId
            )
            ON CONFLICT (bot_instance_id, chat_id, triggering_message_id) WHERE status IN ('PENDING', 'ELIGIBLE', 'SENDING')
            DO UPDATE SET
                    prepared_response = EXCLUDED.prepared_response,
                    response_intent = EXCLUDED.response_intent,
                    response_tone = EXCLUDED.response_tone,
                    response_length = EXCLUDED.response_length,
                    eligible_at = EXCLUDED.eligible_at,
                    expires_at = EXCLUDED.expires_at
            RETURNING *
            """)
    Mono<PendingResponse> upsertActivePending(@Param("chatId") Long chatId,
                                             @Param("triggeringMessageId") Long triggeringMessageId,
                                             @Param("preparedResponse") String preparedResponse,
                                             @Param("responseIntent") String responseIntent,
                                             @Param("responseTone") String responseTone,
                                             @Param("responseLength") String responseLength,
                                             @Param("requiredDelta") Integer requiredDelta,
                                             @Param("eligibleAt") Instant eligibleAt,
                                             @Param("expiresAt") Instant expiresAt,
                                             @Param("now") Instant now,
                                             @Param("botInstanceId") String botInstanceId);

    /**
     * Counts pending responses for a specific chat.
     *
     * @param chatId the chat ID
     * @return mono with count
     */
    @Query("""
            SELECT COUNT(*)
              FROM bot.pending_responses
             WHERE chat_id = :chatId
               AND status = :status
               AND bot_instance_id = :botInstanceId
            """)
    Mono<Long> countByChatIdAndStatus(@Param("chatId") Long chatId,
                                      @Param("status") PendingResponseStatus status,
                                      @Param("botInstanceId") String botInstanceId);

    /**
     * Deletes all expired responses (cleanup operation).
     *
     * @param now current timestamp
     * @return mono with number of deleted rows
     */
    @Query("DELETE FROM bot.pending_responses " +
           "WHERE status = 'EXPIRED'")
    Mono<Integer> deleteExpiredResponses();

    /**
     * Finds active (not expired) pending/eligible responses for the chat, created before the given message id.
     * Intended for augmenting LLM context so it can "see" already prepared but not yet sent responses.
     */
    @Query("""
            SELECT *
              FROM bot.pending_responses
             WHERE chat_id = :chatId
               AND status IN ('PENDING', 'ELIGIBLE', 'SENDING')
               AND expires_at > :now
               AND triggering_message_id < :beforeMessageId
               AND bot_instance_id = :botInstanceId
             ORDER BY created_at DESC
             LIMIT :limit
            """)
    Flux<PendingResponse> findActiveForChatBeforeMessage(@Param("chatId") Long chatId,
                                                         @Param("beforeMessageId") Long beforeMessageId,
                                                         @Param("now") Instant now,
                                                         @Param("limit") int limit,
                                                         @Param("botInstanceId") String botInstanceId);

    @Modifying
    @Query("""
            UPDATE bot.pending_responses
               SET status = 'SENT',
                   sent_at = :sentAt
             WHERE id = :id
               AND status = 'SENDING'
            """)
    Mono<Integer> markAsSentFromSending(@Param("id") Long id, @Param("sentAt") Instant sentAt);

    @Modifying
    @Query("""
            UPDATE bot.pending_responses
               SET status = 'ELIGIBLE'
             WHERE id = :id
               AND status = 'SENDING'
            """)
    Mono<Integer> revertSendingToEligible(@Param("id") Long id);
}
