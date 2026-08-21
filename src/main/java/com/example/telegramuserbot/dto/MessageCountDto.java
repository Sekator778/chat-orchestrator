package com.example.telegramuserbot.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record MessageCountDto(
        @JsonProperty("chat_id") long chatId,
        @JsonProperty("message_count") long messageCount
) {
}

