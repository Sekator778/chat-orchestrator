package com.example.telegramuserbot.service.proactive;

import com.example.telegramuserbot.repository.DigestPersonaRepository;
import com.example.telegramuserbot.service.config.AppSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;

/**
 * On startup, resets {@code last_run_at} to now for all enabled digest personas.
 *
 * <p>Without this, a persona that last posted just before a long downtime would see
 * {@code last_run_at} far in the past on restart, causing the min-interval gate to pass
 * immediately and triggering a burst of posts on the first few 5-min ticks.  Anchoring
 * the clock to restart time means the persona waits a full {@code min-interval-hours}
 * before posting again — a clean, human-paced cadence after any downtime.
 *
 * <p>The flag {@code news.proactive-posting.resync-on-startup} (default {@code true}) lets
 * operators disable this behaviour at runtime without a redeploy.
 *
 * <p>Threading: subscribes to the reactive update without blocking — the
 * {@code ApplicationReadyEvent} fires on the main thread after the context is fully
 * started.  {@code AppSettingsService} is guaranteed to have loaded its snapshot
 * (it uses {@code @Order(HIGHEST_PRECEDENCE)} + a blocking latch) so reading flags
 * here is safe.
 */
@Component
public class ProactivePostingStartupResync {

    private static final Logger log = LoggerFactory.getLogger(ProactivePostingStartupResync.class);

    private final AppSettingsService appSettings;
    private final DigestPersonaRepository digestPersonaRepository;

    public ProactivePostingStartupResync(
            AppSettingsService appSettings,
            DigestPersonaRepository digestPersonaRepository) {
        this.appSettings = Objects.requireNonNull(appSettings);
        this.digestPersonaRepository = Objects.requireNonNull(digestPersonaRepository);
    }

    /**
     * Runs after the application context is fully started.
     * AppSettingsService has already loaded its snapshot at this point.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        // Read flag at run time — guaranteed safe (AppSettings loaded by HIGHEST_PRECEDENCE)
        boolean resync = appSettings.getBoolean("news.proactive-posting.resync-on-startup", true);
        if (!resync) {
            log.info("[ProactiveResync] resync-on-startup=false — skipping last_run_at reset");
            return;
        }

        Instant now = Instant.now();
        log.info("[ProactiveResync] Resetting last_run_at to {} for all enabled digest_personas", now);

        digestPersonaRepository.resyncEnabledLastRunAt(now)
                .subscribe(
                        count -> log.info("[ProactiveResync] Reset last_run_at for {} enabled persona(s)", count),
                        err   -> log.error("[ProactiveResync] Failed to reset last_run_at: {}", err.getMessage(), err)
                );
    }
}
