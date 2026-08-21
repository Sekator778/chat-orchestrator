package com.example.telegramuserbot.service;

import com.example.telegramuserbot.config.BotInstanceProvider;
import com.example.telegramuserbot.domain.TdLibOperation;
import com.example.telegramuserbot.domain.TdLibOperationStatus;
import com.example.telegramuserbot.domain.TdLibOperationType;
import com.example.telegramuserbot.repository.TdLibOperationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TdLibOperationLockService.
 * Tests distributed lock acquisition, release, and operation management.
 */
class TdLibOperationLockServiceTest {

    private TdLibOperationLockService service;
    private TdLibOperationRepository repository;
    private BotInstanceProvider instanceProvider;

    private static final String BOT_INSTANCE_ID = "test-bot-instance";

    @BeforeEach
    void setUp() {
        repository = mock(TdLibOperationRepository.class);
        instanceProvider = mock(BotInstanceProvider.class);
        when(instanceProvider.getInstanceId()).thenReturn(BOT_INSTANCE_ID);
        service = new TdLibOperationLockService(repository, instanceProvider);
    }

    @Test
    void tryAcquireLockReturnsOperationWhenLockAcquired() {
        TdLibOperation operation = createOperation(TdLibOperationType.CHAT_DISCOVERY);
        when(repository.tryAcquireLock(
            eq(TdLibOperationType.CHAT_DISCOVERY.name()),
            eq(BOT_INSTANCE_ID),
            any(),
            any(OffsetDateTime.class),
            any(OffsetDateTime.class)
        )).thenReturn(Mono.just(operation));

        StepVerifier.create(service.tryAcquireLock(TdLibOperationType.CHAT_DISCOVERY))
            .assertNext(result -> {
                assertThat(result.getOperationType(), is(TdLibOperationType.CHAT_DISCOVERY));
                assertThat(result.getBotInstanceId(), is(BOT_INSTANCE_ID));
            })
            .verifyComplete();
    }

    @Test
    void tryAcquireLockReturnsEmptyWhenLockAlreadyHeld() {
        when(repository.tryAcquireLock(
            anyString(), anyString(), any(), any(OffsetDateTime.class), any(OffsetDateTime.class)
        )).thenReturn(Mono.empty());

        StepVerifier.create(service.tryAcquireLock(TdLibOperationType.CHAT_DISCOVERY))
            .verifyComplete();
    }

    @Test
    void tryAcquireLockWithResourceIdPassesResourceToRepository() {
        TdLibOperation operation = createOperation(TdLibOperationType.MESSAGE_SYNC);
        operation.setResourceId("chat-12345");
        String resourceId = "chat-12345";

        when(repository.tryAcquireLock(
            eq(TdLibOperationType.MESSAGE_SYNC.name()),
            eq(BOT_INSTANCE_ID),
            eq(resourceId),
            any(OffsetDateTime.class),
            any(OffsetDateTime.class)
        )).thenReturn(Mono.just(operation));

        StepVerifier.create(service.tryAcquireLock(TdLibOperationType.MESSAGE_SYNC, resourceId))
            .assertNext(result -> {
                assertThat(result.getResourceId(), is(resourceId));
            })
            .verifyComplete();

        verify(repository).tryAcquireLock(
            eq(TdLibOperationType.MESSAGE_SYNC.name()),
            eq(BOT_INSTANCE_ID),
            eq(resourceId),
            any(OffsetDateTime.class),
            any(OffsetDateTime.class)
        );
    }

