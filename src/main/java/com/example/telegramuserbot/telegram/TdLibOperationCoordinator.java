package com.example.telegramuserbot.telegram;

import com.example.telegramuserbot.service.TelegramClientManager;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Coordinates TDLib operations to prevent concurrent state-modifying requests.
 *
 * <p>TDLib's LoadChats operation modifies internal pagination state (dialog date tracking).
 * When multiple LoadChats requests execute concurrently, the state machine invariant
 * (monotonically increasing dialog dates during pagination) can be violated, causing
 * the error: "Last server dialog date didn't increase from X to Y".</p>
 *
 * <p>This coordinator ensures that:</p>
 * <ul>
 *   <li>Only one LoadChats operation runs at a time (serialization)</li>
 *   <li>Operation state is tracked for monitoring and debugging</li>
 *   <li>Timeouts prevent indefinite blocking</li>
 *   <li>Health checks verify TDLib readiness before operations</li>
 * </ul>
 *
 * <p>Thread Safety: This class is thread-safe. The semaphore ensures mutual exclusion
 * for LoadChats operations, while AtomicReference provides thread-safe state tracking.</p>
 *
 * @see TdLibOperationState
 */
@Service
public final class TdLibOperationCoordinator {

    private static final Logger log = LoggerFactory.getLogger(TdLibOperationCoordinator.class);

    /**
     * Default timeout for acquiring the LoadChats semaphore (30 seconds).
     */
    private static final Duration DEFAULT_ACQUIRE_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Default timeout for a single LoadChats operation (60 seconds).
     */
    private static final Duration DEFAULT_OPERATION_TIMEOUT = Duration.ofSeconds(60);

    private final TelegramClientManager telegramClientManager;
    private final Semaphore semaphore;
    private final AtomicReference<TdLibOperationState> state;
    private final AtomicReference<Instant> operationStartTime;
    private final AtomicReference<String> currentOperation;

    /**
     * Creates a new TdLibOperationCoordinator.
     *
     * @param telegramClientManager the Telegram client manager for obtaining a client
     */
    @Autowired
    public TdLibOperationCoordinator(TelegramClientManager telegramClientManager) {
        this.telegramClientManager = telegramClientManager;
        this.semaphore = new Semaphore(1, true);
        this.state = new AtomicReference<>(TdLibOperationState.IDLE);
        this.operationStartTime = new AtomicReference<>(null);
        this.currentOperation = new AtomicReference<>(null);
    }

    /**
     * Package-private constructor for testing with custom semaphore.
     *
     * @param telegramClientManager the Telegram client manager
     * @param semaphore custom semaphore for testing
     */
    TdLibOperationCoordinator(TelegramClientManager telegramClientManager, Semaphore semaphore) {
        this.telegramClientManager = telegramClientManager;
        this.semaphore = semaphore;
        this.state = new AtomicReference<>(TdLibOperationState.IDLE);
        this.operationStartTime = new AtomicReference<>(null);
        this.currentOperation = new AtomicReference<>(null);
    }

    /**
     * Package-private constructor for unit tests that supply a fake TelegramClientFacade directly.
     * Wraps the client in a minimal manager stub.
     *
     * @param client the fake client for testing
     */
    TdLibOperationCoordinator(TelegramClientFacade client) {
        this(wrapInStubManager(client));
    }

    /**
     * Package-private constructor for unit tests with a custom semaphore and fake client.
     *
     * @param client    the fake client for testing
     * @param semaphore custom semaphore for testing
     */
    TdLibOperationCoordinator(TelegramClientFacade client, Semaphore semaphore) {
        this(wrapInStubManager(client), semaphore);
    }

    private static TelegramClientManager wrapInStubManager(TelegramClientFacade client) {
        return new TelegramClientManager(null, null, null, null, false, "", null, null) {
            @Override
            public TelegramClientFacade getAnyClient() {
                return client;
            }
        };
    }

