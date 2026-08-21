package com.example.telegramuserbot.service;

import com.example.telegramuserbot.config.BotInstanceProvider;
import com.example.telegramuserbot.domain.TdLibOperation;
import com.example.telegramuserbot.domain.TdLibOperationStatus;
import com.example.telegramuserbot.domain.TdLibOperationType;
import com.example.telegramuserbot.repository.TdLibOperationRepository;
import com.example.telegramuserbot.service.TdLibRecoveryService.*;
import com.example.telegramuserbot.telegram.TdLibOperationCoordinator;
import com.example.telegramuserbot.telegram.TdLibOperationState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.OffsetDateTime;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TdLibRecoveryService.
 * Tests recovery mechanisms for stuck operations and inconsistent states.
 */
class TdLibRecoveryServiceTest {

    private TdLibRecoveryService service;
    private TdLibOperationCoordinator coordinator;
    private TdLibOperationRepository repository;
    private BotInstanceProvider instanceProvider;

    private static final String BOT_INSTANCE_ID = "test-bot-instance";

    @BeforeEach
    void setUp() {
        coordinator = mock(TdLibOperationCoordinator.class);
        repository = mock(TdLibOperationRepository.class);
        instanceProvider = mock(BotInstanceProvider.class);
        when(instanceProvider.getInstanceId()).thenReturn(BOT_INSTANCE_ID);
        service = new TdLibRecoveryService(coordinator, repository, instanceProvider);
    }

    @Test
    void recoverStuckCoordinatorOperationReturnsNoActionWhenNotInProgress() {
        when(coordinator.isOperationInProgress()).thenReturn(false);

        StepVerifier.create(service.recoverStuckCoordinatorOperation())
            .assertNext(action -> {
                assertThat(action.action(), is(ActionType.NO_ACTION));
                assertThat(action.message(), containsString("No operation in progress"));
            })
            .verifyComplete();
    }

    @Test
    void recoverStuckCoordinatorOperationReturnsNoActionWhenNotStuck() {
        when(coordinator.isOperationInProgress()).thenReturn(true);
        when(coordinator.getCurrentOperationDuration()).thenReturn(Duration.ofMinutes(2));
        when(coordinator.getCurrentOperation()).thenReturn("LoadChats(ChatListMain)");
        when(coordinator.getState()).thenReturn(TdLibOperationState.LOADING);

        StepVerifier.create(service.recoverStuckCoordinatorOperation())
            .assertNext(action -> {
                assertThat(action.action(), is(ActionType.NO_ACTION));
                assertThat(action.message(), containsString("running normally"));
            })
            .verifyComplete();
    }

    @Test
    void recoverStuckCoordinatorOperationForceReleasesStuckOperation() {
        when(coordinator.isOperationInProgress()).thenReturn(true);
        when(coordinator.getCurrentOperationDuration()).thenReturn(Duration.ofMinutes(15));
        when(coordinator.getCurrentOperation()).thenReturn("LoadChats(ChatListMain)");
        when(coordinator.getState()).thenReturn(TdLibOperationState.LOADING);
        when(coordinator.forceReleaseIfStuck(any(Duration.class))).thenReturn(true);

        StepVerifier.create(service.recoverStuckCoordinatorOperation())
            .assertNext(action -> {
                assertThat(action.action(), is(ActionType.FORCE_RELEASED));
                assertThat(action.message(), containsString("Force-released"));
                assertThat(action.details(), containsString("LoadChats"));
            })
            .verifyComplete();

        verify(coordinator).forceReleaseIfStuck(any(Duration.class));
    }

    @Test
    void recoverStuckCoordinatorOperationReturnsFailedWhenForceReleaseFails() {
        when(coordinator.isOperationInProgress()).thenReturn(true);
        when(coordinator.getCurrentOperationDuration()).thenReturn(Duration.ofMinutes(15));
        when(coordinator.getCurrentOperation()).thenReturn("LoadChats(ChatListMain)");
        when(coordinator.getState()).thenReturn(TdLibOperationState.LOADING);
        when(coordinator.forceReleaseIfStuck(any(Duration.class))).thenReturn(false);

        StepVerifier.create(service.recoverStuckCoordinatorOperation())
            .assertNext(action -> {
                assertThat(action.action(), is(ActionType.FAILED));
                assertThat(action.message(), containsString("Failed to force-release"));
            })
            .verifyComplete();
    }