    @Test
    void tryAcquireLockWithCustomTimeoutSetsTimeoutCorrectly() {
        TdLibOperation operation = createOperation(TdLibOperationType.CHAT_DISCOVERY);
        Duration customTimeout = Duration.ofMinutes(10);
        AtomicReference<OffsetDateTime> capturedTimeout = new AtomicReference<>();

        when(repository.tryAcquireLock(
            anyString(), anyString(), any(), any(OffsetDateTime.class), any(OffsetDateTime.class)
        )).thenAnswer(invocation -> {
            capturedTimeout.set(invocation.getArgument(4));
            return Mono.just(operation);
        });

        service.tryAcquireLock(TdLibOperationType.CHAT_DISCOVERY, null, customTimeout).block();

        OffsetDateTime now = OffsetDateTime.now();
        assertThat(capturedTimeout.get(), is(notNullValue()));
        assertThat(capturedTimeout.get().isAfter(now.plusMinutes(9)), is(true));
        assertThat(capturedTimeout.get().isBefore(now.plusMinutes(11)), is(true));
    }

    @Test
    void releaseLockCompletesOperationSuccessfully() {
        TdLibOperation operation = createOperation(TdLibOperationType.CHAT_DISCOVERY);
        operation.setId(1L);

        when(repository.releaseLock(
            eq(1L),
            eq(TdLibOperationStatus.COMPLETED.name()),
            any(OffsetDateTime.class),
            any()
        )).thenReturn(Mono.just(1));

        StepVerifier.create(service.releaseLock(operation))
            .verifyComplete();

        verify(repository).releaseLock(
            eq(1L),
            eq(TdLibOperationStatus.COMPLETED.name()),
            any(OffsetDateTime.class),
            isNull()
        );
    }

    @Test
    void releaseLockWithErrorSetsFailedStatus() {
        TdLibOperation operation = createOperation(TdLibOperationType.CHAT_DISCOVERY);
        operation.setId(1L);
        String errorMessage = "Connection timeout occurred";

        when(repository.releaseLock(any(), anyString(), any(OffsetDateTime.class), any()))
            .thenReturn(Mono.just(1));

        StepVerifier.create(service.releaseLockWithError(operation, errorMessage))
            .verifyComplete();

        verify(repository).releaseLock(
            eq(1L),
            eq(TdLibOperationStatus.FAILED.name()),
            any(OffsetDateTime.class),
            eq(errorMessage)
        );
    }

    @Test
    void releaseLockHandlesNullOperation() {
        StepVerifier.create(service.releaseLock(null))
            .verifyComplete();

        verifyNoInteractions(repository);
    }

    @Test
    void releaseLockHandlesOperationWithNullId() {
        TdLibOperation operation = createOperation(TdLibOperationType.CHAT_DISCOVERY);
        operation.setId(null);

        StepVerifier.create(service.releaseLock(operation))
            .verifyComplete();

        verify(repository, never()).releaseLock(any(), anyString(), any(), any());
    }

    @Test
    void executeWithLockAcquiresAndReleasesLockOnSuccess() {
        TdLibOperation operation = createOperation(TdLibOperationType.CHAT_DISCOVERY);
        operation.setId(1L);
        AtomicInteger operationExecuted = new AtomicInteger(0);

        when(repository.tryAcquireLock(
            anyString(), anyString(), any(), any(OffsetDateTime.class), any(OffsetDateTime.class)
        )).thenReturn(Mono.just(operation));
        when(repository.releaseLock(any(), anyString(), any(OffsetDateTime.class), any()))
            .thenReturn(Mono.just(1));

        Mono<String> operationMono = Mono.fromCallable(() -> {
            operationExecuted.incrementAndGet();
            return "result";
        });

        StepVerifier.create(service.executeWithLock(TdLibOperationType.CHAT_DISCOVERY, operationMono))
            .expectNext("result")
            .verifyComplete();

        assertThat(operationExecuted.get(), is(1));
    }

