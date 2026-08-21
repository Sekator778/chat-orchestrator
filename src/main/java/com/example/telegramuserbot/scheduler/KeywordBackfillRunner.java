package com.example.telegramuserbot.scheduler;

import com.example.telegramuserbot.repository.MessageRepository;
import com.example.telegramuserbot.service.config.AppSettingsService;
import com.example.telegramuserbot.service.ranking.KeywordMatchingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * F3 — One-shot backfill that populates {@code bot.messages.matched_keywords}
 * for all existing rows where the column is null or empty.
 *
 * <p>Runs once at startup (after a short delay to let the keyword cache warm up),
 * then self-disables.  Gated by the app setting
 * {@code news.keyword-backfill.enabled} (default {@code true}).
 *
 * <p>Processes rows in bounded batches of {@value #BATCH_SIZE} to avoid
 * locking the table.  Only touches rows with empty {@code matched_keywords}
 * so it is safe to re-run (idempotent).
 */
@Component
public final class KeywordBackfillRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KeywordBackfillRunner.class);

    private static final String SETTING_ENABLED = "news.keyword-backfill.enabled";
    private static final int BATCH_SIZE = 500;

    /** Prevents re-entry if, for any reason, the runner is triggered more than once. */
    private final AtomicBoolean executed = new AtomicBoolean(false);

    private final AppSettingsService appSettingsService;
    private final KeywordMatchingService keywordMatchingService;
    private final MessageRepository messageRepository;

    public KeywordBackfillRunner(AppSettingsService appSettingsService,
                                 KeywordMatchingService keywordMatchingService,
                                 MessageRepository messageRepository) {
        this.appSettingsService = appSettingsService;
        this.keywordMatchingService = keywordMatchingService;
        this.messageRepository = messageRepository;
    }

    /**
     * Called by Spring after the application context is fully started.
     * Runs the backfill asynchronously on a bounded-elastic thread so it does
     * not block the startup thread.
     */
    @Override
    public void run(ApplicationArguments args) {
        if (!executed.compareAndSet(false, true)) {
            return;
        }
        log.info("[KeywordBackfill] Scheduling keyword backfill (async, boundedElastic, 10 s delay)");
        // Mono.delay emits on Schedulers.parallel(); publishOn(boundedElastic) placed AFTER it moves
        // the downstream Mono.fromRunnable(runBackfill) — which calls .block() in processBatch — onto
        // a bounded-elastic thread where blocking is legal. NOTE: subscribeOn would NOT achieve this,
        // because Mono.delay overrides the execution thread to its own scheduler regardless.
        Mono.delay(java.time.Duration.ofSeconds(10))
                .publishOn(Schedulers.boundedElastic())
                .then(Mono.fromRunnable(this::runBackfill))
                .subscribe(
                        v -> { /* completed inline */ },
                        error -> log.error("[KeywordBackfill] Backfill failed unexpectedly", error)
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
        if (!appSettingsService.getBoolean(SETTING_ENABLED, true)) {
            log.info("[KeywordBackfill] Disabled via app setting '{}'; skipping", SETTING_ENABLED);
            return;
        }
        if (keywordMatchingService.cacheSize() == 0) {
            // Keyword cache not yet populated — try to load it synchronously before backfilling.
            log.info("[KeywordBackfill] Keyword cache empty; triggering synchronous refresh before backfill");
            keywordMatchingService.refreshCache();
            // Give the reactive subscribe a moment to complete (refresh is async internally).
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            if (keywordMatchingService.cacheSize() == 0) {
                log.warn("[KeywordBackfill] Keyword cache still empty after refresh — backfill will skip all rows. " +
                        "Check tgscan.search_keywords table.");
            }
        }

        log.info("[KeywordBackfill] Starting backfill with {} keyword(s) in cache", keywordMatchingService.cacheSize());
        AtomicLong totalUpdated = new AtomicLong(0);
        AtomicLong totalProcessed = new AtomicLong(0);

        // Walk the table forward by id in BATCH_SIZE pages. afterId advances to the max id of
        // each full batch, so every row is visited at most once and the loop always terminates
        // (the table is finite and the cursor strictly increases). No-match rows are set to an
        // empty array and excluded by the IS NULL predicate, so they are never re-scanned.
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
                log.debug("[KeywordBackfill] Batch done: afterId={}, processed={}, updated={}", afterId, processed, updated);
            }
        }

        log.info("[KeywordBackfill] Backfill complete: {} row(s) processed, {} row(s) updated with matched keywords",
                totalProcessed.get(), totalUpdated.get());
    }

    /**
     * Processes one forward-paged batch of up to {@value #BATCH_SIZE} candidate rows whose id
     * is greater than {@code afterId}.
     *
     * @param afterId exclusive lower bound on id for this batch
     * @return {@code long[]{processedCount, updatedCount, maxIdSeen}} — {@code maxIdSeen} is
     *         {@code afterId} when the batch is empty
     */
    private long[] processBatch(long afterId) {
        AtomicLong processed = new AtomicLong(0);
        AtomicLong updated = new AtomicLong(0);
        AtomicLong maxId = new AtomicLong(afterId);

        messageRepository.findUnmatchedKeywordsBatch(afterId, BATCH_SIZE)
                .concatMap(entity -> {
                    processed.incrementAndGet();
                    if (entity.getId() != null) {
                        maxId.accumulateAndGet(entity.getId(), Math::max);
                    }
                    String text = entity.getContent() != null && !entity.getContent().isBlank()
                            ? entity.getContent()
                            : entity.getCaption();
                    String[] matched = keywordMatchingService.match(text);
                    return messageRepository.updateMatchedKeywords(entity.getId(), matched)
                            .doOnNext(rows -> {
                                if (rows > 0 && matched.length > 0) {
                                    updated.incrementAndGet();
                                }
                            })
                            .onErrorResume(ex -> {
                                log.warn("[KeywordBackfill] Failed to update id={}: {}", entity.getId(), ex.getMessage());
                                return Mono.just(0);
                            });
                })
                .then()
                .block();

        return new long[]{processed.get(), updated.get(), maxId.get()};
    }
}
