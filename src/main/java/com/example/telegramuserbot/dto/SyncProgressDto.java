package com.example.telegramuserbot.dto;

import com.example.telegramuserbot.domain.SyncStatus;

/**
 * Real-time progress information for a sync operation.
 */
public record SyncProgressDto(
        Long jobId,
        Long channelId,
        SyncStatus status,
        Long messagesProcessed,
        Long messagesTotal,
        Double completionPercentage,
        String currentAction,
        String errorMessage,
        Long estimatedTimeRemainingSeconds
) {
    /**
     * Creates a progress DTO for a job that just started.
     */
    public static SyncProgressDto started(Long jobId, Long channelId) {
        return new SyncProgressDto(
                jobId,
                channelId,
                SyncStatus.IN_PROGRESS,
                0L,
                null,
                null,
                "Ініціалізація синхронізації...",
                null,
                null
        );
    }

    /**
     * Creates a progress DTO with current processing information.
     */
    public static SyncProgressDto processing(Long jobId, Long channelId, Long processed, Long total, String action) {
        Double percentage = total != null && total > 0 ? (processed.doubleValue() / total.doubleValue()) * 100.0 : null;
        
        Long estimatedRemaining = null;
        if (percentage != null && percentage > 0 && processed > 0) {
            // Simple estimation based on current rate
            long totalEstimated = (long) (processed / (percentage / 100.0));
            estimatedRemaining = (totalEstimated - processed) * 2; // Rough estimate in seconds
        }
        
        return new SyncProgressDto(
                jobId,
                channelId,
                SyncStatus.IN_PROGRESS,
                processed,
                total,
                percentage,
                action,
                null,
                estimatedRemaining
        );
    }

    /**
     * Creates a progress DTO for a completed job.
     */
    public static SyncProgressDto completed(Long jobId, Long channelId, Long totalProcessed) {
        return new SyncProgressDto(
                jobId,
                channelId,
                SyncStatus.COMPLETED,
                totalProcessed,
                totalProcessed,
                100.0,
                "Синхронізацію завершено",
                null,
                0L
        );
    }

    /**
     * Creates a progress DTO for a failed job.
     */
    public static SyncProgressDto failed(Long jobId, Long channelId, String errorMessage) {
        return new SyncProgressDto(
                jobId,
                channelId,
                SyncStatus.FAILED,
                null,
                null,
                null,
                "Синхронізація завершилась помилкою",
                errorMessage,
                null
        );
    }
}