package com.example.telegramuserbot.service.maintenance;

import com.example.telegramuserbot.service.digest.DigestSchedulerService;
import com.example.telegramuserbot.service.ranking.NewsSynthesisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Scheduled job for generating news digests from clustered messages.
 * Supports both legacy single-channel mode and new persona-based multi-channel mode.
 * Publishes digests to configured channels based on persona schedules.
 */
@Service
@ConditionalOnProperty(name = "digest.job.enabled", havingValue = "true", matchIfMissing = false)
public final class DigestGenerationJob {

    private static final Logger log = LoggerFactory.getLogger(DigestGenerationJob.class);

    private final NewsSynthesisService synthesisService;
    private final DigestSchedulerService schedulerService;

    @Value("${digest.job.window-hours:24}")
    private int windowHours;

    @Value("${digest.job.max-messages:10}")
    private int maxMessages;

    @Value("${digest.job.language:en}")
    private String language;

    @Value("${digest.job.persona-mode:true}")
    private boolean personaMode;

    /**
     * Constructs job with required services.
     *
     * @param synthesisService legacy synthesis service for single-channel mode
     * @param schedulerService persona-based scheduler service
     */
    public DigestGenerationJob(
            NewsSynthesisService synthesisService,
            DigestSchedulerService schedulerService) {
        this.synthesisService = Objects.requireNonNull(synthesisService, "synthesisService");
        this.schedulerService = Objects.requireNonNull(schedulerService, "schedulerService");
        log.info("DigestGenerationJob initialized (window={}h, max={}, lang={}, personaMode={})",
            windowHours, maxMessages, language, personaMode);
    }

    /**
     * Scheduled job that runs digest generation.
     * In persona mode processes all enabled personas based on their individual schedules.
     * In legacy mode generates single digest using global configuration.
     */
    @Scheduled(fixedDelayString = "${digest.job.check-interval-ms:300000}")
    public void processDigests() {
        log.info("Digest check started (personaMode={})", personaMode);
        if (personaMode) {
            processPersonaDigests();
        } else {
            processLegacyDigest();
        }
    }

    /**
     * Legacy scheduled job for backwards compatibility.
     * Runs at configured cron time regardless of persona mode.
     */
    @Scheduled(cron = "${digest.job.cron:0 0 9 * * *}")
    public void generateDailyDigest() {
        if (personaMode) {
            log.debug("Skipping legacy daily digest in persona mode");
            return;
        }
        log.info("Starting legacy daily digest generation");
        processLegacyDigest();
    }

    /**
     * Processes all persona-based digests.
     * Checks each enabled persona schedule and generates if due.
     */
    private void processPersonaDigests() {
        log.info("Processing persona-based digests");
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        schedulerService.processScheduledDigests()
            .doOnNext(result -> {
                if (result.success()) {
                    successCount.incrementAndGet();
                    log.info("Digest published for persona {}: digestId={}, channel={}",
                        result.personaName(),
                        result.digestId(),
                        result.targetChannelId());
                } else {
                    failureCount.incrementAndGet();
                    log.warn("Digest failed for persona {}: {}",
                        result.personaName(),
                        result.errorMessage());
                }
            })
            .subscribe(
                result -> {},
                error -> log.error("Persona digest processing error: {}", error.getMessage(), error),
                () -> {
                    int success = successCount.get();
                    int failure = failureCount.get();
                    if (success > 0 || failure > 0) {
                        log.info("Persona digest processing completed: success={}, failed={}", success, failure);
                    } else {
                        log.debug("No personas due for digest generation");
                    }
                }
            );
    }

    /**
     * Processes legacy single-channel digest.
     * Uses global configuration from application properties.
     */
    private void processLegacyDigest() {
        log.info("Starting legacy digest generation (window={}h, max={}, lang={})",
            windowHours, maxMessages, language);
        synthesisService.generateDigest(Duration.ofHours(windowHours), maxMessages, language)
            .doOnSuccess(digest -> {
                log.info("Legacy digest generated, length={}", digest.length());
                log.debug("Digest content:\n{}", digest);
            })
            .doOnError(e -> log.error("Legacy digest generation failed: {}", e.getMessage(), e))
            .subscribe();
    }

    /**
     * Manually triggers digest generation for all enabled personas.
     * Called via admin endpoint for immediate processing.
     */
    public void triggerAllPersonas() {
        log.info("Manual trigger for all persona digests");
        AtomicInteger count = new AtomicInteger(0);
        schedulerService.triggerAllEnabled()
            .doOnNext(result -> {
                count.incrementAndGet();
                log.info("Manual digest {}: persona={}, success={}, digestId={}",
                    count.get(),
                    result.personaName(),
                    result.success(),
                    result.digestId());
            })
            .doOnComplete(() -> log.info("Manual trigger completed: {} digests processed", count.get()))
            .doOnError(e -> log.error("Manual trigger error: {}", e.getMessage(), e))
            .subscribe();
    }
}
