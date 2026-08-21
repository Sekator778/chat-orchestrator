package com.example.telegramuserbot.service.startup;

import com.example.telegramuserbot.service.TelegramClientManager;
import com.example.telegramuserbot.telegram.TdLibOperationCoordinator;
import com.example.telegramuserbot.telegram.TelegramClientFacade;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Implementation of ChatDiscoveryService that uses TDLib API to discover chats
 * where the bot is present and can access messages.
 *
 * <p>This service uses the TdLibOperationCoordinator to ensure that LoadChats
 * operations are executed sequentially, preventing the TDLib dialog date
 * inconsistency error that occurs when multiple LoadChats run concurrently.</p>
 *
 * <p>The discovery flow is:</p>
 * <ol>
 *   <li>Register UpdateNewChat handler to capture chats as they are loaded</li>
 *   <li>Execute GetChats for main and archive lists (read-only, parallel OK)</li>
 *   <li>Execute LoadChats for main list (serialized via coordinator)</li>
 *   <li>Execute LoadChats for archive list (serialized via coordinator)</li>
 *   <li>Wait for updates to arrive, then return discovered chat IDs</li>
 * </ol>
 *
 * @see TdLibOperationCoordinator
 */
@Service
public class ChatDiscoveryServiceImpl implements ChatDiscoveryService {

    private static final Logger log = LoggerFactory.getLogger(ChatDiscoveryServiceImpl.class);

    private final TelegramClientManager telegramClientManager;
    private final TdLibOperationCoordinator coordinator;
    private final Mono<TdApi.User> selfUserMono;
    private final Mono<Long> selfUserIdMono;

    private static final int CHAT_LIST_LIMIT = 1000;
    private static final Duration UPDATE_WAIT_DURATION = Duration.ofSeconds(3);

    /**
     * Creates a new ChatDiscoveryServiceImpl.
     *
     * @param telegramClientManager the Telegram client manager for obtaining a client
     * @param coordinator the operation coordinator for serializing LoadChats calls
     */
    public ChatDiscoveryServiceImpl(TelegramClientManager telegramClientManager, TdLibOperationCoordinator coordinator) {
        this.telegramClientManager = telegramClientManager;
        this.coordinator = coordinator;
        TelegramClientFacade client = telegramClientManager.getAnyClient();
        if (client != null) {
            this.selfUserMono = Mono.fromFuture(() -> client.send(new TdApi.GetMe()))
                    .cast(TdApi.User.class)
                    .cache();
        } else {
            this.selfUserMono = Mono.error(new IllegalStateException("No Telegram client available"));
        }
        this.selfUserIdMono = selfUserMono.map(user -> user.id);
    }

    @Override
    public Flux<ChatInfo> discoverAvailableChats() {
        log.info("Starting chat discovery process...");
        return coordinator.isTdLibReady()
                .flatMap(ready -> {
                    if (!ready) {
                        log.warn("TDLib is not ready for chat discovery");
                        return Mono.<List<Long>>just(new ArrayList<>());
                    }
                    return loadAllChatsSequentially();
                })
                .flatMapMany(Flux::fromIterable)
                .flatMap(this::getChatDetails)
                .filter(chatInfo -> chatInfo.canReadMessages() && chatInfo.isAccessible())
                .doOnNext(chatInfo -> log.debug("Discovered accessible chat: {} (ID: {})",
                        chatInfo.title(), chatInfo.chatId()))
                .doOnComplete(() -> log.info("Chat discovery completed"))
                .onErrorResume(throwable -> {
                    log.error("Error during chat discovery", throwable);
                    return Flux.empty();
                });
    }

    private TelegramClientFacade getClient() {
        return telegramClientManager.getAnyClient();
    }

