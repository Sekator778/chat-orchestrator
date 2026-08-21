package com.example.telegramuserbot.scheduler;

import com.example.telegramuserbot.repository.ChannelCandidateRepository;
import com.example.telegramuserbot.repository.ChannelRepository;
import com.example.telegramuserbot.repository.SearchKeywordRepository;
import com.example.telegramuserbot.repository.TelegramAccountRepository;
import com.example.telegramuserbot.service.TelegramClientManager;
import com.example.telegramuserbot.telegram.TelegramClientFacade;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Periodically searches Telegram for channels matching finance/economics/crypto keywords
 * and records previously-unknown channels as candidates in {@code tgscan.channel_candidates}.
 *
 * <p>This is <em>F0a — keyword-driven discovery</em>.  It only RECORDS candidates;
 * it does NOT join, mute, or interact with any channel in any way.
 *
 * <p>Flow for each enabled keyword:
 * <ol>
 *   <li>Call TDLib {@code SearchPublicChats(keyword)} using the collector account's client.</li>
 *   <li>For every returned chat-id: skip if already in {@code tgscan.channels}.</li>
 *   <li>Idempotently insert new chat-ids into {@code tgscan.channel_candidates}.</li>
 * </ol>
 *
 * <p>Per-keyword errors are logged and skipped — they do not abort the entire run.
 */
@Component
@ConditionalOnProperty(name = "channel-discovery.search.enabled", havingValue = "true", matchIfMissing = true)
public final class ChannelDiscoverySearchScheduler {

    private static final Logger log = LoggerFactory.getLogger(ChannelDiscoverySearchScheduler.class);

    private final SearchKeywordRepository searchKeywordRepository;
    private final ChannelCandidateRepository channelCandidateRepository;
    private final ChannelRepository channelRepository;
    private final TelegramAccountRepository telegramAccountRepository;
    private final TelegramClientManager telegramClientManager;

    /** Guards against overlapping concurrent runs. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public ChannelDiscoverySearchScheduler(SearchKeywordRepository searchKeywordRepository,
                                           ChannelCandidateRepository channelCandidateRepository,
                                           ChannelRepository channelRepository,
                                           TelegramAccountRepository telegramAccountRepository,
                                           TelegramClientManager telegramClientManager) {
        this.searchKeywordRepository = searchKeywordRepository;
        this.channelCandidateRepository = channelCandidateRepository;
        this.channelRepository = channelRepository;
        this.telegramAccountRepository = telegramAccountRepository;
        this.telegramClientManager = telegramClientManager;
    }

    /**
     * Runs the keyword discovery sweep once a day (default).
     * First run ~5 minutes after startup to let TDLib clients finish authorizing.
     */
    @Scheduled(
            fixedDelayString = "${channel-discovery.search.interval-ms:86400000}",
            initialDelayString = "${channel-discovery.search.initial-delay-ms:300000}"
    )
    public void runDiscoverySweep() {
        if (!running.compareAndSet(false, true)) {
            log.debug("Channel discovery sweep already running, skipping");
            return;
        }

        log.info("Channel discovery sweep: starting keyword-driven search");

        resolveCollectorClient()
                .flatMap(client -> searchKeywordRepository.findAllEnabled()
                        .concatMap(keyword -> searchByKeyword(keyword.getKeyword(), client)
                                .onErrorResume(ex -> {
                                    log.warn("Channel discovery sweep: keyword='{}' failed: {}",
                                            keyword.getKeyword(), ex.getMessage());
                                    return Mono.just(0);
                                }))
                        .reduce(0, Integer::sum))
                .doFinally(signal -> running.set(false))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        total -> log.info("Channel discovery sweep: completed, {} new candidates recorded", total),
                        error -> {
                            log.error("Channel discovery sweep: failed", error);
                            running.set(false);
                        }
                );
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    /**
     * Resolves the collector account's active TDLib client.
     * Returns empty (and logs a warning) when no collector is registered or its
     * TDLib session is not yet initialized.
     */
    private Mono<TelegramClientFacade> resolveCollectorClient() {
        return telegramAccountRepository.findCollector()
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("Channel discovery sweep: no collector account found in bot.telegram_accounts; skipping");
                    return Mono.empty();
                }))
                .flatMap(account -> {
                    String botId = account.getBotId();
                    TelegramClientFacade client = telegramClientManager.getClient(botId);
                    if (client == null) {
                        log.warn("Channel discovery sweep: collector botId={} has no active TDLib client; skipping", botId);
                        return Mono.<TelegramClientFacade>empty();
                    }
                    log.debug("Channel discovery sweep: using collector botId={}", botId);
                    return Mono.just(client);
                });
    }

    /**
     * Searches Telegram for the given keyword, filters out already-tracked channels,
     * and inserts the remaining chat-ids as candidates.
     *
     * @param keyword the search term
     * @param client  the collector's TDLib client
     * @return Mono emitting the count of newly inserted candidates
     */
    private Mono<Integer> searchByKeyword(String keyword, TelegramClientFacade client) {
        log.debug("Channel discovery sweep: searching for keyword='{}'", keyword);

        return Mono.fromFuture(() -> client.send(new TdApi.SearchPublicChats(keyword)))
                .cast(TdApi.Chats.class)
                .flatMap(chats -> {
                    if (chats.chatIds == null || chats.chatIds.length == 0) {
                        log.debug("Channel discovery sweep: keyword='{}' returned 0 results", keyword);
                        return Mono.just(0);
                    }
                    log.debug("Channel discovery sweep: keyword='{}' returned {} chat(s)", keyword, chats.chatIds.length);
                    return Flux.fromArray(longBoxed(chats.chatIds))
                            .concatMap(chatId -> isAlreadyTracked(chatId)
                                    .flatMap(tracked -> {
                                        if (tracked) {
                                            log.debug("Channel discovery sweep: chatId={} already in tgscan.channels, skipping", chatId);
                                            return Mono.just(0);
                                        }
                                        return channelCandidateRepository
                                                .insertIfAbsent(String.valueOf(chatId), keyword)
                                                .doOnNext(inserted -> {
                                                    if (inserted > 0) {
                                                        log.info("Channel discovery sweep: recorded candidate chatId={} keyword='{}'",
                                                                chatId, keyword);
                                                    } else {
                                                        log.debug("Channel discovery sweep: chatId={} already a candidate, skipping", chatId);
                                                    }
                                                })
                                                .defaultIfEmpty(0);
                                    }))
                            .reduce(0, Integer::sum);
                });
    }

    /**
     * Returns true if the given chat-id is already present in {@code tgscan.channels}.
     */
    private Mono<Boolean> isAlreadyTracked(long chatId) {
        return channelRepository.findByChatId(chatId)
                .map(ch -> true)
                .defaultIfEmpty(false);
    }

    /** Converts a primitive {@code long[]} to a boxed {@code Long[]} for Flux.fromArray. */
    private static Long[] longBoxed(long[] primitives) {
        Long[] boxed = new Long[primitives.length];
        for (int i = 0; i < primitives.length; i++) {
            boxed[i] = primitives[i];
        }
        return boxed;
    }
}
