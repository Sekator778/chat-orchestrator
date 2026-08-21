package com.example.telegramuserbot.service.startup;

import it.tdlight.jni.TdApi;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

/**
 * Service for discovering chats where the bot is present using Telegram API.
 * Used during application startup to identify all chats that need synchronization.
 */
public interface ChatDiscoveryService {
    
    /**
     * Discovers all chats where the bot is present (private chats, groups, channels).
     * This includes chats where the bot is a member or has access to messages.
     * 
     * @return Flux of chat information containing chat details
     */
    Flux<ChatInfo> discoverAvailableChats();
    
    /**
     * Gets detailed information about a specific chat including last message date
     * and member status.
     * 
     * @param chatId The chat ID to get information for
     * @return Mono with detailed chat information
     */
    Mono<ChatInfo> getChatDetails(Long chatId);
    
    /**
     * Checks if the bot has access to read messages in the specified chat.
     * Some chats may be visible but not accessible for message reading.
     * 
     * @param chatId The chat ID to check access for
     * @return Mono with boolean indicating if messages can be read
     */
    Mono<Boolean> canReadMessagesInChat(Long chatId);
    Mono<Boolean> canSendMessagesInChat(Long chatId);
    
    /**
     * Gets the bot's own user information from Telegram API.
     * Used to determine bot identity and permissions.
     * 
     * @return Mono with bot's user information
     */
    Mono<TdApi.User> getBotUserInfo();
    
    /**
     * Information about a discovered chat including synchronization metadata.
     */
    record ChatInfo(
            Long chatId,
            String title,
            TdApi.ChatType chatType,
            Long lastMessageId,
            Instant lastMessageDate,
            boolean canReadMessages,
            boolean canSendMessages,
            boolean isAccessible,
            int memberCount
    ) {}
}
