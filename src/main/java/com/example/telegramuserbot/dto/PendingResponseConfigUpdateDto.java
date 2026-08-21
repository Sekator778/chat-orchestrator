package com.example.telegramuserbot.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record PendingResponseConfigUpdateDto(
        @JsonProperty("wait_for_human_replies_count") Integer waitForHumanRepliesCount,
        @JsonProperty("pending_response_delay_seconds") Integer pendingResponseDelaySeconds
) {
    public boolean hasUpdates() {
        return waitForHumanRepliesCount != null || pendingResponseDelaySeconds != null;
    }
}

