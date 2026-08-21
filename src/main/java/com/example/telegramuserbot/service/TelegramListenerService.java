package com.example.telegramuserbot.service;

import com.example.telegramuserbot.config.BotInstanceProvider;
import com.example.telegramuserbot.domain.ResponseIntent;
import com.example.telegramuserbot.dto.HumanizedResponseDto;
import com.example.telegramuserbot.dto.MessageContextDto;
import com.example.telegramuserbot.repository.BotPersonaRepository;
import com.example.telegramuserbot.repository.ChannelRepository;
import com.example.telegramuserbot.repository.MessageRepository;
import com.example.telegramuserbot.repository.TelegramAccountRepository;
import com.example.telegramuserbot.service.cache.SyncEnabledChatsCache;
import com.example.telegramuserbot.service.command.CommandService;
import com.example.telegramuserbot.service.orchestration.ResponseOrchestrator;
import com.example.telegramuserbot.service.processing.IdempotencyService;
import com.example.telegramuserbot.service.persistence.MessagePersistenceService;
import com.example.telegramuserbot.service.reaction.ReactionDetectionService;
import com.example.telegramuserbot.service.telegram.TelegramSelfUserIdResolver;
import com.example.telegramuserbot.telegram.TelegramClientFacade;
import com.example.telegramuserbot.service.TelegramClientManager;
import com.example.telegramuserbot.telegram.TelegramClientLifecycleListener;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;


@Service
public class TelegramListenerService implements TelegramClientLifecycleListener {
    private static final Logger log = LoggerFactory.getLogger(TelegramListenerService.class);
    private final MessageRepository messageRepository;
    private final KafkaMessageProducerService kafkaProducerService;
    private final MessagePersistenceService messagePersistenceService;
    private final BotInstanceProvider botInstanceProvider;
    private final TelegramSelfUserIdResolver selfUserIdResolver;
    private final CommandService commandService;
    private final ResponseOrchestrator responseOrchestrator;
    private final Long allowedCommandChatId;
    private final SyncEnabledChatsCache syncEnabledChatsCache;
    private final IdempotencyService idempotencyService;
    private final BotPersonaRepository botPersonaRepository;
    private final TelegramClientManager telegramClientManager;
    private final TelegramAccountRepository telegramAccountRepository;
    private final ChannelRepository channelRepository;

    @Autowired(required = false)
    private ReactionDetectionService reactionDetectionService;

    /** Primary client reference, set in {@link #onClientReady} — no constructor injection needed */
    private volatile TelegramClientFacade primaryClient;
    private volatile Long botUserId = null;
    private volatile String botFirstName = null;
    private final Random random = new Random();
    private final Map<Long, String> botInstanceByUserId = new ConcurrentHashMap<>();
    private final Set<Long> botUserIds = ConcurrentHashMap.newKeySet();

    /**
     * Per-(persona, peer) DM reply cooldown.
     * Key: "{receivedByBotId}:{chatId}" — value: when we last dispatched a reply.
     * A persona replies to a given peer at most once per DM_PEER_COOLDOWN_SECONDS.
     * TODO: make configurable via AppSettingsService#dmPerPeerCooldownSeconds when the
     *       settings schema gains that key.
     */
    private static final long DM_PEER_COOLDOWN_SECONDS = 60L;
    private final Map<String, Instant> dmPeerLastReplied = new ConcurrentHashMap<>();

    public TelegramListenerService(MessageRepository messageRepository,
                                   KafkaMessageProducerService kafkaProducerService,
                                   MessagePersistenceService messagePersistenceService,
                                   BotInstanceProvider botInstanceProvider,
                                   TelegramSelfUserIdResolver selfUserIdResolver,
                                   CommandService commandService,
                                   ResponseOrchestrator responseOrchestrator,
                                   Long allowedCommandChatId,
                                   SyncEnabledChatsCache syncEnabledChatsCache,
                                   IdempotencyService idempotencyService,
                                   BotPersonaRepository botPersonaRepository,
                                   TelegramClientManager telegramClientManager,
                                   TelegramAccountRepository telegramAccountRepository,
                                   ChannelRepository channelRepository) {
        this.messageRepository = messageRepository;
        this.kafkaProducerService = kafkaProducerService;
        this.messagePersistenceService = messagePersistenceService;
        this.botInstanceProvider = botInstanceProvider;
        this.selfUserIdResolver = selfUserIdResolver;
        this.commandService = commandService;
        this.responseOrchestrator = responseOrchestrator;
        this.allowedCommandChatId = allowedCommandChatId;
        this.syncEnabledChatsCache = syncEnabledChatsCache;
        this.idempotencyService = idempotencyService;
        this.botPersonaRepository = botPersonaRepository;
        this.telegramClientManager = telegramClientManager;
        this.telegramAccountRepository = telegramAccountRepository;
        this.channelRepository = channelRepository;
    }

