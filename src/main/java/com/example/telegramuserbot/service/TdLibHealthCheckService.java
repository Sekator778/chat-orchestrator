package com.example.telegramuserbot.service;

import com.example.telegramuserbot.config.BotInstanceProvider;
import com.example.telegramuserbot.domain.TdLibOperation;
import com.example.telegramuserbot.domain.TdLibOperationStatus;
import com.example.telegramuserbot.domain.TdLibOperationType;
import com.example.telegramuserbot.repository.TdLibOperationRepository;
import com.example.telegramuserbot.telegram.TdLibOperationCoordinator;
import com.example.telegramuserbot.telegram.TdLibOperationState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Service for monitoring the health of TDLib operations and the coordination system.
 *
 * <p>This service provides comprehensive health checks for:</p>
 * <ul>
 *   <li>TDLib client connectivity and authorization state</li>
 *   <li>In-process operation coordinator state</li>
 *   <li>Distributed lock system state</li>
 *   <li>Operation history analysis for anomaly detection</li>
 * </ul>
 *
 * <p>The health check results can be used for monitoring dashboards,
 * alerting systems, and automated recovery decisions.</p>
 */
@Service
public final class TdLibHealthCheckService {

    private static final Logger log = LoggerFactory.getLogger(TdLibHealthCheckService.class);

    /**
     * Threshold for considering the system healthy based on recent failure rate.
     */
    private static final double FAILURE_RATE_THRESHOLD = 0.3;

    /**
     * Duration to look back when analyzing operation history (1 hour).
     */
    private static final Duration HISTORY_LOOKBACK = Duration.ofHours(1);

    /**
     * Threshold for considering an operation long-running (5 minutes).
     */
    private static final Duration LONG_RUNNING_THRESHOLD = Duration.ofMinutes(5);

    private final TdLibOperationCoordinator coordinator;
    private final TdLibOperationRepository repository;
    private final BotInstanceProvider instanceProvider;

