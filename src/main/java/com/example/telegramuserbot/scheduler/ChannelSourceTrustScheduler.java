package com.example.telegramuserbot.scheduler;

import com.example.telegramuserbot.domain.Channel;
import com.example.telegramuserbot.repository.ChannelRepository;
import com.example.telegramuserbot.repository.TelegramAccountRepository;
import com.example.telegramuserbot.service.TelegramClientManager;
import com.example.telegramuserbot.telegram.TelegramClientFacade;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Periodic sweep that populates {@code tgscan.channels.subscribers} for broadcast channels
 * ({@code is_channel = true}) whose subscriber count is still {@code NULL}.
 *
 * <p>Subscriber count is the primary source-value signal used by
 * {@code MessageRepository.findPrimaryMessagesForDigest} (which ranks by
 * {@code importance × ln(greatest(subscribers, 2))}). Without it the news-ranking signal
 * is dead for every channel registered by the collector's live harvest.
 *
 * <p>Strategy per channel:
 * <ol>
 *   <li>Call TDLib {@code GetChat} using the collector account.</li>
 *   <li>Extract {@code supergroupId} from {@code ChatTypeSupergroup}.</li>
 *   <li>Call {@code GetSupergroupFullInfo(supergroupId)} → {@code memberCount}.</li>
 *   <li>Persist via {@code ChannelRepository.updateSubscribers}.</li>
 * </ol>
 *
 * <p>Failures for individual channels are logged and skipped — they will be retried on
 * the next sweep (the row stays {@code subscribers IS NULL}).  The sweep is idempotent.
 */
@Component
@ConditionalOnProperty(name = "source-trust.enabled", havingValue = "true", matchIfMissing = true)
public final class ChannelSourceTrustScheduler {

    private static final Logger log = LoggerFactory.getLogger(ChannelSourceTrustScheduler.class);

    private final ChannelRepository channelRepository;
    private final TelegramAccountRepository telegramAccountRepository;
    private final TelegramClientManager telegramClientManager;

    /** Guards against concurrent overlapping sweeps. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public ChannelSourceTrustScheduler(ChannelRepository channelRepository,
                                       TelegramAccountRepository telegramAccountRepository,
                                       TelegramClientManager telegramClientManager) {
        this.channelRepository = channelRepository;
        this.telegramAccountRepository = telegramAccountRepository;
        this.telegramClientManager = telegramClientManager;
    }

    /**
     * Runs the subscriber-count sweep every 6 hours (configurable).
     * Initial delay of 5 minutes lets TDLib clients finish authorizing at startup.
     */
    @Scheduled(
            initialDelayString = "${source-trust.initial-delay-ms:300000}",
            fixedRateString = "${source-trust.rate-ms:21600000}"
    )
    public void sweepSubscriberCounts() {
        if (!running.compareAndSet(false, true)) {
            log.debug("Source-trust sweep already running, skipping");
            return;
        }

        log.info("Source-trust sweep: starting subscriber-count population for broadcast channels");

        resolveCollectorClient()
                .flatMap(client -> channelRepository.findBroadcastChannelsWithoutSubscribers()
                        .concatMap(channel -> fetchAndPersistSubscribers(channel, client)
                                .onErrorResume(ex -> {
                                    log.warn("Source-trust sweep: failed for chatId={} title={}: {}",
                                            channel.getChatId(), channel.getTitle(), ex.getMessage());
                                    return Mono.just(0);
                                }))
                        .reduce(0, Integer::sum))
                .doFinally(signal -> running.set(false))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        total -> log.info("Source-trust sweep: completed, updated {} channels", total),
                        error -> {
                            log.error("Source-trust sweep: sweep failed", error);
                            running.set(false);
                        }
                );
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    /**
     * Resolves the collector account's TDLib client.
     * Returns empty (and logs a warning) if no collector is registered or its
     * TDLib session is not yet initialized.
     */
    private Mono<TelegramClientFacade> resolveCollectorClient() {
        return telegramAccountRepository.findCollector()
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("Source-trust sweep: no collector account found in bot.telegram_accounts; skipping sweep");
                    return Mono.empty();
                }))
                .flatMap(account -> {
                    String botId = account.getBotId();
                    TelegramClientFacade client = telegramClientManager.getClient(botId);
                    if (client == null) {
                        log.warn("Source-trust sweep: collector botId={} has no active TDLib client; skipping sweep", botId);
                        return Mono.empty();
                    }
                    log.debug("Source-trust sweep: using collector botId={}", botId);
                    return Mono.just(client);
                });
    }

    /**
     * For a single channel: fetches chat info → extracts supergroupId → fetches full info →
     * reads memberCount → persists to DB.
     *
     * @return Mono emitting 1 if the row was updated, 0 otherwise
     */
    private Mono<Integer> fetchAndPersistSubscribers(Channel channel, TelegramClientFacade client) {
        long chatId = channel.getChatId();

        return fetchChat(chatId, client)
                .flatMap(chat -> {
                    if (!(chat.type instanceof TdApi.ChatTypeSupergroup supergroup)) {
                        log.debug("Source-trust sweep: chatId={} is not a supergroup, skipping", chatId);
                        return Mono.just(0);
                    }
                    return fetchSupergroupFullInfo(supergroup.supergroupId, client)
                            .flatMap(info -> {
                                long memberCount = info.memberCount;
                                log.info("Source-trust sweep: chatId={} title='{}' memberCount={}",
                                        chatId, channel.getTitle(), memberCount);
                                return channelRepository.updateSubscribers(chatId, memberCount)
                                        .defaultIfEmpty(0);
                            });
                });
    }

    private Mono<TdApi.Chat> fetchChat(long chatId, TelegramClientFacade client) {
        return Mono.fromFuture(() -> client.send(new TdApi.GetChat(chatId)))
                .cast(TdApi.Chat.class);
    }

    private Mono<TdApi.SupergroupFullInfo> fetchSupergroupFullInfo(long supergroupId,
                                                                    TelegramClientFacade client) {
        return Mono.fromFuture(() -> client.send(new TdApi.GetSupergroupFullInfo(supergroupId)))
                .cast(TdApi.SupergroupFullInfo.class);
    }
}