    @Override
    public Mono<ChatInfo> getChatDetails(Long chatId) {
        TelegramClientFacade client = getClient();
        if (client == null) {
            return Mono.error(new IllegalStateException("No Telegram client available"));
        }
        return Mono.fromFuture(() -> client.send(new TdApi.GetChat(chatId)))
                .cast(TdApi.Chat.class)
                .flatMap(chat -> {
                    // Get last message information
                    Mono<Long> lastMessageIdMono = getLastMessageId(chatId);
                    Mono<Instant> lastMessageDateMono = getLastMessageDate(chat);
                    Mono<Boolean> canReadMono = canReadMessagesInChat(chatId);
                    Mono<Boolean> canSendMono = canSendMessagesInChat(chatId);

                    return Mono.zip(lastMessageIdMono, lastMessageDateMono, canReadMono, canSendMono)
                            .map(tuple -> new ChatInfo(
                                    chatId,
                                    chat.title,
                                    chat.type,
                                    tuple.getT1(),
                                    tuple.getT2(),
                                    tuple.getT3(),
                                    tuple.getT4(),
                                    isAccessibleChat(chat),
                                    getMemberCount(chat)
                            ));
                })
                .onErrorResume(throwable -> {
                    log.warn("Could not get details for chat {}: {}", chatId, throwable.getMessage());
                    return Mono.empty();
                });
    }

    @Override
    public Mono<Boolean> canReadMessagesInChat(Long chatId) {
        // Try to get chat history with limit 1 to test access
        TdApi.GetChatHistory getChatHistory = new TdApi.GetChatHistory(
                chatId,
                0, // from_message_id (0 means from the last message)
                0, // offset
                1, // limit (just test with 1 message)
                false // only_local
        );

        TelegramClientFacade clientForRead = getClient();
        if (clientForRead == null) {
            return Mono.just(false);
        }
        return Mono.fromFuture(() -> clientForRead.send(getChatHistory))
                .cast(TdApi.Messages.class)
                .map(messages -> true) // If we get messages, we can read them
                .onErrorReturn(false); // If error, we can't read messages
    }

    @Override
    public Mono<Boolean> canSendMessagesInChat(Long chatId) {
        return selfUserIdMono
                .flatMap(userId -> {
                    TelegramClientFacade clientForSend = getClient();
                    if (clientForSend == null) {
                        return Mono.error(new IllegalStateException("No Telegram client available"));
                    }
                    TdApi.MessageSenderUser member = new TdApi.MessageSenderUser();
                    member.userId = userId;
                    return Mono.fromFuture(() -> clientForSend.send(new TdApi.GetChatMember(chatId, member)));
                })
                .cast(TdApi.ChatMember.class)
                .map(this::hasSendRights)
                .onErrorResume(error -> {
                    log.warn("Could not check send permissions for chat {}: {}", chatId, error.getMessage());
                    return Mono.just(false);
                });
    }

    private boolean hasSendRights(TdApi.ChatMember member) {
        TdApi.ChatMemberStatus status = member.status;
        switch (status.getConstructor()) {
            case TdApi.ChatMemberStatusCreator.CONSTRUCTOR:
            case TdApi.ChatMemberStatusAdministrator.CONSTRUCTOR:
            case TdApi.ChatMemberStatusMember.CONSTRUCTOR:
                return true;
            case TdApi.ChatMemberStatusRestricted.CONSTRUCTOR:
                TdApi.ChatMemberStatusRestricted restricted = (TdApi.ChatMemberStatusRestricted) status;
                return restricted.isMember && restricted.permissions != null && restricted.permissions.canSendBasicMessages;
            default:
                return false;
        }
    }

    @Override
    public Mono<TdApi.User> getBotUserInfo() {
        return selfUserMono.doOnNext(user -> log.info("Bot user info: {} {} (ID: {})",
                user.firstName, user.lastName, user.id));
    }

