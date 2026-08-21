package com.example.telegramuserbot.service.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Bridges the owner's admin API to the TDLib login flow: the owner submits a
 * verification code (or 2FA password) for an account, and the client waiting in
 * AuthorizationStateWaitCode/WaitPassword polls it here instead of reading the
 * container console. One-shot — a value is consumed atomically so it is used
 * exactly once.
 */
@Service
public class TelegramAuthCodeService {

    public static final String KIND_CODE = "CODE";
    public static final String KIND_PASSWORD = "PASSWORD";

    private static final Logger log = LoggerFactory.getLogger(TelegramAuthCodeService.class);

    private final DatabaseClient databaseClient;

    public TelegramAuthCodeService(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    /** Owner submits a code/password for an account (admin API). */
    public Mono<Long> submit(String botId, String kind, String value) {
        return databaseClient.sql("""
                        INSERT INTO bot.auth_codes (bot_id, kind, value)
                        VALUES (:botId, :kind, :value)
                        """)
                .bind("botId", botId)
                .bind("kind", normalizeKind(kind))
                .bind("value", value)
                .fetch()
                .rowsUpdated()
                .doOnSuccess(n -> log.info("Auth {} submitted for botId={}", normalizeKind(kind), botId));
    }

    /** Atomically consumes (deletes) the latest unused value of the kind, or empty if none. */
    public Mono<String> consumeLatest(String botId, String kind) {
        return databaseClient.sql("""
                        DELETE FROM bot.auth_codes
                         WHERE id = (
                             SELECT id FROM bot.auth_codes
                              WHERE bot_id = :botId AND kind = :kind AND consumed = FALSE
                              ORDER BY created_at DESC
                              LIMIT 1
                         )
                        RETURNING value
                        """)
                .bind("botId", botId)
                .bind("kind", normalizeKind(kind))
                .map(row -> row.get("value", String.class))
                .one();
    }

    /**
     * Purges stale auth_codes rows older than the given TTL — both
     * unconsumed-and-stale and any leftover consumed rows.  A Telegram
     * login window is typically a few minutes; keeping codes for more than
     * one hour serves no purpose and is a security hazard.
     *
     * @param ttlHours maximum age in hours before a row is purged
     * @return count of deleted rows
     */
    public Mono<Long> purgeStale(int ttlHours) {
        return databaseClient.sql("""
                        DELETE FROM bot.auth_codes
                         WHERE created_at < NOW() - (INTERVAL '1 hour' * :ttlHours)
                        """)
                .bind("ttlHours", ttlHours)
                .fetch()
                .rowsUpdated()
                .doOnSuccess(n -> { if (n > 0) log.info("Purged {} stale auth_codes rows (ttl={}h)", n, ttlHours); });
    }

    /**
     * Blocks (on the calling auth thread) until a value is submitted or the
     * timeout elapses. Used by the TDLib auth handler during a headless login.
     */
    public String awaitValue(String botId, String kind, Duration timeout, Duration pollInterval) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            String value = consumeLatest(botId, normalizeKind(kind)).block(pollInterval.plusSeconds(5));
            if (value != null && !value.isBlank()) {
                log.info("Auth {} picked up for botId={}", normalizeKind(kind), botId);
                return value;
            }
            try {
                Thread.sleep(pollInterval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        log.warn("Timed out waiting for auth {} for botId={}", normalizeKind(kind), botId);
        return null;
    }

    private String normalizeKind(String kind) {
        return KIND_PASSWORD.equalsIgnoreCase(kind) ? KIND_PASSWORD : KIND_CODE;
    }
}
