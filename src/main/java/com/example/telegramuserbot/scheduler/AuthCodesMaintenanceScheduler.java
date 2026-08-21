package com.example.telegramuserbot.scheduler;

import com.example.telegramuserbot.service.auth.TelegramAuthCodeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Schedulers;

/**
 * Periodically purges stale rows from {@code bot.auth_codes}.
 *
 * <p>Telegram login codes are valid for only a few minutes; keeping them
 * in plaintext beyond that window is a security hazard. This job removes
 * any row (whether consumed or not) that is older than
 * {@value #TTL_HOURS} hour, ensuring secrets do not accumulate.</p>
 *
 * <p>Runs every hour; the first execution is delayed by 5 minutes to
 * avoid contention during application startup.</p>
 */
@Component
public final class AuthCodesMaintenanceScheduler {

    private static final Logger log = LoggerFactory.getLogger(AuthCodesMaintenanceScheduler.class);

    /** Rows older than this many hours are considered stale and purged. */
    static final int TTL_HOURS = 1;

    private final TelegramAuthCodeService authCodeService;

    public AuthCodesMaintenanceScheduler(TelegramAuthCodeService authCodeService) {
        this.authCodeService = authCodeService;
    }

    /**
     * Deletes stale auth_codes rows every hour (initial delay 5 minutes).
     */
    @Scheduled(fixedRateString = "${auth.codes.purge-interval-ms:3600000}",
               initialDelayString = "${auth.codes.purge-initial-delay-ms:300000}")
    public void purgeStaleAuthCodes() {
        log.debug("Starting stale auth_codes purge (ttl={}h)", TTL_HOURS);
        authCodeService.purgeStale(TTL_HOURS)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        n -> { /* logged inside purgeStale when n > 0 */ },
                        e -> log.error("Failed to purge stale auth_codes", e));
    }
}
