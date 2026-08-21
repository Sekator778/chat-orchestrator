package com.example.telegramuserbot.service;

import com.example.telegramuserbot.config.BotInstanceProvider;
import com.example.telegramuserbot.domain.TdLibOperation;
import com.example.telegramuserbot.domain.TdLibOperationStatus;
import com.example.telegramuserbot.domain.TdLibOperationType;
import com.example.telegramuserbot.repository.TdLibOperationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.function.Supplier;

/**
 * Service for distributed locking of TDLib operations across multiple bot instances.
 *
 * <p>This service provides database-backed distributed locks to prevent multiple
 * bot instances from executing conflicting TDLib operations simultaneously.
 * It works in conjunction with {@link com.example.telegramuserbot.telegram.TdLibOperationCoordinator}
 * which handles in-process serialization.</p>
 *
 * <p>The locking mechanism uses PostgreSQL's unique partial index to ensure that
 * only one operation of a given type can be IN_PROGRESS per bot instance at a time.</p>
 *
 * <p>Key features:</p>
 * <ul>
 *   <li>Distributed lock acquisition with timeout</li>
 *   <li>Automatic stale lock cleanup</li>
 *   <li>Heartbeat support for long-running operations</li>
 *   <li>Operation tracking and monitoring</li>
 * </ul>
 */
@Service
public final class TdLibOperationLockService {

    private static final Logger log = LoggerFactory.getLogger(TdLibOperationLockService.class);

    /**
     * Default timeout for TDLib operations (5 minutes).
     */
    private static final Duration DEFAULT_OPERATION_TIMEOUT = Duration.ofMinutes(5);

    /**
     * Duration after which completed operations are cleaned up (24 hours).
     */
    private static final Duration CLEANUP_RETENTION = Duration.ofHours(24);

    /**
     * Heartbeat interval for long-running operations (30 seconds).
     */
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(30);

    private final TdLibOperationRepository repository;
    private final BotInstanceProvider instanceProvider;

    /**
     * Creates a new TdLibOperationLockService.
     *
     * @param repository the TDLib operation repository
     * @param instanceProvider the bot instance provider
     */
    public TdLibOperationLockService(
            TdLibOperationRepository repository,
            BotInstanceProvider instanceProvider) {
        this.repository = repository;
        this.instanceProvider = instanceProvider;
    }

    /**
     * Attempts to acquire a distributed lock for an operation.
     *
     * <p>The lock is acquired by inserting a record into the database with status IN_PROGRESS.
     * If another operation of the same type is already running for this bot instance,
     * the insert will fail due to the unique partial index.</p>
     *
     * @param operationType the type of operation to lock
     * @return a Mono emitting the acquired lock, or empty if lock could not be acquired
     */
    public Mono<TdLibOperation> tryAcquireLock(TdLibOperationType operationType) {
        return tryAcquireLock(operationType, null, DEFAULT_OPERATION_TIMEOUT);
    }

    /**
     * Attempts to acquire a distributed lock for an operation with a resource identifier.
     *
     * @param operationType the type of operation to lock
     * @param resourceId optional resource identifier (e.g., chat ID for sync operations)
     * @return a Mono emitting the acquired lock, or empty if lock could not be acquired
     */
    public Mono<TdLibOperation> tryAcquireLock(TdLibOperationType operationType, String resourceId) {
        return tryAcquireLock(operationType, resourceId, DEFAULT_OPERATION_TIMEOUT);
    }

    /**
     * Attempts to acquire a distributed lock for an operation with custom timeout.
     *
     * @param operationType the type of operation to lock
     * @param resourceId optional resource identifier
     * @param timeout the operation timeout duration
     * @return a Mono emitting the acquired lock, or empty if lock could not be acquired
     */
    public Mono<TdLibOperation> tryAcquireLock(
            TdLibOperationType operationType,
            String resourceId,
            Duration timeout) {

        String botInstanceId = instanceProvider.getInstanceId();
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime timeoutAt = now.plus(timeout);

        log.debug("Attempting to acquire lock for {} (resource: {}, timeout: {})",
            operationType, resourceId, timeout);

        return repository.tryAcquireLock(
                operationType.name(),
                botInstanceId,
                resourceId,
                now,
                timeoutAt
            )
            .doOnNext(operation ->
                log.info("Acquired lock for {} (id: {}, resource: {})",
                    operationType, operation.getId(), resourceId))
            .doOnTerminate(() -> {
                if (log.isDebugEnabled()) {
                    log.debug("Lock acquisition attempt completed for {}", operationType);
                }
            })
            .switchIfEmpty(Mono.defer(() -> {
                log.debug("Could not acquire lock for {} - another operation is in progress", operationType);
                return Mono.empty();
            }));
    }

