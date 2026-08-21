package com.example.telegramuserbot.scheduler;

import com.example.telegramuserbot.domain.Channel;
import com.example.telegramuserbot.repository.ChannelRepository;
import com.example.telegramuserbot.repository.MessageRepository;
import com.example.telegramuserbot.repository.TelegramAccountRepository;
import com.example.telegramuserbot.service.TelegramClientManager;
import com.example.telegramuserbot.service.config.AppSettingsService;
import com.example.telegramuserbot.service.persistence.MessagePersistenceService;
import com.example.telegramuserbot.telegram.TelegramClientFacade;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * F1.5 — Backfills recent message history from registered news channels into
 * {@code bot.messages} so the scoring/value pipeline has a real pool to rank.
 *
 * <p>The real-time harvest (PR #69) only captures messages that arrive AFTER
 * startup — a trickle at first.  This scheduler pulls the last
 * {@code news-backfill.depth-days} days of history for <em>one</em> not-yet-backfilled
 * news channel per run, giving the pipeline thousands of messages quickly.
 *
 * <p><strong>Conservative by design</strong> — one channel per run, small page sizes,
 * a delay between pages, flood-wait-guarded via the wrapped TDLib client.  A single
 * channel failure is logged and skipped; the channel is still marked backfilled so
 * the scheduler doesn't retry it on every run.
 *
 * <h2>Key choices</h2>
 * <ul>
 *   <li>Uses the <em>collector</em> account's TDLib client (news channels have no
 *       chat_config, so the sync-gated {@code initiateSync} path would skip them).
 *   <li>Persists via {@code MessagePersistenceService.forcePersistMessage} (un-gated).
 *       The call already handles duplicates via {@code DuplicateKeyException} internally.
 *   <li>Tracks progress via {@code tgscan.channels.backfilled_at} (changeset 068).
 * </ul>
 *
 * <h2>Startup gap-fill (F1.5b)</h2>
 * <p>On every startup, approximately {@code news-backfill.gap-fill-delay-ms} (default 5 min)
 * after the application is ready, a one-time sweep fetches messages posted by each
 * broadcast channel WHILE THE APP WAS DOWN — i.e. between the channel's last recorded
 * message date and now.  Only channels that have at least one already-recorded message
 * are processed (never-seen channels are covered by the normal backfill path above).
 * The gap is capped at 7 days to avoid massive pulls after a long outage.
 * Gate: {@code news-backfill.gap-fill-on-startup.enabled} (default {@code true}).
 *
 * <p>Enable/disable: {@code news-backfill.enabled} (default {@code true}).
 */
@Component
@ConditionalOnProperty(name = "news-backfill.enabled", havingValue = "true", matchIfMissing = true)
public final class NewsChannelBackfillScheduler {

    private static final Logger log = LoggerFactory.getLogger(NewsChannelBackfillScheduler.class);

    /** Maximum downtime gap to back-fill on startup (safety cap). */
    private static final Duration GAP_FILL_MAX_AGE = Duration.ofDays(7);

    /** Small delay between gap-fill processing of successive channels (flood-safe). */
    private static final Duration GAP_FILL_INTER_CHANNEL_DELAY = Duration.ofSeconds(3);

    /** TDLib GetChatHistory page size. Keep small to stay below flood-wait. */
    private static final int PAGE_SIZE = 25;

    /** Delay between fetching successive pages from a single channel. */
    private static final Duration INTER_PAGE_DELAY = Duration.ofSeconds(2);

    private final TelegramAccountRepository telegramAccountRepository;
    private final TelegramClientManager telegramClientManager;
    private final ChannelRepository channelRepository;
    private final MessageRepository messageRepository;
    private final MessagePersistenceService messagePersistenceService;
    private final AppSettingsService appSettingsService;

    @Value("${news-backfill.depth-days:30}")
    private int depthDays;

    @Value("${news-backfill.max-messages:500}")
    private int maxMessages;

    @Value("${news-backfill.gap-fill-delay-ms:300000}")
    private long gapFillDelayMs;

