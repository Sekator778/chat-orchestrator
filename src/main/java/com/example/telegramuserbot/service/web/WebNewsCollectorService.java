package com.example.telegramuserbot.service.web;

import com.example.telegramuserbot.domain.MessageEntity;
import com.example.telegramuserbot.domain.MessageType;
import com.example.telegramuserbot.domain.WebSource;
import com.example.telegramuserbot.repository.MessageRepository;
import com.example.telegramuserbot.repository.WebSourceRepository;
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
 * Scheduled harvester that pulls configured RSS/Atom feeds and writes each article
 * into {@code bot.messages} as a synthetic-channel news row ({@link MessageType#WEB_NEWS}).
 *
 * <h2>Dual-flag gate</h2>
 * <ol>
 *   <li>{@code @ConditionalOnProperty(name="web-collector.enabled", matchIfMissing=false)}
 *       — the bean is only created when the Spring property {@code web-collector.enabled=true}
 *       is explicitly set (deploy kill-switch; ships absent = false, bean never registered).</li>
 *   <li>Inside each tick, {@code app_settings("web-collector.enabled")} is re-read from the DB
 *       snapshot so the operator can toggle harvesting at runtime without a restart.</li>
 * </ol>
 *
 * <h2>Idempotency / dedup</h2>
 * The synthetic {@code message_id} is derived from the article URL:
 * {@code Math.abs((long) url.hashCode()) & Long.MAX_VALUE}. This is deterministic, so
 * re-harvesting the same article yields the same {@code (chat_id, message_id)} pair and
 * the {@code uq_messages_chat_message} unique constraint fires a {@link DuplicateKeyException},
 * which is caught per-item and logged at DEBUG level (idempotent re-harvest).
 *
 * <h2>Additive guarantee</h2>
 * Only INSERTs into {@code bot.messages} under negative synthetic chat_ids from
 * {@code bot.web_sources}. Never modifies any Telegram-sourced row.
 *
 * <h2>Reactive safety</h2>
 * The {@code @Scheduled} callback fires on Spring's task-scheduler thread.
 * The pipeline is subscribed via {@code Schedulers.boundedElastic()} — no {@code .block()}.
 * Feeds are processed sequentially ({@code concatMap}) to avoid burst pressure on feeds.
 */
@Service
@ConditionalOnProperty(name = "web-collector.enabled", havingValue = "true", matchIfMissing = false)
public class WebNewsCollectorService {

    private static final Logger log = LoggerFactory.getLogger(WebNewsCollectorService.class);

    /** Minimum content length — mirrors the candidate query gate ({@code length(content) > 40}). */
    private static final int MIN_CONTENT_LENGTH = 40;

    private final WebSourceRepository webSourceRepository;
    private final WebFeedClient webFeedClient;
    private final MessageEntityHydrator messageEntityHydrator;
    private final MessageRepository messageRepository;
    private final AppSettingsService appSettings;

    public WebNewsCollectorService(
            WebSourceRepository webSourceRepository,
            WebFeedClient webFeedClient,
            MessageEntityHydrator messageEntityHydrator,
            MessageRepository messageRepository,
            AppSettingsService appSettings) {
        this.webSourceRepository = webSourceRepository;
        this.webFeedClient = webFeedClient;
        this.messageEntityHydrator = messageEntityHydrator;
        this.messageRepository = messageRepository;
        this.appSettings = appSettings;
    }

    /**
     * Harvest tick. Default cadence: 30 minutes.
     *
     * <p>The fixed-delay property {@code web-collector.harvest-interval-ms} (default 1 800 000 ms)
     * controls the inter-tick gap. Initial delay matches the interval so the first run happens
     * after the application has fully started and loaded its settings snapshot.
     */
    @Scheduled(
            fixedDelayString  = "${web-collector.harvest-interval-ms:1800000}",
            initialDelayString = "${web-collector.harvest-interval-ms:1800000}"
    )
    public void tick() {
        buildPipeline()
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        v -> { /* outcomes logged inside */ },
                        err -> log.error("[WebCollector] Unhandled error in harvest tick", err)
                );
    }

    // -----------------------------------------------------------------------
    // Pipeline
    // -----------------------------------------------------------------------

    /**
     * Builds the full reactive harvest pipeline for one tick.
     *
     * <p>Step 1: runtime flag check (allows toggling without restart).
     * Step 2: read {@code max-items-per-feed} knob.
     * Step 3: for each enabled source, fetch + persist.
     */
    Mono<Void> buildPipeline() {
        // Runtime flag — read from the DB snapshot on every tick.
        if (!appSettings.getBoolean("web-collector.enabled", false)) {
            log.debug("[WebCollector] Skipping tick — web-collector.enabled is false in app_settings");
            return Mono.empty();
        }

        int maxItems = appSettings.getInt("web-collector.max-items-per-feed", 20);

        // Counters for the per-tick summary log.
        AtomicLong feedsProcessed = new AtomicLong();
        AtomicLong itemsInserted  = new AtomicLong();
        AtomicLong itemsSkipped   = new AtomicLong();

        return webSourceRepository.findByEnabledTrue()
                .concatMap(source -> harvestSource(source, maxItems, itemsInserted, itemsSkipped)
                        .doOnSuccess(v -> feedsProcessed.incrementAndGet())
                        .onErrorResume(err -> {
                            log.warn("[WebCollector] Feed '{}' failed entirely: {}",
                                    source.getFeedUrl(), err.toString());
                            feedsProcessed.incrementAndGet();
                            return Mono.empty();
                        }))
                .then()
                .doOnSuccess(v -> log.info(
                        "[WebCollector] Tick complete — feeds={}, inserted={}, skipped={}",
                        feedsProcessed.get(), itemsInserted.get(), itemsSkipped.get()));
    }

    // -----------------------------------------------------------------------
    // Per-source harvest
    // -----------------------------------------------------------------------

    private Mono<Void> harvestSource(
            WebSource source,
            int maxItems,
            AtomicLong itemsInserted,
            AtomicLong itemsSkipped) {

        log.debug("[WebCollector] Harvesting '{}' (channel={}) from {}",
                source.getOutletName(), source.getSyntheticChannelId(), source.getFeedUrl());

        return webFeedClient.fetch(source.getFeedUrl())
                .take(maxItems)
                .concatMap(item -> persistItem(source, item, itemsInserted, itemsSkipped))
                .then();
    }

    // -----------------------------------------------------------------------
    // Per-item persistence
    // -----------------------------------------------------------------------

    private Mono<Void> persistItem(
            WebSource source,
            WebFeedItem item,
            AtomicLong itemsInserted,
            AtomicLong itemsSkipped) {

        // Require a URL for stable synthetic message_id derivation.
        if (item.link() == null || item.link().isBlank()) {
            log.debug("[WebCollector] Skipping item with null/blank URL from '{}'",
                    source.getOutletName());
            itemsSkipped.incrementAndGet();
            return Mono.empty();
        }

        // Build content: title + summary, fallback to title alone.
        String title   = item.title()   != null ? item.title().trim()   : "";
        String summary = item.summary() != null ? item.summary().trim() : "";

        String content;
        if (!summary.isBlank()) {
            content = title.isBlank() ? summary : title + "\n\n" + summary;
        } else {
            content = title;
        }
        content = content.trim();

        if (content.isBlank() || content.length() <= MIN_CONTENT_LENGTH) {
            log.debug("[WebCollector] Skipping short/blank item '{}' (len={}) from '{}'",
                    item.link(), content.length(), source.getOutletName());
            itemsSkipped.incrementAndGet();
            return Mono.empty();
        }

        // Derive a stable positive synthetic message_id from the article URL.
        // Using (long) cast before Math.abs avoids the Integer.MIN_VALUE sign bug.
        long syntheticMsgId = Math.abs((long) item.link().hashCode()) & Long.MAX_VALUE;

        // Build the MessageEntity.
        MessageEntity entity = new MessageEntity();
        entity.setChatId(source.getSyntheticChannelId());
        entity.setTelegramMessageId(syntheticMsgId);   // also sets message_id via the setter
        entity.setOutgoing(false);
        entity.setDate(item.publishedAt());
        entity.setContent(content);
        entity.setMessageType(MessageType.WEB_NEWS);
        // views / forwards left NULL — web articles have no TG engagement signal.

        // Populate content-derived fields (hash, simhash, keywords, geo) via the shared helper.
        messageEntityHydrator.applyContentDerived(entity, content);

        final String finalContent = content;
        return messageRepository.save(entity)
                .doOnSuccess(saved -> {
                    log.debug("[WebCollector] Inserted msg id={} (url='{}') for outlet '{}'",
                            saved.getId(), item.link(), source.getOutletName());
                    itemsInserted.incrementAndGet();
                })
                .then()
                .onErrorResume(DuplicateKeyException.class, ex -> {
                    log.debug("[WebCollector] Dup — article already stored (url='{}', syntheticMsgId={})",
                            item.link(), syntheticMsgId);
                    itemsSkipped.incrementAndGet();
                    return Mono.empty();
                })
                .onErrorResume(ex -> {
                    log.warn("[WebCollector] Failed to persist item '{}' from '{}': {}",
                            item.link(), source.getOutletName(), ex.toString());
                    itemsSkipped.incrementAndGet();
                    return Mono.empty();
                });
    }
}