    /**
     * Releases a lock by marking the operation as completed.
     *
     * @param operation the operation to release
     * @return a Mono that completes when the lock is released
     */
    public Mono<Void> releaseLock(TdLibOperation operation) {
        return releaseLock(operation, TdLibOperationStatus.COMPLETED, null);
    }

    /**
     * Releases a lock by marking the operation as failed.
     *
     * @param operation the operation to release
     * @param errorMessage the error message describing the failure
     * @return a Mono that completes when the lock is released
     */
    public Mono<Void> releaseLockWithError(TdLibOperation operation, String errorMessage) {
        return releaseLock(operation, TdLibOperationStatus.FAILED, errorMessage);
    }

    /**
     * Releases a lock with the specified status.
     *
     * @param operation the operation to release
     * @param status the completion status
     * @param errorMessage optional error message
     * @return a Mono that completes when the lock is released
     */
    public Mono<Void> releaseLock(TdLibOperation operation, TdLibOperationStatus status, String errorMessage) {
        if (operation == null || operation.getId() == null) {
            return Mono.empty();
        }

        log.debug("Releasing lock for operation {} with status {}", operation.getId(), status);

        return repository.releaseLock(
                operation.getId(),
                status.name(),
                OffsetDateTime.now(),
                errorMessage
            )
            .doOnSuccess(rows -> {
                if (rows > 0) {
                    log.info("Released lock for {} (id: {}, status: {})",
                        operation.getOperationType(), operation.getId(), status);
                } else {
                    log.warn("Lock release had no effect for operation {} - may already be released",
                        operation.getId());
                }
            })
            .then();
    }

    /**
     * Executes an operation with automatic lock management.
     *
     * <p>This method acquires a lock, executes the operation, and releases the lock
     * when the operation completes (successfully or with an error).</p>
     *
     * @param operationType the type of operation
     * @param operation the operation to execute
     * @param <T> the result type
     * @return a Mono emitting the operation result, or error if lock couldn't be acquired
     */
    public <T> Mono<T> executeWithLock(TdLibOperationType operationType, Mono<T> operation) {
        return executeWithLock(operationType, null, DEFAULT_OPERATION_TIMEOUT, operation);
    }

    /**
     * Executes an operation with automatic lock management and custom parameters.
     *
     * @param operationType the type of operation
     * @param resourceId optional resource identifier
     * @param timeout the operation timeout
     * @param operation the operation to execute
     * @param <T> the result type
     * @return a Mono emitting the operation result, or error if lock couldn't be acquired
     */
    public <T> Mono<T> executeWithLock(
            TdLibOperationType operationType,
            String resourceId,
            Duration timeout,
            Mono<T> operation) {

        return tryAcquireLock(operationType, resourceId, timeout)
            .switchIfEmpty(Mono.error(new LockNotAcquiredException(
                "Could not acquire lock for " + operationType + " - another operation is in progress")))
            .flatMap(lock -> operation
                .doOnSuccess(result -> releaseLock(lock).subscribe())
                .doOnError(error -> releaseLockWithError(lock, error.getMessage()).subscribe())
                .doOnCancel(() -> releaseLockWithError(lock, "Operation cancelled").subscribe())
            );
    }

    /**
     * Executes an operation with automatic lock management using a supplier.
     *
     * @param operationType the type of operation
     * @param operationSupplier supplier that creates the operation Mono
     * @param <T> the result type
     * @return a Mono emitting the operation result
     */
    public <T> Mono<T> executeWithLock(TdLibOperationType operationType, Supplier<Mono<T>> operationSupplier) {
        return tryAcquireLock(operationType)
            .switchIfEmpty(Mono.error(new LockNotAcquiredException(
                "Could not acquire lock for " + operationType + " - another operation is in progress")))
            .flatMap(lock -> operationSupplier.get()
                .doOnSuccess(result -> releaseLock(lock).subscribe())
                .doOnError(error -> releaseLockWithError(lock, error.getMessage()).subscribe())
                .doOnCancel(() -> releaseLockWithError(lock, "Operation cancelled").subscribe())
            );
    }

