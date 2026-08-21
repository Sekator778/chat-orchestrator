package com.example.telegramuserbot.scheduler;

import com.example.telegramuserbot.config.BotInstanceProvider;
import com.example.telegramuserbot.service.TdLibOperationLockService;
import com.example.telegramuserbot.telegram.TdLibOperationCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;

/**
 * Scheduler for maintenance tasks related to TDLib operations tracking.
 *
 * <p>This scheduler performs the following maintenance tasks:</p>
 * <ul>
 *   <li>Cleanup of stale (timed out) operation locks</li>
 *   <li>Deletion of old completed operation records</li>
 *   <li>Recovery of stuck in-process TDLib operations</li>
 * </ul>
 *
 * <p>These tasks ensure the TDLib operation coordination system remains healthy
 * and doesn't accumulate stale data over time.</p>
 */
@Component
public final class TdLibOperationsMaintenanceScheduler {

    private static final Logger log = LoggerFactory.getLogger(TdLibOperationsMaintenanceScheduler.class);

    /**
     * Threshold for considering an in-process operation as stuck (10 minutes).
     */
    private static final Duration STUCK_OPERATION_THRESHOLD = Duration.ofMinutes(10);

    private final TdLibOperationLockService lockService;
    private final TdLibOperationCoordinator operationCoordinator;
    private final BotInstanceProvider botInstanceProvider;

    public TdLibOperationsMaintenanceScheduler(
            TdLibOperationLockService lockService,
            TdLibOperationCoordinator operationCoordinator,
            BotInstanceProvider botInstanceProvider) {
        this.lockService = lockService;
        this.operationCoordinator = operationCoordinator;
        this.botInstanceProvider = botInstanceProvider;
    }

    /**
     * Cleans up stale operation locks every 5 minutes.
     *
     * <p>This task marks operations that have exceeded their timeout as TIMEOUT status,
     * allowing other instances to acquire locks for those operation types.</p>
     */
    @Scheduled(fixedRate = 300000) // Every 5 minutes
    public void cleanupStaleOperations() {
        log.debug("🧹 TDLIB MAINTENANCE[{}]: Starting stale operation cleanup",
                botInstanceProvider.getInstanceId());

        lockService.cleanupStaleOperations()
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        count -> {
                            if (count > 0) {
                                log.info("🧹 TDLIB MAINTENANCE[{}]: Cleaned up {} stale operations",
                                        botInstanceProvider.getInstanceId(), count);
                            }
                        },
                        error -> log.error("🧹 TDLIB MAINTENANCE[{}]: Stale cleanup failed",
                                botInstanceProvider.getInstanceId(), error)
                );
    }

    /**
     * Deletes old completed operation records daily at 3:00 AM.
     *
     * <p>This task removes operation records that have been completed for more than
     * 24 hours to prevent the table from growing indefinitely.</p>
     */
    @Scheduled(cron = "0 0 3 * * *")
    public void deleteOldOperations() {
        log.info("🧹 TDLIB MAINTENANCE[{}]: Starting old operation cleanup",
                botInstanceProvider.getInstanceId());

        lockService.deleteOldOperations()
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        count -> log.info("🧹 TDLIB MAINTENANCE[{}]: Deleted {} old operation records",
                                botInstanceProvider.getInstanceId(), count),
                        error -> log.error("🧹 TDLIB MAINTENANCE[{}]: Old operation cleanup failed",
                                botInstanceProvider.getInstanceId(), error)
                );
    }

    /**
     * Checks for and recovers stuck in-process TDLib operations every 2 minutes.
     *
     * <p>If an in-process operation (like LoadChats) has been running longer than
     * the stuck threshold, this task will force-release the operation's semaphore
     * to allow other operations to proceed.</p>
     */
    @Scheduled(fixedRate = 120000) // Every 2 minutes
    public void recoverStuckOperations() {
        if (operationCoordinator.isOperationInProgress()) {
            Duration duration = operationCoordinator.getCurrentOperationDuration();
            String operationName = operationCoordinator.getCurrentOperation();

            if (duration.compareTo(STUCK_OPERATION_THRESHOLD) > 0) {
                log.warn("🧹 TDLIB MAINTENANCE[{}]: Detected stuck operation '{}' running for {}",
                        botInstanceProvider.getInstanceId(), operationName, duration);

                boolean released = operationCoordinator.forceReleaseIfStuck(STUCK_OPERATION_THRESHOLD);
                if (released) {
                    log.warn("🧹 TDLIB MAINTENANCE[{}]: Force-released stuck operation '{}'",
                            botInstanceProvider.getInstanceId(), operationName);
                }
            }
        }
    }
}
