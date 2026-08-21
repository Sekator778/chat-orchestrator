package com.example.telegramuserbot.scheduler;

import com.example.telegramuserbot.config.BotInstanceProvider;
import com.example.telegramuserbot.domain.TdLibOperationType;
import com.example.telegramuserbot.service.TdLibOperationLockService;
import com.example.telegramuserbot.service.channels.discovery.ChannelDiscoveryCoordinator;
import com.example.telegramuserbot.service.channels.pipeline.ChannelProcessingCoordinator;
import com.example.telegramuserbot.service.maintenance.PrimaryChannelLinkService;
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
 * Periodic scheduler that runs the channel-processing pipeline (Phase 1-3)
 * and then performs a primary-channel link refresh. This provides a single
 * entry point for all channel synchronization work outside of application startup.
 *
 * <p>The scheduler uses distributed locking via {@link TdLibOperationLockService}
 * to prevent concurrent channel sync operations across multiple bot instances.
 * It also verifies TDLib readiness before starting operations.</p>
 */
@Component
public final class ChannelSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(ChannelSyncScheduler.class);

    /**
     * Timeout for the entire scheduled sync operation (30 minutes).
     */
    private static final Duration SCHEDULED_SYNC_TIMEOUT = Duration.ofMinutes(30);

    private final ChannelDiscoveryCoordinator channelDiscoveryCoordinator;
    private final ChannelProcessingCoordinator channelProcessingCoordinator;
    private final PrimaryChannelLinkService primaryChannelLinkService;
    private final BotInstanceProvider botInstanceProvider;
    private final TdLibOperationCoordinator tdLibOperationCoordinator;
    private final TdLibOperationLockService lockService;
    private final int spreadMinutes;

    public ChannelSyncScheduler(ChannelDiscoveryCoordinator channelDiscoveryCoordinator,
                                ChannelProcessingCoordinator channelProcessingCoordinator,
                                PrimaryChannelLinkService primaryChannelLinkService,
                                BotInstanceProvider botInstanceProvider,
                                TdLibOperationCoordinator tdLibOperationCoordinator,
                                TdLibOperationLockService lockService,
                                @Value("${sync.channels.scheduler.spread-minutes:0}") int spreadMinutes) {
        this.channelDiscoveryCoordinator = channelDiscoveryCoordinator;
        this.channelProcessingCoordinator = channelProcessingCoordinator;
        this.primaryChannelLinkService = primaryChannelLinkService;
        this.botInstanceProvider = botInstanceProvider;
        this.tdLibOperationCoordinator = tdLibOperationCoordinator;
        this.lockService = lockService;
        this.spreadMinutes = spreadMinutes;
    }

    /**
     * Runs the channel synchronization pipeline once per day (default 04:00).
     * The cron expression can be overridden via `sync.channels.scheduler.cron`.
     *
     * <p>This method first verifies TDLib is ready and acquires a distributed lock
     * before starting the synchronization pipeline.</p>
     */
    @Scheduled(cron = "${sync.channels.scheduler.cron:0 0 4 * * *}")
    public void synchronizeChannelsOnSchedule() {
        log.info("📅 CHANNEL SYNC[{}]: Starting scheduled channel processing pipeline run",
                botInstanceProvider.getInstanceId());

        Mono<ChannelProcessingCoordinator.PipelineResult> pipeline = checkTdLibReadiness()
                .then(lockService.tryAcquireLock(TdLibOperationType.CHANNEL_SYNC_SCHEDULED, null, SCHEDULED_SYNC_TIMEOUT))
                .flatMap(lock -> executeChannelSyncPipeline()
                        .doFinally(signal -> lockService.releaseLock(lock).subscribe()))
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("📅 CHANNEL SYNC[{}]: Skipping - TDLib not ready or another sync is in progress",
                            botInstanceProvider.getInstanceId());
                    return Mono.just(new ChannelProcessingCoordinator.PipelineResult(0, 0, 0, Duration.ZERO));
                }));

        scheduleWithInstanceOffset(pipeline)
                .subscribe(
                        result -> log.info("📅 CHANNEL SYNC[{}]: Pipeline finished (phase1={}, phase2={}, phase3={}, duration={})",
                                botInstanceProvider.getInstanceId(),
                                result.phase1Count(), result.phase2Count(), result.phase3Count(), result.duration()),
                        error -> log.error("📅 CHANNEL SYNC[{}]: Scheduled pipeline run failed",
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
                        log.debug("📅 CHANNEL SYNC: TDLib is ready for sync operations");
                        return Mono.just(true);
                    }
                    log.warn("📅 CHANNEL SYNC: TDLib is not ready, skipping scheduled sync");
                    return Mono.empty();
                });
    }

    /**
     * Executes the channel sync pipeline (discovery + processing + link refresh).
     */
    private Mono<ChannelProcessingCoordinator.PipelineResult> executeChannelSyncPipeline() {
        return channelDiscoveryCoordinator.discoverAndPopulateChats()
                .subscribeOn(Schedulers.boundedElastic())
                .doOnSuccess(summary -> log.info("📅 CHANNEL SYNC: Discovery finished (channels={}, failures={}, duration={})",
                        summary.channelsProcessed(), summary.failures(), summary.duration()))
                .onErrorResume(error -> {
                    log.error("📅 CHANNEL SYNC: Discovery failed", error);
                    return Mono.empty();
                })
                .then(channelProcessingCoordinator.processPendingChannels()
                        .subscribeOn(Schedulers.boundedElastic())
                        .flatMap(result -> primaryChannelLinkService.refreshPrimaryChannelLinks()
                                .doOnSuccess(updated -> log.info("📅 CHANNEL SYNC: Primary link refresh completed: {} configs updated", updated))
                                .onErrorResume(error -> {
                                    log.error("📅 CHANNEL SYNC: Primary link refresh failed", error);
                                    return Mono.empty();
                                })
                                .thenReturn(result)));
    }

    private <T> Mono<T> scheduleWithInstanceOffset(Mono<T> pipeline) {
        Duration offset = computeOffset(spreadMinutes);
        if (offset.isZero()) {
            return pipeline;
        }
        log.info("📅 CHANNEL SYNC[{}]: Applying instance offset of {} before execution",
                botInstanceProvider.getInstanceId(), offset);
        return Mono.delay(offset).then(pipeline);
    }

    private Duration computeOffset(int spreadMinutes) {
        if (spreadMinutes <= 0) {
            return Duration.ZERO;
        }
        int slot = Math.floorMod(botInstanceProvider.getInstanceId().hashCode(), spreadMinutes);
        return Duration.ofMinutes(slot);
    }
}