    @Test
    void recoverStuckCoordinatorOperationUsesCustomThreshold() {
        Duration customThreshold = Duration.ofMinutes(5);
        when(coordinator.isOperationInProgress()).thenReturn(true);
        when(coordinator.getCurrentOperationDuration()).thenReturn(Duration.ofMinutes(6));
        when(coordinator.getCurrentOperation()).thenReturn("LoadChats(ChatListMain)");
        when(coordinator.getState()).thenReturn(TdLibOperationState.LOADING);
        when(coordinator.forceReleaseIfStuck(eq(customThreshold))).thenReturn(true);

        StepVerifier.create(service.recoverStuckCoordinatorOperation(customThreshold))
            .assertNext(action -> {
                assertThat(action.action(), is(ActionType.FORCE_RELEASED));
            })
            .verifyComplete();

        verify(coordinator).forceReleaseIfStuck(customThreshold);
    }

    @Test
    void cleanupStaleLocksReturnsNoActionWhenNoStaleOperations() {
        when(repository.findStaleOperations(any(OffsetDateTime.class)))
            .thenReturn(Flux.empty());

        StepVerifier.create(service.cleanupStaleLocks())
            .assertNext(action -> {
                assertThat(action.action(), is(ActionType.NO_ACTION));
                assertThat(action.message(), containsString("No stale locks"));
            })
            .verifyComplete();
    }

    @Test
    void cleanupStaleLocksMarksStaleOperationsAsTimeout() {
        TdLibOperation staleOp = createStaleOperation();
        when(repository.findStaleOperations(any(OffsetDateTime.class)))
            .thenReturn(Flux.just(staleOp));
        when(repository.markStaleOperationsAsTimeout(any(OffsetDateTime.class)))
            .thenReturn(Mono.just(1));

        StepVerifier.create(service.cleanupStaleLocks())
            .assertNext(action -> {
                assertThat(action.action(), is(ActionType.CLEANED_UP));
                assertThat(action.message(), containsString("1 stale operations"));
            })
            .verifyComplete();
    }

    @Test
    void cleanupStaleLocksHandlesCleanupFailure() {
        TdLibOperation staleOp = createStaleOperation();
        when(repository.findStaleOperations(any(OffsetDateTime.class)))
            .thenReturn(Flux.just(staleOp));
        when(repository.markStaleOperationsAsTimeout(any(OffsetDateTime.class)))
            .thenReturn(Mono.error(new RuntimeException("Database error")));

        StepVerifier.create(service.cleanupStaleLocks())
            .assertNext(action -> {
                assertThat(action.action(), is(ActionType.FAILED));
                assertThat(action.message(), containsString("Database error"));
            })
            .verifyComplete();
    }

    @Test
    void reconcileStateReturnsNoActionWhenBothIdle() {
        when(repository.findActiveOperationsForInstance(BOT_INSTANCE_ID))
            .thenReturn(Flux.empty());
        when(coordinator.isOperationInProgress()).thenReturn(false);

        StepVerifier.create(service.reconcileState())
            .assertNext(action -> {
                assertThat(action.action(), is(ActionType.NO_ACTION));
                assertThat(action.message(), containsString("both idle"));
            })
            .verifyComplete();
    }

    @Test
    void reconcileStateReturnsNoActionWhenBothActive() {
        TdLibOperation activeOp = createOperation(TdLibOperationType.CHAT_DISCOVERY);
        when(repository.findActiveOperationsForInstance(BOT_INSTANCE_ID))
            .thenReturn(Flux.just(activeOp));
        when(coordinator.isOperationInProgress()).thenReturn(true);

        StepVerifier.create(service.reconcileState())
            .assertNext(action -> {
                assertThat(action.action(), is(ActionType.NO_ACTION));
                assertThat(action.message(), containsString("both active"));
            })
            .verifyComplete();
    }