    @Override
    public void onClientReady(String botId, TelegramClientFacade readyClient) {
        boolean isPrimary = botId.equals(botInstanceProvider.getInstanceId());

        if (isPrimary) {
            this.primaryClient = readyClient;
            fetchBotInfo(readyClient, botId);
        }

        // Ingestion on EVERY client: each persona must see its own chats, not only the
        // primary account. Cross-account duplicates from shared chats collapse twice —
        // isDuplicateMessage (DB lookup) and the consumer idempotency key (chatId:messageId).
        readyClient.addUpdateHandler(TdApi.UpdateNewMessage.class,
            update -> handleNewMessage(botId, update));

        readyClient.addUpdateHandler(TdApi.UpdateNewMessage.class, update -> {
            TdApi.Message msg = ((TdApi.UpdateNewMessage) update).message;
            if (!msg.isOutgoing && !isBotPersonaMessage(msg)) {
                fireReactionDetection(msg.chatId, msg.id, botId);
            }
        });

        readyClient.addUpdateHandler(TdApi.UpdateMessageSendSucceeded.class,
            update -> handleMessageSendSucceeded(botId, (TdApi.UpdateMessageSendSucceeded) update));

        selfUserIdResolver.resolveSelfUserId(botId)
            .doOnNext(userId -> {
                botInstanceByUserId.putIfAbsent(userId, botId);
                botUserIds.add(userId);
                log.info("Registered bot persona userId={} for botInstanceId={}", userId, botId);
            })
            .subscribe();

        log.info("Registered TDLib handlers for persona={} (primary={})", botId, isPrimary);
    }

    private void fetchBotInfo(TelegramClientFacade targetClient, String botId) {
        Mono.<TdApi.User>create(sink ->
                targetClient.send(new TdApi.GetMe(), result -> {
                    if (result.isError()) {
                        sink.error(new RuntimeException("Failed to get bot info: " + result.getError().message));
                    } else {
                        sink.success(result.get());
                    }
                })
        ).subscribe(
                me -> {
                    this.botUserId = me.id;
                    this.botFirstName = me.firstName;
                    botInstanceByUserId.putIfAbsent(me.id, botId);
                    botUserIds.add(me.id);
                    log.info("Successfully fetched bot info. UserID: {}, FirstName: {}", this.botUserId, this.botFirstName);
                    if (me.usernames == null || me.usernames.activeUsernames == null || me.usernames.activeUsernames.length == 0) {
                        log.warn("Bot does not have an active username set in Telegram");
                    } else {
                        log.info("Bot username (@{}) is available", me.usernames.activeUsernames[0]);
                    }
                },
                error -> log.error("Failed to get bot info (GetMe) for botId={}: {}", botId, error.getMessage(), error)
        );
    }

    private void handleNewMessage(String receivedByBotId, TdApi.Update update) {
        TdApi.Message newMsg = ((TdApi.UpdateNewMessage) update).message;
        long chatId = newMsg.chatId;
        long msgId = newMsg.id;

        log.info("=== MESSAGE RECEIVED === Chat: {}, MsgId: {}, Outgoing: {}, via={}", chatId, msgId, newMsg.isOutgoing, receivedByBotId);

        if (isBotPersonaMessage(newMsg)) {
            log.debug("Ignoring bot persona message {} (senderId={}) in listener. It will be persisted by the sender.",
                    msgId, extractSenderUserId(newMsg.senderId).orElse(null));
            return;
        }

        if (newMsg.isOutgoing) {
            log.debug("Ignoring outgoing (bot) message {} in listener. It will be persisted by the sender.", msgId);
            return;
        }

        // Admin commands (sent to the configured allowedCommandChatId — a positive user/DM id
        // by default) bypass the chat-config check and are processed immediately. Checked BEFORE
        // the DM branch so an admin command in that DM is handled as a command, not a normal DM.
        if (isAdminCommand(chatId, newMsg)) {
            processAdminCommand(newMsg)
                    .subscribe(
                            v -> log.trace("Admin command processing completed for msgId: {}", msgId),
                            err -> log.error("Error processing admin command for msgId: {}", msgId, err)
                    );
            return;
        }

        // Private chat (TDLib: chatId > 0 means the peer's user id) — route to the
        // DM flow. No chat_configs row can exist for a private chat (tgscan.channels
        // CHECK id < 0), so the config-gated group path below would silently drop it.
        if (chatId > 0) {
            handleDirectMessage(receivedByBotId, newMsg)
                    .subscribe(
                            v -> log.trace("DM processing chain completed for msgId: {}", msgId),
                            err -> log.error("Error in DM processing chain for msgId: {}", msgId, err)
                    );
            return;
        }

        // chatId < 0: run the collector-news harvest FIRST, before the cross-account
        // dedup. When both the collector and a reply persona are in the same broadcast
        // channel, whichever client processes the post first would claim it via
        // isCrossAccountDuplicate and the other client's copy would be silently
        // dropped. By harvesting first the collector account never loses a channel
        // post to the dedup gate. tryHarvestCollectorNews fails open (returns false)
        // for non-collector accounts and on any TDLib error, so the existing
        // dedup → group pipeline runs unchanged for all other cases.
        tryHarvestCollectorNews(receivedByBotId, chatId, msgId, newMsg)
                .subscribe(
                        harvested -> {
                            if (!harvested) {
                                handleGroupAfterDedup(receivedByBotId, chatId, msgId, newMsg);
                            }
                        },
                        err -> {
                            log.error("Error in collector-news harvest for msgId: {}", msgId, err);
                            handleGroupAfterDedup(receivedByBotId, chatId, msgId, newMsg);
                        }
                );
    }

