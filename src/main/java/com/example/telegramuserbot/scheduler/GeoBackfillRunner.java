package com.example.telegramuserbot.scheduler;

import com.example.telegramuserbot.repository.MessageRepository;
import com.example.telegramuserbot.service.config.AppSettingsService;
import com.example.telegramuserbot.service.ranking.GeoTaggingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One-shot backfill that populates {@code bot.messages.geo} for all existing rows
 * where the column is {@code NULL} and there is content to classify.
 *
 * <p>Gated by the app setting {@code news.geo-backfill.enabled} (default {@code true}).
 * The flag is read AFTER the startup delay so AppSettingsService has already loaded
 * its snapshot from the DB — no startup race possible.
 *
 * <p>Processing is purely forward-paged by {@code id} (cursor strictly increases →
 * always terminates).  Runs on {@link Schedulers#boundedElastic()} so blocking
 * calls ({@code .block()} inside {@link #processBatch}) are legal.
 *
 * <p>Unlike {@link KeywordBackfillRunner}, this runner intentionally reads the enable
 * flag inside the delayed runnable (after the startup delay) rather than at
 * {@link #run} call-time, so late-loading settings are always respected.
 */
@Component
public final class GeoBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GeoBackfillRunner.class);

    private static final String SETTING_ENABLED = "news.geo-backfill.enabled";
    private static final int BATCH_SIZE = 500;

    /** Prevents re-entry if the runner is somehow triggered more than once. */
    private final AtomicBoolean executed = new AtomicBoolean(false);

    private final AppSettingsService appSettingsService;
    private final GeoTaggingService geoTaggingService;
    private final MessageRepository messageRepository;

    public GeoBackfillRunner(AppSettingsService appSettingsService,
                             GeoTaggingService geoTaggingService,
                             MessageRepository messageRepository) {
        this.appSettingsService = appSettingsService;
        this.geoTaggingService = geoTaggingService;
        this.messageRepository = messageRepository;
    }

    /**
     * Called by Spring after the application context is fully started.
     * Schedules the backfill asynchronously on a bounded-elastic thread after a short
     * delay to let AppSettingsService finish its initial load from the DB.
     */
    @Override
    public void run(ApplicationArguments args) {
        if (!executed.compareAndSet(false, true)) {
            return;
        }
        log.info("[GeoBackfill] Scheduling geo backfill (async, boundedElastic, 15 s delay)");
        // Mono.delay emits on Schedulers.parallel(); publishOn(boundedElastic) placed AFTER it moves
        // the downstream Mono.fromRunnable(runBackfill) — which calls .block() in processBatch — onto
        // a bounded-elastic thread where blocking is legal. NOTE: subscribeOn would NOT achieve this,
        // because Mono.delay overrides the execution thread to its own scheduler regardless.
        Mono.delay(Duration.ofSeconds(15))
                .publishOn(Schedulers.boundedElastic())
                .then(Mono.fromRunnable(this::runBackfill))
                .subscribe(
                        v -> { /* completed inline */ },
                        error -> log.error("[GeoBackfill] Backfill failed unexpectedly", error)
                );
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    /**
     * Runs synchronously on a bounded-elastic thread (called via {@code Mono.fromRunnable}).
     * Reads the enable flag here — AFTER the startup delay — so AppSettingsService has
     * already loaded its DB snapshot.
     */
    private void runBackfill() {
        // Read enable flag AFTER the startup delay (AppSettingsService is already loaded)
        if (!appSettingsService.getBoolean(SETTING_ENABLED, true)) {
            log.info("[GeoBackfill] Disabled via app setting '{}'; skipping", SETTING_ENABLED);
            return;
        }

        log.info("[GeoBackfill] Starting geo backfill");
        AtomicLong totalUpdated = new AtomicLong(0);
        AtomicLong totalProcessed = new AtomicLong(0);

        long afterId = 0L;
        boolean hasMore = true;
        while (hasMore) {
            long[] batchCounts = processBatch(afterId);
            long processed = batchCounts[0];
            long updated = batchCounts[1];
            long maxId = batchCounts[2];
            totalProcessed.addAndGet(processed);
            totalUpdated.addAndGet(updated);
            if (processed < BATCH_SIZE) {
                hasMore = false;
            } else {
                afterId = maxId;
            }
            if (processed > 0) {
                log.debug("[GeoBackfill] Batch done: afterId={}, processed={}, updated={}", afterId, processed, updated);
            }
        }

        log.info("[GeoBackfill] Backfill complete: {} row(s) processed, {} row(s) updated with geo",
                totalProcessed.get(), totalUpdated.get());
    }

    /**
     * Processes one forward-paged batch of up to {@value #BATCH_SIZE} rows whose id
     * is greater than {@code afterId}.  Runs on a bounded-elastic thread so {@code .block()}
     * is legal here.
     *
     * @param afterId exclusive lower bound on id for this batch
     * @return {@code long[]{processedCount, updatedCount, maxIdSeen}}
     */
    private long[] processBatch(long afterId) {
        AtomicLong processed = new AtomicLong(0);
        AtomicLong updated = new AtomicLong(0);
        AtomicLong maxId = new AtomicLong(afterId);

        messageRepository.findGeoBackfillBatch(afterId, BATCH_SIZE)
                .concatMap(entity -> {
                    processed.incrementAndGet();
                    if (entity.getId() != null) {
                        maxId.accumulateAndGet(entity.getId(), Math::max);
                    }
                    String text = entity.getContent() != null && !entity.getContent().isBlank()
                            ? entity.getContent()
                            : entity.getCaption();
                    String geo = geoTaggingService.classify(text);
                    return messageRepository.updateGeo(entity.getId(), geo)
                            .doOnNext(rows -> {
                                if (rows > 0) {
                                    updated.incrementAndGet();
                                }
                            })
                            .onErrorResume(ex -> {
                                log.warn("[GeoBackfill] Failed to update geo for id={}: {}", entity.getId(), ex.getMessage());
                                return Mono.just(0);
                            });
                })
                .then()
                .block();  // legal: we are on Schedulers.boundedElastic()

        return new long[]{processed.get(), updated.get(), maxId.get()};
    }
}
