package com.example.telegramuserbot.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RateLimitsDto(
        Long id,
        @JsonProperty("chat_config_id") Long chatConfigId,
        @JsonProperty("max_messages_per_minute") Integer maxMessagesPerMinute,
        @JsonProperty("max_messages_per_hour") Integer maxMessagesPerHour,
        @JsonProperty("max_messages_per_day") Integer maxMessagesPerDay,
        @JsonProperty("current_daily_messages") Integer currentDailyMessages,
        @JsonProperty("max_tokens_per_day") Integer maxTokensPerDay,
        @JsonProperty("pending_response_delay_seconds") Integer pendingResponseDelaySeconds,
        @JsonProperty("cooldown_after_limit_minutes") Integer cooldownAfterLimitMinutes,
        @JsonProperty("burst_limit") Integer burstLimit,
        @JsonProperty("burst_window_seconds") Integer burstWindowSeconds,
        @JsonProperty("user_specific_limits") boolean userSpecificLimits
) {
    // Factory method to create DTO from entity
    public static RateLimitsDto fromEntity(com.example.telegramuserbot.domain.RateLimits limits) {
        return new RateLimitsDto(
                limits.getId(),
                limits.getChatConfigId(),
                limits.getMaxMessagesPerMinute(),
                limits.getMaxMessagesPerHour(),
                limits.getMaxMessagesPerDay(),
                limits.getCurrentDailyMessages(),
                limits.getMaxTokensPerDay(),
                limits.getPendingResponseDelaySeconds(),
                limits.getCooldownAfterLimitMinutes(),
                limits.getBurstLimit(),
                limits.getBurstWindowSeconds(),
                limits.isUserSpecificLimits()
        );
    }

    // Factory method for creation with defaults
    public static RateLimitsDto withDefaults(Long chatConfigId) {
        return new RateLimitsDto(
                null, chatConfigId, null, 20, 100, 0, 50000, 0, 60, 3, 60, false
        );
    }
}
