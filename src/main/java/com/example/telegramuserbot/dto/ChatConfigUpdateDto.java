package com.example.telegramuserbot.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * DTO for updating basic chat configuration settings
 */
public record ChatConfigUpdateDto(
        @JsonProperty("prompt_template") String promptTemplate,
        Boolean enabled,
        @JsonProperty("max_daily_messages") Integer maxDailyMessages,
        Integer maxTokens,
        Double temperature,
        String language,
        @JsonProperty("primary_channel_id") Long primaryChannelId,
        @JsonProperty("context_window_size") Integer contextWindowSize,
        @JsonProperty("respond_to_forwarded_bot_messages") Boolean respondToForwardedBotMessages,
        @JsonProperty("multi_stage_enabled") Boolean multiStageEnabled
) {
    /**
     * Check if this update DTO contains any actual updates
     */
    public boolean hasUpdates() {
        return promptTemplate != null || enabled != null || maxDailyMessages != null
               || maxTokens != null || temperature != null || language != null
               || primaryChannelId != null || contextWindowSize != null
               || respondToForwardedBotMessages != null || multiStageEnabled != null;
    }
}