    @Test
    void reconcileStateDetectsInconsistencyWhenCoordinatorActiveButDbIdle() {
        when(repository.findActiveOperationsForInstance(BOT_INSTANCE_ID))
            .thenReturn(Flux.empty());
        when(coordinator.isOperationInProgress()).thenReturn(true);
        when(coordinator.getCurrentOperation()).thenReturn("LoadChats(ChatListMain)");

        StepVerifier.create(service.reconcileState())
            .assertNext(action -> {
                assertThat(action.action(), is(ActionType.INCONSISTENCY_DETECTED));
                assertThat(action.message(), containsString("no database record"));
            })
            .verifyComplete();
    }

    @Test
    void reconcileStateDetectsInconsistencyWhenDbActiveButCoordinatorIdle() {
        TdLibOperation activeOp = createOperation(TdLibOperationType.CHAT_DISCOVERY);
        when(repository.findActiveOperationsForInstance(BOT_INSTANCE_ID))
            .thenReturn(Flux.just(activeOp));
        when(coordinator.isOperationInProgress()).thenReturn(false);

        StepVerifier.create(service.reconcileState())
            .assertNext(action -> {
                assertThat(action.action(), is(ActionType.INCONSISTENCY_DETECTED));
                assertThat(action.message(), containsString("coordinator is idle"));
            })
            .verifyComplete();
    }

    @Test
    void reconcileStateHandlesError() {
        when(repository.findActiveOperationsForInstance(BOT_INSTANCE_ID))
            .thenReturn(Flux.error(new RuntimeException("Connection lost")));

        StepVerifier.create(service.reconcileState())
            .assertNext(action -> {
                assertThat(action.action(), is(ActionType.FAILED));
                assertThat(action.message(), containsString("Connection lost"));
            })
            .verifyComplete();
    }

    @Test
    void recoverOperationReturnsNoActionWhenOperationNotFound() {
        when(repository.findById(1L)).thenReturn(Mono.empty());

        StepVerifier.create(service.recoverOperation(1L))
            .assertNext(action -> {
                assertThat(action.action(), is(ActionType.NO_ACTION));
                assertThat(action.message(), containsString("not found"));
            })
            .verifyComplete();
    }

    @Test
    void recoverOperationReturnsNoActionWhenOperationNotInProgress() {
        TdLibOperation completedOp = createOperation(TdLibOperationType.CHAT_DISCOVERY);
        completedOp.setStatus(TdLibOperationStatus.COMPLETED);
        when(repository.findById(1L)).thenReturn(Mono.just(completedOp));

        StepVerifier.create(service.recoverOperation(1L))
            .assertNext(action -> {
                assertThat(action.action(), is(ActionType.NO_ACTION));
                assertThat(action.message(), containsString("not in progress"));
            })
            .verifyComplete();
    }

    @Test
    void recoverOperationReturnsNoActionWhenOperationNotStale() {
        TdLibOperation freshOp = createOperation(TdLibOperationType.CHAT_DISCOVERY);
        freshOp.setTimeoutAt(OffsetDateTime.now().plusHours(1));
        when(repository.findById(1L)).thenReturn(Mono.just(freshOp));

        StepVerifier.create(service.recoverOperation(1L))
            .assertNext(action -> {
                assertThat(action.action(), is(ActionType.NO_ACTION));
                assertThat(action.message(), containsString("not stale"));
            })
            .verifyComplete();
    }

    @Test
    void recoverOperationRecoverStaleOperation() {
        TdLibOperation staleOp = createStaleOperation();
        when(repository.findById(1L)).thenReturn(Mono.just(staleOp));
        when(repository.releaseLock(
            eq(1L),
            eq(TdLibOperationStatus.TIMEOUT.name()),
            any(OffsetDateTime.class),
            anyString()
        )).thenReturn(Mono.just(1));

        StepVerifier.create(service.recoverOperation(1L))
            .assertNext(action -> {
                assertThat(action.action(), is(ActionType.RECOVERED));
                assertThat(action.message(), containsString("Recovered operation"));
            })
            .verifyComplete();
    }

