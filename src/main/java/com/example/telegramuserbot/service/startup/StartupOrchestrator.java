package com.example.telegramuserbot.service.startup;

import com.example.telegramuserbot.config.BotInstanceProvider;
import com.example.telegramuserbot.service.TdLibOperationLockService;
import com.example.telegramuserbot.service.TelegramClientManager;
import com.example.telegramuserbot.service.channels.discovery.ChannelDiscoveryCoordinator;
import com.example.telegramuserbot.service.channels.pipeline.ChannelProcessingCoordinator;
import com.example.telegramuserbot.service.messagesync.ChannelMessageSynchronizationService;
import com.example.telegramuserbot.telegram.TdLibOperationCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;

/**
 * Central orchestrator for application startup sequence.
 * Ensures proper initialization order:
 * 1. Full chat synchronization (discover all chats)
 * 2. Channel processing pipeline (3-phase: Ingestion → Linking → Template Application)
 *
 * <p>This orchestrator waits for TDLib to be ready before starting the initialization
 * pipeline, and uses distributed locking to prevent concurrent startup sequences
 * across multiple bot instances.</p>
 *
 * This replaces the event-driven approach with a simple, explicit sequential flow.
 */
@Component
public final class StartupOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(StartupOrchestrator.class);

    /**
     * Interval for checking TDLib readiness during startup.
     */
    private static final Duration TDLIB_READINESS_CHECK_INTERVAL = Duration.ofSeconds(5);

    /**
     * Maximum time to wait for TDLib to become ready.
     */
    private static final Duration TDLIB_READINESS_TIMEOUT = Duration.ofMinutes(5);

    private final ChannelDiscoveryCoordinator channelDiscoveryCoordinator;
    private final ChannelMessageSynchronizationService channelMessageSynchronizationService;
    private final ChannelProcessingCoordinator channelProcessingCoordinator;
    private final BotInstanceProvider botInstanceProvider;
    private final TdLibOperationCoordinator tdLibOperationCoordinator;
    private final TdLibOperationLockService lockService;
    private final TelegramClientManager telegramClientManager;

    private volatile boolean startupCompleted = false;
    private volatile String orchestratedInstanceId;

    public StartupOrchestrator(
            ChannelDiscoveryCoordinator channelDiscoveryCoordinator,
            ChannelMessageSynchronizationService channelMessageSynchronizationService,
            ChannelProcessingCoordinator channelProcessingCoordinator,
            BotInstanceProvider botInstanceProvider,
            TdLibOperationCoordinator tdLibOperationCoordinator,
            TdLibOperationLockService lockService,
            TelegramClientManager telegramClientManager
    ) {
        this.channelDiscoveryCoordinator = channelDiscoveryCoordinator;
        this.channelMessageSynchronizationService = channelMessageSynchronizationService;
        this.channelProcessingCoordinator = channelProcessingCoordinator;
        this.botInstanceProvider = botInstanceProvider;
        this.tdLibOperationCoordinator = tdLibOperationCoordinator;
        this.lockService = lockService;
        this.telegramClientManager = telegramClientManager;
    }

    /**
     * Orchestrates startup sequence after application is fully ready.
     * Runs asynchronously to not block application startup.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent event) {
        String instanceId = botInstanceProvider.getInstanceId();
        if (startupCompleted && instanceId.equals(orchestratedInstanceId)) {
            log.debug("StartupOrchestrator[{}]: Initialization pipeline already completed, skipping", instanceId);
            return;
        }

        log.info("=================================================================================");
        log.info("StartupOrchestrator[{}]: Application is ready, starting initialization pipeline...", instanceId);
        log.info("=================================================================================");

        // First clean up any stale operations from previous runs, then attempt dialog repair
        lockService.cleanupStaleOperations()
                .then(waitForTdLibReadiness())
                .then(attemptDialogStateRepair())
                .then(performStartupSequence())
                .subscribeOn(Schedulers.boundedElastic())
                .doOnSuccess(unused -> {
                    startupCompleted = true;
                    orchestratedInstanceId = botInstanceProvider.getInstanceId();
                    log.info("=================================================================================");
                    log.info("StartupOrchestrator[{}]: Initialization pipeline completed successfully",
                            botInstanceProvider.getInstanceId());
                    log.info("=================================================================================");
                    if (telegramClientManager.hasPendingSecondaryClients()) {
                        log.info(">>> STEP 4: Initializing secondary Telegram clients...");
                        telegramClientManager.initializeSecondaryClients();
                        log.info(">>> STEP 4 COMPLETE: Secondary clients initialized");
                    }
                })
                .subscribe(
                    unused -> {},
                    error -> {
                        startupCompleted = true;
                        orchestratedInstanceId = botInstanceProvider.getInstanceId();
                        log.error("=================================================================================");
                        log.error("StartupOrchestrator[{}]: Initialization pipeline failed",
                                botInstanceProvider.getInstanceId());
                        log.error("Error: {}", error.getMessage(), error);
                        log.error("Application will continue in degraded mode");
                        log.error("=================================================================================");
                    }
                );
    }

    /**
     * Waits for TDLib to become ready (authorized) before proceeding.
     *
     * <p>When no TDLib clients are configured ({@code getClientCount() == 0}),
     * this method short-circuits and returns immediately without waiting
     * (FR-028). This eliminates the 5-minute readiness wait for the smoke
     * profile where {@code telegram.client.enabled=false}.</p>
     *
     * <p>Package-private for testability; tests in the same package verify
     * the short-circuit behaviour with a mocked {@link TelegramClientManager}.</p>
     *
     * @return a Mono that completes when TDLib is ready (or immediately if
     *         no clients are configured)
     */
    Mono<Void> waitForTdLibReadiness() {
        log.info(">>> STEP 0: Waiting for TDLib to become ready...");

        // FR-028/FR-030: Short-circuit when no TDLib clients are configured.
        // Reuses the existing getClientCount() surface (TelegramClientManager line 159),
        // no new abstraction introduced.
        if (telegramClientManager.getClientCount() == 0) {
            log.info(">>> STEP 0 SKIPPED: No TDLib clients configured — TDLib readiness already satisfied");
            return Mono.empty();
        }

        return Mono.defer(() -> tdLibOperationCoordinator.isTdLibReady())
                .flatMap(ready -> {
                    if (ready) {
                        log.info(">>> STEP 0 COMPLETE: TDLib is authorized and ready");
                        return Mono.empty();
                    }
                    log.debug("TDLib not ready yet, will retry...");
                    return Mono.delay(TDLIB_READINESS_CHECK_INTERVAL)
                            .then(Mono.error(new RuntimeException("TDLib not ready")));
                })
                .retry()
                .timeout(TDLIB_READINESS_TIMEOUT)
                .onErrorResume(error -> {
                    if (error instanceof java.util.concurrent.TimeoutException) {
                        log.error(">>> STEP 0 FAILED: TDLib did not become ready within {}", TDLIB_READINESS_TIMEOUT);
                        return Mono.error(new RuntimeException("TDLib readiness timeout after " + TDLIB_READINESS_TIMEOUT));
                    }
                    return Mono.error(error);
                })
                .then();
    }

    /**
     * Attempts to repair dialog state at startup to prevent "dialog date didn't increase" errors.
     *
     * <p>This runs proactively to fix any corrupted state from previous runs.</p>
     *
     * @return a Mono that completes when repair attempt is done (success or failure)
     */
    private Mono<Void> attemptDialogStateRepair() {
        log.info(">>> STEP 0.5: Attempting proactive dialog state repair...");
        return tdLibOperationCoordinator.repairDialogState()
                .doOnNext(result -> {
                    if (result.success()) {
                        log.info(">>> STEP 0.5 COMPLETE: Dialog state repair succeeded - {}", result.message());
                    } else {
                        log.warn(">>> STEP 0.5 WARNING: Dialog state repair reported issue - {}", result.message());
                        log.warn("    Continuing with startup, but LoadChats errors may occur");
                    }
                })
                .onErrorResume(error -> {
                    log.warn(">>> STEP 0.5 WARNING: Dialog state repair failed - {}", error.getMessage());
                    log.warn("    Continuing with startup anyway");
                    return Mono.empty();
                })
                .then();
    }

    /**
     * Performs the complete startup sequence in order.
     * Each step depends on the previous one completing successfully.
     */
    private Mono<Void> performStartupSequence() {
        return Mono.fromRunnable(() -> log.info(">>> STEP 1: Discovering existing chats via TDLib..."))
                .then(channelDiscoveryCoordinator.discoverAndPopulateChats())
                .doOnSuccess(summary -> log.info(">>> STEP 1 COMPLETE: Discovery finished (channels={}, failures={}, duration={})",
                        summary.channelsProcessed(), summary.failures(), summary.duration()))
                .then(Mono.fromRunnable(() -> log.info(">>> STEP 2: Starting automatic message synchronization...")))
                .then(channelMessageSynchronizationService.synchronizeAutoSyncChannels())
                .doOnSuccess(summary -> {
                    log.info(">>> STEP 2 COMPLETE: Message synchronization finished");
                    log.info("    - Auto-sync channels: {}", summary.autoSyncChannels());
                    log.info("    - Sync jobs attempted: {}", summary.syncJobsAttempted());
                    log.info("    - Sync jobs failed: {}", summary.syncJobsFailed());
                    log.info("    - Duration: {}", summary.duration());
                })
                .then(Mono.fromRunnable(() -> log.info(">>> STEP 3: Starting channel processing pipeline...")))
                .then(channelProcessingCoordinator.processPendingChannels())
                .doOnSuccess(result -> {
                    log.info(">>> STEP 3 COMPLETE: Channel processing pipeline finished");
                    log.info("    - Phase 1 (Ingestion): {} channels processed", result.phase1Count());
                    log.info("    - Phase 2 (Linking): {} channels processed", result.phase2Count());
                    log.info("    - Phase 3 (Template): {} channels processed", result.phase3Count());
                    log.info("    - Total duration: {}", result.duration());
                })
                .then();
    }
}
