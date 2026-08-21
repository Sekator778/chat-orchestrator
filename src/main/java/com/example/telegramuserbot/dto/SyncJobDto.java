package com.example.telegramuserbot.dto;

import com.example.telegramuserbot.domain.Channel;
import com.example.telegramuserbot.domain.SyncJob;
import com.example.telegramuserbot.domain.SyncStatus;

import java.time.LocalDateTime;

/**
 * Data Transfer Object for SyncJob entity.
 */
public record SyncJobDto(
        Long id,
        Long channelId,
        String channelTitle,
        SyncStatus status,
        Integer syncDepthDays,
        LocalDateTime syncFromDate,
        LocalDateTime syncToDate,
        Long messagesProcessed,
        Long messagesTotal,
        Double completionPercentage,
        String errorMessage,
        LocalDateTime createdAt,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        Long createdByUserId,
        String botInstanceId
) {
    /**
     * Creates a DTO from a SyncJob entity and its corresponding Channel.
     */
    public static SyncJobDto fromEntity(SyncJob entity, Channel channel) {
        return new SyncJobDto(
                entity.getId(),
                channel.getChatId(),
                channel.getTitle(),
                entity.getStatus(),
                entity.getSyncDepthDays(),
                entity.getSyncFromDate(),
                entity.getSyncToDate(),
                entity.getMessagesProcessed(),
                entity.getMessagesTotal(),
                entity.getCompletionPercentage(),
                entity.getErrorMessage(),
                entity.getCreatedAt(),
                entity.getStartedAt(),
                entity.getCompletedAt(),
                entity.getCreatedByUserId(),
                entity.getBotInstanceId()
        );
    }

    /**
     * Creates a summary version with limited information for listings.
     */
    public static SyncJobDto summaryFromEntity(SyncJob entity, Channel channel) {
        return new SyncJobDto(
                entity.getId(),
                channel.getChatId(),
                channel.getTitle(),
                entity.getStatus(),
                entity.getSyncDepthDays(),
                null,
                null,
                entity.getMessagesProcessed(),
                entity.getMessagesTotal(),
                entity.getCompletionPercentage(),
                entity.getStatus() == SyncStatus.FAILED ? entity.getErrorMessage() : null,
                entity.getCreatedAt(),
                entity.getStartedAt(),
                entity.getCompletedAt(),
                entity.getCreatedByUserId(),
                entity.getBotInstanceId()
        );
    }
}