package com.example.telegramuserbot.repository;

import com.example.telegramuserbot.domain.PersonaReactionLog;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * Reactive repository for persona reaction log records.
 * Tracks all pending, executed, and failed reactions.
 */
@Repository
public interface PersonaReactionLogRepository extends ReactiveCrudRepository<PersonaReactionLog, Long> {

    /**
     * Counts successfully executed reactions for a persona since a given time.
     *
     * @param personaId the persona identifier
     * @param since     the lower bound timestamp
     * @return mono of the count
     */
    @Query("SELECT COUNT(*) FROM bot.persona_reaction_log WHERE persona_id = :personaId AND status = 'DONE' AND created_at >= :since")
    Mono<Long> countDoneByPersonaIdSince(String personaId, Instant since);

    /**
     * Counts successfully executed reactions for a persona on a specific channel since a given time.
     *
     * @param personaId the persona identifier
     * @param channelId the Telegram channel ID
     * @param since     the lower bound timestamp
     * @return mono of the count
     */
    @Query("SELECT COUNT(*) FROM bot.persona_reaction_log WHERE persona_id = :personaId AND channel_id = :channelId AND status = 'DONE' AND created_at >= :since")
    Mono<Long> countDoneByPersonaIdAndChannelIdSince(String personaId, Long channelId, Instant since);

    /**
     * Finds the most recently executed reaction for a persona on a specific channel.
     *
     * @param personaId the persona identifier
     * @param channelId the Telegram channel ID
     * @return mono of the last done log entry, or empty if none
     */
    @Query("SELECT * FROM bot.persona_reaction_log WHERE persona_id = :personaId AND channel_id = :channelId AND status = 'DONE' ORDER BY executed_at DESC LIMIT 1")
    Mono<PersonaReactionLog> findLastDoneByPersonaIdAndChannelId(String personaId, Long channelId);

    /**
     * Finds pending reaction log entries that are due for execution.
     *
     * @param limit maximum number of entries to return
     * @return flux of pending reactions scheduled at or before now
     */
    @Query("SELECT * FROM bot.persona_reaction_log WHERE status = 'PENDING' AND scheduled_at <= NOW() ORDER BY persona_id, channel_id, scheduled_at ASC LIMIT :limit")
    Flux<PersonaReactionLog> findPendingDue(int limit);

    /**
     * Counts reactions by status since a given time.
     *
     * @param status the status string to filter by
     * @param since  the lower bound timestamp
     * @return mono of the count
     */
    @Query("SELECT COUNT(*) FROM bot.persona_reaction_log WHERE status = :status AND created_at >= :since")
    Mono<Long> countByStatusSince(String status, Instant since);

    /**
     * Finds all reaction log entries for a persona ordered by creation time descending.
     *
     * @param personaId the persona identifier
     * @return flux of log entries ordered newest first
     */
    Flux<PersonaReactionLog> findByPersonaIdOrderByCreatedAtDesc(String personaId);
}
