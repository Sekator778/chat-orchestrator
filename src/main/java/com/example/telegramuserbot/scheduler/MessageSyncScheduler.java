package com.example.telegramuserbot.scheduler;

import com.example.telegramuserbot.config.BotInstanceProvider;
import com.example.telegramuserbot.domain.TdLibOperationType;
import com.example.telegramuserbot.service.TdLibOperationLockService;
import com.example.telegramuserbot.service.messagesync.ChannelMessageSynchronizationService;
import com.example.telegramuserbot.telegram.TdLibOperationCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;

/**
 * Periodic scheduler that batches message synchronization jobs for channels that
 * have auto-sync enabled. Uses the unified ChannelMessageSynchronizationService
 * so that all decisions happen through a single entry point.
 *
 * <p>The scheduler uses distributed locking via {@link TdLibOperationLockService}
 * to prevent concurrent message sync operations across multiple bot instances.
 * It also verifies TDLib readiness before starting operations.</p>
 */
@Component
public final class MessageSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(MessageSyncScheduler.class);

    /**
     * Timeout for the message sync scheduler operation (60 minutes).
     * Message sync can take a long time for channels with extensive history.
     */
    private static final Duration MESSAGE_SYNC_TIMEOUT = Duration.ofMinutes(60);

    private final ChannelMessageSynchronizationService channelMessageSynchronizationService;
    private final BotInstanceProvider botInstanceProvider;
    private final TdLibOperationCoordinator tdLibOperationCoordinator;
    private final TdLibOperationLockService lockService;
    private final int spreadMinutes;

    public MessageSyncScheduler(ChannelMessageSynchronizationService channelMessageSynchronizationService,
                                BotInstanceProvider botInstanceProvider,
                                TdLibOperationCoordinator tdLibOperationCoordinator,
                                TdLibOperationLockService lockService,
                                @Value("${sync.messages.scheduler.spread-minutes:0}") int spreadMinutes) {
        this.channelMessageSynchronizationService = channelMessageSynchronizationService;
        this.botInstanceProvider = botInstanceProvider;
        this.tdLibOperationCoordinator = tdLibOperationCoordinator;
        this.lockService = lockService;
        this.spreadMinutes = spreadMinutes;
    }

    /**
     * Triggers message synchronization every five hours (default) after a short initial delay.
     * The schedule is configurable via properties:
     * - sync.messages.scheduler.initial-delay-ms (default 60s)
     * - sync.messages.scheduler.rate-ms (default 5h)
     *
     * <p>This method first verifies TDLib is ready and acquires a distributed lock
     * before starting the synchronization.</p>
     */
    @Scheduled(
            initialDelayString = "${sync.messages.scheduler.initial-delay-ms:60000}",
            fixedRateString = "${sync.messages.scheduler.rate-ms:18000000}"
    )
    public void synchronizeMessagesOnSchedule() {
        log.info("📅 MESSAGE SYNC[{}]: Starting scheduled message synchronization run",
                botInstanceProvider.getInstanceId());

        Mono<ChannelMessageSynchronizationService.MessageSyncSummary> task = checkTdLibReadiness()
                .then(lockService.tryAcquireLock(TdLibOperationType.MESSAGE_SYNC, null, MESSAGE_SYNC_TIMEOUT))
                .flatMap(lock -> channelMessageSynchronizationService.synchronizeAutoSyncChannels()
                        .subscribeOn(Schedulers.boundedElastic())
                        .doOnSuccess(summary -> log.info("📅 MESSAGE SYNC[{}]: Completed run (autoSyncConfigs={}, attempted={}, failed={}, duration={})",
                                botInstanceProvider.getInstanceId(),
                                summary.autoSyncChannels(),
                                summary.syncJobsAttempted(),
                                summary.syncJobsFailed(),
                                summary.duration()))
                        .doFinally(signal -> lockService.releaseLock(lock).subscribe()))
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("📅 MESSAGE SYNC[{}]: Skipping - TDLib not ready or another sync is in progress",
                            botInstanceProvider.getInstanceId());
                    return Mono.just(new ChannelMessageSynchronizationService.MessageSyncSummary(
                            0, 0, 0, 0, Duration.ZERO, java.util.List.of()));
                }));

        scheduleWithInstanceOffset(task)
                .subscribe(
                        unused -> { },
                        error -> log.error("📅 MESSAGE SYNC[{}]: Scheduled run failed",
                                botInstanceProvider.getInstanceId(), error)
                );
    }

    /**
     * Checks if TDLib is ready before starting sync operations.
     *
     * @return a Mono that completes if TDLib is ready, or empty if not
     */
    private Mono<Boolean> checkTdLibReadiness() {
        return tdLibOperationCoordinator.isTdLibReady()
                .flatMap(ready -> {
                    if (ready) {
                        log.debug("📅 MESSAGE SYNC: TDLib is ready for sync operations");
                        return Mono.just(true);
                    }
                    log.warn("📅 MESSAGE SYNC: TDLib is not ready, skipping scheduled sync");
                    return Mono.empty();
                });
    }

    private <T> Mono<T> scheduleWithInstanceOffset(Mono<T> mono) {
        Duration offset = computeOffset(spreadMinutes);
        if (offset.isZero()) {
            return mono;
        }
        log.info("📅 MESSAGE SYNC[{}]: Applying instance offset of {} before execution",
                botInstanceProvider.getInstanceId(), offset);
        return Mono.delay(offset).then(mono);
    }

    private Duration computeOffset(int spreadMinutes) {
        if (spreadMinutes <= 0) {
            return Duration.ZERO;
        }
        int slot = Math.floorMod(botInstanceProvider.getInstanceId().hashCode(), spreadMinutes);
        return Duration.ofMinutes(slot);
    }
}
