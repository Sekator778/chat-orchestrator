package com.example.telegramuserbot.service.safety;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The owner's emergency stop for ALL outbound Telegram traffic of every
 * persona. State lives in bot.runtime_flags (survives restarts, can be flipped
 * straight in the DB) and is mirrored into an AtomicBoolean so the hot send
 * path reads it without I/O. A DB hiccup never activates the switch by
 * accident — the last known state is kept and the poll retries.
 */
@Service
public class OutboundKillSwitch {

    public static final String FLAG_NAME = "outbound_kill_switch";

    private static final Logger log = LoggerFactory.getLogger(OutboundKillSwitch.class);

    private final DatabaseClient databaseClient;
    private final AtomicBoolean active = new AtomicBoolean(false);

    public OutboundKillSwitch(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    /** Hot-path read: no I/O, safe to call per message. */
    public boolean isActive() {
        return active.get();
    }

    /** Flips the switch durably and takes effect in-process immediately. */
    public Mono<Boolean> set(boolean enable) {
        return databaseClient.sql("""
                        INSERT INTO bot.runtime_flags (name, enabled, updated_at)
                        VALUES (:name, :enabled, now())
                        ON CONFLICT (name) DO UPDATE SET enabled = :enabled, updated_at = now()
                        """)
                .bind("name", FLAG_NAME)
                .bind("enabled", enable)
                .fetch()
                .rowsUpdated()
                .map(rows -> {
                    boolean previous = active.getAndSet(enable);
                    if (previous != enable) {
                        log.warn("KILL SWITCH {} — outbound Telegram traffic is now {}",
                                enable ? "ACTIVATED" : "DEACTIVATED", enable ? "SUPPRESSED" : "allowed");
                    }
                    return enable;
                });
    }

    /** Picks up flips made directly in the DB (or by another instance). */
    @Scheduled(fixedDelayString = "${safety.kill-switch.poll-ms:5000}", initialDelayString = "${safety.kill-switch.initial-delay-ms:10000}")
    public void refreshFromDatabase() {
        databaseClient.sql("SELECT enabled FROM bot.runtime_flags WHERE name = :name")
                .bind("name", FLAG_NAME)
                .map(row -> Boolean.TRUE.equals(row.get("enabled", Boolean.class)))
                .one()
                .defaultIfEmpty(false)
                .doOnNext(dbValue -> {
                    boolean previous = active.getAndSet(dbValue);
                    if (previous != dbValue) {
                        log.warn("KILL SWITCH {} (picked up from DB) — outbound Telegram traffic is now {}",
                                dbValue ? "ACTIVATED" : "DEACTIVATED", dbValue ? "SUPPRESSED" : "allowed");
                    }
                })
                .onErrorResume(e -> {
                    log.debug("Kill-switch DB poll failed (keeping last state {}): {}", active.get(), e.getMessage());
                    return Mono.empty();
                })
                .subscribe();
    }
}
