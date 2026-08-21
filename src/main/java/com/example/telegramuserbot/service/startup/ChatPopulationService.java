package com.example.telegramuserbot.service.startup;

import com.example.telegramuserbot.domain.Channel;
import com.example.telegramuserbot.repository.MessageRepository;
import com.example.telegramuserbot.service.ChannelService;
import com.example.telegramuserbot.service.TelegramClientManager;
import com.example.telegramuserbot.telegram.TelegramClientFacade;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

/**
 * Service to populate the channels table with chat information
 * discovered from existing message history.
 */
@Service
public class ChatPopulationService {

    private static final Logger log = LoggerFactory.getLogger(ChatPopulationService.class);

    private final ChannelService channelService;
    private final MessageRepository messageRepository;
    private final TelegramClientManager telegramClientManager;

    public ChatPopulationService(
            ChannelService channelService,
            MessageRepository messageRepository,
            TelegramClientManager telegramClientManager) {
        this.channelService = channelService;
        this.messageRepository = messageRepository;
        this.telegramClientManager = telegramClientManager;
    }

    /**
     * Populates the channels table with chats discovered from message history.
     * This is useful when the database has messages but missing channel records.
     *
     * @return Mono indicating completion with count of populated channels
     */
    public Mono<Integer> populateChannelsFromMessages() {
        log.info("Starting channel population from message history...");
        return messageRepository.findChatIdsNotInChannels()
                .collectList()
                .flatMap(chatIds -> {
                    log.info("Found {} chat IDs in messages that are not in channels table", chatIds.size());
                    return Flux.fromIterable(chatIds)
                            .flatMap(chatId -> populateChannel(chatId)
                                    .doOnError(error -> log.warn("Failed to populate channel for chat ID {}: {}", chatId, error.getMessage()))
                                    .onErrorResume(error -> Mono.empty()))
                            .count()
                            .map(Long::intValue);
                })
                .doOnSuccess(count -> {
                    if (count > 0) {
                        log.info("Successfully populated {} channels from message history", count);
                    } else {
                        log.info("No new channels found to populate from message history");
                    }
                })
                .doOnError(error -> log.error("Failed to populate channels from messages", error));
    }

    /**
     * Populates a specific channel by fetching its information from Telegram API.
     *
     * @param chatId The chat ID to populate
     * @return Mono with the created/updated channel
     */
    public Mono<Channel> populateChannel(Long chatId) {
        log.debug("Populating channel information for chat ID: {}", chatId);

        TelegramClientFacade client = telegramClientManager.getAnyClient();
        if (client == null) {
            log.warn("No Telegram client available to populate channel for chat ID: {}", chatId);
            return Mono.empty();
        }
        return Mono.<TdApi.Chat>create(sink ->
                        client.send(new TdApi.GetChat(chatId), result -> {
                            if (result.isError()) {
                                sink.error(new RuntimeException(result.getError().message));
                            } else {
                                sink.success(result.get());
                            }
                        }))
                .flatMap(chat -> {
                    ChatDiscoveryService.ChatInfo chatInfo = toChatInfo(chat);
                    return channelService.findOrCreateChannelAndConfig(chatInfo);
                })
                .doOnSuccess(channel -> log.debug("Successfully populated channel: {} (ID: {})",
                        channel.getTitle(), channel.getChatId()))
                .onErrorResume(error -> {
                    log.warn("Failed to populate channel for chat ID {}: {}", chatId, error.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * Bulk populate channels for a list of chat IDs.
     *
     * @param chatIds List of chat IDs to populate
     * @return Mono indicating completion with count of populated channels
     */
    public Mono<Integer> populateChannels(List<Long> chatIds) {
        log.info("Starting bulk channel population for {} chat IDs", chatIds.size());

        return Flux.fromIterable(chatIds)
                .flatMap(this::populateChannel, 3)
                .count()
                .map(Long::intValue)
                .doOnSuccess(count -> log.info("Successfully populated {} channels", count));
    }

    private ChatDiscoveryService.ChatInfo toChatInfo(TdApi.Chat chat) {
        Long lastMessageId = chat.lastMessage != null ? chat.lastMessage.id : null;
        Instant lastMessageDate = chat.lastMessage != null ? Instant.ofEpochSecond(chat.lastMessage.date) : null;
        boolean canReadMessages = true; // We were able to fetch the chat, assume accessible
        boolean canSendMessages = chat.permissions != null && chat.permissions.canSendBasicMessages;
        boolean isAccessible = true;
        int memberCount = 0;

        return new ChatDiscoveryService.ChatInfo(
                chat.id,
                chat.title,
                chat.type,
                lastMessageId,
                lastMessageDate,
                canReadMessages,
                canSendMessages,
                isAccessible,
                memberCount
        );
    }
}