    /**
     * Creates a new TdLibHealthCheckService.
     *
     * @param coordinator the TDLib operation coordinator
     * @param repository the TDLib operation repository
     * @param instanceProvider the bot instance provider
     */
    public TdLibHealthCheckService(
            TdLibOperationCoordinator coordinator,
            TdLibOperationRepository repository,
            BotInstanceProvider instanceProvider) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator must not be null");
        this.repository = Objects.requireNonNull(repository, "repository must not be null");
        this.instanceProvider = Objects.requireNonNull(instanceProvider, "instanceProvider must not be null");
    }

    /**
     * Performs a comprehensive health check of the TDLib coordination system.
     *
     * @return a Mono emitting the health check result
     */
    public Mono<HealthCheckResult> performHealthCheck() {
        log.debug("Performing TDLib health check for instance {}", instanceProvider.getInstanceId());

        return Mono.zip(
                checkTdLibConnectivity(),
                checkCoordinatorState(),
                checkDistributedLockState(),
                analyzeRecentOperations()
            )
            .map(tuple -> {
                HealthCheckResult result = new HealthCheckResult(
                    tuple.getT1(),
                    tuple.getT2(),
                    tuple.getT3(),
                    tuple.getT4()
                );
                log.info("TDLib health check completed: overall={}, tdlib={}, coordinator={}, locks={}, history={}",
                    result.overallStatus(),
                    result.tdLibConnectivity().status(),
                    result.coordinatorState().status(),
                    result.lockState().status(),
                    result.operationHistory().status());
                return result;
            });
    }

    /**
     * Checks if TDLib is connected and authorized.
     *
     * @return a Mono emitting the connectivity check result
     */
    public Mono<ComponentHealth> checkTdLibConnectivity() {
        return coordinator.isTdLibReady()
            .map(ready -> {
                if (ready) {
                    return new ComponentHealth(
                        HealthStatus.HEALTHY,
                        "TDLib is connected and authorized",
                        Map.of("authorized", true)
                    );
                }
                return new ComponentHealth(
                    HealthStatus.UNHEALTHY,
                    "TDLib is not authorized or not ready",
                    Map.of("authorized", false)
                );
            })
            .onErrorResume(error -> {
                log.warn("TDLib connectivity check failed: {}", error.getMessage());
                return Mono.just(new ComponentHealth(
                    HealthStatus.UNHEALTHY,
                    "Failed to check TDLib connectivity: " + error.getMessage(),
                    Map.of("error", error.getMessage())
                ));
            })
            .timeout(Duration.ofSeconds(10))
            .onErrorReturn(new ComponentHealth(
                HealthStatus.UNHEALTHY,
                "TDLib connectivity check timed out",
                Map.of("timeout", true)
            ));
    }

    /**
     * Checks the state of the in-process operation coordinator.
     *
     * @return a Mono emitting the coordinator state check result
     */
    public Mono<ComponentHealth> checkCoordinatorState() {
        return Mono.fromCallable(() -> {
            TdLibOperationState state = coordinator.getState();
            boolean inProgress = coordinator.isOperationInProgress();
            Duration duration = coordinator.getCurrentOperationDuration();
            String currentOp = coordinator.getCurrentOperation();

            Map<String, Object> details = new HashMap<>();
            details.put("state", state.name());
            details.put("operationInProgress", inProgress);

            if (inProgress) {
                details.put("currentOperation", currentOp);
                details.put("durationMs", duration.toMillis());

                if (duration.compareTo(LONG_RUNNING_THRESHOLD) > 0) {
                    return new ComponentHealth(
                        HealthStatus.DEGRADED,
                        "Long-running operation detected: " + currentOp + " (" + duration.toSeconds() + "s)",
                        details
                    );
                }
            }

            if (state == TdLibOperationState.ERROR) {
                return new ComponentHealth(
                    HealthStatus.DEGRADED,
                    "Coordinator is in ERROR state",
                    details
                );
            }

            return new ComponentHealth(
                HealthStatus.HEALTHY,
                "Coordinator is operating normally",
                details
            );
        });
    }

    /**
     * Checks the state of the distributed lock system.
     *
     * @return a Mono emitting the lock state check result
     */
    public Mono<ComponentHealth> checkDistributedLockState() {
        String botInstanceId = instanceProvider.getInstanceId();
        OffsetDateTime now = OffsetDateTime.now();

        return Mono.zip(
                repository.findActiveOperationsForInstance(botInstanceId).collectList(),
                repository.findStaleOperations(now).collectList()
            )
            .map(tuple -> {
                var activeOps = tuple.getT1();
                var staleOps = tuple.getT2();

                Map<String, Object> details = new HashMap<>();
                details.put("activeOperations", activeOps.size());
                details.put("staleOperations", staleOps.size());
                details.put("botInstanceId", botInstanceId);

                if (!activeOps.isEmpty()) {
                    details.put("activeTypes", activeOps.stream()
                        .map(op -> op.getOperationType().name())
                        .toList());
                }

                if (!staleOps.isEmpty()) {
                    return new ComponentHealth(
                        HealthStatus.DEGRADED,
                        "Found " + staleOps.size() + " stale operations requiring cleanup",
                        details
                    );
                }

                if (activeOps.size() > 5) {
                    return new ComponentHealth(
                        HealthStatus.DEGRADED,
                        "High number of active operations (" + activeOps.size() + ")",
                        details
                    );
                }

                return new ComponentHealth(
                    HealthStatus.HEALTHY,
                    "Distributed lock system is healthy",
                    details
                );
            })
            .onErrorResume(error -> {
                log.warn("Distributed lock state check failed: {}", error.getMessage());
                return Mono.just(new ComponentHealth(
                    HealthStatus.UNHEALTHY,
                    "Failed to check distributed lock state: " + error.getMessage(),
                    Map.of("error", error.getMessage())
                ));
            });
    }

    /**
     * Analyzes recent operation history for anomalies.
     *
     * @return a Mono emitting the operation history analysis result
     */
    public Mono<ComponentHealth> analyzeRecentOperations() {
        String botInstanceId = instanceProvider.getInstanceId();
        OffsetDateTime since = OffsetDateTime.now().minus(HISTORY_LOOKBACK);

        return repository.findRecentOperations(botInstanceId, since)
            .collectList()
            .map(operations -> {
                Map<String, Object> details = new HashMap<>();
                details.put("totalOperations", operations.size());
                details.put("lookbackMinutes", HISTORY_LOOKBACK.toMinutes());

                if (operations.isEmpty()) {
                    return new ComponentHealth(
                        HealthStatus.HEALTHY,
                        "No recent operations to analyze",
                        details
                    );
                }

                long completed = operations.stream()
                    .filter(op -> op.getStatus() == TdLibOperationStatus.COMPLETED)
                    .count();
                long failed = operations.stream()
                    .filter(op -> op.getStatus() == TdLibOperationStatus.FAILED)
                    .count();
                long timeout = operations.stream()
                    .filter(op -> op.getStatus() == TdLibOperationStatus.TIMEOUT)
                    .count();
                long inProgress = operations.stream()
                    .filter(op -> op.getStatus() == TdLibOperationStatus.IN_PROGRESS)
                    .count();

                details.put("completed", completed);
                details.put("failed", failed);
                details.put("timeout", timeout);
                details.put("inProgress", inProgress);

                double failureRate = (double) (failed + timeout) / operations.size();
                details.put("failureRate", String.format("%.2f", failureRate));

                if (failureRate > FAILURE_RATE_THRESHOLD) {
                    return new ComponentHealth(
                        HealthStatus.DEGRADED,
                        "High failure rate in recent operations: " + String.format("%.1f%%", failureRate * 100),
                        details
                    );
                }

                return new ComponentHealth(
                    HealthStatus.HEALTHY,
                    "Operation history is healthy",
                    details
                );
            })
            .onErrorResume(error -> {
                log.warn("Operation history analysis failed: {}", error.getMessage());
                return Mono.just(new ComponentHealth(
                    HealthStatus.UNHEALTHY,
                    "Failed to analyze operation history: " + error.getMessage(),
                    Map.of("error", error.getMessage())
                ));
            });
    }

    /**
     * Checks if a specific operation type is currently blocked.
     *
     * @param operationType the operation type to check
     * @return a Mono emitting true if the operation is blocked
     */
    public Mono<Boolean> isOperationBlocked(TdLibOperationType operationType) {
        String botInstanceId = instanceProvider.getInstanceId();
        return repository.findActiveOperation(operationType.name(), botInstanceId)
            .hasElement();
    }

    /**
     * Gets statistics about recent operations by type.
     *
     * @return a Mono emitting operation statistics
     */
    public Mono<Map<TdLibOperationType, OperationStats>> getOperationStatistics() {
        String botInstanceId = instanceProvider.getInstanceId();
        OffsetDateTime since = OffsetDateTime.now().minus(HISTORY_LOOKBACK);

        return repository.findRecentOperations(botInstanceId, since)
            .collect(
                HashMap<TdLibOperationType, OperationStats>::new,
                (map, op) -> {
                    OperationStats stats = map.computeIfAbsent(
                        op.getOperationType(),
                        k -> new OperationStats(0, 0, 0, 0, Duration.ZERO)
                    );
                    map.put(op.getOperationType(), stats.merge(op));
                }
            )
            .map(map -> (Map<TdLibOperationType, OperationStats>) map);
    }

    /**
     * Health status enumeration.
     */
    public enum HealthStatus {
        /** Component is operating normally. */
        HEALTHY,
        /** Component is operational but with some issues. */
        DEGRADED,
        /** Component is not operational. */
        UNHEALTHY
    }

    /**
     * Health check result for a single component.
     *
     * @param status the health status
     * @param message descriptive message
     * @param details additional details
     */
    public record ComponentHealth(
        HealthStatus status,
        String message,
        Map<String, Object> details
    ) {}

    /**
     * Overall health check result.
     *
     * @param tdLibConnectivity TDLib connectivity health
     * @param coordinatorState coordinator state health
     * @param lockState distributed lock health
     * @param operationHistory operation history health
     */
    public record HealthCheckResult(
        ComponentHealth tdLibConnectivity,
        ComponentHealth coordinatorState,
        ComponentHealth lockState,
        ComponentHealth operationHistory
    ) {
        /**
         * Gets the overall health status (worst of all components).
         *
         * @return the overall health status
         */
        public HealthStatus overallStatus() {
            HealthStatus worst = HealthStatus.HEALTHY;
            for (ComponentHealth component : new ComponentHealth[] {
                tdLibConnectivity, coordinatorState, lockState, operationHistory
            }) {
                if (component.status() == HealthStatus.UNHEALTHY) {
                    return HealthStatus.UNHEALTHY;
                }
                if (component.status() == HealthStatus.DEGRADED) {
                    worst = HealthStatus.DEGRADED;
                }
            }
            return worst;
        }

        /**
         * Checks if all components are healthy.
         *
         * @return true if all components are healthy
         */
        public boolean isHealthy() {
            return overallStatus() == HealthStatus.HEALTHY;
        }
    }

    /**
     * Statistics for a specific operation type.
     *
     * @param total total number of operations
     * @param completed number of completed operations
     * @param failed number of failed operations
     * @param timeout number of timed out operations
     * @param averageDuration average operation duration
     */
    public record OperationStats(
        int total,
        int completed,
        int failed,
        int timeout,
        Duration averageDuration
    ) {
        /**
         * Merges this stats with a new operation.
         *
         * @param operation the operation to merge
         * @return new stats with the operation included
         */
        OperationStats merge(TdLibOperation operation) {
            int newCompleted = completed + (operation.getStatus() == TdLibOperationStatus.COMPLETED ? 1 : 0);
            int newFailed = failed + (operation.getStatus() == TdLibOperationStatus.FAILED ? 1 : 0);
            int newTimeout = timeout + (operation.getStatus() == TdLibOperationStatus.TIMEOUT ? 1 : 0);
            int newTotal = total + 1;

            Duration opDuration = operation.getDuration();
            Duration newAvg = averageDuration.isZero()
                ? opDuration
                : Duration.ofMillis((averageDuration.toMillis() * total + opDuration.toMillis()) / newTotal);

            return new OperationStats(newTotal, newCompleted, newFailed, newTimeout, newAvg);
        }

        /**
         * Calculates the failure rate.
         *
         * @return the failure rate as a decimal (0.0 to 1.0)
         */
        public double failureRate() {
            if (total == 0) return 0.0;
            return (double) (failed + timeout) / total;
        }
    }
}
