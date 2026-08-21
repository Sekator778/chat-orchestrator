package com.example.telegramuserbot.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record MessagePurgeResultDto(
        @JsonProperty("chat_id") long chatId,
        @JsonProperty("message_count_before") long messageCountBefore,
        @JsonProperty("deleted_messages") long deletedMessages
) {
}

