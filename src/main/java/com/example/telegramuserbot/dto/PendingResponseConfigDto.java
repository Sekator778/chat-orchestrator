package com.example.telegramuserbot.dto;

import com.example.telegramuserbot.domain.ChatConfig;
import com.example.telegramuserbot.domain.RateLimits;
import com.fasterxml.jackson.annotation.JsonProperty;

public record PendingResponseConfigDto(
        @JsonProperty("wait_for_human_replies_count") Integer waitForHumanRepliesCount,
        @JsonProperty("pending_response_delay_seconds") Integer pendingResponseDelaySeconds
) {
    public static PendingResponseConfigDto fromEntities(ChatConfig chatConfig, RateLimits rateLimits) {
        Integer delaySeconds = rateLimits != null ? rateLimits.getPendingResponseDelaySeconds() : null;
        return new PendingResponseConfigDto(
                chatConfig.getWaitForHumanRepliesCount(),
                delaySeconds
        );
    }
}
