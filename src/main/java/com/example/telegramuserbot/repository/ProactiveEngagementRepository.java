package com.example.telegramuserbot.repository;

import com.example.telegramuserbot.domain.ProactiveEngagement;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * R2DBC repository for proactive engagement schedules.
 * Each row tracks one bot persona's daily send schedule for one chat.
 */
public interface ProactiveEngagementRepository extends R2dbcRepository<ProactiveEngagement, Long> {

    @Query("SELECT * FROM bot.proactive_engagements WHERE chat_id = :chatId AND bot_instance_id = :botInstanceId")
    Mono<ProactiveEngagement> findByChatIdAndBotInstanceId(
            @Param("chatId") Long chatId,
            @Param("botInstanceId") String botInstanceId);

    /**
     * Returns engagements that are due to run: enabled, send_hour_utc matches current UTC hour,
     * and either never sent today or not sent today at all.
     */
    @Query("""
        SELECT * FROM bot.proactive_engagements
        WHERE enabled = TRUE
          AND send_hour_utc = :currentHour
          AND (last_sent_at IS NULL
               OR last_sent_at < date_trunc('day', NOW() AT TIME ZONE 'UTC'))
        ORDER BY last_sent_at ASC NULLS FIRST
        LIMIT 50
    """)
    Flux<ProactiveEngagement> findDueEngagements(@Param("currentHour") short currentHour);

    @Modifying
    @Query("""
        UPDATE bot.proactive_engagements
        SET last_sent_at = :lastSentAt, last_anchor_message_id = :anchorId, updated_at = NOW()
        WHERE id = :id
    """)
    Mono<Integer> markSent(
            @Param("id") Long id,
            @Param("lastSentAt") java.time.Instant lastSentAt,
            @Param("anchorId") Long anchorId);

    @Query("DELETE FROM bot.proactive_engagements WHERE chat_id = :chatId AND bot_instance_id = :botInstanceId")
    Mono<Void> deleteByChatIdAndBotInstanceId(
            @Param("chatId") Long chatId,
            @Param("botInstanceId") String botInstanceId);
}