    /**
     * Runs the cross-account dedup → group-message pipeline for a chatId &lt; 0 message, called only
     * after {@code tryHarvestCollectorNews} returned false (i.e. the message was NOT consumed as
     * collector news). Admin commands are handled earlier (before the DM branch), so they are not
     * re-checked here. The dedup and group blocks are verbatim from the pre-refactor inline code.
     */
    private void handleGroupAfterDedup(String receivedByBotId, long chatId, long msgId, TdApi.Message newMsg) {
        // EARLIEST-STAGE cross-account dedup, GROUP CHATS ONLY: in a shared chat
        // every persona client receives the same physical message, but TDLib
        // assigns a DIFFERENT local id per account — so a chatId:messageId key
        // cannot collapse them. Claim the logical message by
        // chatId:senderId:contentHash here, before persist + Kafka, so only the
        // first client's copy enters the pipeline and the chat gets exactly one
        // set of replies. Private chats are excluded: a DM exists on exactly one
        // client, and the same human texting the same words to two personas is
        // two distinct logical messages that share chatId (= the human's user id).
        if (isCrossAccountDuplicate(chatId, newMsg)) {
            log.info("⊘ SKIP DUPLICATE: chat={}, msgId={}, via={} — same physical message already claimed by another persona client",
                    chatId, msgId, receivedByBotId);
            return;
        }

        // Group message flow.
        handleGroupMessage(receivedByBotId, chatId, msgId, newMsg)
                .subscribe(
                        v -> log.trace("Message processing chain completed for msgId: {}", msgId),
                        err -> log.error("Error in message processing chain for msgId: {}", msgId, err)
                );
    }

    /**
     * Attempts to harvest {@code newMsg} as collector news. Returns {@code true} when this message
     * was recognised as a broadcast-channel post received by the collector account (and was
     * persisted as news); returns {@code false} when it should fall through to the normal group flow.
     *
     * <p>Detection logic: ask TDLib for the chat object of {@code chatId} and inspect
     * {@code ChatTypeSupergroup.isChannel}. This is the same check used by
     * {@link com.example.telegramuserbot.service.channels.pipeline.ChannelLinkingServiceImpl}
     * and {@link com.example.telegramuserbot.service.maintenance.PrimaryChannelLinkService}.
     *
     * <p>On any error the method fails open — returns {@code false} — so the group-flow fallback
     * always runs and live message ingestion is never silenced by a transient TDLib lookup failure.
     */
    private Mono<Boolean> tryHarvestCollectorNews(String receivedByBotId, long chatId, long msgId, TdApi.Message newMsg) {
        return telegramAccountRepository.isCollector(receivedByBotId)
                .defaultIfEmpty(false)
                .flatMap(isCollector -> {
                    if (!isCollector) {
                        return Mono.just(false);
                    }
                    // Fetch the chat object to determine its type (broadcast channel vs group).
                    TelegramClientFacade client = telegramClientManager.getClient(receivedByBotId);
                    if (client == null) {
                        log.warn("[Chat {}] Collector {} has no active TDLib client — skipping channel-type check",
                                chatId, receivedByBotId);
                        return Mono.just(false);
                    }
                    return Mono.<TdApi.Object>create(sink ->
                            client.send(new TdApi.GetChat(chatId), result -> {
                                if (result.isError()) {
                                    sink.error(new RuntimeException("GetChat failed for chat=" + chatId
                                            + ": " + result.getError().message));
                                } else {
                                    sink.success(result.get());
                                }
                            })
                    )
                    .cast(TdApi.Chat.class)
                    .flatMap(chat -> {
                        boolean isBroadcastChannel =
                                chat.type instanceof TdApi.ChatTypeSupergroup sg && sg.isChannel;
                        if (!isBroadcastChannel) {
                            log.debug("[Chat {}] Collector account {} received group message (not broadcast channel), routing to group flow",
                                    chatId, receivedByBotId);
                            return Mono.just(false);
                        }
                        // Broadcast channel confirmed: upsert the channel row and force-persist the post.
                        String title = chat.title != null ? chat.title : "";
                        log.info("📰 COLLECTOR NEWS: harvesting channel post chat={}, msgId={}, title='{}', via={}",
                                chatId, msgId, title, receivedByBotId);
                        return channelRepository.upsertBroadcastChannel(chatId, title)
                                .doOnSuccess(rows -> log.debug("[Chat {}] Channel upserted (rows={})", chatId, rows))
                                .then(messagePersistenceService.forcePersistMessage(receivedByBotId, chatId, newMsg))
                                .doOnSuccess(entity -> log.info("📰 COLLECTOR NEWS: persisted msgId={} in chat={} as news entry",
                                        msgId, chatId))
                                .thenReturn(true);
                    })
                    .onErrorResume(e -> {
                        log.warn("[Chat {}] tryHarvestCollectorNews failed for msgId={} via {}: {} — falling through to group flow",
                                chatId, msgId, receivedByBotId, e.getMessage());
                        return Mono.just(false);
                    });
                });
    }

