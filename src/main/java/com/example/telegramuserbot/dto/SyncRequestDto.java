package com.example.telegramuserbot.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Request DTO for initiating a chat history sync operation.
 */
public record SyncRequestDto(
        @NotNull(message = "Channel ID is required")
        Long channelId,
        
        @NotNull(message = "Sync depth in days is required")
        @Min(value = 1, message = "Sync depth must be at least 1 day")
        @Max(value = 1095, message = "Sync depth cannot exceed 3 years (1095 days)")
        Integer syncDepthDays,
        
        /**
         * Optional: force sync even if another sync is in progress for this channel.
         * Default is false for safety.
         */
        Boolean forceSync
) {
    public SyncRequestDto {
        if (forceSync == null) {
            forceSync = false;
        }
    }

    /**
     * Factory method for creating a sync request with default force sync setting.
     */
    public static SyncRequestDto create(Long channelId, Integer syncDepthDays) {
        return new SyncRequestDto(channelId, syncDepthDays, false);
    }
}