    @Test
    void recoverOperationReturnsFailedWhenRecoveryFails() {
        TdLibOperation staleOp = createStaleOperation();
        when(repository.findById(1L)).thenReturn(Mono.just(staleOp));
        when(repository.releaseLock(any(), anyString(), any(OffsetDateTime.class), anyString()))
            .thenReturn(Mono.just(0));

        StepVerifier.create(service.recoverOperation(1L))
            .assertNext(action -> {
                assertThat(action.action(), is(ActionType.FAILED));
            })
            .verifyComplete();
    }

    @Test
    void forceCleanupAllActiveOperationsReturnsNoActionWhenNoActiveOps() {
        when(repository.findActiveOperationsForInstance(BOT_INSTANCE_ID))
            .thenReturn(Flux.empty());

        StepVerifier.create(service.forceCleanupAllActiveOperations())
            .assertNext(action -> {
                assertThat(action.action(), is(ActionType.NO_ACTION));
                assertThat(action.message(), containsString("No active operations"));
            })
            .verifyComplete();
    }

    @Test
    void forceCleanupAllActiveOperationsCleansAllOps() {
        TdLibOperation op1 = createOperation(TdLibOperationType.CHAT_DISCOVERY);
        op1.setId(1L);
        TdLibOperation op2 = createOperation(TdLibOperationType.MESSAGE_SYNC);
        op2.setId(2L);

        when(repository.findActiveOperationsForInstance(BOT_INSTANCE_ID))
            .thenReturn(Flux.just(op1, op2));
        when(repository.releaseLock(any(), anyString(), any(OffsetDateTime.class), anyString()))
            .thenReturn(Mono.just(1));

        StepVerifier.create(service.forceCleanupAllActiveOperations())
            .assertNext(action -> {
                assertThat(action.action(), is(ActionType.FORCE_CLEANED));
                assertThat(action.message(), containsString("2 active operations"));
            })
            .verifyComplete();

        verify(repository, times(2)).releaseLock(any(), anyString(), any(OffsetDateTime.class), anyString());
    }

    @Test
    void forceCleanupAllActiveOperationsHandlesError() {
        when(repository.findActiveOperationsForInstance(BOT_INSTANCE_ID))
            .thenReturn(Flux.error(new RuntimeException("Database unavailable")));

        StepVerifier.create(service.forceCleanupAllActiveOperations())
            .assertNext(action -> {
                assertThat(action.action(), is(ActionType.FAILED));
                assertThat(action.message(), containsString("Database unavailable"));
            })
            .verifyComplete();
    }

    @Test
    void performFullRecoveryExecutesAllRecoverySteps() {
        when(coordinator.isOperationInProgress()).thenReturn(false);
        when(repository.findStaleOperations(any(OffsetDateTime.class)))
            .thenReturn(Flux.empty());
        when(repository.findActiveOperationsForInstance(BOT_INSTANCE_ID))
            .thenReturn(Flux.empty());

        StepVerifier.create(service.performFullRecovery())
            .assertNext(result -> {
                assertThat(result.isSuccessful(), is(true));
                assertThat(result.anyActionTaken(), is(false));
                assertThat(result.failedCount(), is(0));
            })
            .verifyComplete();
    }

