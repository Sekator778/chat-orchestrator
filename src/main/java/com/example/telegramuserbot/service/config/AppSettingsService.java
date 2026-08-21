package com.example.telegramuserbot.service.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Owner control plane for runtime-tunable behavior. Every operational knob lives
 * as a row in bot.app_settings instead of an env var or a compiled-in default, so
 * the owner changes behavior by editing a row — no redeploy.
 *
 * <p>Values are kept in an in-memory snapshot and refreshed on a TTL poll (≈20 min,
 * matching the existing chat-config cache style), so hot-path reads cost no I/O.
 * The snapshot is loaded eagerly at startup and swapped atomically on each refresh;
 * a DB hiccup keeps the last good snapshot rather than reverting to fallbacks.
 *
 * <p>Typed getters take a fallback used ONLY when the row is absent or unparseable —
 * the table value always wins when present. The fallback is a safety net for a
 * missing row, not a place to configure behavior.
 *
 * <p>Mirrors the durable-DB-flag pattern already used by
 * {@link com.example.telegramuserbot.service.safety.OutboundKillSwitch}.
 */
@Service
public class AppSettingsService {

    /** Refresh cadence (ms). 20 min — the owner's intended TTL for the settings cache. */
    private static final long REFRESH_MS = 20 * 60 * 1000L;

    private static final Logger log = LoggerFactory.getLogger(AppSettingsService.class);

    private final DatabaseClient databaseClient;

    /** Immutable snapshot, swapped wholesale on refresh; volatile for safe publication. */
    private volatile Map<String, String> snapshot = Map.of();

    public AppSettingsService(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    public boolean getBoolean(String name, boolean fallback) {
        String v = snapshot.get(name);
        return v != null ? Boolean.parseBoolean(v.trim()) : fallback;
    }

    public int getInt(String name, int fallback) {
        String v = snapshot.get(name);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            log.warn("AppSettings '{}'='{}' is not an int — using fallback {}", name, v, fallback);
            return fallback;
        }
    }

    public long getLong(String name, long fallback) {
        String v = snapshot.get(name);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            log.warn("AppSettings '{}'='{}' is not a long — using fallback {}", name, v, fallback);
            return fallback;
        }
    }

    public double getDouble(String name, double fallback) {
        String v = snapshot.get(name);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            log.warn("AppSettings '{}'='{}' is not a double — using fallback {}", name, v, fallback);
            return fallback;
        }
    }

    public String getString(String name, String fallback) {
        String v = snapshot.get(name);
        return v != null ? v : fallback;
    }

    /**
     * BLOCKING initial load, ordered FIRST among {@link ApplicationReadyEvent} listeners
     * ({@link Ordered#HIGHEST_PRECEDENCE}) so the in-memory snapshot is fully populated before
     * any other startup consumer runs — e.g. {@code TelegramClientManager}'s lifecycle-listener
     * notification (which drives {@code onClientReady} consumers like the collector channel
     * registry). This closes the startup race where a consumer could read a flag
     * ({@code collector.channel-registry.enabled}, {@code news.*-backfill.enabled}, …) before the
     * previously-asynchronous snapshot load completed and silently get the fallback value.
     *
     * <p>Runs at ApplicationReady (after Liquibase has created {@code bot.app_settings}). The
     * bounded {@code block} timeout means a slow/unavailable DB degrades to an empty snapshot
     * (fallbacks) rather than hanging startup; the {@link #scheduledRefresh()} poll then recovers.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public void onApplicationReady() {
        try {
            refreshFromDatabase().block(Duration.ofSeconds(10));
        } catch (Exception e) {
            log.warn("AppSettings: blocking initial load failed ({}) — starting with empty snapshot; "
                    + "scheduled refresh will recover", e.getMessage());
        }
    }

    /** TTL poll: picks up edits made straight in the DB (or by another instance). */
    @Scheduled(fixedDelay = REFRESH_MS, initialDelay = REFRESH_MS)
    public void scheduledRefresh() {
        refreshFromDatabase().subscribe();
    }

    /**
     * Reloads the whole settings table into a fresh snapshot and swaps it in.
     * On error the previous snapshot is kept (never reverts to fallbacks).
     * Public so an admin endpoint can force an immediate re-read.
     */
    public Mono<Void> refreshFromDatabase() {
        return databaseClient.sql("SELECT name, value FROM bot.app_settings")
                .map(row -> new String[]{row.get("name", String.class), row.get("value", String.class)})
                .all()
                .collectMap(pair -> pair[0], pair -> pair[1] != null ? pair[1] : "")
                .doOnNext(loaded -> {
                    snapshot = Map.copyOf(new HashMap<>(loaded));
                    log.info("AppSettings refreshed: {} setting(s) loaded from bot.app_settings", loaded.size());
                })
                .onErrorResume(e -> {
                    log.warn("AppSettings refresh failed — keeping last snapshot ({} setting(s)): {}",
                            snapshot.size(), e.getMessage());
                    return Mono.empty();
                })
                .then();
    }
}
