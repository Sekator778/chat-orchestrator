package com.example.telegramuserbot.service;

import com.example.telegramuserbot.config.BotInstanceProvider;
import com.example.telegramuserbot.domain.TdLibOperation;
import com.example.telegramuserbot.domain.TdLibOperationStatus;
import com.example.telegramuserbot.repository.TdLibOperationRepository;
import com.example.telegramuserbot.telegram.TdLibOperationCoordinator;
import com.example.telegramuserbot.telegram.TdLibOperationState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Service for recovering from TDLib operation failures and inconsistent states.
 *
 * <p>This service provides recovery mechanisms for:</p>
 * <ul>
 *   <li>Stuck in-process operations (semaphore deadlock)</li>
 *   <li>Stale distributed locks</li>
 *   <li>Orphaned operation records</li>
 *   <li>State inconsistencies between coordinator and database</li>
 * </ul>
 *
 * <p>Recovery operations are designed to be safe to run at any time,
 * including during normal operation. They will only take action when
 * an actual problem is detected.</p>
 */
@Service
public final class TdLibRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(TdLibRecoveryService.class);

    /**
     * Default threshold for considering an in-process operation as stuck (10 minutes).
     */
    private static final Duration DEFAULT_STUCK_THRESHOLD = Duration.ofMinutes(10);

    /**
     * Threshold for considering a stale lock as abandoned (5 minutes past timeout).
     */
    private static final Duration STALE_LOCK_THRESHOLD = Duration.ofMinutes(5);

    /**
     * Maximum number of recovery attempts before giving up.
     */
    private static final int MAX_RECOVERY_ATTEMPTS = 3;

    private final TdLibOperationCoordinator coordinator;
    private final TdLibOperationRepository repository;
    private final BotInstanceProvider instanceProvider;

    /**
     * Creates a new TdLibRecoveryService.
     *
     * @param coordinator the TDLib operation coordinator
     * @param repository the TDLib operation repository
     * @param instanceProvider the bot instance provider
     */
    public TdLibRecoveryService(
            TdLibOperationCoordinator coordinator,
            TdLibOperationRepository repository,
            BotInstanceProvider instanceProvider) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator must not be null");
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.instanceProvider = Objects.requireNonNull(instanceProvider, "instanceProvider must not be null");
    }

    /**
     * Performs comprehensive recovery of the TDLib coordination system.
     *
     * <p>This method attempts to recover from all known failure states:</p>
     * <ol>
     *   <li>Recover stuck in-process operations</li>
     *   <li>Clean up stale distributed locks</li>
     *   <li>Reconcile coordinator and database state</li>
     * </ol>
     *
     * @return a Mono emitting the recovery result
     */
    public Mono<RecoveryResult> performFullRecovery() {
        log.info("Starting full TDLib recovery for instance {}", instanceProvider.getInstanceId());

        return Mono.zip(
                recoverStuckCoordinatorOperation(),
                cleanupStaleLocks(),
                reconcileState()
            )
            .map(tuple -> {
                RecoveryResult result = new RecoveryResult(
                    tuple.getT1(),
                    tuple.getT2(),
                    tuple.getT3()
                );
                log.info("TDLib recovery completed: coordinator={}, locks={}, reconciliation={}",
                    result.coordinatorRecovery().action(),
                    result.lockCleanup().action(),
                    result.stateReconciliation().action());
                return result;
            });
    }

    /**
     * Attempts to recover a stuck in-process operation.
     *
     * @return a Mono emitting the recovery action result
     */
    public Mono<RecoveryAction> recoverStuckCoordinatorOperation() {
        return recoverStuckCoordinatorOperation(DEFAULT_STUCK_THRESHOLD);
    }

    /**
     * Attempts to recover a stuck in-process operation with a custom threshold.
     *
     * @param stuckThreshold the duration after which an operation is considered stuck
     * @return a Mono emitting the recovery action result
     */
    public Mono<RecoveryAction> recoverStuckCoordinatorOperation(Duration stuckThreshold) {
        return Mono.fromCallable(() -> {
            if (!coordinator.isOperationInProgress()) {
                return new RecoveryAction(
                    ActionType.NO_ACTION,
                    "No operation in progress",
                    null
                );
            }

            Duration duration = coordinator.getCurrentOperationDuration();
            String operationName = coordinator.getCurrentOperation();
            TdLibOperationState state = coordinator.getState();

            if (duration.compareTo(stuckThreshold) <= 0) {
                return new RecoveryAction(
                    ActionType.NO_ACTION,
                    "Operation " + operationName + " is running normally (" + duration.toSeconds() + "s)",
                    null
                );
            }

            log.warn("Detected stuck operation '{}' running for {} (threshold: {})",
                operationName, duration, stuckThreshold);

            boolean released = coordinator.forceReleaseIfStuck(stuckThreshold);

            if (released) {
                log.info("Successfully force-released stuck operation '{}'", operationName);
                return new RecoveryAction(
                    ActionType.FORCE_RELEASED,
                    "Force-released stuck operation: " + operationName + " after " + duration.toSeconds() + "s",
                    operationName
                );
            }

            return new RecoveryAction(
                ActionType.FAILED,
                "Failed to force-release stuck operation: " + operationName,
                operationName
            );
        });
    }

    /**
     * Cleans up stale distributed locks from the database.
     *
     * @return a Mono emitting the cleanup action result
     */
    public Mono<RecoveryAction> cleanupStaleLocks() {
        OffsetDateTime now = OffsetDateTime.now();

        return repository.findStaleOperations(now)
            .collectList()
            .flatMap(staleOps -> {
                if (staleOps.isEmpty()) {
                    return Mono.just(new RecoveryAction(
                        ActionType.NO_ACTION,
                        "No stale locks found",
                        null
                    ));
                }

                log.info("Found {} stale operations to clean up", staleOps.size());

                return repository.markStaleOperationsAsTimeout(now)
                    .map(count -> {
                        if (count > 0) {
                            log.info("Cleaned up {} stale operations", count);
                            return new RecoveryAction(
                                ActionType.CLEANED_UP,
                                "Cleaned up " + count + " stale operations",
                                String.valueOf(count)
                            );
                        }
                        return new RecoveryAction(
                            ActionType.NO_ACTION,
                            "No stale operations needed cleanup",
                            null
                        );
                    });
            })
            .onErrorResume(error -> {
                log.error("Failed to cleanup stale locks: {}", error.getMessage());
                return Mono.just(new RecoveryAction(
                    ActionType.FAILED,
                    "Failed to cleanup stale locks: " + error.getMessage(),
                    null
                ));
            });
    }

    /**
     * Reconciles the coordinator state with database state.
     *
     * <p>This method checks for inconsistencies between the in-process coordinator
     * and the distributed lock database, and attempts to resolve them.</p>
     *
     * @return a Mono emitting the reconciliation action result
     */
    public Mono<RecoveryAction> reconcileState() {
        String botInstanceId = instanceProvider.getInstanceId();

        return repository.findActiveOperationsForInstance(botInstanceId)
            .collectList()
            .map(dbOperations -> {
                boolean coordinatorInProgress = coordinator.isOperationInProgress();

                if (dbOperations.isEmpty() && !coordinatorInProgress) {
                    return new RecoveryAction(
                        ActionType.NO_ACTION,
                        "Coordinator and database state are consistent (both idle)",
                        null
                    );
                }

                if (!dbOperations.isEmpty() && coordinatorInProgress) {
                    return new RecoveryAction(
                        ActionType.NO_ACTION,
                        "Coordinator and database state are consistent (both active)",
                        null
                    );
                }

                if (dbOperations.isEmpty() && coordinatorInProgress) {
                    String opName = coordinator.getCurrentOperation();
                    log.warn("Inconsistency detected: coordinator has operation '{}' but no database record",
                        opName);
                    return new RecoveryAction(
                        ActionType.INCONSISTENCY_DETECTED,
                        "Coordinator has operation but no database record: " + opName,
                        opName
                    );
                }

                if (!dbOperations.isEmpty() && !coordinatorInProgress) {
                    List<String> opTypes = dbOperations.stream()
                        .map(op -> op.getOperationType().name())
                        .toList();
                    log.warn("Inconsistency detected: database has {} active operations but coordinator is idle",
                        dbOperations.size());
                    return new RecoveryAction(
                        ActionType.INCONSISTENCY_DETECTED,
                        "Database has active operations but coordinator is idle: " + opTypes,
                        String.join(", ", opTypes)
                    );
                }

                return new RecoveryAction(
                    ActionType.NO_ACTION,
                    "State reconciliation completed normally",
                    null
                );
            })
            .onErrorResume(error -> {
                log.error("Failed to reconcile state: {}", error.getMessage());
                return Mono.just(new RecoveryAction(
                    ActionType.FAILED,
                    "Failed to reconcile state: " + error.getMessage(),
                    null
                ));
            });
    }

    /**
     * Recovers a specific operation by ID.
     *
     * @param operationId the operation ID to recover
     * @return a Mono emitting the recovery action result
     */
    public Mono<RecoveryAction> recoverOperation(Long operationId) {
        return repository.findById(operationId)
            .flatMap(operation -> {
                if (operation.getStatus() != TdLibOperationStatus.IN_PROGRESS) {
                    return Mono.just(new RecoveryAction(
                        ActionType.NO_ACTION,
                        "Operation " + operationId + " is not in progress (status: " + operation.getStatus() + ")",
                        null
                    ));
                }

                if (!operation.isStale()) {
                    return Mono.just(new RecoveryAction(
                        ActionType.NO_ACTION,
                        "Operation " + operationId + " is not stale yet",
                        null
                    ));
                }

                log.info("Recovering stale operation {} ({})", operationId, operation.getOperationType());

                return repository.releaseLock(
                        operationId,
                        TdLibOperationStatus.TIMEOUT.name(),
                        OffsetDateTime.now(),
                        "Manually recovered by TdLibRecoveryService"
                    )
                    .map(rows -> {
                        if (rows > 0) {
                            return new RecoveryAction(
                                ActionType.RECOVERED,
                                "Recovered operation " + operationId + " (" + operation.getOperationType() + ")",
                                operationId.toString()
                            );
                        }
                        return new RecoveryAction(
                            ActionType.FAILED,
                            "Failed to recover operation " + operationId,
                            operationId.toString()
                        );
                    });
            })
            .switchIfEmpty(Mono.just(new RecoveryAction(
                ActionType.NO_ACTION,
                "Operation " + operationId + " not found",
                null
            )))
            .onErrorResume(error -> {
                log.error("Failed to recover operation {}: {}", operationId, error.getMessage());
                return Mono.just(new RecoveryAction(
                    ActionType.FAILED,
                    "Failed to recover operation " + operationId + ": " + error.getMessage(),
                    operationId.toString()
                ));
            });
    }

    /**
     * Forces cleanup of all active operations for the current instance.
     *
     * <p>WARNING: This method should only be used during startup or in emergency
     * recovery situations. It will force-terminate all active operations.</p>
     *
     * @return a Mono emitting the cleanup result
     */
    public Mono<RecoveryAction> forceCleanupAllActiveOperations() {
        String botInstanceId = instanceProvider.getInstanceId();
        OffsetDateTime now = OffsetDateTime.now();

        log.warn("Force-cleaning all active operations for instance {}", botInstanceId);

        return repository.findActiveOperationsForInstance(botInstanceId)
            .flatMap(operation -> repository.releaseLock(
                operation.getId(),
                TdLibOperationStatus.TIMEOUT.name(),
                now,
                "Force-cleaned during startup/recovery"
            ))
            .collectList()
            .map(results -> {
                int cleaned = results.stream().mapToInt(Integer::intValue).sum();
                if (cleaned > 0) {
                    log.info("Force-cleaned {} active operations for instance {}", cleaned, botInstanceId);
                    return new RecoveryAction(
                        ActionType.FORCE_CLEANED,
                        "Force-cleaned " + cleaned + " active operations",
                        String.valueOf(cleaned)
                    );
                }
                return new RecoveryAction(
                    ActionType.NO_ACTION,
                    "No active operations to clean up",
                    null
                );
            })
            .onErrorResume(error -> {
                log.error("Failed to force-clean active operations: {}", error.getMessage());
                return Mono.just(new RecoveryAction(
                    ActionType.FAILED,
                    "Failed to force-clean active operations: " + error.getMessage(),
                    null
                ));
            });
    }

    /**
     * Gets the default stuck operation threshold.
     *
     * @return the default stuck threshold duration
     */
    public Duration getDefaultStuckThreshold() {
        return DEFAULT_STUCK_THRESHOLD;
    }

    /**
     * Types of recovery actions.
     */
    public enum ActionType {
        /** No action was needed. */
        NO_ACTION,
        /** Operation was force-released from coordinator. */
        FORCE_RELEASED,
        /** Stale operations were cleaned up. */
        CLEANED_UP,
        /** Operation was recovered. */
        RECOVERED,
        /** All operations were force-cleaned. */
        FORCE_CLEANED,
        /** State inconsistency was detected. */
        INCONSISTENCY_DETECTED,
        /** Recovery action failed. */
        FAILED
    }

    /**
     * Result of a single recovery action.
     *
     * @param action the type of action taken
     * @param message descriptive message
     * @param details additional details
     */
    public record RecoveryAction(
        ActionType action,
        String message,
        String details
    ) {
        /**
         * Checks if this action indicates a successful recovery.
         *
         * @return true if recovery was successful
         */
        public boolean isSuccessful() {
            return action != ActionType.FAILED;
        }

        /**
         * Checks if any action was taken.
         *
         * @return true if an action was taken
         */
        public boolean actionTaken() {
            return action != ActionType.NO_ACTION;
        }
    }

    /**
     * Result of a full recovery operation.
     *
     * @param coordinatorRecovery coordinator recovery result
     * @param lockCleanup lock cleanup result
     * @param stateReconciliation state reconciliation result
     */
    public record RecoveryResult(
        RecoveryAction coordinatorRecovery,
        RecoveryAction lockCleanup,
        RecoveryAction stateReconciliation
    ) {
        /**
         * Checks if all recovery actions were successful.
         *
         * @return true if all actions succeeded
         */
        public boolean isSuccessful() {
            return coordinatorRecovery.isSuccessful()
                && lockCleanup.isSuccessful()
                && stateReconciliation.isSuccessful();
        }

        /**
         * Checks if any action was taken during recovery.
         *
         * @return true if at least one action was taken
         */
        public boolean anyActionTaken() {
            return coordinatorRecovery.actionTaken()
                || lockCleanup.actionTaken()
                || stateReconciliation.actionTaken();
        }

        /**
         * Gets the count of failed actions.
         *
         * @return number of failed actions
         */
        public int failedCount() {
            int count = 0;
            if (!coordinatorRecovery.isSuccessful()) count++;
            if (!lockCleanup.isSuccessful()) count++;
            if (!stateReconciliation.isSuccessful()) count++;
            return count;
        }
    }
}