    @Test
    void executeWithLockReleasesLockOnError() {
        TdLibOperation operation = createOperation(TdLibOperationType.CHAT_DISCOVERY);
        operation.setId(1L);
        String errorMessage = "Operation failed unexpectedly";

        when(repository.tryAcquireLock(
            anyString(), anyString(), any(), any(OffsetDateTime.class), any(OffsetDateTime.class)
        )).thenReturn(Mono.just(operation));
        when(repository.releaseLock(any(), anyString(), any(OffsetDateTime.class), any()))
            .thenReturn(Mono.just(1));

        Mono<String> failingOperation = Mono.error(new RuntimeException(errorMessage));

        StepVerifier.create(service.executeWithLock(TdLibOperationType.CHAT_DISCOVERY, failingOperation))
            .expectError(RuntimeException.class)
            .verify();

        verify(repository).releaseLock(
            eq(1L),
            eq(TdLibOperationStatus.FAILED.name()),
            any(OffsetDateTime.class),
            eq(errorMessage)
        );
    }

    @Test
    void executeWithLockThrowsExceptionWhenLockNotAcquired() {
        when(repository.tryAcquireLock(
            anyString(), anyString(), any(), any(OffsetDateTime.class), any(OffsetDateTime.class)
        )).thenReturn(Mono.empty());

        Mono<String> operationMono = Mono.just("result");

        StepVerifier.create(service.executeWithLock(TdLibOperationType.CHAT_DISCOVERY, operationMono))
            .expectError(TdLibOperationLockService.LockNotAcquiredException.class)
            .verify();
    }

    @Test
    void executeWithLockWithSupplierAcquiresAndReleasesLock() {
        TdLibOperation operation = createOperation(TdLibOperationType.CHAT_DISCOVERY);
        operation.setId(1L);
        AtomicInteger supplierCalled = new AtomicInteger(0);

        when(repository.tryAcquireLock(
            anyString(), anyString(), any(), any(OffsetDateTime.class), any(OffsetDateTime.class)
        )).thenReturn(Mono.just(operation));
        when(repository.releaseLock(any(), anyString(), any(OffsetDateTime.class), any()))
            .thenReturn(Mono.just(1));

        StepVerifier.create(service.executeWithLock(
            TdLibOperationType.CHAT_DISCOVERY,
            () -> {
                supplierCalled.incrementAndGet();
                return Mono.just("supplier-result");
            }
        ))
            .expectNext("supplier-result")
            .verifyComplete();

        assertThat(supplierCalled.get(), is(1));
    }

    @Test
    void updateHeartbeatCallsRepository() {
        TdLibOperation operation = createOperation(TdLibOperationType.CHAT_DISCOVERY);
        operation.setId(1L);

        when(repository.updateHeartbeat(eq(1L), any(OffsetDateTime.class)))
            .thenReturn(Mono.just(1));

        StepVerifier.create(service.updateHeartbeat(operation))
            .verifyComplete();

        verify(repository).updateHeartbeat(eq(1L), any(OffsetDateTime.class));
    }

    @Test
    void updateHeartbeatHandlesNullOperation() {
        StepVerifier.create(service.updateHeartbeat(null))
            .verifyComplete();

        verify(repository, never()).updateHeartbeat(any(), any());
    }

    @Test
    void isOperationInProgressReturnsTrueWhenOperationExists() {
        TdLibOperation operation = createOperation(TdLibOperationType.CHAT_DISCOVERY);

        when(repository.findActiveOperation(
            eq(TdLibOperationType.CHAT_DISCOVERY.name()),
            eq(BOT_INSTANCE_ID)
        )).thenReturn(Mono.just(operation));

        StepVerifier.create(service.isOperationInProgress(TdLibOperationType.CHAT_DISCOVERY))
            .expectNext(true)
            .verifyComplete();
    }

    @Test
    void isOperationInProgressReturnsFalseWhenNoOperation() {
        when(repository.findActiveOperation(anyString(), anyString()))
            .thenReturn(Mono.empty());

        StepVerifier.create(service.isOperationInProgress(TdLibOperationType.CHAT_DISCOVERY))
            .expectNext(false)
            .verifyComplete();
    }