    /**
     * Executes a LoadChats request with serialization.
     *
     * <p>This method ensures that only one LoadChats operation can be in-flight at a time.
     * If another operation is already running, this method will wait up to the acquire
     * timeout before failing.</p>
     *
     * <p>The operation flow is:</p>
     * <ol>
     *   <li>Acquire semaphore (blocking with timeout)</li>
     *   <li>Update state to LOADING</li>
     *   <li>Execute LoadChats request</li>
     *   <li>Update state to COMPLETED or ERROR</li>
     *   <li>Release semaphore</li>
     * </ol>
     *
     * @param chatList the chat list to load (Main or Archive)
     * @param limit maximum number of chats to load
     * @return a Mono that completes when the operation finishes (success or expected 404)
     */
    public Mono<Void> loadChatsSequentially(TdApi.ChatList chatList, int limit) {
        return loadChatsSequentially(chatList, limit, DEFAULT_ACQUIRE_TIMEOUT, DEFAULT_OPERATION_TIMEOUT);
    }

    /**
     * Executes a LoadChats request with serialization and custom timeouts.
     *
     * @param chatList the chat list to load (Main or Archive)
     * @param limit maximum number of chats to load
     * @param acquireTimeout timeout for acquiring the semaphore
     * @param operationTimeout timeout for the LoadChats operation itself
     * @return a Mono that completes when the operation finishes
     */
    public Mono<Void> loadChatsSequentially(
            TdApi.ChatList chatList,
            int limit,
            Duration acquireTimeout,
            Duration operationTimeout) {

        String operationName = formatOperationName(chatList);

        return Mono.fromCallable(() -> acquireSemaphore(operationName, acquireTimeout))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(acquired -> {
                if (!acquired) {
                    return Mono.error(new TdLibOperationTimeoutException(
                        "Timeout waiting to acquire lock for " + operationName));
                }
                return executeLoadChats(chatList, limit, operationName, operationTimeout);
            })
            .doFinally(signal -> releaseSemaphoreIfHeld(operationName));
    }

    /**
     * Checks if TDLib is ready for chat discovery operations.
     *
     * @return a Mono emitting true if TDLib is authorized and ready
     */
    public Mono<Boolean> isTdLibReady() {
        TelegramClientFacade client = telegramClientManager.getAnyClient();
        if (client == null) {
            return Mono.just(false);
        }
        return Mono.fromFuture(() -> client.send(new TdApi.GetAuthorizationState()))
            .map(authState -> authState instanceof TdApi.AuthorizationStateReady)
            .timeout(Duration.ofSeconds(5))
            .onErrorReturn(false);
    }

    /**
     * Attempts to repair corrupted TDLib dialog date state without deleting the database.
     *
     * <p>This method tries to reset the internal pagination state by:</p>
     * <ol>
     *   <li>Closing and reopening the chat list to reset internal cursors</li>
     *   <li>Requesting chats from the beginning to re-establish valid pagination</li>
     * </ol>
     *
     * <p>This should be called when the dialog date inconsistency error is detected.</p>
     *
     * @return a Mono that completes when repair is attempted
     */
    public Mono<RepairResult> repairDialogState() {
        log.info("Attempting to repair TDLib dialog date state...");
        return Mono.fromCallable(() -> acquireSemaphore("RepairDialogState", DEFAULT_ACQUIRE_TIMEOUT))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(acquired -> {
                if (!acquired) {
                    return Mono.just(new RepairResult(false, "Could not acquire lock for repair"));
                }
                return executeRepair();
            })
            .doFinally(signal -> releaseSemaphoreIfHeld("RepairDialogState"));
    }