    /** Guards against overlapping runs of the periodic backfill. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public NewsChannelBackfillScheduler(
            TelegramAccountRepository telegramAccountRepository,
            TelegramClientManager telegramClientManager,
            ChannelRepository channelRepository,
            MessageRepository messageRepository,
            MessagePersistenceService messagePersistenceService,
            AppSettingsService appSettingsService) {
        this.telegramAccountRepository = telegramAccountRepository;
        this.telegramClientManager = telegramClientManager;
        this.channelRepository = channelRepository;
        this.messageRepository = messageRepository;
        this.messagePersistenceService = messagePersistenceService;
        this.appSettingsService = appSettingsService;
    }

    /**
     * Runs every {@code news-backfill.interval-ms} milliseconds (default 30 min) after an
     * initial delay of {@code news-backfill.initial-delay-ms} (default 10 min).
     * Each invocation processes exactly ONE news channel.
     */
    @Scheduled(
            fixedDelayString = "${news-backfill.interval-ms:1800000}",
            initialDelayString = "${news-backfill.initial-delay-ms:600000}"
    )
    public void runBackfillRound() {
        if (!running.compareAndSet(false, true)) {
            log.debug("[NewsBackfill] Previous run still in progress, skipping");
            return;
        }

        log.info("[NewsBackfill] Starting round (depth={}d, cap={} msgs)", depthDays, maxMessages);

        resolveCollectorClient()
                .flatMap(client -> channelRepository.findNextNewsChannelForBackfill()
                        .switchIfEmpty(Mono.defer(() -> {
                            log.info("[NewsBackfill] All joined news channels have been backfilled; nothing to do");
                            return Mono.empty();
                        }))
                        .flatMap(channel -> backfillChannel(channel, client)))
                .doFinally(signal -> running.set(false))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        count -> log.info("[NewsBackfill] Round complete, persisted {} messages", count),
                        error -> {
                            log.error("[NewsBackfill] Round failed unexpectedly", error);
                            running.set(false);
                        }
                );
    }

    // -------------------------------------------------------------------------
    // Startup gap-fill (F1.5b)
    // -------------------------------------------------------------------------

    /**
     * Triggered once per boot, approximately {@code news-backfill.gap-fill-delay-ms}
     * (default 5 min) after the application context is fully started.
     *
     * <p>For each broadcast channel that already has at least one recorded message, fetches
     * all messages posted SINCE the last recorded date (capped at 7 days back) so news
     * published during an outage is not permanently lost.
     *
     * <p>Gated at run time by {@code news-backfill.gap-fill-on-startup.enabled}
     * (checked via {@link AppSettingsService}, default {@code true}).
     * Inherits the class-level {@code @ConditionalOnProperty(news-backfill.enabled)} gate.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        Mono.delay(Duration.ofMillis(gapFillDelayMs))
                .then(Mono.fromCallable(() ->
                        appSettingsService.getBoolean("news-backfill.gap-fill-on-startup.enabled", true)))
                .flatMap(enabled -> {
                    if (!enabled) {
                        log.info("[GapFill] Startup gap-fill disabled via app_settings; skipping");
                        return Mono.<long[]>empty();
                    }
                    return runGapFill();
                })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        totals -> log.info("[GapFill] Startup gap-fill complete: {} channels checked, {} messages caught up",
                                totals[0], totals[1]),
                        error -> log.error("[GapFill] Startup gap-fill failed", error)
                );
    }

    /**
     * Core gap-fill pipeline: lists all joined broadcast channels, looks up the last
     * recorded message date for each, and calls {@link #fetchPageRecursively} with
     * {@code cutoff = lastSeen} (capped at 7 days back) to pull the downtime window.
     *
     * @return Mono emitting a two-element {@code long[]} with [channelsChecked, totalPersisted]
     */
    private Mono<long[]> runGapFill() {
        Instant now = Instant.now();
        Instant maxCutoffAge = now.minus(GAP_FILL_MAX_AGE);

        AtomicLong channelsChecked = new AtomicLong(0);
        AtomicLong totalPersisted = new AtomicLong(0);

        return resolveCollectorClient()
                .flatMap(client ->
                        channelRepository.findJoinedBroadcastChannels()
                                .concatMap(channel -> gapFillOneChannel(client, channel, now, maxCutoffAge, totalPersisted)
                                        .doOnTerminate(channelsChecked::incrementAndGet)
                                        .onErrorResume(ex -> {
                                            log.warn("[GapFill] chatId={} title='{}' failed: {}",
                                                    channel.getChatId(), channel.getTitle(), ex.getMessage());
                                            return Mono.just(0L);
                                        })
                                        .delayElement(GAP_FILL_INTER_CHANNEL_DELAY))
                                .reduce(0L, Long::sum)
                )
                .map(ignored -> new long[]{channelsChecked.get(), totalPersisted.get()})
                .defaultIfEmpty(new long[]{0L, 0L});
    }

    /**
     * Gap-fills a single channel: look up its last recorded message date, compute the
     * cutoff (capped at 7 days), and fetch the missing window.
     *
     * <p>If the channel has no recorded messages ({@code findMaxMessageDateByChatId} emits
     * empty), this method returns immediately — the normal backfill path handles
     * never-seen channels.
     *
     * @param client       collector TDLib client
     * @param channel      the broadcast channel to gap-fill
     * @param now          reference instant (computed once per sweep)
     * @param maxCutoffAge oldest allowed cutoff instant (now - 7 days)
     * @param totalPersisted global accumulator for logging
     * @return Mono emitting the count of messages persisted for this channel
     */
    private Mono<Long> gapFillOneChannel(TelegramClientFacade client,
                                         Channel channel,
                                         Instant now,
                                         Instant maxCutoffAge,
                                         AtomicLong totalPersisted) {
        long chatId = channel.getChatId();
        String title = channel.getTitle() != null ? channel.getTitle() : String.valueOf(chatId);

        return messageRepository.findLatestMessageByChatId(chatId)
                .flatMap(latest -> {
                    Instant lastSeen = latest.getDate();
                    if (lastSeen == null) {
                        return Mono.just(0L);
                    }
                    // Safety cap: never pull more than 7 days back
                    Instant cutoff = lastSeen.isBefore(maxCutoffAge) ? maxCutoffAge : lastSeen;

                    // If last message was very recent, there is nothing to catch up
                    if (!cutoff.isBefore(now)) {
                        log.debug("[GapFill] chatId={} '{}' up-to-date (lastSeen={}); skipping",
                                chatId, title, lastSeen);
                        return Mono.just(0L);
                    }

                    log.info("[GapFill] chatId={} '{}' catching up from {} (cap={}d)",
                            chatId, title, cutoff, GAP_FILL_MAX_AGE.toDays());

                    AtomicLong channelPersisted = new AtomicLong(0);

                    return fetchPageRecursively(client, chatId, 0L, cutoff, channelPersisted)
                            .doOnComplete(() -> {
                                long count = channelPersisted.get();
                                totalPersisted.addAndGet(count);
                                log.info("[GapFill] chatId={} '{}' caught up {} messages", chatId, title, count);
                            })
                            .onErrorResume(ex -> {
                                log.warn("[GapFill] chatId={} '{}' fetch error after {} msgs: {}",
                                        chatId, title, channelPersisted.get(), ex.getMessage());
                                totalPersisted.addAndGet(channelPersisted.get());
                                return Flux.empty();
                            })
                            .then(Mono.fromCallable(channelPersisted::get));
                });
        // If findMaxMessageDateByChatId emits empty: channel never harvested -> skip (flatMap does not fire)
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    /**
     * Resolves the collector account's active TDLib client.
     * Returns empty and logs a warning when no collector is registered or its
     * TDLib session is not yet ready.
     */
    private Mono<TelegramClientFacade> resolveCollectorClient() {
        return telegramAccountRepository.findCollector()
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("[NewsBackfill] No collector account registered; skipping round");
                    return Mono.empty();
                }))
                .flatMap(account -> {
                    String botId = account.getBotId();
                    TelegramClientFacade client = telegramClientManager.getClient(botId);
                    if (client == null) {
                        log.warn("[NewsBackfill] Collector botId={} has no active TDLib client; skipping round", botId);
                        return Mono.<TelegramClientFacade>empty();
                    }
                    log.debug("[NewsBackfill] Using collector botId={}", botId);
                    return Mono.just(client);
                });
    }

    /**
     * Pulls the recent history of one channel and force-persists each message.
     * On error: logs the failure, still marks the channel backfilled to avoid
     * hammering a broken channel on every subsequent run.
     *
     * @param channel the news channel to backfill
     * @param client  the collector's TDLib client facade
     * @return Mono of the total number of messages persisted
     */
    private Mono<Long> backfillChannel(Channel channel, TelegramClientFacade client) {
        long chatId = channel.getChatId();
        String title = channel.getTitle() != null ? channel.getTitle() : String.valueOf(chatId);
        Instant cutoff = Instant.now().minus(Duration.ofDays(depthDays));
        AtomicLong totalPersisted = new AtomicLong(0);

        log.info("[NewsBackfill] Backfilling channel '{}' (chatId={}, depth={}d, cap={})",
                title, chatId, depthDays, maxMessages);

        return fetchPageRecursively(client, chatId, 0L, cutoff, totalPersisted)
                .doOnComplete(() -> log.info("[NewsBackfill] Channel '{}' (chatId={}) done, persisted={}",
                        title, chatId, totalPersisted.get()))
                .onErrorResume(ex -> {
                    log.warn("[NewsBackfill] Channel '{}' (chatId={}) failed mid-backfill after {} messages: {}",
                            title, chatId, totalPersisted.get(), ex.getMessage());
                    return Flux.empty();
                })
                .then(channelRepository.markBackfilled(chatId)
                        .doOnNext(rows -> log.debug("[NewsBackfill] Marked chatId={} backfilled (rows={})", chatId, rows))
                        .thenReturn(totalPersisted.get()));
    }

    /**
     * Recursively fetches pages of history and force-persists each message.
     * Stops when the batch is empty, the cutoff date is passed, or the per-channel
     * message cap ({@code maxMessages}) is reached.
     *
     * @param client         collector TDLib client
     * @param chatId         channel chat ID
     * @param fromMsgId      fetch messages older than this ID (0 = start from newest)
     * @param cutoff         do not persist messages older than this instant
     * @param totalPersisted running counter of successfully persisted messages
     * @return Flux that emits the number of messages persisted in each batch
     */
    private Flux<Integer> fetchPageRecursively(TelegramClientFacade client,
                                               long chatId,
                                               long fromMsgId,
                                               Instant cutoff,
                                               AtomicLong totalPersisted) {
        if (totalPersisted.get() >= maxMessages) {
            log.info("[NewsBackfill] chatId={} reached cap of {} messages; stopping pagination",
                    chatId, maxMessages);
            return Flux.empty();
        }

        return fetchPage(client, chatId, fromMsgId)
                .delayElement(INTER_PAGE_DELAY)
                .flatMapMany(messages -> {
                    if (messages.messages == null || messages.messages.length == 0) {
                        log.debug("[NewsBackfill] chatId={} empty batch at fromMsgId={}; done", chatId, fromMsgId);
                        return Flux.empty();
                    }

                    long oldestMsgId = Arrays.stream(messages.messages)
                            .mapToLong(m -> m.id)
                            .min()
                            .orElse(0L);

                    // Check if the oldest message in the batch is already past the cutoff
                    boolean dateLimitReached = Arrays.stream(messages.messages)
                            .anyMatch(m -> Instant.ofEpochSecond(m.date).isBefore(cutoff));

                    // Persist messages that are within the date window and cap
                    Flux<Integer> persistFlux = Flux.fromArray(messages.messages)
                            .filter(m -> !Instant.ofEpochSecond(m.date).isBefore(cutoff))
                            .takeWhile(m -> totalPersisted.get() < maxMessages)
                            .concatMap(msg -> messagePersistenceService
                                    .forcePersistMessage(null, chatId, msg)
                                    .doOnNext(saved -> totalPersisted.incrementAndGet())
                                    .onErrorResume(ex -> {
                                        log.debug("[NewsBackfill] chatId={} msgId={} persist error (likely dup): {}",
                                                chatId, msg.id, ex.getMessage());
                                        return Mono.empty();
                                    })
                                    .map(saved -> 1)
                                    .defaultIfEmpty(0));

                    if (dateLimitReached || oldestMsgId == 0 || totalPersisted.get() >= maxMessages) {
                        return persistFlux;
                    }

                    return persistFlux.thenMany(
                            fetchPageRecursively(client, chatId, oldestMsgId, cutoff, totalPersisted));
                });
    }

    /**
     * Issues a single {@code GetChatHistory} request and returns the result as a Mono.
     *
     * @param client    the TDLib client to use
     * @param chatId    the channel's chat ID
     * @param fromMsgId fetch messages older than this message ID (0 = from the newest)
     * @return Mono of {@code TdApi.Messages}
     */
    private Mono<TdApi.Messages> fetchPage(TelegramClientFacade client, long chatId, long fromMsgId) {
        return Mono.<TdApi.Messages>create(sink -> {
            var request = new TdApi.GetChatHistory(chatId, fromMsgId, 0, PAGE_SIZE, false);
            client.send(request, result -> {
                if (result.isError()) {
                    TdApi.Error error = result.getError();
                    log.warn("[NewsBackfill] TDLib error for chatId={} fromMsgId={}: code={} msg={}",
                            chatId, fromMsgId, error.code, error.message);
                    sink.error(new RuntimeException(
                            "TDLib error " + error.code + ": " + error.message));
                } else {
                    sink.success(result.get());
                }
            });
        });
    }
}