    @Test
    void isOperationInProgressGloballyReturnsTrueWhenOperationExistsOnAnyInstance() {
        TdLibOperation operation = createOperation(TdLibOperationType.CHAT_DISCOVERY);
        operation.setBotInstanceId("other-instance");

        when(repository.findActiveOperationGlobal(eq(TdLibOperationType.CHAT_DISCOVERY.name())))
            .thenReturn(Mono.just(operation));

        StepVerifier.create(service.isOperationInProgressGlobally(TdLibOperationType.CHAT_DISCOVERY))
            .expectNext(true)
            .verifyComplete();
    }

    @Test
    void findActiveOperationsReturnsOperationsForCurrentInstance() {
        TdLibOperation op1 = createOperation(TdLibOperationType.CHAT_DISCOVERY);
        TdLibOperation op2 = createOperation(TdLibOperationType.MESSAGE_SYNC);

        when(repository.findActiveOperationsForInstance(eq(BOT_INSTANCE_ID)))
            .thenReturn(Flux.just(op1, op2));

        StepVerifier.create(service.findActiveOperations())
            .expectNext(op1)
            .expectNext(op2)
            .verifyComplete();
    }

    @Test
    void cleanupStaleOperationsMarksStaleOperationsAsTimeout() {
        when(repository.markStaleOperationsAsTimeout(any(OffsetDateTime.class)))
            .thenReturn(Mono.just(3));

        StepVerifier.create(service.cleanupStaleOperations())
            .expectNext(3)
            .verifyComplete();

        verify(repository).markStaleOperationsAsTimeout(any(OffsetDateTime.class));
    }

    @Test
    void deleteOldOperationsRemovesCompletedOperations() {
        when(repository.deleteOldOperations(any(OffsetDateTime.class)))
            .thenReturn(Mono.just(5));

        StepVerifier.create(service.deleteOldOperations())
            .expectNext(5)
            .verifyComplete();

        verify(repository).deleteOldOperations(any(OffsetDateTime.class));
    }

    @Test
    void findRecentOperationsReturnsOperationsWithinLookback() {
        TdLibOperation op1 = createOperation(TdLibOperationType.CHAT_DISCOVERY);
        Duration lookback = Duration.ofHours(1);

        when(repository.findRecentOperations(eq(BOT_INSTANCE_ID), any(OffsetDateTime.class)))
            .thenReturn(Flux.just(op1));

        StepVerifier.create(service.findRecentOperations(lookback))
            .expectNext(op1)
            .verifyComplete();
    }

    @Test
    void getHeartbeatIntervalReturnsExpectedDuration() {
        Duration interval = service.getHeartbeatInterval();
        assertThat(interval, is(Duration.ofSeconds(30)));
    }

    @Test
    void lockNotAcquiredExceptionContainsMessage() {
        TdLibOperationLockService.LockNotAcquiredException exception =
            new TdLibOperationLockService.LockNotAcquiredException("Test message");
        assertThat(exception.getMessage(), is("Test message"));
    }

    @Test
    void tryAcquireLockUsesCorrectBotInstanceId() {
        TdLibOperation operation = createOperation(TdLibOperationType.CHAT_DISCOVERY);

        when(repository.tryAcquireLock(
            anyString(), eq(BOT_INSTANCE_ID), any(), any(OffsetDateTime.class), any(OffsetDateTime.class)
        )).thenReturn(Mono.just(operation));

        service.tryAcquireLock(TdLibOperationType.CHAT_DISCOVERY).block();

        verify(instanceProvider).getInstanceId();
        verify(repository).tryAcquireLock(
            anyString(), eq(BOT_INSTANCE_ID), any(), any(OffsetDateTime.class), any(OffsetDateTime.class)
        );
    }

    private TdLibOperation createOperation(TdLibOperationType type) {
        TdLibOperation operation = new TdLibOperation(type, BOT_INSTANCE_ID);
        operation.setId(1L);
        return operation;
    }
}
