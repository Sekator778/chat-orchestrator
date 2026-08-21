package com.example.telegramuserbot.repository;

import com.example.telegramuserbot.domain.RateLimits;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface RateLimitsRepository extends R2dbcRepository<RateLimits, Long> {

    /**
     * ENTERPRISE FIX: Заменяем старый, сложный и ошибочный запрос на
     * корректный, читаемый и эффективный запрос с использованием JOIN.
     */
    @Query("""
        SELECT rl.* FROM bot.rate_limits rl
        JOIN bot.chat_configs cc ON rl.chat_config_id = cc.id
        WHERE cc.channel_chat_id = :chatId
    """)
    Mono<RateLimits> findByChatConfigChannelChatId(@Param("chatId") Long chatId);

    /**
     * ENTERPRISE FIX: Исправляем запрос для проверки существования записи.
     */
    @Query("""
        SELECT COUNT(rl.id) > 0 FROM bot.rate_limits rl
        JOIN bot.chat_configs cc ON rl.chat_config_id = cc.id
        WHERE cc.channel_chat_id = :chatId
    """)
    Mono<Boolean> existsByChatConfigChannelChatId(@Param("chatId") Long chatId);

    @Query("SELECT * FROM bot.rate_limits WHERE chat_config_id = :chatConfigId")
    Mono<RateLimits> findByChatConfigId(@Param("chatConfigId") Long chatConfigId);

    @Query("SELECT * FROM bot.rate_limits WHERE max_messages_per_day IS NOT NULL")
    Flux<RateLimits> findAllWithDailyLimits();

    @Query("SELECT * FROM bot.rate_limits WHERE max_messages_per_hour IS NOT NULL")
    Flux<RateLimits> findAllWithHourlyLimits();

    @Query("SELECT * FROM bot.rate_limits WHERE burst_limit IS NOT NULL AND burst_window_seconds IS NOT NULL")
    Flux<RateLimits> findAllWithBurstLimits();

    @Modifying
    @Query("UPDATE bot.rate_limits SET max_messages_per_day = :limit WHERE id = :id")
    Mono<Integer> updateDailyLimit(@Param("id") Long id, @Param("limit") Integer limit);

    @Modifying
    @Query("UPDATE bot.rate_limits SET max_messages_per_hour = :limit WHERE id = :id")
    Mono<Integer> updateHourlyLimit(@Param("id") Long id, @Param("limit") Integer limit);

    @Query("DELETE FROM bot.rate_limits WHERE chat_config_id = :chatConfigId")
    Mono<Void> deleteByChatConfigId(@Param("chatConfigId") Long chatConfigId);

    @Modifying
    @Query("""
        UPDATE bot.rate_limits
           SET current_daily_messages = current_daily_messages + 1
         WHERE chat_config_id = :chatConfigId
    """)
    Mono<Integer> incrementCurrentDailyCount(@Param("chatConfigId") Long chatConfigId);

    @Modifying
    @Query("""
        UPDATE bot.rate_limits
           SET current_daily_messages = current_daily_messages + 1
         WHERE chat_config_id = :chatConfigId
           AND max_messages_per_day IS NOT NULL
           AND max_messages_per_day > 0
           AND current_daily_messages < max_messages_per_day
    """)
    Mono<Integer> incrementDailyIfAllowed(@Param("chatConfigId") Long chatConfigId);

    /**
     * Atomically reserves N daily slots (chat-scoped) if enough quota remains.
     * Returns 1 if the reservation succeeded, 0 otherwise.
     */
    @Modifying
    @Query("""
        UPDATE bot.rate_limits
           SET current_daily_messages = current_daily_messages + :slots
         WHERE chat_config_id = :chatConfigId
           AND max_messages_per_day IS NOT NULL
           AND max_messages_per_day > 0
           AND current_daily_messages + :slots <= max_messages_per_day
    """)
    Mono<Integer> reserveDailySlotsIfAllowed(@Param("chatConfigId") Long chatConfigId, @Param("slots") int slots);

    @Modifying
    @Query("""
        UPDATE bot.rate_limits
           SET current_daily_messages = 0
         WHERE current_daily_messages > 0
    """)
    Mono<Integer> resetAllDailyCounts();

    @Modifying
    @Query("""
        UPDATE bot.rate_limits rl
           SET current_daily_messages = 0
          FROM bot.chat_configs cc
         WHERE rl.chat_config_id = cc.id
           AND cc.channel_chat_id = :chatId
    """)
    Mono<Integer> resetDailyCountByChatId(@Param("chatId") long chatId);
}