    /**
     * Handles a chatId&lt;0 (group/supergroup) message that was NOT intercepted by the collector-news
     * path. Applies the chat-config gate, deduplication, command routing, and persist+Kafka.
     */
    private Mono<Void> handleGroupMessage(String receivedByBotId, long chatId, long msgId, TdApi.Message newMsg) {
        return syncEnabledChatsCache.getConfig(chatId)
                .switchIfEmpty(Mono.defer(() -> {
                    log.debug("[Chat {}] No chat configuration found, skipping message {}", chatId, msgId);
                    return Mono.empty();
                }))
                .flatMap(config -> {
                    boolean allowTracking = config.isEnabled()
                            || config.isSyncEnabled()
                            || config.getPrimaryChannelId() != null;
                    if (!allowTracking) {
                        log.debug("[Chat {}] Chat disabled for both responses and sync, skipping message {}", chatId, msgId);
                        return Mono.<Void>empty();
                    }

                    Mono<TdApi.Message> deduplicated = isDuplicateMessage(chatId, msgId, newMsg)
                            .flatMap(isDuplicate -> {
                                if (isDuplicate) {
                                    log.debug("Skipping duplicate/old message {}", msgId);
                                    return Mono.<TdApi.Message>empty();
                                }
                                return Mono.just(newMsg);
                            });

                    if (config.isEnabled()) {
                        return deduplicated
                                .flatMap(this::processCommandsOrContinue)
                                .flatMap(m -> persistAndSendToKafka(receivedByBotId, m));
                    }

                    // Discussion chats with primary_channel_id should still persist messages even when disabled
                    log.trace("[Chat {}] Chat disabled for responses but tracked due to sync/primary link, persisting only", chatId);
                    return deduplicated.flatMap(m -> persistAndSendToKafka(receivedByBotId, m));
                });
    }

    /**
     * DM (private chat) flow. The addressed persona is the only one that can see the
     * chat, so the message is persisted with received_by_bot_id = that persona and the
     * consumer replies as it unconditionally — bot_personas.reply_to_direct is the
     * decision (a direct address is an explicit request; no probability roll).
     *
     * <p>forcePersistMessage is used because the regular persist path is gated on a
     * chat_configs row, which cannot exist for a private chat.
     *
     * <p>Safety rails:
     * <ul>
     *   <li>Telegram service account (userId=777000) is silently skipped — it sends
     *       service notifications, never a real human.</li>
     *   <li>Bot senders (TdApi.UserTypeBot) are silently skipped — prevents bot-to-bot
     *       DM ping-pong on the private-chat path.</li>
     * </ul>
     */
    Mono<Void> handleDirectMessage(String receivedByBotId, TdApi.Message msg) {
        long chatId = msg.chatId;
        long msgId = msg.id;

        // Rail 1: skip Telegram service account and bot senders.
        // Extract sender userId synchronously; DMs always have a MessageSenderUser sender.
        Long senderUserId = extractSenderUserId(msg.senderId).orElse(null);
        if (senderUserId == null) {
            log.debug("⊘ SKIP DM: chat={}, msgId={} — sender has no user id (not MessageSenderUser)", chatId, msgId);
            return Mono.empty();
        }
        if (senderUserId == 777000L) {
            log.info("⊘ SKIP DM: chat={}, msgId={} — sender is Telegram service account (777000)", chatId, msgId);
            return Mono.empty();
        }

        return isBotSender(receivedByBotId, senderUserId)
                .flatMap(isBot -> {
                    if (isBot) {
                        log.info("⊘ SKIP DM: chat={}, msgId={}, senderId={} — sender is a Telegram bot, skipping to prevent DM ping-pong",
                                chatId, msgId, senderUserId);
                        return Mono.<Void>empty();
                    }
                    return botPersonaRepository.replyToDirectEnabled(receivedByBotId)
                            .defaultIfEmpty(false)
                            .flatMap(enabled -> {
                                if (!enabled) {
                                    log.info("⊘ SKIP DM: chat={}, msgId={} — persona {} has reply_to_direct=false",
                                            chatId, msgId, receivedByBotId);
                                    return Mono.empty();
                                }
                                // Rail 3: per-(persona, peer) cooldown — at most one reply per window.
                                if (!checkDmPeerCooldown(receivedByBotId, chatId)) {
                                    log.info("⊘ SKIP DM COOLDOWN: chat={}, msgId={}, persona={} — already replied to this peer within {}s cooldown window",
                                            chatId, msgId, receivedByBotId, DM_PEER_COOLDOWN_SECONDS);
                                    return Mono.<Void>empty();
                                }
                                return isDuplicateMessage(chatId, msgId, msg)
                                        .flatMap(isDuplicate -> {
                                            if (isDuplicate) {
                                                // Either a TDLib redelivery, or an id collision with another
                                                // persona's DM thread (account-local ids + shared chatId).
                                                log.info("⊘ SKIP DM DUPLICATE: chat={}, msgId={}, via={} — message already persisted (redelivery or cross-persona id collision)",
                                                        chatId, msgId, receivedByBotId);
                                                return Mono.empty();
                                            }
                                            log.info("📩 DM ACCEPTED: chat={}, msgId={} → persona {} will reply (reply_to_direct=true)",
                                                    chatId, msgId, receivedByBotId);
                                            recordDmPeerReply(receivedByBotId, chatId);
                                            return messagePersistenceService.forcePersistMessage(receivedByBotId, chatId, msg)
                                                    .flatMap(entity -> sendToKafkaReactive(chatId, msgId));
                                        });
                            });
                });
    }