    /**
     * Loads all chats using sequential LoadChats calls via the coordinator.
     *
     * <p>This method prevents the TDLib dialog date inconsistency error by
     * ensuring LoadChats operations execute one at a time.</p>
     *
     * <p>Flow:</p>
     * <ol>
     *   <li>Register UpdateNewChat handler to capture chats</li>
     *   <li>GetChats(Main) + GetChats(Archive) - read-only, can be parallel</li>
     *   <li>LoadChats(Main) - serialized via coordinator</li>
     *   <li>LoadChats(Archive) - serialized via coordinator</li>
     *   <li>Wait for updates, then return results</li>
     * </ol>
     *
     * @return a Mono containing the list of discovered chat IDs
     */
    Mono<List<Long>> loadAllChatsSequentially() {
        log.info("Loading chats using sequential TDLib API calls...");
        List<Long> chatIds = new CopyOnWriteArrayList<>();
        AtomicBoolean handlerActive = new AtomicBoolean(true);
        registerUpdateHandler(chatIds, handlerActive);
        Mono<Void> getChatsPhase = executeGetChatsPhase(chatIds);
        Mono<Void> loadChatsPhase = executeLoadChatsPhase();
        return getChatsPhase
                .then(loadChatsPhase)
                .delayElement(UPDATE_WAIT_DURATION)
                .doFinally(signal -> {
                    handlerActive.set(false);
                    logDiscoveryResults(chatIds);
                })
                .thenReturn(chatIds)
                .map(ArrayList::new);
    }

    private void registerUpdateHandler(List<Long> chatIds, AtomicBoolean handlerActive) {
        TelegramClientFacade client = getClient();
        if (client == null) {
            log.warn("No Telegram client available for update handler registration");
            return;
        }
        client.addUpdateHandler(TdApi.UpdateNewChat.class, update -> {
            if (!handlerActive.get()) {
                return;
            }
            TdApi.Chat chat = ((TdApi.UpdateNewChat) update).chat;
            if (!chatIds.contains(chat.id)) {
                chatIds.add(chat.id);
                log.debug("Discovered chat via UpdateNewChat: '{}' (ID: {}, Type: {})",
                    chat.title, chat.id, chat.type.getClass().getSimpleName());
            }
        });
        log.info("Registered UpdateNewChat handler for sequential chat loading");
    }

    private Mono<Void> executeGetChatsPhase(List<Long> chatIds) {
        log.info("Phase 1: Fetching already-loaded chats (read-only, parallel)...");
        Mono<Void> getMain = getChatsFromList(new TdApi.ChatListMain(), chatIds, "main");
        Mono<Void> getArchive = getChatsFromList(new TdApi.ChatListArchive(), chatIds, "archive");
        return Mono.when(getMain, getArchive)
                .doOnSuccess(v -> log.info("Phase 1 complete: {} chats from GetChats", chatIds.size()));
    }

    private Mono<Void> getChatsFromList(TdApi.ChatList chatList, List<Long> chatIds, String listName) {
        TelegramClientFacade client = getClient();
        if (client == null) {
            log.warn("No Telegram client available for GetChats({})", listName);
            return Mono.empty();
        }
        return Mono.fromFuture(() -> client.send(new TdApi.GetChats(chatList, CHAT_LIST_LIMIT)))
                .doOnNext(response -> {
                    TdApi.Chats chats = (TdApi.Chats) response;
                    int added = 0;
                    for (long chatId : chats.chatIds) {
                        if (!chatIds.contains(chatId)) {
                            chatIds.add(chatId);
                            added++;
                        }
                    }
                    log.info("GetChats({}) returned {} chats, {} new", listName, chats.chatIds.length, added);
                })
                .onErrorResume(error -> {
                    log.warn("GetChats({}) failed: {}", listName, error.getMessage());
                    return Mono.empty();
                })
                .then();
    }

