package com.example.telegramuserbot.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record MessagePurgeRequestDto(
        @JsonProperty("chat_id") long chatId,
        @JsonProperty("confirm_chat_id") long confirmChatId
) {
}

