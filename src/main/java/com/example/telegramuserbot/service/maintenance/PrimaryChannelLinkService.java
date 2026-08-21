package com.example.telegramuserbot.service.maintenance;

import com.example.telegramuserbot.config.BotInstanceProvider;
import com.example.telegramuserbot.domain.ChatConfig;
import com.example.telegramuserbot.domain.ProblematicChatReason;
import com.example.telegramuserbot.repository.ChatConfigRepository;
import com.example.telegramuserbot.service.ChannelService;
import com.example.telegramuserbot.service.ProblematicChatService;
import com.example.telegramuserbot.service.TelegramClientManager;
import com.example.telegramuserbot.service.startup.ChatDiscoveryService;
import com.example.telegramuserbot.telegram.TelegramClientFacade;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;


/**
 * Periodically resolves primary channel identifiers for discussion chats.
 * Uses TDLib to discover linked channels (linked_chat_id) and persists the
 * association in bot.chat_configs.primary_channel_id to enable cross-channel
 * filtering and attribution logic.
 */
@Service
public class PrimaryChannelLinkService {

    private static final Logger log = LoggerFactory.getLogger(PrimaryChannelLinkService.class);
    private static final Duration RECHECK_INTERVAL = Duration.ofDays(30);
    private static final int BATCH_SIZE = 100;
    private static final Duration INTER_PERSONA_DELAY_MIN = Duration.ofSeconds(30);
    private static final Duration INTER_PERSONA_DELAY_MAX = Duration.ofSeconds(60);

    private final ChatConfigRepository chatConfigRepository;
    private final TelegramClientManager telegramClientManager;
    private final ChannelService channelService;
    private final ProblematicChatService problematicChatService;
    private final BotInstanceProvider botInstanceProvider;

    public PrimaryChannelLinkService(ChatConfigRepository chatConfigRepository,
                                     TelegramClientManager telegramClientManager,
                                     ChannelService channelService,
                                     ProblematicChatService problematicChatService,
                                     BotInstanceProvider botInstanceProvider) {
        this.chatConfigRepository = chatConfigRepository;
        this.telegramClientManager = telegramClientManager;
        this.channelService = channelService;
        this.problematicChatService = problematicChatService;
        this.botInstanceProvider = botInstanceProvider;
    }

    /**
     * Resolves missing primary_channel_id values for discussion chats.
     *
     * @return Mono emitting the number of configs updated during this run
     */
    public Mono<Integer> refreshPrimaryChannelLinks() {
        Instant threshold = Instant.now().minus(RECHECK_INTERVAL);

        log.info("=================================================================");
        log.info("🔄 Starting primary channel link refresh");
        log.info("Threshold date: {} (configs checked before this date will be reprocessed)", threshold);
        log.info("=================================================================");

        return chatConfigRepository.findConfigsMissingPrimaryChannel(threshold, BATCH_SIZE)
                .filterWhen(config -> problematicChatService.shouldProcess(config.getChannelId()))
                .doOnNext(config -> log.info(
                        "✅ Queued config {} for chat {} (primary_channel_id={}, last_checked={})",
                        config.getId(),
                        config.getChannelId(),
                        config.getPrimaryChannelId(),
                        config.getPrimaryChannelCheckedAt()))
                .collectList()
                .doOnNext(configs -> {
                    log.info("=================================================================");
                    log.info("📋 Found {} configs needing primary channel resolution", configs.size());
                    if (configs.size() > 0) {
                        log.info("Config IDs: {}", configs.stream().map(c -> c.getId()).toList());
                        log.info("Chat IDs: {}", configs.stream().map(c -> c.getChannelId()).toList());
                    }
                    log.info("=================================================================");
                })
                .flatMapMany(Flux::fromIterable)
                .switchIfEmpty(Flux.defer(() -> {
                    log.info("⚠️ No configs found needing primary channel resolution");
                    log.info("This means all configs either:");
                    log.info("  • already have primary_channel_id set");
                    log.info("  • or were checked less than {} days ago", RECHECK_INTERVAL.toDays());
                    log.info("=================================================================");
                    return Flux.empty();
                }))
                .flatMap(this::linkPrimaryChannel, 2)
                .reduce(0, Integer::sum)
                .doOnSuccess(count -> {
                    log.info("=================================================================");
                    log.info("✅ Primary channel link refresh completed: {} configs updated", count);
                    log.info("=================================================================");
                });
    }