    private Mono<RepairResult> executeRepair() {
        TelegramClientFacade client = telegramClientManager.getAnyClient();
        if (client == null) {
            state.set(TdLibOperationState.ERROR);
            return Mono.just(new RepairResult(false, "No Telegram client available for repair"));
        }
        state.set(TdLibOperationState.LOADING);
        operationStartTime.set(Instant.now());
        currentOperation.set("RepairDialogState");
        log.info("Step 1: Resetting chat list position by requesting fresh chat list...");
        return Mono.fromFuture(() -> client.send(new TdApi.GetChats(new TdApi.ChatListMain(), 1)))
            .timeout(Duration.ofSeconds(30))
            .doOnNext(chats -> log.info("Step 1 complete: GetChats(Main, 1) returned {} chats",
                ((TdApi.Chats) chats).chatIds.length))
            .then(Mono.fromFuture(() -> client.send(new TdApi.GetChats(new TdApi.ChatListArchive(), 1))))
            .timeout(Duration.ofSeconds(30))
            .doOnNext(chats -> log.info("Step 2 complete: GetChats(Archive, 1) returned {} chats",
                ((TdApi.Chats) chats).chatIds.length))
            .then(Mono.defer(() -> {
                log.info("Step 3: Attempting small LoadChats to verify state...");
                return Mono.fromFuture(() -> client.send(new TdApi.LoadChats(new TdApi.ChatListMain(), 10)))
                    .timeout(Duration.ofSeconds(30))
                    .then(Mono.just(new RepairResult(true, "Dialog state repair completed successfully")))
                    .onErrorResume(error -> {
                        String msg = error.getMessage();
                        if (msg != null && msg.contains("404")) {
                            log.info("LoadChats returned 404 (all chats loaded) - repair successful");
                            return Mono.just(new RepairResult(true, "Dialog state repair completed (all chats already loaded)"));
                        }
                        if (msg != null && msg.contains("dialog date")) {
                            log.warn("Dialog date error persists - database may need to be deleted");
                            return Mono.just(new RepairResult(false,
                                "Dialog date corruption persists. Manual intervention required: delete tdlib-sessions/ directory"));
                        }
                        log.warn("Repair verification failed: {}", msg);
                        return Mono.just(new RepairResult(false, "Repair verification failed: " + msg));
                    });
            }))
            .doFinally(signal -> {
                state.set(TdLibOperationState.COMPLETED);
                operationStartTime.set(null);
                currentOperation.set(null);
            })
            .onErrorResume(error -> {
                log.error("Dialog state repair failed: {}", error.getMessage());
                state.set(TdLibOperationState.ERROR);
                return Mono.just(new RepairResult(false, "Repair failed: " + error.getMessage()));
            });
    }

    /**
     * Result of a dialog state repair attempt.
     *
     * @param success whether the repair was successful
     * @param message descriptive message about the repair outcome
     */
    public record RepairResult(boolean success, String message) {}

    /**
     * Returns the current state of LoadChats operations.
     *
     * @return the current operation state
     */
    public TdLibOperationState getState() {
        return state.get();
    }

    /**
     * Returns whether an operation is currently in progress.
     *
     * @return true if a LoadChats operation is running
     */
    public boolean isOperationInProgress() {
        return state.get() == TdLibOperationState.LOADING;
    }

    /**
     * Returns the start time of the current operation, if any.
     *
     * @return the operation start time, or null if no operation is running
     */
    public Instant getOperationStartTime() {
        return operationStartTime.get();
    }

    /**
     * Returns the name of the current operation, if any.
     *
     * @return the operation name (e.g., "LoadChats(ChatListMain)"), or null
     */
    public String getCurrentOperation() {
        return currentOperation.get();
    }

    /**
     * Returns the duration of the current operation, if any.
     *
     * @return the duration since operation started, or Duration.ZERO if no operation
     */
    public Duration getCurrentOperationDuration() {
        Instant start = operationStartTime.get();
        if (start == null) {
            return Duration.ZERO;
        }
        return Duration.between(start, Instant.now());
    }

    /**
     * Attempts to force-release the semaphore if an operation appears stuck.
     *
     * <p>WARNING: This method should only be used for recovery from stuck states.
     * Using it during normal operation can cause concurrent LoadChats requests.</p>
     *
     * @param stuckThreshold minimum duration an operation must be running to be considered stuck
     * @return true if the semaphore was released, false if no stuck operation was detected
     */
    public boolean forceReleaseIfStuck(Duration stuckThreshold) {
        Duration currentDuration = getCurrentOperationDuration();
        if (currentDuration.compareTo(stuckThreshold) > 0 && isOperationInProgress()) {
            log.warn("Force-releasing stuck operation '{}' after {}",
                currentOperation.get(), currentDuration);
            state.set(TdLibOperationState.ERROR);
            operationStartTime.set(null);
            currentOperation.set(null);
            if (semaphore.availablePermits() == 0) {
                semaphore.release();
            }
            return true;
        }
        return false;
    }

