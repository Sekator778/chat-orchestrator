package com.example.telegramuserbot.dto;

import com.example.telegramuserbot.domain.Channel;
import com.example.telegramuserbot.domain.SyncConfiguration;
import java.time.LocalDateTime;

/**
 * Data Transfer Object for SyncConfiguration entity.
 */
public record SyncConfigurationDto(
        Long id,
        Long channelId,
        String channelTitle,
        Integer defaultSyncDepthDays,
        Integer maxSyncDepthDays,
        boolean autoSyncEnabled,
        Integer autoSyncIntervalDays,
        LocalDateTime lastAutoSyncAt,
        Integer maxConcurrentSyncs,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    /**
     * Creates a DTO from a SyncConfiguration entity.
     */
    public static SyncConfigurationDto fromEntity(SyncConfiguration entity, Channel channel) {
        return new SyncConfigurationDto(
                entity.getId(),
                entity.getChannelId(),
                channel.getTitle(),
                entity.getDefaultSyncDepthDays(),
                entity.getMaxSyncDepthDays(),
                entity.isAutoSyncEnabled(),
                entity.getAutoSyncIntervalDays(),
                entity.getLastAutoSyncAt(),
                entity.getMaxConcurrentSyncs(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