    private Mono<Integer> linkPrimaryChannel(ChatConfig config) {
        // Using original TDLib chat ID directly - no prefix needed
        Long tdlibChatId = config.getChannelId();
        log.info("🔧 Processing config {} for chat {} (TDLib ID: {}, last_checked={})",
                config.getId(), tdlibChatId, tdlibChatId, config.getPrimaryChannelCheckedAt());
        return fetchChat(tdlibChatId)
                .doOnError(error -> log.warn("Failed to fetch chat {} for config {}: {}",
                        tdlibChatId, config.getId(), error.getMessage()))
                .flatMap(chat -> {
                    boolean isChannel = isChannel(chat);
                    log.info("Fetched chat {} (isChannel={}), checking for linked chat", chat.id, isChannel);
                    return resolveLinkedChannel(chat)
                            .flatMap(linkedChat -> {
                                if (isChannel) {
                                    // Channel → found discussion group
                                    // Ensure discussion is configured, but don't update primary_channel_id
                                    log.info("Found discussion group {} for channel {}",
                                            linkedChat.id, config.getChannelId());
                                    return ensurePrimaryChannelConfigured(linkedChat)
                                            .then(markChecked(config.getId(), config.getChannelId()));
                                } else {
                                    // Discussion → found channel
                                    // Ensure channel is configured and update primary_channel_id
                                    log.info("Found primary channel {} for discussion {}",
                                            linkedChat.id, config.getChannelId());
                                    return ensurePrimaryChannelConfigured(linkedChat)
                                            .then(updatePrimaryChannel(config.getId(), linkedChat.id));
                                }
                            })
                            .switchIfEmpty(markChecked(config.getId(), config.getChannelId()));
                })
                .onErrorResume(error -> {
                    log.warn("Failed to resolve linked chat for chat {}: {}", config.getChannelId(), error.getMessage());
                    if (isChatNotFoundError(error)) {
                        log.info("Marking config {} for chat {} as checked after TDLib 'Chat not found'", config.getId(), config.getChannelId());
                        return problematicChatService.markProblematic(config.getChannelId(), ProblematicChatReason.CHAT_NOT_FOUND, error.getMessage())
                                .then(markChecked(config.getId(), config.getChannelId()));
                    }
                    return Mono.just(0);
                });
    }

    private Mono<Integer> updatePrimaryChannel(Long configId, long primaryChannelId) {
        // Using original TDLib primary channel ID directly
        Instant now = Instant.now();
        return chatConfigRepository.updatePrimaryChannelLink(configId, primaryChannelId, now)
                .map(updatedRows -> {
                    if (updatedRows != null && updatedRows > 0) {
                        log.info("Linked chat config {} -> primary channel {}",
                                configId, primaryChannelId);
                        return 1;
                    }
                    log.debug("No rows updated when linking chat config {}", configId);
                    return 0;
                })
                .defaultIfEmpty(0);
    }

    private Mono<Integer> markChecked(Long configId, Long channelId) {
        return chatConfigRepository.updatePrimaryChannelCheckedAt(configId, Instant.now())
                .doOnSuccess(ignored -> log.debug("Marked chat {} as checked for primary channel resolution", channelId))
                .onErrorResume(error -> {
                    log.warn("Failed to update primary_channel_checked_at for config {}: {}", configId, error.getMessage());
                    return Mono.empty();
                })
                .thenReturn(0);
    }

    private Mono<TdApi.Chat> fetchChat(long chatId) {
        TelegramClientFacade client = telegramClientManager.getAnyClient();
        if (client == null) {
            return Mono.error(new IllegalStateException("No Telegram client available"));
        }
        return Mono.fromFuture(() -> client.send(new TdApi.GetChat(chatId)))
                .cast(TdApi.Chat.class);
    }

