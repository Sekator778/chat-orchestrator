package com.example.telegramuserbot.service.messagesync;

import com.example.telegramuserbot.domain.SyncJob;
import com.example.telegramuserbot.dto.SyncProgressDto;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Service interface for executing the actual sync operations.
 * This service handles the low-level details of fetching and storing message history.
 */
public interface SyncExecutionService {

    /**
     * Executes a sync job, fetching messages from Telegram and storing them.
     *
     * @param job the sync job to execute
     * @return a Flux that emits progress updates and completes when the sync is finished.
     */
    Flux<SyncProgressDto> executeSync(SyncJob job);

    /**
     * Requests cancellation of a running sync job.
     *
     * @param jobId the job ID to cancel
     * @return a Mono that completes when the cancellation signal has been processed.
     */
    Mono<Void> cancelSync(Long jobId);
}