    private Mono<Void> executeLoadChatsPhase() {
        log.info("Phase 2: Loading additional chats (sequential via coordinator)...");
        return coordinator.loadChatsSequentially(new TdApi.ChatListMain(), CHAT_LIST_LIMIT)
                .doOnSuccess(v -> log.info("LoadChats(Main) completed via coordinator"))
                .onErrorResume(error -> {
                    log.warn("LoadChats(Main) error (non-critical): {}", error.getMessage());
                    return Mono.empty();
                })
                .then(coordinator.loadChatsSequentially(new TdApi.ChatListArchive(), CHAT_LIST_LIMIT))
                .doOnSuccess(v -> log.info("LoadChats(Archive) completed via coordinator"))
                .onErrorResume(error -> {
                    log.debug("LoadChats(Archive) error (non-critical): {}", error.getMessage());
                    return Mono.empty();
                })
                .doOnTerminate(() -> log.info("Phase 2 complete: Sequential LoadChats finished"));
    }

    private void logDiscoveryResults(List<Long> chatIds) {
        log.info("=================================================================");
        log.info("Chat loading complete. Total chats discovered: {}", chatIds.size());
        if (!chatIds.isEmpty()) {
            log.info("Chat IDs: {}", chatIds);
        } else {
            log.warn("No chats discovered! Check TDLib connection or authorization");
        }
        log.info("=================================================================");
    }


    private Mono<Long> getLastMessageId(Long chatId) {
        TdApi.GetChatHistory getChatHistory = new TdApi.GetChatHistory(
                chatId, 0, 0, 1, false
        );

        TelegramClientFacade clientForHistory = getClient();
        if (clientForHistory == null) {
            return Mono.just(0L);
        }
        return Mono.fromFuture(() -> clientForHistory.send(getChatHistory))
                .cast(TdApi.Messages.class)
                .map(messages -> {
                    if (messages.messages.length > 0) {
                        return messages.messages[0].id;
                    }
                    return 0L; // No messages
                })
                .onErrorReturn(0L);
    }

    private Mono<Instant> getLastMessageDate(TdApi.Chat chat) {
        if (chat.lastMessage != null) {
            return Mono.just(Instant.ofEpochSecond(chat.lastMessage.date));
        }
        return Mono.just(Instant.EPOCH); // Very old date if no last message
    }

    private boolean isAccessibleChat(TdApi.Chat chat) {
        // Check if chat is accessible based on various factors
        switch (chat.type.getConstructor()) {
            case TdApi.ChatTypePrivate.CONSTRUCTOR:
                return true; // Private chats are usually accessible

            case TdApi.ChatTypeBasicGroup.CONSTRUCTOR:
                return true; // Basic groups where bot is member

            case TdApi.ChatTypeSupergroup.CONSTRUCTOR:
                TdApi.ChatTypeSupergroup supergroup = (TdApi.ChatTypeSupergroup) chat.type;
                // Check if it's a channel or supergroup and if we have access
                return !supergroup.isChannel || canAccessChannel(chat);

            case TdApi.ChatTypeSecret.CONSTRUCTOR:
                return false; // Secret chats are not supported for bots

            default:
                return false;
        }
    }

    private boolean canAccessChannel(TdApi.Chat chat) {
        // For channels, we need to check if we have read access
        // This is a basic check - in practice, you might want to verify permissions
        // Note: TdApi.ChatPermissions doesn't have canSendMessages field in this version
        // We'll use a simple check based on chat availability
        return chat.permissions != null;
    }

    private int getMemberCount(TdApi.Chat chat) {
        switch (chat.type.getConstructor()) {
            case TdApi.ChatTypeBasicGroup.CONSTRUCTOR:
                TdApi.ChatTypeBasicGroup basicGroup = (TdApi.ChatTypeBasicGroup) chat.type;
                // Note: To get exact member count, we'd need to call GetBasicGroup
                return -1; // Unknown for now

            case TdApi.ChatTypeSupergroup.CONSTRUCTOR:
                TdApi.ChatTypeSupergroup supergroup = (TdApi.ChatTypeSupergroup) chat.type;
                // Note: To get exact member count, we'd need to call GetSupergroup
                return -1; // Unknown for now

            case TdApi.ChatTypePrivate.CONSTRUCTOR:
                return 2; // Private chat always has 2 members

            default:
                return -1; // Unknown
        }
    }
}
