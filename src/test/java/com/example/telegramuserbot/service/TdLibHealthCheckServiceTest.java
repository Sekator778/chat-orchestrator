package com.example.telegramuserbot.service;

import com.example.telegramuserbot.config.BotInstanceProvider;
import com.example.telegramuserbot.domain.TdLibOperation;
import com.example.telegramuserbot.domain.TdLibOperationStatus;
import com.example.telegramuserbot.domain.TdLibOperationType;
import com.example.telegramuserbot.repository.TdLibOperationRepository;
import com.example.telegramuserbot.service.TdLibHealthCheckService.*;
import com.example.telegramuserbot.telegram.TdLibOperationCoordinator;
import com.example.telegramuserbot.telegram.TdLibOperationState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TdLibHealthCheckService.
 * Tests health monitoring and anomaly detection for TDLib operations.
 */
class TdLibHealthCheckServiceTest {

    private TdLibHealthCheckService service;
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
        service = new TdLibHealthCheckService(coordinator, repository, instanceProvider);
    }

    @Test
    void checkTdLibConnectivityReturnsHealthyWhenTdLibReady() {
        when(coordinator.isTdLibReady()).thenReturn(Mono.just(true));

        StepVerifier.create(service.checkTdLibConnectivity())
            .assertNext(health -> {
                assertThat(health.status(), is(HealthStatus.HEALTHY));
                assertThat(health.message(), containsString("authorized"));
                assertThat(health.details().get("authorized"), is(true));
            })
            .verifyComplete();
    }

    @Test
    void checkTdLibConnectivityReturnsUnhealthyWhenTdLibNotReady() {
        when(coordinator.isTdLibReady()).thenReturn(Mono.just(false));

        StepVerifier.create(service.checkTdLibConnectivity())
            .assertNext(health -> {
                assertThat(health.status(), is(HealthStatus.UNHEALTHY));
                assertThat(health.details().get("authorized"), is(false));
            })
            .verifyComplete();
    }

    @Test
    void checkTdLibConnectivityReturnsUnhealthyOnError() {
        when(coordinator.isTdLibReady()).thenReturn(Mono.error(new RuntimeException("Connection failed")));

        StepVerifier.create(service.checkTdLibConnectivity())
            .assertNext(health -> {
                assertThat(health.status(), is(HealthStatus.UNHEALTHY));
                assertThat(health.message(), containsString("Connection failed"));
            })
            .verifyComplete();
    }

    @Test
    void checkCoordinatorStateReturnsHealthyWhenIdle() {
        when(coordinator.getState()).thenReturn(TdLibOperationState.IDLE);
        when(coordinator.isOperationInProgress()).thenReturn(false);
        when(coordinator.getCurrentOperationDuration()).thenReturn(Duration.ZERO);
        when(coordinator.getCurrentOperation()).thenReturn(null);

        StepVerifier.create(service.checkCoordinatorState())
            .assertNext(health -> {
                assertThat(health.status(), is(HealthStatus.HEALTHY));
                assertThat(health.details().get("operationInProgress"), is(false));
            })
            .verifyComplete();
    }

    @Test
    void checkCoordinatorStateReturnsHealthyWhenShortOperationRunning() {
        when(coordinator.getState()).thenReturn(TdLibOperationState.LOADING);
        when(coordinator.isOperationInProgress()).thenReturn(true);
        when(coordinator.getCurrentOperationDuration()).thenReturn(Duration.ofSeconds(30));
        when(coordinator.getCurrentOperation()).thenReturn("LoadChats(ChatListMain)");

        StepVerifier.create(service.checkCoordinatorState())
            .assertNext(health -> {
                assertThat(health.status(), is(HealthStatus.HEALTHY));
                assertThat(health.details().get("operationInProgress"), is(true));
                assertThat(health.details().get("currentOperation"), is("LoadChats(ChatListMain)"));
            })
            .verifyComplete();
    }

    @Test
    void checkCoordinatorStateReturnsDegradedWhenLongRunningOperation() {
        when(coordinator.getState()).thenReturn(TdLibOperationState.LOADING);
        when(coordinator.isOperationInProgress()).thenReturn(true);
        when(coordinator.getCurrentOperationDuration()).thenReturn(Duration.ofMinutes(10));
        when(coordinator.getCurrentOperation()).thenReturn("LoadChats(ChatListMain)");

        StepVerifier.create(service.checkCoordinatorState())
            .assertNext(health -> {
                assertThat(health.status(), is(HealthStatus.DEGRADED));
                assertThat(health.message(), containsString("Long-running operation"));
            })
            .verifyComplete();
    }

    @Test
    void checkCoordinatorStateReturnsDegradedWhenInErrorState() {
        when(coordinator.getState()).thenReturn(TdLibOperationState.ERROR);
        when(coordinator.isOperationInProgress()).thenReturn(false);
        when(coordinator.getCurrentOperationDuration()).thenReturn(Duration.ZERO);
        when(coordinator.getCurrentOperation()).thenReturn(null);

        StepVerifier.create(service.checkCoordinatorState())
            .assertNext(health -> {
                assertThat(health.status(), is(HealthStatus.DEGRADED));
                assertThat(health.message(), containsString("ERROR"));
            })
            .verifyComplete();
    }

    @Test
    void checkDistributedLockStateReturnsHealthyWhenNoActiveOrStaleOperations() {
        when(repository.findActiveOperationsForInstance(BOT_INSTANCE_ID))
            .thenReturn(Flux.empty());
        when(repository.findStaleOperations(any(OffsetDateTime.class)))
            .thenReturn(Flux.empty());

        StepVerifier.create(service.checkDistributedLockState())
            .assertNext(health -> {
                assertThat(health.status(), is(HealthStatus.HEALTHY));
                assertThat(health.details().get("activeOperations"), is(0));
                assertThat(health.details().get("staleOperations"), is(0));
            })
            .verifyComplete();
    }

    @Test
    void checkDistributedLockStateReturnsDegradedWhenStaleOperationsExist() {
        TdLibOperation staleOp = createOperation(TdLibOperationType.CHAT_DISCOVERY);

        when(repository.findActiveOperationsForInstance(BOT_INSTANCE_ID))
            .thenReturn(Flux.empty());
        when(repository.findStaleOperations(any(OffsetDateTime.class)))
            .thenReturn(Flux.just(staleOp));

        StepVerifier.create(service.checkDistributedLockState())
            .assertNext(health -> {
                assertThat(health.status(), is(HealthStatus.DEGRADED));
                assertThat(health.message(), containsString("stale operations"));
                assertThat(health.details().get("staleOperations"), is(1));
            })
            .verifyComplete();
    }

    @Test
    void checkDistributedLockStateReturnsDegradedWhenTooManyActiveOperations() {
        List<TdLibOperation> manyOps = List.of(
            createOperation(TdLibOperationType.CHAT_DISCOVERY),
            createOperation(TdLibOperationType.MESSAGE_SYNC),
            createOperation(TdLibOperationType.CHANNEL_SYNC_SCHEDULED),
            createOperation(TdLibOperationType.LOAD_CHATS_MAIN),
            createOperation(TdLibOperationType.LOAD_CHATS_ARCHIVE),
            createOperation(TdLibOperationType.HEALTH_CHECK)
        );

        when(repository.findActiveOperationsForInstance(BOT_INSTANCE_ID))
            .thenReturn(Flux.fromIterable(manyOps));
        when(repository.findStaleOperations(any(OffsetDateTime.class)))
            .thenReturn(Flux.empty());

        StepVerifier.create(service.checkDistributedLockState())
            .assertNext(health -> {
                assertThat(health.status(), is(HealthStatus.DEGRADED));
                assertThat(health.message(), containsString("High number of active operations"));
            })
            .verifyComplete();
    }

    @Test
    void checkDistributedLockStateReturnsUnhealthyOnError() {
        when(repository.findActiveOperationsForInstance(BOT_INSTANCE_ID))
            .thenReturn(Flux.error(new RuntimeException("Database connection failed")));
        when(repository.findStaleOperations(any(OffsetDateTime.class)))
            .thenReturn(Flux.empty());

        StepVerifier.create(service.checkDistributedLockState())
            .assertNext(health -> {
                assertThat(health.status(), is(HealthStatus.UNHEALTHY));
                assertThat(health.message(), containsString("Database connection failed"));
            })
            .verifyComplete();
    }

    @Test
    void analyzeRecentOperationsReturnsHealthyWithNoOperations() {
        when(repository.findRecentOperations(anyString(), any(OffsetDateTime.class)))
            .thenReturn(Flux.empty());

        StepVerifier.create(service.analyzeRecentOperations())
            .assertNext(health -> {
                assertThat(health.status(), is(HealthStatus.HEALTHY));
                assertThat(health.message(), containsString("No recent operations"));
            })
            .verifyComplete();
    }

    @Test
    void analyzeRecentOperationsReturnsHealthyWithLowFailureRate() {
        TdLibOperation completed1 = createOperationWithStatus(TdLibOperationType.CHAT_DISCOVERY, TdLibOperationStatus.COMPLETED);
        TdLibOperation completed2 = createOperationWithStatus(TdLibOperationType.MESSAGE_SYNC, TdLibOperationStatus.COMPLETED);
        TdLibOperation completed3 = createOperationWithStatus(TdLibOperationType.CHANNEL_SYNC_SCHEDULED, TdLibOperationStatus.COMPLETED);
        TdLibOperation failed = createOperationWithStatus(TdLibOperationType.LOAD_CHATS_MAIN, TdLibOperationStatus.FAILED);

        when(repository.findRecentOperations(anyString(), any(OffsetDateTime.class)))
            .thenReturn(Flux.just(completed1, completed2, completed3, failed));

        StepVerifier.create(service.analyzeRecentOperations())
            .assertNext(health -> {
                assertThat(health.status(), is(HealthStatus.HEALTHY));
                assertThat(health.details().get("completed"), is(3L));
                assertThat(health.details().get("failed"), is(1L));
            })
            .verifyComplete();
    }

    @Test
    void analyzeRecentOperationsReturnsDegradedWithHighFailureRate() {
        TdLibOperation completed = createOperationWithStatus(TdLibOperationType.CHAT_DISCOVERY, TdLibOperationStatus.COMPLETED);
        TdLibOperation failed1 = createOperationWithStatus(TdLibOperationType.MESSAGE_SYNC, TdLibOperationStatus.FAILED);
        TdLibOperation failed2 = createOperationWithStatus(TdLibOperationType.CHANNEL_SYNC_SCHEDULED, TdLibOperationStatus.FAILED);
        TdLibOperation timeout = createOperationWithStatus(TdLibOperationType.LOAD_CHATS_MAIN, TdLibOperationStatus.TIMEOUT);

        when(repository.findRecentOperations(anyString(), any(OffsetDateTime.class)))
            .thenReturn(Flux.just(completed, failed1, failed2, timeout));

        StepVerifier.create(service.analyzeRecentOperations())
            .assertNext(health -> {
                assertThat(health.status(), is(HealthStatus.DEGRADED));
                assertThat(health.message(), containsString("High failure rate"));
            })
            .verifyComplete();
    }

    @Test
    void performHealthCheckCombinesAllComponentChecks() {
        when(coordinator.isTdLibReady()).thenReturn(Mono.just(true));
        when(coordinator.getState()).thenReturn(TdLibOperationState.IDLE);
        when(coordinator.isOperationInProgress()).thenReturn(false);
        when(coordinator.getCurrentOperationDuration()).thenReturn(Duration.ZERO);
        when(coordinator.getCurrentOperation()).thenReturn(null);
        when(repository.findActiveOperationsForInstance(BOT_INSTANCE_ID)).thenReturn(Flux.empty());
        when(repository.findStaleOperations(any(OffsetDateTime.class))).thenReturn(Flux.empty());
        when(repository.findRecentOperations(anyString(), any(OffsetDateTime.class))).thenReturn(Flux.empty());

        StepVerifier.create(service.performHealthCheck())
            .assertNext(result -> {
                assertThat(result.overallStatus(), is(HealthStatus.HEALTHY));
                assertThat(result.isHealthy(), is(true));
                assertThat(result.tdLibConnectivity().status(), is(HealthStatus.HEALTHY));
                assertThat(result.coordinatorState().status(), is(HealthStatus.HEALTHY));
                assertThat(result.lockState().status(), is(HealthStatus.HEALTHY));
                assertThat(result.operationHistory().status(), is(HealthStatus.HEALTHY));
            })
            .verifyComplete();
    }

    @Test
    void performHealthCheckReturnsUnhealthyWhenAnyComponentUnhealthy() {
        when(coordinator.isTdLibReady()).thenReturn(Mono.just(false));
        when(coordinator.getState()).thenReturn(TdLibOperationState.IDLE);
        when(coordinator.isOperationInProgress()).thenReturn(false);
        when(coordinator.getCurrentOperationDuration()).thenReturn(Duration.ZERO);
        when(coordinator.getCurrentOperation()).thenReturn(null);
        when(repository.findActiveOperationsForInstance(BOT_INSTANCE_ID)).thenReturn(Flux.empty());
        when(repository.findStaleOperations(any(OffsetDateTime.class))).thenReturn(Flux.empty());
        when(repository.findRecentOperations(anyString(), any(OffsetDateTime.class))).thenReturn(Flux.empty());

        StepVerifier.create(service.performHealthCheck())
            .assertNext(result -> {
                assertThat(result.overallStatus(), is(HealthStatus.UNHEALTHY));
                assertThat(result.isHealthy(), is(false));
                assertThat(result.tdLibConnectivity().status(), is(HealthStatus.UNHEALTHY));
            })
            .verifyComplete();
    }

    @Test
    void performHealthCheckReturnsDegradedWhenAnyComponentDegraded() {
        when(coordinator.isTdLibReady()).thenReturn(Mono.just(true));
        when(coordinator.getState()).thenReturn(TdLibOperationState.ERROR);
        when(coordinator.isOperationInProgress()).thenReturn(false);
        when(coordinator.getCurrentOperationDuration()).thenReturn(Duration.ZERO);
        when(coordinator.getCurrentOperation()).thenReturn(null);
        when(repository.findActiveOperationsForInstance(BOT_INSTANCE_ID)).thenReturn(Flux.empty());
        when(repository.findStaleOperations(any(OffsetDateTime.class))).thenReturn(Flux.empty());
        when(repository.findRecentOperations(anyString(), any(OffsetDateTime.class))).thenReturn(Flux.empty());

        StepVerifier.create(service.performHealthCheck())
            .assertNext(result -> {
                assertThat(result.overallStatus(), is(HealthStatus.DEGRADED));
                assertThat(result.coordinatorState().status(), is(HealthStatus.DEGRADED));
            })
            .verifyComplete();
    }

    @Test
    void isOperationBlockedReturnsTrueWhenOperationActive() {
        TdLibOperation activeOp = createOperation(TdLibOperationType.CHAT_DISCOVERY);

        when(repository.findActiveOperation(TdLibOperationType.CHAT_DISCOVERY.name(), BOT_INSTANCE_ID))
            .thenReturn(Mono.just(activeOp));

        StepVerifier.create(service.isOperationBlocked(TdLibOperationType.CHAT_DISCOVERY))
            .expectNext(true)
            .verifyComplete();
    }

    @Test
    void isOperationBlockedReturnsFalseWhenNoActiveOperation() {
        when(repository.findActiveOperation(anyString(), anyString()))
            .thenReturn(Mono.empty());

        StepVerifier.create(service.isOperationBlocked(TdLibOperationType.CHAT_DISCOVERY))
            .expectNext(false)
            .verifyComplete();
    }

    @Test
    void getOperationStatisticsReturnsEmptyMapWhenNoOperations() {
        when(repository.findRecentOperations(anyString(), any(OffsetDateTime.class)))
            .thenReturn(Flux.empty());

        StepVerifier.create(service.getOperationStatistics())
            .assertNext(stats -> {
                assertThat(stats.isEmpty(), is(true));
            })
            .verifyComplete();
    }

    @Test
    void getOperationStatisticsAggregatesOperationsByType() {
        TdLibOperation op1 = createOperationWithStatus(TdLibOperationType.CHAT_DISCOVERY, TdLibOperationStatus.COMPLETED);
        TdLibOperation op2 = createOperationWithStatus(TdLibOperationType.CHAT_DISCOVERY, TdLibOperationStatus.COMPLETED);
        TdLibOperation op3 = createOperationWithStatus(TdLibOperationType.MESSAGE_SYNC, TdLibOperationStatus.FAILED);

        when(repository.findRecentOperations(anyString(), any(OffsetDateTime.class)))
            .thenReturn(Flux.just(op1, op2, op3));

        StepVerifier.create(service.getOperationStatistics())
            .assertNext(stats -> {
                assertThat(stats.containsKey(TdLibOperationType.CHAT_DISCOVERY), is(true));
                assertThat(stats.containsKey(TdLibOperationType.MESSAGE_SYNC), is(true));
                assertThat(stats.get(TdLibOperationType.CHAT_DISCOVERY).total(), is(2));
                assertThat(stats.get(TdLibOperationType.CHAT_DISCOVERY).completed(), is(2));
                assertThat(stats.get(TdLibOperationType.MESSAGE_SYNC).failed(), is(1));
            })
            .verifyComplete();
    }

    @Test
    void operationStatsCalculatesFailureRateCorrectly() {
        OperationStats stats = new OperationStats(10, 6, 2, 2, Duration.ofSeconds(5));
        assertThat(stats.failureRate(), is(closeTo(0.4, 0.001)));
    }

    @Test
    void operationStatsMergesOperationCorrectly() {
        OperationStats initial = new OperationStats(1, 1, 0, 0, Duration.ofSeconds(5));
        TdLibOperation failedOp = createOperationWithStatus(TdLibOperationType.CHAT_DISCOVERY, TdLibOperationStatus.FAILED);

        OperationStats merged = initial.merge(failedOp);

        assertThat(merged.total(), is(2));
        assertThat(merged.completed(), is(1));
        assertThat(merged.failed(), is(1));
    }

    @Test
    void healthCheckResultOverallStatusReturnsWorstStatus() {
        ComponentHealth healthy = new ComponentHealth(HealthStatus.HEALTHY, "ok", Map.of());
        ComponentHealth degraded = new ComponentHealth(HealthStatus.DEGRADED, "degraded", Map.of());
        ComponentHealth unhealthy = new ComponentHealth(HealthStatus.UNHEALTHY, "unhealthy", Map.of());

        HealthCheckResult allHealthy = new HealthCheckResult(healthy, healthy, healthy, healthy);
        assertThat(allHealthy.overallStatus(), is(HealthStatus.HEALTHY));

        HealthCheckResult hasDegraded = new HealthCheckResult(healthy, degraded, healthy, healthy);
        assertThat(hasDegraded.overallStatus(), is(HealthStatus.DEGRADED));

        HealthCheckResult hasUnhealthy = new HealthCheckResult(healthy, degraded, unhealthy, healthy);
        assertThat(hasUnhealthy.overallStatus(), is(HealthStatus.UNHEALTHY));
    }

    @Test
    void constructorRejectsNullCoordinator() {
        try {
            new TdLibHealthCheckService(null, repository, instanceProvider);
            throw new AssertionError("Expected NullPointerException");
        } catch (NullPointerException e) {
            assertThat(e.getMessage(), containsString("coordinator"));
        }
    }

    @Test
    void constructorRejectsNullRepository() {
        try {
            new TdLibHealthCheckService(coordinator, null, instanceProvider);
            throw new AssertionError("Expected NullPointerException");
        } catch (NullPointerException e) {
            assertThat(e.getMessage(), containsString("repository"));
        }
    }

    @Test
    void constructorRejectsNullInstanceProvider() {
        try {
            new TdLibHealthCheckService(coordinator, repository, null);
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

    private TdLibOperation createOperationWithStatus(TdLibOperationType type, TdLibOperationStatus status) {
        TdLibOperation operation = createOperation(type);
        operation.setStatus(status);
        if (status != TdLibOperationStatus.IN_PROGRESS) {
            operation.setCompletedAt(OffsetDateTime.now());
        }
        return operation;
    }
}