    private Mono<TdApi.Chat> resolveLinkedChannel(TdApi.Chat chat) {
        if (!(chat.type instanceof TdApi.ChatTypeSupergroup supergroup)) {
            log.trace("Chat {} is not a supergroup; skipping linked chat discovery", chat.id);
            return Mono.empty();
        }

        boolean isChannel = supergroup.isChannel;
        log.info("Resolving linked chat for {} {} (supergroup ID: {})",
                isChannel ? "channel" : "discussion", chat.id, supergroup.supergroupId);

        return fetchSupergroupInfo(supergroup.supergroupId)
                .doOnSuccess(info -> log.debug("Fetched supergroup info for {}, linkedChatId={}",
                        supergroup.supergroupId, info.linkedChatId))
                .flatMap(info -> {
                    long linkedChatId = info.linkedChatId;
                    if (linkedChatId == 0) {
                        if (isChannel) {
                            log.info("Channel {} '{}' has NO linked discussion chat configured",
                                    chat.id, chat.title);
                        } else {
                            log.info("Discussion chat {} '{}' has NO linked channel configured",
                                    chat.id, chat.title);
                        }
                        return Mono.empty();
                    }

                    log.info("{} {} '{}' has linked {} {}",
                            isChannel ? "Channel" : "Discussion",
                            chat.id, chat.title,
                            isChannel ? "discussion" : "channel",
                            linkedChatId);

                    return fetchChat(linkedChatId)
                            .onErrorResume(error -> {
                                if (isChatNotFoundError(error)) {
                                    log.info("Attempting to join linked {} for {} {}",
                                            isChannel ? "discussion group" : "channel",
                                            isChannel ? "channel" : "discussion group",
                                            chat.id);
                                    return attemptJoinAndFetch(linkedChatId);
                                }
                                return Mono.error(error);
                            })
                            .filter(linkedChat -> validateLinkedChatType(linkedChat, isChannel, chat.id, linkedChatId))
                            .switchIfEmpty(Mono.defer(() -> {
                                log.warn("Linked chat {} has unexpected type for {} {}",
                                        linkedChatId,
                                        isChannel ? "channel" : "discussion",
                                        chat.id);
                                return Mono.empty();
                            }));
                })
                .onErrorResume(error -> {
                    log.warn("Failed to load supergroup info for chat {}: {}", chat.id, error.getMessage());
                    return Mono.empty();
                });
    }

    private boolean validateLinkedChatType(TdApi.Chat linkedChat, boolean sourceIsChannel, long sourceChatId, long linkedChatId) {
        boolean linkedIsChannel = isChannel(linkedChat);

        if (sourceIsChannel) {
            // Channel's linked chat should be a discussion group (NOT a channel)
            if (linkedIsChannel) {
                log.warn("Channel {} has linkedChatId {} which is also a channel (expected discussion group)",
                        sourceChatId, linkedChatId);
                return false;
            }
            log.debug("Successfully resolved discussion group {} for channel {}", linkedChatId, sourceChatId);
            return true;
        } else {
            // Discussion group's linked chat should be a channel
            if (!linkedIsChannel) {
                log.warn("Discussion {} has linkedChatId {} which is not a channel", sourceChatId, linkedChatId);
                return false;
            }
            log.debug("Successfully resolved channel {} for discussion group {}", linkedChatId, sourceChatId);
            return true;
        }
    }

    private boolean isChannel(TdApi.Chat chat) {
        if (chat.type instanceof TdApi.ChatTypeSupergroup supergroup) {
            return supergroup.isChannel;
        }
        return false;
    }

    private Mono<TdApi.SupergroupFullInfo> fetchSupergroupInfo(long supergroupId) {
        TelegramClientFacade client = telegramClientManager.getAnyClient();
        if (client == null) {
            return Mono.error(new IllegalStateException("No Telegram client available"));
        }
        return Mono.fromFuture(() -> client.send(new TdApi.GetSupergroupFullInfo(supergroupId)))
                .cast(TdApi.SupergroupFullInfo.class);
    }

    private Mono<Void> ensurePrimaryChannelConfigured(TdApi.Chat chat) {
        log.debug("Ensuring primary channel {} is configured", chat.id);
        return channelService.findOrCreateChannelAndConfig(toChatInfo(chat)).then();
    }