    /**
     * Updates the heartbeat for a running operation.
     *
     * <p>Long-running operations should call this periodically to indicate
     * they are still active and prevent stale lock cleanup.</p>
     *
     * @param operation the operation to update
     * @return a Mono that completes when the heartbeat is updated
     */
    public Mono<Void> updateHeartbeat(TdLibOperation operation) {
        if (operation == null || operation.getId() == null) {
            return Mono.empty();
        }
        return repository.updateHeartbeat(operation.getId(), OffsetDateTime.now())
            .then();
    }

    /**
     * Checks if an operation of the specified type is currently in progress.
     *
     * @param operationType the type of operation to check
     * @return a Mono emitting true if an operation is in progress
     */
    public Mono<Boolean> isOperationInProgress(TdLibOperationType operationType) {
        String botInstanceId = instanceProvider.getInstanceId();
        return repository.findActiveOperation(operationType.name(), botInstanceId)
            .hasElement();
    }

    /**
     * Checks if any operation of the specified type is in progress globally (any instance).
     *
     * @param operationType the type of operation to check
     * @return a Mono emitting true if an operation is in progress on any instance
     */
    public Mono<Boolean> isOperationInProgressGlobally(TdLibOperationType operationType) {
        return repository.findActiveOperationGlobal(operationType.name())
            .hasElement();
    }

    /**
     * Finds all active operations for the current bot instance.
     *
     * @return a Flux of active operations
     */
    public Flux<TdLibOperation> findActiveOperations() {
        String botInstanceId = instanceProvider.getInstanceId();
        return repository.findActiveOperationsForInstance(botInstanceId);
    }

    /**
     * Cleans up stale operations by marking them as timed out.
     *
     * <p>This should be called periodically (e.g., from a scheduled task) to
     * release locks held by crashed or stuck processes.</p>
     *
     * @return a Mono emitting the number of operations marked as timed out
     */
    public Mono<Integer> cleanupStaleOperations() {
        OffsetDateTime now = OffsetDateTime.now();
        log.debug("Cleaning up stale operations (timeout before {})", now);

        return repository.markStaleOperationsAsTimeout(now)
            .doOnSuccess(count -> {
                if (count > 0) {
                    log.info("Cleaned up {} stale TDLib operations", count);
                }
            });
    }

    /**
     * Deletes old completed operations from the database.
     *
     * @return a Mono emitting the number of deleted operations
     */
    public Mono<Integer> deleteOldOperations() {
        OffsetDateTime cutoff = OffsetDateTime.now().minus(CLEANUP_RETENTION);
        log.debug("Deleting operations completed before {}", cutoff);

        return repository.deleteOldOperations(cutoff)
            .doOnSuccess(count -> {
                if (count > 0) {
                    log.info("Deleted {} old TDLib operation records", count);
                }
            });
    }

    /**
     * Finds recent operations for monitoring and debugging.
     *
     * @param lookback how far back to look for operations
     * @return a Flux of recent operations
     */
    public Flux<TdLibOperation> findRecentOperations(Duration lookback) {
        String botInstanceId = instanceProvider.getInstanceId();
        OffsetDateTime since = OffsetDateTime.now().minus(lookback);
        return repository.findRecentOperations(botInstanceId, since);
    }

    /**
     * Gets the heartbeat interval for long-running operations.
     *
     * @return the heartbeat interval
     */
    public Duration getHeartbeatInterval() {
        return HEARTBEAT_INTERVAL;
    }

    /**
     * Exception thrown when a lock cannot be acquired.
     */
    public static final class LockNotAcquiredException extends RuntimeException {
        /**
         * Creates a new lock exception.
         *
         * @param message the error message
         */
        public LockNotAcquiredException(String message) {
            super(message);
        }
    }
}
