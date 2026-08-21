package com.example.telegramuserbot.service.persistence; // New sub-package

import com.example.telegramuserbot.domain.MessageEntity;
import it.tdlight.jni.TdApi;
import reactor.core.publisher.Mono;

/**
 * Handles the persistence of individual Telegram messages,
 * including triggering asynchronous media processing.
 */
public interface MessagePersistenceService {

    /**
     * Saves a single Telegram message to the database and initiates
     * asynchronous media download if applicable.
     * This method checks for duplicates before saving.
     *
     * @param chatId The ID of the chat the message belongs to.
     * @param message The TdApi.Message object from TDLib.
     * @return A Mono emitting the persisted MessageEntity, or empty if not persisted (e.g., duplicate).
     */
    default Mono<MessageEntity> persistMessage(long chatId, TdApi.Message message) {
        return persistMessage(null, chatId, message);
    }

    /**
     * Same as {@link #persistMessage(long, TdApi.Message)} but allows selecting a Telegram client instance
     * (needed when multiple Telegram accounts run inside one application).
     */
    Mono<MessageEntity> persistMessage(String botInstanceId, long chatId, TdApi.Message message);

    /**
     * Same as {@link #persistMessage(String, long, TdApi.Message)} but bypasses the sync_enabled
     * check in chat_configs. Used by manual scan jobs where the user explicitly requested a sync
     * regardless of the channel's sync configuration.
     */
    Mono<MessageEntity> forcePersistMessage(String botInstanceId, long chatId, TdApi.Message message);

    /**
     * Updates a previously persisted outgoing message when Telegram assigns
     * the final message ID (UpdateMessageSendSucceeded).
     *
     * @param chatId The chat ID that contains the message.
     * @param temporaryMessageId The provisional message ID used during send.
     * @param finalMessage The finalized TdApi.Message instance from TDLib.
     * @return A Mono emitting the updated MessageEntity.
     */
    default Mono<MessageEntity> updateMessageAfterSend(long chatId, long temporaryMessageId, TdApi.Message finalMessage) {
        return updateMessageAfterSend(null, chatId, temporaryMessageId, finalMessage);
    }

    /**
     * Same as {@link #updateMessageAfterSend(long, long, TdApi.Message)} but allows selecting the Telegram client instance.
     */
    Mono<MessageEntity> updateMessageAfterSend(String botInstanceId, long chatId, long temporaryMessageId, TdApi.Message finalMessage);
}
