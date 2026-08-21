package com.example.telegramuserbot.repository;

import com.example.telegramuserbot.domain.ChannelCandidate;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Repository for {@code tgscan.channel_candidates}.
 * Provides an idempotent insert used by {@code ChannelDiscoverySearchScheduler}
 * so that re-runs of the keyword sweep do not produce duplicate rows.
 * Also provides the join-phase queries used by {@code ChannelDiscoveryJoinScheduler}.
 */
public interface ChannelCandidateRepository extends R2dbcRepository<ChannelCandidate, Long> {

    /**
     * Inserts a candidate row only if the same {@code candidate} value is not yet present
     * (processed or not). Returns 1 if a row was inserted, 0 if it was skipped.
     *
     * @param candidate  TDLib chat-id as text (e.g. "-1001234567890")
     * @param note       Discovery keyword that produced this candidate
     * @return Mono emitting the number of rows inserted (0 or 1)
     */
    @Modifying
    @Query("""
            INSERT INTO tgscan.channel_candidates (candidate, discovered_at, processed, note)
            SELECT :candidate, now(), false, :note
            WHERE NOT EXISTS (
                SELECT 1 FROM tgscan.channel_candidates WHERE candidate = :candidate
            )
            """)
    Mono<Integer> insertIfAbsent(@Param("candidate") String candidate,
                                 @Param("note") String note);

    /**
     * Returns up to {@code limit} candidates that have not yet been processed
     * (joined or attempted). Ordered oldest-first so the backlog drains in FIFO order.
     *
     * @param limit maximum number of rows to return
     * @return Flux of unprocessed candidates
     */
    @Query("""
            SELECT *
              FROM tgscan.channel_candidates
             WHERE processed = false
             ORDER BY discovered_at ASC
             LIMIT :limit
            """)
    Flux<ChannelCandidate> findUnprocessed(@Param("limit") int limit);

    /**
     * Marks a candidate as processed so it is not retried on the next scheduler run.
     * Called both on success (joined) and on permanent failure (e.g. invalid chat-id).
     *
     * @param id primary key of the row to mark
     * @return Mono emitting the number of rows updated (1 on success)
     */
    @Modifying
    @Query("""
            UPDATE tgscan.channel_candidates
               SET processed = true
             WHERE id = :id
            """)
    Mono<Integer> markProcessed(@Param("id") Long id);
}
