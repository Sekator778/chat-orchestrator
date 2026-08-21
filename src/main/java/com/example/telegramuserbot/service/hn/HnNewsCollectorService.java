package com.example.telegramuserbot.service.hn;

import com.example.telegramuserbot.domain.MessageEntity;
import com.example.telegramuserbot.domain.MessageType;
import com.example.telegramuserbot.repository.MessageRepository;
import com.example.telegramuserbot.service.config.AppSettingsService;
import com.example.telegramuserbot.service.persistence.MessageEntityHydrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Scheduled harvester that pulls Hacker News top stories via the Firebase API
 * and writes each story into {@code bot.messages} as a synthetic-channel news row
 * ({@link MessageType#HN_NEWS}).
 *
 * <h2>Normalization contract (Track B3 template)</h2>
 * HN stories are normalized to the same shape as web/TG news:
 * <ul>
 *   <li>{@code chat_id} = {@value HN_SYNTHETIC_CHANNEL_ID} — the HN platform synthetic channel
 *       (reserved band {@code -8_000_000_001}, distinct from TG and web bands).</li>
 *   <li>{@code message_id} = the HN item id (stable integer — no hashing needed, guarantees
 *       idempotent re-harvest via the {@code uq_messages_chat_message} unique constraint).</li>
 *   <li>{@code content} = title + optional URL on a second line (URL inclusion pushes short
 *       titles past the 40-char candidate-query gate).</li>
 *   <li>{@code views} = HN score (upvotes) — real engagement signal for the ranking brain.</li>
 *   <li>{@code forwards} = HN comment count — real engagement signal for the ranking brain.</li>
 *   <li>{@code date} = story submission time.</li>
 * </ul>
 *
 * <h2>Engagement mapping rationale</h2>
 * Unlike web RSS items (which have no TG engagement signal), HN stories carry genuine audience
 * signals: score = upvotes (proxy for views/reach), descendants = comments (proxy for forwards/
 * discussion). Mapping these directly into {@code views}/{@code forwards} makes
 * {@code fn_recompute_importance}'s authority/quality terms compute real values, so the ranking
 * brain orders HN stories by actual HN engagement — not a flat trust constant.
 *
 * <h2>Dual-flag gate</h2>
 * <ol>
 *   <li>{@code @ConditionalOnProperty(name="hn-collector.enabled", matchIfMissing=false)} —
 *       the bean is only registered when the Spring property is {@code true} (kill-switch; ships
 *       absent = false, bean never created, no network calls at boot).</li>
 *   <li>Inside each tick, {@code app_settings("hn-collector.enabled")} is re-read from the DB
 *       snapshot so the operator can toggle harvesting at runtime without a restart.</li>
 * </ol>
 *
 * <h2>Idempotency / dedup</h2>
 * {@code message_id} = HN item id (stable). Re-harvesting the same story yields the same
 * {@code (chat_id, message_id)} pair; the {@code uq_messages_chat_message} constraint fires a
 * {@link DuplicateKeyException}, caught per-item and logged at DEBUG (idempotent re-harvest).
 */
@Service
@ConditionalOnProperty(name = "hn-collector.enabled", havingValue = "true", matchIfMissing = false)
public class HnNewsCollectorService {

    private static final Logger log = LoggerFactory.getLogger(HnNewsCollectorService.class);

    /**
     * Synthetic {@code chat_id} for HN stories in {@code bot.messages}.
     *
     * <p>Band {@code -8_000_000_001} is distinct from:
     * <ul>
     *   <li>Telegram basic groups ({@code ~-5_000_000_000})</li>
     *   <li>Web RSS outlets ({@code -9_000_000_001..-9_999_999_999})</li>
     * </ul>
     * A matching {@code tgscan.channels} row is seeded by changeset cs079.
     */
    static final long HN_SYNTHETIC_CHANNEL_ID = -8_000_000_001L;

    private final HnClient hnClient;
    private final MessageEntityHydrator messageEntityHydrator;
    private final MessageRepository messageRepository;
    private final AppSettingsService appSettings;

    public HnNewsCollectorService(
            HnClient hnClient,
            MessageEntityHydrator messageEntityHydrator,
            MessageRepository messageRepository,
            AppSettingsService appSettings) {
        this.hnClient = hnClient;
        this.messageEntityHydrator = messageEntityHydrator;
        this.messageRepository = messageRepository;
        this.appSettings = appSettings;
    }

    /**
     * Harvest tick. Default cadence: 10 minutes.
     *
     * <p>{@code hn-collector.interval-ms} (default 600 000 ms) controls the gap.
     * Initial delay matches the interval — the first run happens after full startup,
     * which prevents any blocking network call during the smoke-gate boot sequence.
     */
    @Scheduled(
            fixedDelayString   = "${hn-collector.interval-ms:600000}",
            initialDelayString = "${hn-collector.interval-ms:600000}"
    )
    public void tick() {
        buildPipeline()
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        v -> { /* outcomes logged inside */ },
                        err -> log.error("[HnCollector] Unhandled error in harvest tick", err)
                );
    }

    // -----------------------------------------------------------------------
    // Pipeline
    // -----------------------------------------------------------------------

    /**
     * Builds the full reactive harvest pipeline for one tick.
     *
     * <p>Step 1: runtime flag check (allows toggling without restart).
     * Step 2: read {@code max-stories} knob.
     * Step 3: fetch top-story ids, resolve each to a story detail, persist.
     */
    Mono<Void> buildPipeline() {
        if (!appSettings.getBoolean("hn-collector.enabled", false)) {
            log.debug("[HnCollector] Skipping tick — hn-collector.enabled is false in app_settings");
            return Mono.empty();
        }

        int maxStories = appSettings.getInt("hn-collector.max-stories", 30);

        AtomicLong inserted = new AtomicLong();
        AtomicLong skipped  = new AtomicLong();

        return hnClient.fetchTopStories(maxStories)
                .concatMap(story -> persistStory(story, inserted, skipped)
                        .onErrorResume(err -> {
                            log.warn("[HnCollector] Story id={} failed entirely: {}", story.id(), err.toString());
                            skipped.incrementAndGet();
                            return Mono.empty();
                        }))
                .then()
                .doOnSuccess(v -> log.info(
                        "[HnCollector] Tick complete — inserted={}, skipped={}",
                        inserted.get(), skipped.get()));
    }

    // -----------------------------------------------------------------------
    // Per-story persistence
    // -----------------------------------------------------------------------

    private Mono<Void> persistStory(HnStory story, AtomicLong inserted, AtomicLong skipped) {
        // Title is the primary content — skip if blank.
        String title = story.title() != null ? story.title().trim() : "";
        if (title.isBlank()) {
            log.debug("[HnCollector] Skipping story id={} — blank title", story.id());
            skipped.incrementAndGet();
            return Mono.empty();
        }

        // Build content: title + URL on a second line (pushes short titles past
        // the 40-char candidate-query gate; load-bearing, not decorative).
        String content;
        if (story.url() != null && !story.url().isBlank()) {
            content = title + "\n" + story.url().trim();
        } else {
            content = title;
        }

        // Build the MessageEntity.
        MessageEntity entity = new MessageEntity();
        entity.setChatId(HN_SYNTHETIC_CHANNEL_ID);
        // HN item id is a stable long — used directly as message_id for dedup.
        entity.setTelegramMessageId(story.id());
        entity.setOutgoing(false);
        entity.setDate(story.time());
        entity.setContent(content);
        entity.setMessageType(MessageType.HN_NEWS);
        // Map HN engagement to ranking signals so the brain ranks by real audience:
        //   views    = HN score (upvotes)   — proxy for reach / interest
        //   forwards = HN descendants (comments) — proxy for discussion depth
        entity.setViews((long) story.score());
        entity.setForwards((long) story.descendants());

        // Populate content-derived fields (content_hash, content_simhash, matched_keywords, geo).
        messageEntityHydrator.applyContentDerived(entity, content);

        return messageRepository.save(entity)
                .doOnSuccess(saved -> {
                    log.debug("[HnCollector] Inserted story id={} title='{}'", story.id(), title);
                    inserted.incrementAndGet();
                })
                .then()
                .onErrorResume(DuplicateKeyException.class, ex -> {
                    log.debug("[HnCollector] Dup — story already stored (id={})", story.id());
                    skipped.incrementAndGet();
                    return Mono.empty();
                })
                .onErrorResume(ex -> {
                    log.warn("[HnCollector] Failed to persist story id={}: {}", story.id(), ex.toString());
                    skipped.incrementAndGet();
                    return Mono.empty();
                });
    }
}
