package com.example.telegramuserbot.scheduler;

import com.example.telegramuserbot.repository.MessageRepository;
import com.example.telegramuserbot.service.config.AppSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Daily retention job that prunes {@code bot.messages} rows older than a
 * configurable number of days (default 7).
 *
 * <p>Retention window is controlled via {@code bot.app_settings}:
 * <ul>
 *   <li>{@code retention.enabled} — {@code false} (fail-safe default) / {@code true} to enable purge</li>
 *   <li>{@code retention.days}    — integer days (default 7)</li>
 * </ul>
 * No DB migration is required: the fallback values cover a missing row.
 *
 * <p>Media files on disk are <em>not</em> cleaned up by this job — the
 * {@link com.example.telegramuserbot.service.MediaStorageService} does not expose
 * a delete API, and the stored paths require TDLib context to resolve safely.
 * Media cleanup is tracked as a follow-up item.
 *
 * <p>Mirrors the {@code @Scheduled} + {@code subscribeOn(boundedElastic())} pattern
 * used by {@code AuthCodesMaintenanceScheduler} and other maintenance jobs.
 */
@Component
public final class MessageRetentionScheduler {

    private static final Logger log = LoggerFactory.getLogger(MessageRetentionScheduler.class);

    /** Default retention window in days when the app_settings row is absent. */
    static final int DEFAULT_RETENTION_DAYS = 7;

    private final MessageRepository messageRepository;
    private final AppSettingsService appSettings;

    public MessageRetentionScheduler(MessageRepository messageRepository,
                                     AppSettingsService appSettings) {
        this.messageRepository = messageRepository;
        this.appSettings = appSettings;
    }

    /**
     * Runs once a day (initial delay 5 minutes to avoid startup contention).
     * Deletes {@code bot.messages} rows whose {@code date} is older than
     * {@code retention.days} calendar days.
     */
    @Scheduled(fixedRateString  = "${retention.purge-interval-ms:86400000}",
               initialDelayString = "${retention.purge-initial-delay-ms:300000}")
    public void purgeOldMessages() {
        boolean enabled = appSettings.getBoolean("retention.enabled", false);
        if (!enabled) {
            log.debug("Message retention is disabled via app_settings (retention.enabled=false) — skipping");
            return;
        }

        int retentionDays = appSettings.getInt("retention.days", DEFAULT_RETENTION_DAYS);
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);

        log.info("Starting message retention purge (retention.days={}, cutoff={})", retentionDays, cutoff);

        messageRepository.deleteOlderThan(cutoff)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        deleted -> {
                            if (deleted > 0) {
                                log.info("Message retention purge complete: deleted {} row(s) older than {} days",
                                        deleted, retentionDays);
                            } else {
                                log.debug("Message retention purge: no rows older than {} days found", retentionDays);
                            }
                        },
                        error -> log.error("Message retention purge failed", error));
    }
}
