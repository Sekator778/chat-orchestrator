package com.example.telegramuserbot.service.messagesync;

import com.example.telegramuserbot.controller.SyncController.BulkSyncEnableRequest;
import com.example.telegramuserbot.controller.SyncController.BulkSyncResultDto;
import com.example.telegramuserbot.controller.SyncController.ChannelSyncInfoDto;
import com.example.telegramuserbot.dto.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Service interface for orchestrating chat history synchronization operations.
 * Provides high-level sync management with progress tracking and error handling.
 */
public interface SyncOrchestrationService {

    /**
     * Initiates a new chat history sync operation.
     * 
     * @param request the sync request containing channel ID and depth
     * @param initiatorUserId the user ID who requested the sync
     * @return mono containing the created sync job
     */
    Mono<SyncJobDto> initiateSync(SyncRequestDto request, Long initiatorUserId);

    /**
     * Gets the current status of a sync job.
     * 
     * @param jobId the sync job ID
     * @return mono containing the sync job details
     */
    Mono<SyncJobDto> getSyncJobStatus(Long jobId);

    /**
     * Gets real-time progress updates for a sync job.
     * 
     * @param jobId the sync job ID
     * @return flux of progress updates
     */
    Flux<SyncProgressDto> getSyncProgress(Long jobId);

    /**
     * Cancels a running sync job.
     * 
     * @param jobId the sync job ID
     * @param userId the user requesting cancellation
     * @return mono containing the updated job status
     */
    Mono<SyncJobDto> cancelSync(Long jobId, Long userId);

    /**
     * Gets all sync jobs for a specific channel.
     * 
     * @param channelId the channel ID
     * @return mono containing list of sync jobs
     */
    Mono<List<SyncJobDto>> getChannelSyncHistory(Long channelId);

    /**
     * Gets sync jobs created by a specific user.
     * 
     * @param userId the user ID
     * @return mono containing list of sync jobs
     */
    Mono<List<SyncJobDto>> getUserSyncHistory(Long userId);

    /**
     * Gets active (running or pending) sync jobs across all channels.
     * 
     * @return mono containing list of active sync jobs
     */
    Mono<List<SyncJobDto>> getActiveSyncJobs();

    /**
     * Retries a failed sync job.
     * 
     * @param jobId the failed job ID
     * @param userId the user requesting retry
     * @return mono containing the new sync job
     */
    Mono<SyncJobDto> retrySync(Long jobId, Long userId);

    /**
     * Gets sync configuration for a channel.
     * 
     * @param channelId the channel ID
     * @return mono containing sync configuration
     */
    Mono<SyncConfigurationDto> getSyncConfiguration(Long channelId);

    /**
     * Updates sync configuration for a channel.
     * 
     * @param channelId the channel ID
     * @param config the new configuration
     * @return mono containing updated configuration
     */
    Mono<SyncConfigurationDto> updateSyncConfiguration(Long channelId, SyncConfigurationDto config);

    /**
     * Checks for and initiates automatic syncs for channels with auto-sync enabled.
     * This method is typically called by a scheduler.
     * 
     * @return mono containing count of auto-syncs initiated
     */
    Mono<Integer> processAutoSyncs();

    /**
     * Performs maintenance tasks like cleaning up old completed jobs.
     *
     * @return mono containing count of jobs cleaned up
     */
    Mono<Integer> performMaintenance();

    /**
     * Gets available channels for sync from tgscan schema.
     *
     * @param minSubscribers minimum subscriber count filter
     * @param minWeight minimum weight filter
     * @param limit max results
     * @return mono containing list of available channels
     */
    Mono<List<ChannelSyncInfoDto>> getAvailableChannelsForSync(Integer minSubscribers, Double minWeight, Integer limit);

    /**
     * Bulk enable or disable sync for channels.
     *
     * @param request the bulk sync request
     * @return mono containing result of bulk operation
     */
    Mono<BulkSyncResultDto> bulkEnableSync(BulkSyncEnableRequest request);

    /**
     * Toggle sync enabled status for a specific channel.
     *
     * @param channelId the channel ID
     * @param enabled whether to enable or disable sync
     * @return mono containing updated channel sync info
     */
    Mono<ChannelSyncInfoDto> toggleChannelSync(Long channelId, boolean enabled);

    /**
     * Starts a quick scan of a chat's message history by Telegram chat ID.
     * Bypasses sync-enabled configuration checks and auto-detects the bot persona
     * that is a member of the given chat. Trivial messages (≤3 words) are filtered out.
     *
     * @param request the quick scan request containing chatId and depth in days
     * @return mono containing the created sync job
     */
    Mono<SyncJobDto> quickScan(QuickScanRequestDto request);
}