    @Test
    void performFullRecoveryReportsFailedActions() {
        when(coordinator.isOperationInProgress()).thenReturn(true);
        when(coordinator.getCurrentOperationDuration()).thenReturn(Duration.ofMinutes(15));
        when(coordinator.getCurrentOperation()).thenReturn("StuckOperation");
        when(coordinator.getState()).thenReturn(TdLibOperationState.LOADING);
        when(coordinator.forceReleaseIfStuck(any(Duration.class))).thenReturn(false);

        when(repository.findStaleOperations(any(OffsetDateTime.class)))
            .thenReturn(Flux.error(new RuntimeException("DB error")));
        when(repository.findActiveOperationsForInstance(BOT_INSTANCE_ID))
            .thenReturn(Flux.error(new RuntimeException("DB error")));

        StepVerifier.create(service.performFullRecovery())
            .assertNext(result -> {
                assertThat(result.isSuccessful(), is(false));
                assertThat(result.failedCount(), greaterThan(0));
            })
            .verifyComplete();
    }

    @Test
    void recoveryActionIsSuccessfulReturnsCorrectValues() {
        RecoveryAction noAction = new RecoveryAction(ActionType.NO_ACTION, "", null);
        assertThat(noAction.isSuccessful(), is(true));

        RecoveryAction forceReleased = new RecoveryAction(ActionType.FORCE_RELEASED, "", null);
        assertThat(forceReleased.isSuccessful(), is(true));

        RecoveryAction failed = new RecoveryAction(ActionType.FAILED, "", null);
        assertThat(failed.isSuccessful(), is(false));
    }

    @Test
    void recoveryActionActionTakenReturnsCorrectValues() {
        RecoveryAction noAction = new RecoveryAction(ActionType.NO_ACTION, "", null);
        assertThat(noAction.actionTaken(), is(false));

        RecoveryAction forceReleased = new RecoveryAction(ActionType.FORCE_RELEASED, "", null);
        assertThat(forceReleased.actionTaken(), is(true));
    }

    @Test
    void recoveryResultAnyActionTakenReturnsCorrectValues() {
        RecoveryAction noAction = new RecoveryAction(ActionType.NO_ACTION, "", null);
        RecoveryAction action = new RecoveryAction(ActionType.CLEANED_UP, "", null);

        RecoveryResult allNoAction = new RecoveryResult(noAction, noAction, noAction);
        assertThat(allNoAction.anyActionTaken(), is(false));

        RecoveryResult oneAction = new RecoveryResult(noAction, action, noAction);
        assertThat(oneAction.anyActionTaken(), is(true));
    }

    @Test
    void getDefaultStuckThresholdReturnsExpectedValue() {
        Duration threshold = service.getDefaultStuckThreshold();
        assertThat(threshold, is(Duration.ofMinutes(10)));
    }

    @Test
    void constructorRejectsNullCoordinator() {
        try {
            new TdLibRecoveryService(null, repository, instanceProvider);
            throw new AssertionError("Expected NullPointerException");
        } catch (NullPointerException e) {
            assertThat(e.getMessage(), containsString("coordinator"));
        }
    }

    @Test
    void constructorRejectsNullRepository() {
        try {
            new TdLibRecoveryService(coordinator, null, instanceProvider);
            throw new AssertionError("Expected NullPointerException");
        } catch (NullPointerException e) {
            assertThat(e.getMessage(), containsString("repository"));
        }
    }

    @Test
    void constructorRejectsNullInstanceProvider() {
        try {
            new TdLibRecoveryService(coordinator, repository, null);
            throw new AssertionError("Expected NullPointerException");
        } catch (NullPointerException e) {
            assertThat(e.getMessage(), containsString("instanceProvider"));
        }
    }

    private TdLibOperation createOperation(TdLibOperationType type) {
        TdLibOperation operation = new TdLibOperation(type, BOT_INSTANCE_ID);
        operation.setId(1L);
        operation.setStartedAt(OffsetDateTime.now().minusMinutes(1));
        return operation;
    }

    private TdLibOperation createStaleOperation() {
        TdLibOperation operation = new TdLibOperation(TdLibOperationType.CHAT_DISCOVERY, BOT_INSTANCE_ID);
        operation.setId(1L);
        operation.setStartedAt(OffsetDateTime.now().minusMinutes(30));
        operation.setTimeoutAt(OffsetDateTime.now().minusMinutes(5));
        operation.setStatus(TdLibOperationStatus.IN_PROGRESS);
        return operation;
    }
}