    private ChatDiscoveryService.ChatInfo toChatInfo(TdApi.Chat chat) {
        Long lastMessageId = chat.lastMessage != null ? chat.lastMessage.id : null;
        Instant lastMessageDate = chat.lastMessage != null && chat.lastMessage.date > 0
                ? Instant.ofEpochSecond(chat.lastMessage.date)
                : null;
        return new ChatDiscoveryService.ChatInfo(
                chat.id,
                chat.title,
                chat.type,
                lastMessageId,
                lastMessageDate,
                true,
                chat.permissions != null && chat.permissions.canSendBasicMessages,
                true,
                0
        );
    }

    private boolean isChatNotFoundError(Throwable error) {
        Throwable root = Exceptions.unwrap(error);
        String message = root != null ? root.getMessage() : error.getMessage();
        return message != null && message.contains("Chat not found");
    }

    private Mono<TdApi.Chat> attemptJoinAndFetch(long chatId) {
        log.info("🔔 ATTEMPTING TO JOIN chat {} with all personas because it was not found", chatId);
        return joinAndMuteAllPersonas(chatId)
                .then(fetchChat(chatId))
                .doOnSuccess(chat -> log.info("✅ Successfully joined and fetched chat {} '{}'", chatId, chat.title))
                .doOnError(error -> log.error("❌ Failed to join chat {}: {}", chatId, error.getMessage()));
    }

    private Mono<Void> joinAndMuteAllPersonas(long chatId) {
        return Flux.fromIterable(botInstanceProvider.getInstanceIds())
                .index()
                .concatMap(indexed -> {
                    String personaId = indexed.getT2();
                    TelegramClientFacade client = telegramClientManager.getClient(personaId);
                    if (client == null) {
                        log.warn("No TDLib client for persona={}, skipping join for chat {}", personaId, chatId);
                        return Mono.empty();
                    }
                    Mono<Void> joinMute = joinAndMuteForPersona(chatId, client, personaId);
                    if (indexed.getT1() == 0) {
                        return joinMute;
                    }
                    return Mono.delay(randomInterPersonaDelay()).then(joinMute);
                })
                .then();
    }

    private Mono<Void> joinAndMuteForPersona(long chatId, TelegramClientFacade client, String personaId) {
        log.info("📤 Sending JoinChat for chat {} (persona={})", chatId, personaId);
        return Mono.fromFuture(() -> client.send(new TdApi.JoinChat(chatId)))
                .doOnSuccess(ignored -> log.info("✅ JoinChat succeeded chat={} persona={}", chatId, personaId))
                .onErrorResume(error -> {
                    String msg = error.getMessage();
                    if (msg != null && msg.contains("USER_ALREADY_PARTICIPANT")) {
                        log.info("Persona {} already in chat {}", personaId, chatId);
                        return Mono.empty();
                    }
                    log.warn("JoinChat failed chat={} persona={}: {}", chatId, personaId, msg);
                    return Mono.empty();
                })
                .then(muteForPersona(chatId, client, personaId));
    }

    private Mono<Void> muteForPersona(long chatId, TelegramClientFacade client, String personaId) {
        TdApi.ChatNotificationSettings settings = new TdApi.ChatNotificationSettings();
        settings.muteFor = Integer.MAX_VALUE;
        return Mono.fromFuture(() -> client.send(
                        new TdApi.SetChatNotificationSettings(chatId, settings)))
                .doOnSuccess(ignored -> log.info("🔇 Muted chat {} persona={}", chatId, personaId))
                .onErrorResume(ex -> {
                    log.warn("Mute failed chat={} persona={}: {}", chatId, personaId, ex.getMessage());
                    return Mono.empty();
                })
                .then();
    }

    private Duration randomInterPersonaDelay() {
        long minMs = INTER_PERSONA_DELAY_MIN.toMillis();
        long maxMs = INTER_PERSONA_DELAY_MAX.toMillis();
        return Duration.ofMillis(ThreadLocalRandom.current().nextLong(minMs, maxMs + 1));
    }

    private Instant extractLastMessageInstant(TdApi.Chat chat) {
        if (chat.lastMessage != null && chat.lastMessage.date > 0) {
            return Instant.ofEpochSecond(chat.lastMessage.date);
        }
        return null;
    }

}
