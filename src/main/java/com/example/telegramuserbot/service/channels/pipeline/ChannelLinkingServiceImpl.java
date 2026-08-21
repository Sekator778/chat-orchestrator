package com.example.telegramuserbot.service.channels.pipeline;

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
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * Implementation of Phase 2: Channel Linking.
 * Resolves channel-discussion relationships via TDLib and persists them.
 */
@Service
public final class ChannelLinkingServiceImpl implements ChannelLinkingService {

    private static final Logger log = LoggerFactory.getLogger(ChannelLinkingServiceImpl.class);

    private final ChatConfigRepository chatConfigRepository;
    private final TelegramClientManager telegramClientManager;
    private final ChannelService channelService;
    private final ProblematicChatService problematicChatService;

    public ChannelLinkingServiceImpl(ChatConfigRepository chatConfigRepository,
                                     TelegramClientManager telegramClientManager,
                                     ChannelService channelService,
                                     ProblematicChatService problematicChatService) {
        this.chatConfigRepository = chatConfigRepository;
        this.telegramClientManager = telegramClientManager;
        this.channelService = channelService;
        this.problematicChatService = problematicChatService;
    }

    @Override
    public Mono<Boolean> processChannel(ChatConfig config) {
        Long tdlibChatId = config.getChannelId();
        log.debug("Phase 2 (Linking): Processing channel {} (ID: {})", tdlibChatId, config.getId());

        return fetchChat(tdlibChatId)
                .flatMap(chat -> {
                    boolean isChannel = isChannel(chat);
                    return resolveLinkedChannel(chat)
                            .flatMap(linkedChat -> {
                                if (isChannel) {
                                    // Current channel is a channel, linkedChat is its discussion group
                                    log.info("Phase 2: Found discussion group {} for channel {}",
                                            linkedChat.id, config.getChannelId());
                                    return ensurePrimaryChannelConfigured(linkedChat)
                                            .then(linkDiscussionToPrimaryChannel(linkedChat.id, config.getChannelId()))
                                            .then(markChecked(config.getId(), config.getChannelId()))
                                            .thenReturn(true);
                                } else {
                                    // Current channel is a discussion group, linkedChat is its primary channel
                                    log.info("Phase 2: Found primary channel {} for discussion {}",
                                            linkedChat.id, config.getChannelId());
                                    return ensurePrimaryChannelConfigured(linkedChat)
                                            .then(updatePrimaryChannel(config, linkedChat.id))
                                            .thenReturn(true);
                                }
                            })
                            .switchIfEmpty(markChecked(config.getId(), config.getChannelId()).thenReturn(true));
                })
                .onErrorResume(error -> {
                    log.warn("Phase 2 failed for channel {}: {}", config.getChannelId(), error.getMessage());
                    if (isChatNotFoundError(error)) {
                        return problematicChatService.markProblematic(config.getChannelId(),
                                        ProblematicChatReason.CHAT_NOT_FOUND, error.getMessage())
                                .then(markChecked(config.getId(), config.getChannelId()))
                                .thenReturn(false);
                    }
                    return Mono.just(false);
                });
    }

    private Mono<Integer> updatePrimaryChannel(ChatConfig config, long primaryChannelId) {
        Instant now = Instant.now();
        return chatConfigRepository.updatePrimaryChannelLink(config.getId(), primaryChannelId, now)
                .doOnSuccess(updatedRows -> {
                    if (updatedRows != null && updatedRows > 0) {
                        log.info("Phase 2: Linked chat config {} -> primary channel {}",
                                config.getId(), primaryChannelId);
                    }
                })
                .defaultIfEmpty(0);
    }

    private Mono<Void> linkDiscussionToPrimaryChannel(long discussionChatId, long primaryChannelId) {
        Instant now = Instant.now();
        return chatConfigRepository.findByChannelChatId(discussionChatId)
                .flatMap(discussionConfig ->
                        chatConfigRepository.updatePrimaryChannelLink(discussionConfig.getId(), primaryChannelId, now)
                                .doOnSuccess(updatedRows -> {
                                    if (updatedRows != null && updatedRows > 0) {
                                        log.info("Phase 2: Linked discussion {} -> primary channel {}",
                                                discussionChatId, primaryChannelId);
                                    }
                                })
                )
                .then();
    }

    private Mono<Void> markChecked(Long configId, Long channelId) {
        return chatConfigRepository.updatePrimaryChannelCheckedAt(configId, Instant.now())
                .doOnSuccess(ignored -> log.debug("Phase 2: Marked chat {} as checked", channelId))
                .then();
    }

    private Mono<TdApi.Chat> fetchChat(long chatId) {
        TelegramClientFacade telegramClient = telegramClientManager.getAnyClient();
        if (telegramClient == null) {
            return Mono.error(new IllegalStateException("No Telegram client available"));
        }
        return Mono.fromFuture(() -> telegramClient.send(new TdApi.GetChat(chatId)))
                .cast(TdApi.Chat.class);
    }

    private Mono<TdApi.Chat> resolveLinkedChannel(TdApi.Chat chat) {
        if (!(chat.type instanceof TdApi.ChatTypeSupergroup supergroup)) {
            return Mono.empty();
        }

        return fetchSupergroupInfo(supergroup.supergroupId)
                .flatMap(info -> {
                    long linkedChatId = info.linkedChatId;
                    if (linkedChatId == 0) {
                        return Mono.empty();
                    }
                    return fetchChat(linkedChatId);
                });
    }

    private Mono<TdApi.SupergroupFullInfo> fetchSupergroupInfo(long supergroupId) {
        TelegramClientFacade telegramClient = telegramClientManager.getAnyClient();
        if (telegramClient == null) {
            return Mono.error(new IllegalStateException("No Telegram client available"));
        }
        return Mono.fromFuture(() -> telegramClient.send(new TdApi.GetSupergroupFullInfo(supergroupId)))
                .cast(TdApi.SupergroupFullInfo.class);
    }

    private Mono<Void> ensurePrimaryChannelConfigured(TdApi.Chat chat) {
        return channelService.findOrCreateChannelAndConfig(toChatInfo(chat)).then();
    }

    private ChatDiscoveryService.ChatInfo toChatInfo(TdApi.Chat chat) {
        Long lastMessageId = chat.lastMessage != null ? chat.lastMessage.id : null;
        Instant lastMessageDate = chat.lastMessage != null && chat.lastMessage.date > 0
                ? Instant.ofEpochSecond(chat.lastMessage.date)
                : null;
        boolean canSendMessages = chat.permissions != null && chat.permissions.canSendBasicMessages;
        return new ChatDiscoveryService.ChatInfo(
                chat.id,
                chat.title,
                chat.type,
                lastMessageId,
                lastMessageDate,
                true,
                canSendMessages,
                true,
                0
        );
    }

    private boolean isChannel(TdApi.Chat chat) {
        if (chat.type instanceof TdApi.ChatTypeSupergroup supergroup) {
            return supergroup.isChannel;
        }
        return false;
    }

    private boolean isChatNotFoundError(Throwable error) {
        Throwable root = Exceptions.unwrap(error);
        String message = root != null ? root.getMessage() : error.getMessage();
        return message != null && message.contains("Chat not found");
    }
}