    private boolean acquireSemaphore(String operationName, Duration timeout) {
        log.debug("Attempting to acquire semaphore for {} (timeout: {})", operationName, timeout);
        try {
            boolean acquired = semaphore.tryAcquire(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (acquired) {
                log.debug("Semaphore acquired for {}", operationName);
            } else {
                log.warn("Failed to acquire semaphore for {} within {}", operationName, timeout);
            }
            return acquired;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for semaphore for {}", operationName);
            return false;
        }
    }

    private Mono<Void> executeLoadChats(
            TdApi.ChatList chatList,
            int limit,
            String operationName,
            Duration timeout) {

        TelegramClientFacade client = telegramClientManager.getAnyClient();
        if (client == null) {
            state.set(TdLibOperationState.ERROR);
            return Mono.error(new IllegalStateException("No Telegram client available for " + operationName));
        }

        state.set(TdLibOperationState.LOADING);
        operationStartTime.set(Instant.now());
        currentOperation.set(operationName);

        log.info("Starting serialized {} with limit {}", operationName, limit);

        TdApi.LoadChats request = new TdApi.LoadChats(chatList, limit);

        return Mono.fromFuture(() -> client.send(request))
            .timeout(timeout)
            .doOnSuccess(result -> handleLoadChatsSuccess(operationName))
            .then()
            .onErrorResume(error -> handleExpected404Error(error, operationName));
    }

    private void handleLoadChatsSuccess(String operationName) {
        Duration duration = getCurrentOperationDuration();
        log.info("Completed {} successfully in {}", operationName, duration);
        state.set(TdLibOperationState.COMPLETED);
        operationStartTime.set(null);
        currentOperation.set(null);
    }

    private Mono<Void> handleExpected404Error(Throwable error, String operationName) {
        Duration duration = getCurrentOperationDuration();
        String errorMessage = error.getMessage();
        if (isExpected404Error(errorMessage)) {
            log.info("Completed {} in {} (404 - all chats loaded)", operationName, duration);
            state.set(TdLibOperationState.COMPLETED);
            operationStartTime.set(null);
            currentOperation.set(null);
            return Mono.empty();
        }
        log.error("Failed {} after {}: {}", operationName, duration, errorMessage);
        state.set(TdLibOperationState.ERROR);
        operationStartTime.set(null);
        currentOperation.set(null);
        return Mono.error(error);
    }

    private boolean isExpected404Error(String errorMessage) {
        return errorMessage != null && errorMessage.contains("404");
    }

    private void releaseSemaphoreIfHeld(String operationName) {
        if (semaphore.availablePermits() == 0) {
            semaphore.release();
            log.debug("Semaphore released after {}", operationName);
            if (state.get() == TdLibOperationState.LOADING) {
                state.set(TdLibOperationState.IDLE);
            }
        }
    }

    private String formatOperationName(TdApi.ChatList chatList) {
        if (chatList instanceof TdApi.ChatListMain) {
            return "LoadChats(ChatListMain)";
        } else if (chatList instanceof TdApi.ChatListArchive) {
            return "LoadChats(ChatListArchive)";
        } else if (chatList instanceof TdApi.ChatListFolder) {
            TdApi.ChatListFolder folder = (TdApi.ChatListFolder) chatList;
            return "LoadChats(ChatListFolder:" + folder.chatFolderId + ")";
        }
        return "LoadChats(Unknown)";
    }

    /**
     * Exception thrown when a LoadChats operation cannot acquire the semaphore in time.
     */
    public static final class TdLibOperationTimeoutException extends RuntimeException {
        /**
         * Creates a new timeout exception.
         *
         * @param message the error message
         */
        public TdLibOperationTimeoutException(String message) {
            super(message);
        }
    }
}
