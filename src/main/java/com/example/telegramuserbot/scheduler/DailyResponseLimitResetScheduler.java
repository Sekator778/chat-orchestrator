package com.example.telegramuserbot.scheduler;

import com.example.telegramuserbot.repository.RateLimitsRepository;
import com.example.telegramuserbot.service.cache.SyncEnabledChatsCache;
import com.example.telegramuserbot.service.orchestration.BotContextResolver;
import com.example.telegramuserbot.service.ratelimit.ResponseRateLimitGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Schedulers;

/**
 * Resets daily response counters and clears in-memory caches.
 *
 * Runs daily at 02:00 Europe/Berlin by default (configurable via properties).
 */
@Component
public final class DailyResponseLimitResetScheduler {

    private static final Logger log = LoggerFactory.getLogger(DailyResponseLimitResetScheduler.class);

    private final RateLimitsRepository rateLimitsRepository;
    private final ResponseRateLimitGate responseRateLimitGate;
    private final SyncEnabledChatsCache syncEnabledChatsCache;
    private final BotContextResolver botContextResolver;

    public DailyResponseLimitResetScheduler(
            RateLimitsRepository rateLimitsRepository,
            ResponseRateLimitGate responseRateLimitGate,
            SyncEnabledChatsCache syncEnabledChatsCache,
            BotContextResolver botContextResolver
    ) {
        this.rateLimitsRepository = rateLimitsRepository;
        this.responseRateLimitGate = responseRateLimitGate;
        this.syncEnabledChatsCache = syncEnabledChatsCache;
        this.botContextResolver = botContextResolver;
    }

    @Scheduled(
            cron = "${rate-limits.daily-reset.cron:0 0 2 * * ?}",
            zone = "${rate-limits.daily-reset.zone:Europe/Berlin}"
    )
    public void resetDailyCountersAndCaches() {
        log.info("🧹 DAILY RESET: starting daily counters reset + caches clear");

        rateLimitsRepository.resetAllDailyCounts()
                .doOnSuccess(updated -> log.info("🧹 DAILY RESET: rate_limits resetAllDailyCounts updatedRows={}", updated))
                .doOnError(error -> log.error("🧹 DAILY RESET: failed to reset rate_limits daily counts", error))
                .doFinally(signal -> {
                    responseRateLimitGate.clearAllCaches();
                    syncEnabledChatsCache.invalidateAll();
                    botContextResolver.invalidateAll();
                    log.info("🧹 DAILY RESET: caches cleared (signal={})", signal);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }
}
