package com.example.telegramuserbot.service.embedding;

import com.example.telegramuserbot.domain.MessageEntity;
import com.example.telegramuserbot.repository.MessageRepository;
import com.example.telegramuserbot.service.config.AppSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Scheduled job that embeds news-eligible {@code bot.messages} rows into Qdrant.
 *
 * <p><b>What it does per tick:</b>
 * <ol>
 *   <li>Checks the runtime flag {@code news.embedding.enabled} (default: false).
 *   <li>Reads a bounded batch of rows with {@code embedded_at IS NULL}
 *       (query ordered by {@code content_simhash} so cluster siblings are adjacent).
 *   <li>For each row, checks an in-run simhash cache:
 *       <ul>
 *         <li>Cache hit (same simhash already embedded this tick) → reuse the cached
 *             vector; upsert this id as a separate point; mark {@code embedded_at}.
 *         <li>Cache miss → call {@link EmbeddingClient#embed} (blocking SDK on
 *             boundedElastic), upsert, cache the vector by simhash, mark embedded_at.
 *       </ul>
 *   <li>Fail-open per row: any embed or upsert failure leaves {@code embedded_at NULL}
 *       so the next tick retries.
 * </ol>
 *
 * <p><b>Natural backfill:</b> the {@code WHERE embedded_at IS NULL} predicate processes
 * both new rows (forward) and the ~40k existing rows (backfill) in the same loop.
 * No separate one-shot backfill job is needed.
 *
 * <p><b>Boot safety:</b> gated by {@code @ConditionalOnProperty(news.embedding.enabled)},
 * so the bean is NOT created in profiles without that property (smoke gate, default profile).
 * Within staging the bean is created, but the runtime flag read from {@code app_settings}
 * provides a second, zero-redeploy on/off switch.
 *
 * <p><b>Ordering guarantee (drift invariant):</b> {@code embedded_at} is written only
 * AFTER a successful {@link QdrantVectorStore#upsert} confirms the point is in Qdrant —
 * never before — so Postgres and Qdrant never drift (a partial write leaves the row
 * unembedded and retried on the next tick).
 */
@Service
@ConditionalOnProperty(name = "news.embedding.enabled", havingValue = "true", matchIfMissing = false)
public class NewsEmbeddingScheduledJob {

    private static final Logger log = LoggerFactory.getLogger(NewsEmbeddingScheduledJob.class);

    /** Fallback batch size when app_settings row is missing. */
    private static final int DEFAULT_BATCH_SIZE = 200;

    /** Max reactive concurrency: process rows concurrently but bounded to avoid flooding the TEI service. */
    private static final int CONCURRENCY = 4;

    private static final String SETTING_ENABLED    = "news.embedding.enabled";
    private static final String SETTING_BATCH_SIZE = "news.embedding.batch-size";

    private final MessageRepository  messageRepository;
    private final EmbeddingClient    embeddingClient;
    private final QdrantVectorStore  qdrantVectorStore;
    private final AppSettingsService appSettings;

    public NewsEmbeddingScheduledJob(
            MessageRepository  messageRepository,
            EmbeddingClient    embeddingClient,
            QdrantVectorStore  qdrantVectorStore,
            AppSettingsService appSettings) {
        this.messageRepository = messageRepository;
        this.embeddingClient   = embeddingClient;
        this.qdrantVectorStore = qdrantVectorStore;
        this.appSettings       = appSettings;
        log.info("[NewsEmbeddingJob] Initialized (news.embedding.enabled=true in Spring properties)");
    }

    /**
     * Embedding tick.
     *
     * <p>Fixed delay (not fixed rate) so ticks never overlap — a slow batch
     * finishes before the next one starts.  Initial delay matches the interval
     * so AppSettings has fully loaded its snapshot before the first run.
     */
    @Scheduled(
            fixedDelayString   = "${news.embedding.interval-ms:120000}",
            initialDelayString = "${news.embedding.interval-ms:120000}"
    )
    public void tick() {
        buildPipeline()
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        v  -> { /* outcomes logged inside */ },
                        err -> log.error("[NewsEmbeddingJob] Unhandled error in embedding tick", err)
                );
    }

    // -------------------------------------------------------------------------
    // Pipeline
    // -------------------------------------------------------------------------

    /**
     * Builds the full reactive embedding pipeline for one tick.
     * Package-private for testability.
     */
    Mono<Void> buildPipeline() {
        // Runtime flag — allows toggling without a restart
        if (!appSettings.getBoolean(SETTING_ENABLED, false)) {
            log.debug("[NewsEmbeddingJob] Skipping tick — {} is false in app_settings", SETTING_ENABLED);
            return Mono.empty();
        }

        int batchSize = appSettings.getInt(SETTING_BATCH_SIZE, DEFAULT_BATCH_SIZE);
        Instant tickStart = Instant.now();

        // In-run simhash → vector cache (lives only for this tick; ConcurrentHashMap because
        // flatMap(CONCURRENCY=4) means up to 4 boundedElastic threads can get/put concurrently)
        Map<String, float[]> simhashCache = new ConcurrentHashMap<>();
        AtomicLong embedded  = new AtomicLong();
        AtomicLong cacheHits = new AtomicLong();
        AtomicLong skipped   = new AtomicLong();

        log.debug("[NewsEmbeddingJob] Starting tick (batchSize={})", batchSize);

        return messageRepository.findNewsEligibleWithoutEmbedding(batchSize)
                .flatMap(msg -> embedOneRow(msg, simhashCache, embedded, cacheHits, skipped),
                        CONCURRENCY)
                .then()
                .doOnSuccess(v -> log.info(
                        "[NewsEmbeddingJob] Tick done in {} ms — embedded={}, cacheHits={}, skipped={}",
                        Duration.between(tickStart, Instant.now()).toMillis(),
                        embedded.get(), cacheHits.get(), skipped.get()));
    }

    // -------------------------------------------------------------------------
    // Per-row embedding
    // -------------------------------------------------------------------------

    /**
     * Per-row outcome values — used to increment exactly one counter per row.
     */
    private enum Outcome { EMBEDDED, UPSERT_FAIL, NO_VECTOR, ERROR }

    /**
     * Processes one message row: embed (or cache-hit), upsert into Qdrant, mark embedded_at.
     *
     * <p><b>Drift invariant:</b> {@code embedded_at} is written ONLY after
     * {@link QdrantVectorStore#upsert} returns {@code true} (confirmed HTTP success).
     * A fail-open upsert returns {@code false} rather than completing empty, so the
     * caller knows the row was NOT written and must not stamp {@code embedded_at}.
     *
     * <p><b>Counter accuracy:</b> the pipeline returns an explicit {@link Outcome} for
     * every row so each counter is incremented exactly once with no double-counting.
     * {@code switchIfEmpty} is NOT used because the inner chain always terminates with
     * a concrete value.
     *
     * <p>Fail-open: any unexpected error catches via {@code onErrorReturn(Outcome.ERROR)}
     * and leaves {@code embedded_at} NULL so the next tick retries.
     */
    private Mono<Void> embedOneRow(
            MessageEntity msg,
            Map<String, float[]> simhashCache,
            AtomicLong embedded,
            AtomicLong cacheHits,
            AtomicLong skipped) {

        Long id = msg.getId();
        if (id == null) {
            return Mono.empty();
        }

        String simhash = msg.getContentSimhash();
        float[] cachedVector = (simhash != null && !simhash.isBlank())
                ? simhashCache.get(simhash) : null;

        Mono<float[]> vectorMono;
        if (cachedVector != null) {
            // Sibling of an already-embedded story this tick: reuse the vector.
            cacheHits.incrementAndGet();
            vectorMono = Mono.just(cachedVector);
        } else {
            String text = buildInputText(msg);
            vectorMono = embeddingClient.embed(text)
                    .doOnNext(vec -> {
                        // Cache so any sibling in this same batch avoids another API call.
                        if (simhash != null && !simhash.isBlank()) {
                            simhashCache.put(simhash, vec);
                        }
                    });
        }

        return vectorMono
                // vector acquired — attempt Qdrant upsert; returns true=ok, false=failed
                .flatMap(vec -> qdrantVectorStore.upsert(id, vec)
                        .flatMap(ok -> {
                            if (ok) {
                                // Upsert confirmed in Qdrant — now safe to stamp embedded_at
                                return messageRepository.markEmbedded(id)
                                        .thenReturn(Outcome.EMBEDDED);
                            } else {
                                // Upsert failed (fail-open) — leave embedded_at NULL, retry next tick
                                log.warn("[NewsEmbeddingJob] Qdrant upsert returned false for id={} — will retry", id);
                                return Mono.just(Outcome.UPSERT_FAIL);
                            }
                        })
                )
                // EmbeddingClient returned empty (key absent, blank text, or embed error)
                .defaultIfEmpty(Outcome.NO_VECTOR)
                // Any unexpected error (e.g. markEmbedded DB error) — leave unembedded, retry
                .onErrorReturn(Outcome.ERROR)
                .doOnNext(outcome -> {
                    switch (outcome) {
                        case EMBEDDED    -> {
                            embedded.incrementAndGet();
                            log.debug("[NewsEmbeddingJob] Embedded id={} (simhash={})", id, simhash);
                        }
                        case UPSERT_FAIL -> {
                            skipped.incrementAndGet();
                        }
                        case NO_VECTOR   -> {
                            skipped.incrementAndGet();
                            log.debug("[NewsEmbeddingJob] No vector for id={} (embed returned empty)", id);
                        }
                        case ERROR       -> {
                            skipped.incrementAndGet();
                            log.warn("[NewsEmbeddingJob] Unexpected error for id={} — will retry next tick", id);
                        }
                    }
                })
                .then();
    }

    /**
     * Builds the text to embed for a message.
     * Concatenates content (or caption if content is absent).
     * Truncation is handled inside {@link EmbeddingClient}.
     */
    private static String buildInputText(MessageEntity msg) {
        String content = msg.getContent();
        if (content != null && !content.isBlank()) {
            return content;
        }
        String caption = msg.getCaption();
        return caption != null ? caption : "";
    }
}
