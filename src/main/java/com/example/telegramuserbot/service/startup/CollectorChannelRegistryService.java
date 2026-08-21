package com.example.telegramuserbot.service.startup;

import com.example.telegramuserbot.repository.ChannelRepository;
import com.example.telegramuserbot.repository.TelegramAccountRepository;
import com.example.telegramuserbot.service.config.AppSettingsService;
import com.example.telegramuserbot.telegram.TelegramClientFacade;
import com.example.telegramuserbot.telegram.TelegramClientLifecycleListener;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * On collector-account client-ready: enumerate ALL broadcast channels the collector
 * is a member of (via {@link TdApi.GetChats} + {@link TdApi.GetChat}) and register
 * each one in {@code tgscan.channels} via
 * {@link ChannelRepository#upsertBroadcastChannel(Long, String)}.
 *
 * <p>REGISTRY-ONLY: does NOT create chat_configs, persona_chat_bindings, rate_limits,
 * triggers, or any reply configuration. Registering a channel here must never cause
 * any persona to start replying in it.
 *
 * <p>Gated behind the {@code collector.channel-registry.enabled} app setting
 * (default {@code false}}); the operator enables it via {@code bot.app_settings}
 * after reviewing the collector's channel membership. The flag is read at run time
 * (after startup) — not in the constructor — so the TTL-cached snapshot is already
 * populated when {@link #onClientReady} fires.
 *
 * <p>Skips all non-collector accounts. A {@link TdApi.GetChat} failure on a single
 * chat is absorbed ({@code onErrorResume}) so a transient error never aborts the
 * full enumeration. Idempotent: the underlying upsert uses {@code ON CONFLICT DO
 * UPDATE} so re-runs are safe.
 */
@Service
public class CollectorChannelRegistryService implements TelegramClientLifecycleListener {

    private static final Logger log = LoggerFactory.getLogger(CollectorChannelRegistryService.class);

    /** Number of chat-ids to request from TDLib in one GetChats call. */
    private static final int CHAT_LIMIT = 200;

    /** Runtime flag name in bot.app_settings. Default false — must be opted-in. */
    private static final String SETTING_ENABLED = "collector.channel-registry.enabled";

    private final TelegramAccountRepository telegramAccountRepository;
    private final ChannelRepository channelRepository;
    private final AppSettingsService appSettingsService;

    public CollectorChannelRegistryService(TelegramAccountRepository telegramAccountRepository,
                                           ChannelRepository channelRepository,
                                           AppSettingsService appSettingsService) {
        this.telegramAccountRepository = telegramAccountRepository;
        this.channelRepository = channelRepository;
        this.appSettingsService = appSettingsService;
    }

    /**
     * Called by {@link com.example.telegramuserbot.service.TelegramClientManager} for
     * every account when its TDLib client becomes ready (primary and secondary alike).
     * We gate on the collector flag early so non-collector accounts exit immediately.
     */
    @Override
    public void onClientReady(String botId, TelegramClientFacade client) {
        // Read flag at call time — AppSettingsService snapshot is populated by the time
        // onClientReady fires (ApplicationReadyEvent loads it eagerly; secondary client
        // initialisation is deferred until after ApplicationReadyEvent).
        if (!appSettingsService.getBoolean(SETTING_ENABLED, false)) {
            log.debug("CollectorChannelRegistry: disabled ({}=false) — skipping botId={}", SETTING_ENABLED, botId);
            return;
        }

        // Non-blocking: fire-and-forget with subscribeOn(boundedElastic()) to bridge the
        // CompletableFuture-backed TDLib sends off the Netty/scheduler event-loop thread.
        enumerateAndRegister(botId, client)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        count -> log.info("CollectorChannelRegistry: botId={} registered {} broadcast channel(s)", botId, count),
                        error -> log.warn("CollectorChannelRegistry: enumeration failed for botId={}: {}", botId, error.getMessage())
                );
    }

    /**
     * Core pipeline (package-visible for testing without Spring context):
     * <ol>
     *   <li>Check collector flag via DB — emit nothing if not a collector.</li>
     *   <li>{@link TdApi.GetChats} (ChatListMain, limit=200) → stream of chat ids.</li>
     *   <li>For each chat id: {@link TdApi.GetChat} — on failure absorb and continue.</li>
     *   <li>Filter to broadcast channels ({@code ChatTypeSupergroup.isChannel == true}).</li>
     *   <li>Upsert into {@code tgscan.channels} via
     *       {@link ChannelRepository#upsertBroadcastChannel}.</li>
     * </ol>
     *
     * @return count of channels successfully registered
     */
    Mono<Long> enumerateAndRegister(String botId, TelegramClientFacade client) {
        return telegramAccountRepository.isCollector(botId)
                .defaultIfEmpty(false)
                .flatMapMany(isCollector -> {
                    if (!isCollector) {
                        log.debug("CollectorChannelRegistry: botId={} is not the collector — skipping", botId);
                        return Flux.<Long>empty();
                    }
                    log.info("CollectorChannelRegistry: starting broadcast-channel enumeration for collector botId={}", botId);
                    return Mono.fromFuture(() -> client.send(new TdApi.GetChats(new TdApi.ChatListMain(), CHAT_LIMIT)))
                            .cast(TdApi.Chats.class)
                            .flatMapMany(chats -> Flux.fromStream(
                                    java.util.Arrays.stream(chats.chatIds).boxed()
                            ));
                })
                .flatMap(chatId -> resolveBroadcastChannel(client, chatId), 4)
                .flatMap(chat -> channelRepository.upsertBroadcastChannel(chat.id, chat.title != null ? chat.title : "")
                        .doOnSuccess(rows -> log.debug("CollectorChannelRegistry: registered chatId={} title='{}' (rows={})",
                                chat.id, chat.title, rows))
                        .onErrorResume(e -> {
                            log.warn("CollectorChannelRegistry: upsert failed for chatId={}: {}", chat.id, e.getMessage());
                            return Mono.empty();
                        }), 4)
                .count();
    }

    /**
     * Fetches the {@link TdApi.Chat} for the given chatId and emits it only if it is
     * a broadcast channel ({@code ChatTypeSupergroup.isChannel == true}).
     * Any TDLib error on a single chat is absorbed so the rest of the enumeration continues.
     */
    private Mono<TdApi.Chat> resolveBroadcastChannel(TelegramClientFacade client, Long chatId) {
        return Mono.fromFuture(() -> client.send(new TdApi.GetChat(chatId)))
                .cast(TdApi.Chat.class)
                .flatMap(chat -> {
                    boolean isBroadcastChannel = chat.type instanceof TdApi.ChatTypeSupergroup sg && sg.isChannel;
                    return isBroadcastChannel ? Mono.just(chat) : Mono.empty();
                })
                .onErrorResume(e -> {
                    log.debug("CollectorChannelRegistry: GetChat failed for chatId={} — skipping: {}", chatId, e.getMessage());
                    return Mono.empty();
                });
    }
}