    /**
     * Returns {@code true} when the persona has NOT replied to this peer within the
     * cooldown window (i.e. the reply is allowed). Returns {@code false} when still
     * throttled. Thread-safe: uses a single {@link ConcurrentHashMap} lookup.
     */
    private boolean checkDmPeerCooldown(String receivedByBotId, long chatId) {
        String key = receivedByBotId + ":" + chatId;
        Instant last = dmPeerLastReplied.get(key);
        if (last == null) {
            return true;
        }
        return Instant.now().isAfter(last.plusSeconds(DM_PEER_COOLDOWN_SECONDS));
    }

    /**
     * Records that {@code receivedByBotId} just dispatched a reply to peer {@code chatId}.
     * Also lazily evicts stale entries whose cooldown has long expired (entries older than
     * 2× the window) to prevent unbounded map growth in long-running processes.
     */
    private void recordDmPeerReply(String receivedByBotId, long chatId) {
        String key = receivedByBotId + ":" + chatId;
        Instant now = Instant.now();
        dmPeerLastReplied.put(key, now);

        // Lazy eviction: remove entries that expired more than one full window ago.
        Instant evictBefore = now.minusSeconds(DM_PEER_COOLDOWN_SECONDS * 2);
        Iterator<Map.Entry<String, Instant>> it = dmPeerLastReplied.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().isBefore(evictBefore)) {
                it.remove();
            }
        }
    }

    /**
     * Returns true when the given userId belongs to a Telegram bot account.
     * Looks up the user via the persona's own TDLib client. Fails open (returns
     * false) so that a lookup error never permanently silences a DM thread.
     */
    private Mono<Boolean> isBotSender(String receivedByBotId, long userId) {
        TelegramClientFacade client = telegramClientManager.getClient(receivedByBotId);
        if (client == null) {
            return Mono.just(false);
        }
        return Mono.<TdApi.Object>create(sink ->
                client.send(new TdApi.GetUser(userId), result -> {
                    if (result.isError()) {
                        sink.error(new RuntimeException(result.getError().message));
                    } else {
                        sink.success(result.get());
                    }
                })
        )
        .cast(TdApi.User.class)
        .map(user -> user.type instanceof TdApi.UserTypeBot)
        .defaultIfEmpty(false)
        .onErrorResume(e -> {
            log.debug("⚠ isBotSender lookup failed for userId={} via botId={} (treating as non-bot): {}",
                    userId, receivedByBotId, e.getMessage());
            return Mono.just(false);
        });
    }

    private void fireReactionDetection(long chatId, long msgId, String personaId) {
        if (reactionDetectionService != null) {
            reactionDetectionService.onNewMessageForPersona(chatId, msgId, personaId)
                .onErrorResume(e -> {
                    log.warn("Reaction detection failed for persona={} chat={}: {}", personaId, chatId, e.getMessage());
                    return Mono.just(0);
                })
                .subscribe();
        }
    }

    private Mono<TdApi.Message> processCommandsOrContinue(TdApi.Message msg) {
        if (msg.content instanceof TdApi.MessageText textContent) {
            String text = textContent.text.text;
            if (text != null && text.startsWith("/")) {
                log.info("[Chat {}] Detected command: {}", msg.chatId, text);
                Long senderId = (msg.senderId instanceof TdApi.MessageSenderUser userSender) ? userSender.userId : null;

                return commandService.processCommand(msg.chatId, msg.id, senderId, text)
                        .flatMap(responseOpt -> {
                            if (responseOpt.isPresent()) {
                                // 1. Вызываем асинхронный метод humanizeCommandResponse.
                                //    Он возвращает Mono<String>.
                                return humanizeCommandResponse(responseOpt.get(), senderId, msg.chatId, text)
                                        // 2. Используем flatMap, чтобы работать с результатом, когда он будет готов.
                                        .flatMap(humanizedResponse ->
                                                // 3. Теперь humanizedResponse - это обычная String,
                                                //    и мы можем передать ее в следующий метод.
                                                applyTypingDelayAndSend(msg.chatId, msg.id, humanizedResponse, senderId)
                                        )
                                        // 4. Завершаем цепочку, возвращая пустой Mono<TdApi.Message>,
                                        //    чтобы сигнализировать о прекращении дальнейшей обработки.
                                        //    Оператор .then() как раз это и делает.
                                        .then(Mono.empty());
                            } else {
                                log.debug("[Chat {}] Command '{}' returned empty response. Stopping.", msg.chatId, text);
                                // Команда не вернула ответа, просто останавливаемся.
                                return Mono.empty();
                            }
                        });
            }
        }
        // Это не команда, возвращаем исходное сообщение для дальнейшей обработки в другой части цепочки.
        return Mono.just(msg);
    }

    private Mono<Void> persistAndSendToKafka(String receivedByBotId, TdApi.Message msg) {
        // Using original TDLib chat ID directly
        long chatId = msg.chatId;
        long messageId = msg.id;

        return syncEnabledChatsCache.syncEnabled(chatId)
                .onErrorResume(error -> {
                    log.warn("[Chat {}] Failed to resolve sync-enabled flag for message {}. Proceeding as enabled. Reason: {}", chatId, messageId, error.getMessage());
                    return Mono.just(true);
                })
                .flatMap(syncEnabled -> {
                    if (!syncEnabled) {
                        log.debug("[Chat {}] Sync disabled via cache, skipping message {}", chatId, messageId);
                        return Mono.empty();
                    }

                    return messagePersistenceService.persistMessage(receivedByBotId, chatId, msg)
                            .doOnSuccess(p -> log.debug("[Chat {}] Message {} persisted successfully", chatId, messageId))
                            .doOnError(e -> log.error("[Chat {}] Failed to persist message {}: {}", chatId, messageId, e.getMessage(), e))
                            .flatMap(persistedEntity -> {
                                log.debug("[Chat {}] Sending message {} to Kafka after successful DB persist", chatId, messageId);
                                return sendToKafkaReactive(chatId, messageId);
                            })
                            .switchIfEmpty(Mono.defer(() -> {
                                log.debug("[Chat {}] Message {} not persisted (sync disabled or filtered), skipping Kafka send", chatId, messageId);
                                return Mono.empty();
                            }));
                });
    }

    private void handleMessageSendSucceeded(String botInstanceId, TdApi.UpdateMessageSendSucceeded update) {
        if (update == null || update.message == null) {
            log.warn("Received UpdateMessageSendSucceeded without message payload");
            return;
        }
        // Using original TDLib chat ID directly
        long chatId = update.message.chatId;
        long provisionalId = update.oldMessageId;

        messagePersistenceService.updateMessageAfterSend(botInstanceId, chatId, provisionalId, update.message)
                .doOnSuccess(v -> log.debug("[Chat {}] Message send succeeded (botInstanceId={}): provisionalId={} -> finalId={}",
                        chatId, botInstanceId, provisionalId, update.message.id))
                .doOnError(e -> log.error("[Chat {}] Failed to update provisional message {} after send succeeded (botInstanceId={}): {}",
                        chatId, provisionalId, botInstanceId, e.getMessage(), e))
                .subscribe();
    }

    private boolean isBotPersonaMessage(TdApi.Message message) {
        return extractSenderUserId(message.senderId)
                .map(botUserIds::contains)
                .orElse(false);
    }

    /**
     * Atomically claims a logical (cross-account) message identity. Returns true
     * when this physical message was already claimed by another persona client
     * in the same chat — text/caption is hashed so per-account-local TDLib ids
     * do not matter. The server-assigned {@code message.date} (seconds, identical
     * across all observing accounts for one physical message) is included in the
     * key so that a genuine repeat (same text, same sender, different timestamp)
     * is NOT silently dropped. Messages with no text (media-only) are not deduped
     * here (downstream per-account idempotency still applies); a follow-up will
     * cover media by file id.
     */
    private boolean isCrossAccountDuplicate(long chatId, TdApi.Message message) {
        String text = extractDedupText(message);
        if (text == null || text.isBlank()) {
            return false;
        }
        String contentHash = com.example.telegramuserbot.service.persistence.ContentHash.of(text);
        if (contentHash == null) {
            return false;
        }
        Long senderId = extractSenderUserId(message.senderId).orElse(0L);
        // Include message.date (server-assigned seconds, identical across all
        // observing accounts for the SAME physical message) so two genuine
        // repeat messages with the same text from the same sender in the same
        // chat (e.g. "да", "+1") are NOT collapsed — only the exact same
        // physical transmission is.
        String key = "content:" + chatId + ":" + senderId + ":" + message.date + ":" + contentHash;
        // checkAndSet returns true when the key is NEW (claimed) → NOT a duplicate.
        return !idempotencyService.checkAndSet(key);
    }

    private String extractDedupText(TdApi.Message message) {
        if (message.content instanceof TdApi.MessageText textContent) {
            return textContent.text != null ? textContent.text.text : null;
        }
        if (message.content instanceof TdApi.MessagePhoto photo) {
            return photo.caption != null ? photo.caption.text : null;
        }
        if (message.content instanceof TdApi.MessageVideo video) {
            return video.caption != null ? video.caption.text : null;
        }
        if (message.content instanceof TdApi.MessageDocument document) {
            return document.caption != null ? document.caption.text : null;
        }
        if (message.content instanceof TdApi.MessageAnimation animation) {
            return animation.caption != null ? animation.caption.text : null;
        }
        if (message.content instanceof TdApi.MessageVoiceNote voiceNote) {
            return voiceNote.caption != null ? voiceNote.caption.text : null;
        }
        if (message.content instanceof TdApi.MessageAudio audio) {
            return audio.caption != null ? audio.caption.text : null;
        }
        return null;
    }

    private java.util.Optional<Long> extractSenderUserId(TdApi.MessageSender sender) {
        if (sender instanceof TdApi.MessageSenderUser userSender) {
            return java.util.Optional.of(userSender.userId);
        }
        return java.util.Optional.empty();
    }

    private Mono<Void> sendToKafkaReactive(long chatId, long msgId) {
        return kafkaProducerService.sendNewMessageNotification(chatId, msgId)
                .doOnSubscribe(sub -> log.info("=== KAFKA SEND STARTED === Chat: {}, MsgId: {}", chatId, msgId))
                .doOnSuccess(result -> log.info("[Chat {}] Message {} sent to Kafka for human-like processing ✅", chatId, msgId))
                .doOnError(e -> log.error("[Chat {}] Failed to send message {} to Kafka: {}", chatId, msgId, e.getMessage(), e))
                .subscribeOn(Schedulers.boundedElastic())
                .retryWhen(reactor.util.retry.Retry.backoff(2, Duration.ofSeconds(1))
                        .doBeforeRetry(retrySignal -> log.warn("[Chat {}] Retrying Kafka send for message {}. Attempt: {}", chatId, msgId, retrySignal.totalRetries() + 1)))
                .then();
    }

    private Mono<Void> sendDirectTelegramResponse(long chatId, long replyToMessageId, String text) {
        return Mono.create(sink -> {
            if (primaryClient == null) {
                sink.error(new IllegalStateException("Primary TDLib client not yet initialized"));
                return;
            }
            TdApi.InputMessageContent content = new TdApi.InputMessageText(new TdApi.FormattedText(text, null), null, false);
            TdApi.InputMessageReplyToMessage replyTo = new TdApi.InputMessageReplyToMessage();
            replyTo.chatId = chatId;
            replyTo.messageId = replyToMessageId;
            TdApi.SendMessage request = new TdApi.SendMessage(chatId, 0, replyTo, null, null, content);

            log.debug("[Chat {}] Sending direct response to message {}", chatId, replyToMessageId);
            primaryClient.send(request, result -> {
                if (result.isError()) {
                    log.error("[Chat {}] Failed to send direct response to message {}: {}", chatId, replyToMessageId, result.getError());
                    sink.error(new RuntimeException("Failed to send message: " + result.getError().message));
                } else {
                    log.info("[Chat {}] Successfully sent direct response to message {}. New message id: {}", chatId, replyToMessageId, result.get().id);
                    sink.success();
                }
            });
        });
    }

    private Mono<Void> applyTypingDelayAndSend(long chatId, long msgId, String response, Long userId) {
        log.debug("[Chat {}] Sending command response immediately (typing indicator disabled)", chatId);
        return sendDirectTelegramResponse(chatId, msgId, response);
    }

    private Mono<Boolean> isDuplicateMessage(long chatId, long msgId, TdApi.Message message) {
        return messageRepository.findByChatIdAndMessageId(chatId, msgId)
                .map(existing -> {
                    log.debug("[Chat {}] Message {} already exists in database - confirmed duplicate", chatId, msgId);
                    return true;
                })
                .switchIfEmpty(Mono.defer(() -> {
                    if (isServiceMessage(message)) {
                        log.debug("[Chat {}] Service message {} detected - allowing processing", chatId, msgId);
                        return Mono.just(false);
                    }
                    return Mono.just(false);
                }));
    }

    private Mono<String> humanizeCommandResponse(String originalResponse, Long senderId, long chatId, String userCommand) {
        try {
            if (isTechnicalCommand(userCommand)) {
                log.debug("[Chat {}] Technical command detected, returning unmodified response", chatId);
                return Mono.just(originalResponse); // Оборачиваем простой результат в Mono
            }
            if (senderId == null) {
                // addBasicHumanization - это синхронный метод, поэтому результат тоже оборачиваем
                return Mono.just(addBasicHumanization(originalResponse));
            }

            MessageContextDto context = MessageContextDto.basic(chatId, userCommand);
            ResponseIntent intent = determineCommandIntent(userCommand);

            // Теперь мы работаем с "обещанием"
            return Mono.just(originalResponse);

        } catch (Exception e) {
            // Этот catch теперь нужен только для синхронных ошибок до начала реактивной цепочки
            log.warn("[Chat {}] Synchronous error before humanization, using original: {}", chatId, e.getMessage());
            return Mono.just(originalResponse);
        }
    }

    private boolean isTechnicalCommand(String commandText) {
        String lowerCommand = commandText.toLowerCase();
        return lowerCommand.startsWith("/set_prompt") ||
                lowerCommand.startsWith("/set_limit") ||
                lowerCommand.startsWith("/set_name") ||
                lowerCommand.startsWith("/set_title") ||
                lowerCommand.startsWith("/set_style") ||
                lowerCommand.startsWith("/set_length") ||
                lowerCommand.startsWith("/set_language") ||
                lowerCommand.startsWith("/set_traits") ||
                lowerCommand.startsWith("/set_context") ||
                lowerCommand.startsWith("/get_config") ||
                lowerCommand.startsWith("/enable_config") ||
                lowerCommand.startsWith("/disable_config") ||
                lowerCommand.startsWith("/sync_config") ||
                lowerCommand.startsWith("/sync_history") ||
                lowerCommand.startsWith("/sync_status") ||
                lowerCommand.startsWith("/sync_list") ||
                lowerCommand.startsWith("/sync_cancel") ||
                lowerCommand.startsWith("/sync_count") ||
                lowerCommand.startsWith("/delete_my_messages") ||
                lowerCommand.startsWith("/list_channels") ||
                lowerCommand.startsWith("/list_channel") ||
                lowerCommand.startsWith("/my_profile") ||
                lowerCommand.startsWith("/toggle_ai");
    }

    private ResponseIntent determineCommandIntent(String commandText) {
        String lowerCommand = commandText.toLowerCase();
        if (lowerCommand.startsWith("/help") || lowerCommand.startsWith("/start")) {
            return ResponseIntent.INFORMATION;
        } else if (lowerCommand.startsWith("/sync")) {
            return ResponseIntent.ACKNOWLEDGMENT;
        } else if (lowerCommand.startsWith("/enable") || lowerCommand.startsWith("/disable")) {
            return ResponseIntent.ACKNOWLEDGMENT;
        } else if (lowerCommand.startsWith("/set")) {
            return ResponseIntent.ACKNOWLEDGMENT;
        } else if (lowerCommand.startsWith("/get") || lowerCommand.startsWith("/list")) {
            return ResponseIntent.INFORMATION;
        } else {
            return ResponseIntent.GENERAL;
        }
    }

    private String addBasicHumanization(String response) {
        return response.replace("Конфігурація", "Налаштування")
                .replace("успішно", "готово")
                .replace(".", random.nextDouble() < 0.3 ? ")" : ".");
    }

    private boolean isAdminCommand(long chatId, TdApi.Message msg) {
        if (allowedCommandChatId == null || chatId != allowedCommandChatId) {
            return false;
        }
        if (!(msg.content instanceof TdApi.MessageText textContent)) {
            return false;
        }
        String text = textContent.text.text;
        return text != null && text.startsWith("/");
    }

    private Mono<Void> processAdminCommand(TdApi.Message msg) {
        TdApi.MessageText textContent = (TdApi.MessageText) msg.content;
        String text = textContent.text.text;
        long chatId = msg.chatId;
        long msgId = msg.id;
        Long senderId = (msg.senderId instanceof TdApi.MessageSenderUser userSender) ? userSender.userId : null;

        log.info("[Admin Chat {}] Processing command: {}", chatId, text);

        return commandService.processCommand(chatId, msgId, senderId, text)
                .flatMap(responseOpt -> {
                    if (responseOpt.isPresent()) {
                        return humanizeCommandResponse(responseOpt.get(), senderId, chatId, text)
                                .flatMap(humanizedResponse ->
                                        applyTypingDelayAndSend(chatId, msgId, humanizedResponse, senderId)
                                );
                    }
                    log.debug("[Admin Chat {}] Command '{}' returned empty response", chatId, text);
                    return Mono.empty();
                });
    }

    private boolean isServiceMessage(TdApi.Message message) {
        return message.content instanceof TdApi.MessageChatAddMembers ||
                message.content instanceof TdApi.MessageChatDeleteMember ||
                message.content instanceof TdApi.MessageChatChangeTitle ||
                message.content instanceof TdApi.MessageChatChangePhoto ||
                message.content instanceof TdApi.MessageChatDeletePhoto ||
                message.content instanceof TdApi.MessageBasicGroupChatCreate ||
                message.content instanceof TdApi.MessageSupergroupChatCreate ||
                message.content instanceof TdApi.MessageChatUpgradeFrom ||
                message.content instanceof TdApi.MessageChatUpgradeTo ||
                message.content instanceof TdApi.MessagePinMessage ||
                message.content instanceof TdApi.MessageChatSetMessageAutoDeleteTime ||
                message.content instanceof TdApi.MessageContactRegistered ||
                message.content instanceof TdApi.MessageChatJoinByLink ||
                message.content instanceof TdApi.MessageChatJoinByRequest ||
                message.content instanceof TdApi.MessageBotWriteAccessAllowed ||
                message.content instanceof TdApi.MessageCustomServiceAction ||
                message.content instanceof TdApi.MessageForumTopicCreated ||
                message.content instanceof TdApi.MessageForumTopicEdited ||
                message.content instanceof TdApi.MessageForumTopicIsClosedToggled ||
                message.content instanceof TdApi.MessageForumTopicIsHiddenToggled ||
                message.content instanceof TdApi.MessageCall ||
                message.content instanceof TdApi.MessageGameScore;
    }
}
