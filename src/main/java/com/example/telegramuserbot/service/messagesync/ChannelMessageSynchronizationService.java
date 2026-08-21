package com.example.telegramuserbot.service.messagesync;

import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

/**
 * Synchronizes historical messages for channels where automatic sync is enabled.
 * Used during startup (and optionally manual triggers) to keep the local database current.
 */
public interface ChannelMessageSynchronizationService {

    /**
     * Launches synchronization jobs for all channels flagged for auto sync.
     *
     * @return Mono with summary of the performed operations
     */
    Mono<MessageSyncSummary> synchronizeAutoSyncChannels();

    /**
     * Returns summary of the most recent synchronization run, if any.
     */
    Mono<MessageSyncSummary> getLastSummary();

    /**
     * Checks if a channel is marked for automatic synchronization.
     *
     * @param chatId Telegram chat ID
     * @return Mono emitting true if auto sync is enabled for the channel
     */
    Mono<Boolean> isChannelMarkedForSync(Long chatId);

    /**
     * Immutable summary of a synchronization run.
     */
    record MessageSyncSummary(
            int autoSyncChannels,
            int syncJobsAttempted,
            int syncJobsSucceeded,
            int syncJobsFailed,
            Duration duration,
            List<Long> failedChatIds
    ) {}
}
