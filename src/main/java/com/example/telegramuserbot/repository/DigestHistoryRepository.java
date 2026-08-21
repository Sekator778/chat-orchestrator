package com.example.telegramuserbot.repository;

import com.example.telegramuserbot.domain.DigestHistory;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * Repository for managing digest history.
 * Tracks all generated and published digests.
 */
public interface DigestHistoryRepository extends R2dbcRepository<DigestHistory, Long> {

    /**
     * Finds recent history for a persona.
     *
     * @param personaId persona ID
     * @param limit maximum number of records
     * @return flux of recent digest history
     */
    @Query("""
            SELECT * FROM bot.digest_history
            WHERE persona_id = :personaId
            ORDER BY created_at DESC
            LIMIT :limit
            """)
    Flux<DigestHistory> findRecentByPersonaId(@Param("personaId") Long personaId, @Param("limit") int limit);

    /**
     * Finds history by digest ID.
     *
     * @param digestId digest ID
     * @return the digest history if found
     */
    @Query("SELECT * FROM bot.digest_history WHERE digest_id = :digestId")
    Mono<DigestHistory> findByDigestId(@Param("digestId") String digestId);

    /**
     * Finds history by status.
     *
     * @param status status filter
     * @return flux of matching digest history
     */
    @Query("SELECT * FROM bot.digest_history WHERE status = :status ORDER BY created_at DESC")
    Flux<DigestHistory> findByStatus(@Param("status") String status);

    /**
     * Counts digests by persona and status.
     *
     * @param personaId persona ID
     * @param status status filter
     * @return count
     */
    @Query("SELECT COUNT(*) FROM bot.digest_history WHERE persona_id = :personaId AND status = :status")
    Mono<Long> countByPersonaIdAndStatus(@Param("personaId") Long personaId, @Param("status") String status);

    /**
     * Counts digests by persona since a timestamp.
     *
     * @param personaId persona ID
     * @param since timestamp threshold
     * @return count
     */
    @Query("SELECT COUNT(*) FROM bot.digest_history WHERE persona_id = :personaId AND created_at >= :since")
    Mono<Long> countByPersonaIdSince(@Param("personaId") Long personaId, @Param("since") Instant since);

    /**
     * Calculates average generation time for a persona.
     *
     * @param personaId persona ID
     * @return average generation time in milliseconds
     */
    @Query("""
            SELECT COALESCE(AVG(generation_time_ms), 0)
            FROM bot.digest_history
            WHERE persona_id = :personaId AND generation_time_ms IS NOT NULL
            """)
    Mono<Double> avgGenerationTimeByPersonaId(@Param("personaId") Long personaId);

    /**
     * Finds failed digests since a timestamp.
     *
     * @param since timestamp threshold
     * @return flux of failed digests
     */
    @Query("""
            SELECT * FROM bot.digest_history
            WHERE status = 'FAILED' AND created_at >= :since
            ORDER BY created_at DESC
            """)
    Flux<DigestHistory> findFailedSince(@Param("since") Instant since);

    /**
     * Updates status of a digest.
     *
     * @param id history record ID
     * @param status new status
     * @param telegramMessageId Telegram message ID
     * @param publishedAt published timestamp
     * @return number of updated rows
     */
    @Modifying
    @Query("""
            UPDATE bot.digest_history
            SET status = :status,
                telegram_message_id = :telegramMessageId,
                published_at = :publishedAt
            WHERE id = :id
            """)
    Mono<Integer> updatePublished(
            @Param("id") Long id,
            @Param("status") String status,
            @Param("telegramMessageId") Long telegramMessageId,
            @Param("publishedAt") Instant publishedAt
    );

    /**
     * Updates status to failed with error message.
     *
     * @param id history record ID
     * @param errorMessage error message
     * @return number of updated rows
     */
    @Modifying
    @Query("""
            UPDATE bot.digest_history
            SET status = 'FAILED', error_message = :errorMessage
            WHERE id = :id
            """)
    Mono<Integer> updateFailed(@Param("id") Long id, @Param("errorMessage") String errorMessage);

    /**
     * Finds all history ordered by creation date descending.
     *
     * @param limit maximum records
     * @return flux of digest history
     */
    @Query("SELECT * FROM bot.digest_history ORDER BY created_at DESC LIMIT :limit")
    Flux<DigestHistory> findAllRecent(@Param("limit") int limit);

    /**
     * Calculates success rate for a persona.
     *
     * @param personaId persona ID
     * @return success rate as percentage (0-100)
     */
    @Query("""
            SELECT COALESCE(
                CAST(SUM(CASE WHEN status = 'PUBLISHED' THEN 1 ELSE 0 END) AS DOUBLE PRECISION) * 100 /
                NULLIF(COUNT(*), 0),
                0
            )
            FROM bot.digest_history
            WHERE persona_id = :personaId
            """)
    Mono<Double> calculateSuccessRate(@Param("personaId") Long personaId);

    /**
     * Deletes old history records older than a threshold.
     *
     * @param olderThan timestamp threshold
     * @return number of deleted rows
     */
    @Modifying
    @Query("DELETE FROM bot.digest_history WHERE created_at < :olderThan")
    Mono<Integer> deleteOlderThan(@Param("olderThan") Instant olderThan);
}
