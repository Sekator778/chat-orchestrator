package com.example.telegramuserbot.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ContextSettingsDto(
        Long id,
        @JsonProperty("chat_config_id") Long chatConfigId,
        @JsonProperty("history_message_count") Integer historyMessageCount,
        @JsonProperty("history_time_window_hours") Integer historyTimeWindowHours,
        @JsonProperty("include_user_context") boolean includeUserContext,
        @JsonProperty("include_media_descriptions") boolean includeMediaDescriptions,
        @JsonProperty("context_compression_enabled") boolean contextCompressionEnabled,
        @JsonProperty("max_context_tokens") Integer maxContextTokens,
        @JsonProperty("preserve_important_messages") boolean preserveImportantMessages
) {
    // Factory method to create DTO from entity
    public static ContextSettingsDto fromEntity(com.example.telegramuserbot.domain.ContextSettings settings) {
        return new ContextSettingsDto(
                settings.getId(),
                settings.getChatConfigId(),
                settings.getHistoryMessageCount(),
                settings.getHistoryTimeWindowHours(),
                settings.isIncludeUserContext(),
                settings.isIncludeMediaDescriptions(),
                settings.isContextCompressionEnabled(),
                settings.getMaxContextTokens(),
                settings.isPreserveImportantMessages()
        );
    }

    // Factory method for creation with defaults
    public static ContextSettingsDto withDefaults(Long chatConfigId) {
        return new ContextSettingsDto(
                null, chatConfigId, 10, 24, true, true, false, 2000, true
        );
    }
}