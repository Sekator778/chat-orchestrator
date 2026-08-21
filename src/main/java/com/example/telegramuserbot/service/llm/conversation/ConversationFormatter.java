package com.example.telegramuserbot.service.llm.conversation;

import com.example.telegramuserbot.domain.MessageEntity;
import com.example.telegramuserbot.service.llm.dto.ApiMessage;

import java.util.List;

/**
 * Formats conversation messages for LLM API calls.
 *
 * Converts a sequence of MessageEntity objects into a normalized list of ApiMessage
 * objects suitable for sending to the LLM API, along with speaker context metadata.
 */
public interface ConversationFormatter {

    /**
     * Result of formatting a conversation for LLM consumption.
     *
     * @param messages the formatted API messages ready for LLM call
     * @param speakerContext metadata about conversation participants
     */
    record FormatResult(List<ApiMessage> messages, LlmSpeakerContext speakerContext) { }

    /**
     * Formats conversation messages for LLM API consumption.
     *
     * @param contextMessages previous messages in the conversation for context
     * @param triggeringMessage the message that triggered the response
     * @param botInstanceId identifier for the bot instance
     * @param selfTelegramUserId the Telegram user ID of the bot (for identifying own messages)
     * @return formatted result with API messages and speaker context
     */
    FormatResult format(List<MessageEntity> contextMessages,
                        MessageEntity triggeringMessage,
                        String botInstanceId,
                        Long selfTelegramUserId);

    /**
     * Formats conversation messages with media placeholder support.
     *
     * @param contextMessages previous messages in the conversation for context
     * @param triggeringMessage the message that triggered the response
     * @param botInstanceId identifier for the bot instance
     * @param selfTelegramUserId the Telegram user ID of the bot (for identifying own messages)
     * @param includeMediaPlaceholders whether to include media type placeholders in content
     * @return formatted result with API messages and speaker context
     */
    FormatResult format(List<MessageEntity> contextMessages,
                        MessageEntity triggeringMessage,
                        String botInstanceId,
                        Long selfTelegramUserId,
                        boolean includeMediaPlaceholders);
}
