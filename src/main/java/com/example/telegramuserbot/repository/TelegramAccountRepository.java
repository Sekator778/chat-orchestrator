package com.example.telegramuserbot.repository;

import com.example.telegramuserbot.domain.TelegramAccount;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Mono;

public interface TelegramAccountRepository extends R2dbcRepository<TelegramAccount, Long> {

    @Query("""
            SELECT *
              FROM bot.telegram_accounts
             WHERE bot_id = :botId
            """)
    Mono<TelegramAccount> findByBotId(@Param("botId") String botId);

    @Query("""
            SELECT EXISTS (
                SELECT 1
                  FROM bot.telegram_accounts
                 WHERE is_collector = TRUE
            )
            """)
    Mono<Boolean> collectorExists();

    @Query("""
            SELECT COALESCE(
                (SELECT is_collector
                   FROM bot.telegram_accounts
                  WHERE bot_id = :botId),
                FALSE)
            """)
    Mono<Boolean> isCollector(@Param("botId") String botId);

    /**
     * Returns the single collector account (is_collector = true).
     * Empty if no collector has been registered yet.
     */
    @Query("""
            SELECT *
              FROM bot.telegram_accounts
             WHERE is_collector = TRUE
             LIMIT 1
            """)
    Mono<TelegramAccount> findCollector();
}
