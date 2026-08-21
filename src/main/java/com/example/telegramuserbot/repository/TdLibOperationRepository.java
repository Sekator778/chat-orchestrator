package com.example.telegramuserbot.repository;

import com.example.telegramuserbot.domain.TdLibOperation;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;

/**
 * Repository for managing TdLibOperation entities.
 * Provides distributed locking support for TDLib operations.
 */
@Repository
public interface TdLibOperationRepository extends R2dbcRepository<TdLibOperation, Long> {

    /**
     * Finds an in-progress operation by type and bot instance.
     * Used to check if an operation is already running.
     */
    @Query("""
            SELECT *
              FROM bot.tdlib_operations
             WHERE operation_type = :operationType
               AND bot_instance_id = :botInstanceId
               AND status = 'IN_PROGRESS'
             LIMIT 1
            """)
    Mono<TdLibOperation> findActiveOperation(
            @Param("operationType") String operationType,
            @Param("botInstanceId") String botInstanceId
    );

    /**
     * Finds any in-progress operation of the specified type across all instances.
     * Used for global operation locking (e.g., CHAT_DISCOVERY).
     */
    @Query("""
            SELECT *
              FROM bot.tdlib_operations
             WHERE operation_type = :operationType
               AND status = 'IN_PROGRESS'
             LIMIT 1
            """)
    Mono<TdLibOperation> findActiveOperationGlobal(@Param("operationType") String operationType);

    /**
     * Finds all in-progress operations for a bot instance.
     */
    @Query("""
            SELECT *
              FROM bot.tdlib_operations
             WHERE bot_instance_id = :botInstanceId
               AND status = 'IN_PROGRESS'
             ORDER BY started_at
            """)
    Flux<TdLibOperation> findActiveOperationsForInstance(@Param("botInstanceId") String botInstanceId);

    /**
     * Finds stale operations that have exceeded their timeout.
     */
    @Query("""
            SELECT *
              FROM bot.tdlib_operations
             WHERE status = 'IN_PROGRESS'
               AND timeout_at IS NOT NULL
               AND timeout_at < :now
             ORDER BY started_at
            """)
    Flux<TdLibOperation> findStaleOperations(@Param("now") OffsetDateTime now);

    /**
     * Finds recent operations for monitoring and debugging.
     */
    @Query("""
            SELECT *
              FROM bot.tdlib_operations
             WHERE bot_instance_id = :botInstanceId
               AND started_at > :since
             ORDER BY started_at DESC
            """)
    Flux<TdLibOperation> findRecentOperations(
            @Param("botInstanceId") String botInstanceId,
            @Param("since") OffsetDateTime since
    );

    /**
     * Updates the status and completion time of an operation atomically.
     */
    @Modifying
    @Query("""
            UPDATE bot.tdlib_operations
               SET status = :status,
                   completed_at = :completedAt,
                   error_message = :errorMessage
             WHERE id = :id
               AND status = 'IN_PROGRESS'
            """)
    Mono<Integer> completeOperation(
            @Param("id") Long id,
            @Param("status") String status,
            @Param("completedAt") OffsetDateTime completedAt,
            @Param("errorMessage") String errorMessage
    );

    /**
     * Updates the heartbeat timestamp for a running operation.
     */
    @Modifying
    @Query("""
            UPDATE bot.tdlib_operations
               SET heartbeat_at = :heartbeatAt
             WHERE id = :id
               AND status = 'IN_PROGRESS'
            """)
    Mono<Integer> updateHeartbeat(
            @Param("id") Long id,
            @Param("heartbeatAt") OffsetDateTime heartbeatAt
    );

    /**
     * Marks stale operations as timed out.
     * Returns the number of operations marked as timeout.
     */
    @Modifying
    @Query("""
            UPDATE bot.tdlib_operations
               SET status = 'TIMEOUT',
                   completed_at = :now,
                   error_message = 'Operation exceeded timeout threshold'
             WHERE status = 'IN_PROGRESS'
               AND timeout_at IS NOT NULL
               AND timeout_at < :now
            """)
    Mono<Integer> markStaleOperationsAsTimeout(@Param("now") OffsetDateTime now);

    /**
     * Deletes old completed operations for cleanup.
     */
    @Modifying
    @Query("""
            DELETE FROM bot.tdlib_operations
             WHERE status IN ('COMPLETED', 'FAILED', 'TIMEOUT')
               AND completed_at < :cutoffTime
            """)
    Mono<Integer> deleteOldOperations(@Param("cutoffTime") OffsetDateTime cutoffTime);

    /**
     * Counts in-progress operations by type.
     */
    @Query("""
            SELECT COUNT(*)
              FROM bot.tdlib_operations
             WHERE operation_type = :operationType
               AND status = 'IN_PROGRESS'
            """)
    Mono<Long> countActiveOperationsByType(@Param("operationType") String operationType);

    /**
     * Attempts to acquire a lock by inserting a new operation.
     * Uses ON CONFLICT to handle race conditions - returns null if lock already held.
     * Note: This relies on the unique partial index uq_tdlib_operations_active_type.
     */
    @Query("""
            INSERT INTO bot.tdlib_operations
                   (operation_type, bot_instance_id, resource_id, status, started_at, timeout_at, heartbeat_at)
            VALUES (:operationType, :botInstanceId, :resourceId, 'IN_PROGRESS', :startedAt, :timeoutAt, :startedAt)
            ON CONFLICT (operation_type, bot_instance_id) WHERE status = 'IN_PROGRESS'
            DO NOTHING
            RETURNING *
            """)
    Mono<TdLibOperation> tryAcquireLock(
            @Param("operationType") String operationType,
            @Param("botInstanceId") String botInstanceId,
            @Param("resourceId") String resourceId,
            @Param("startedAt") OffsetDateTime startedAt,
            @Param("timeoutAt") OffsetDateTime timeoutAt
    );

    /**
     * Releases a lock by completing the operation.
     */
    @Modifying
    @Query("""
            UPDATE bot.tdlib_operations
               SET status = :status,
                   completed_at = :completedAt,
                   error_message = :errorMessage
             WHERE id = :id
            """)
    Mono<Integer> releaseLock(
            @Param("id") Long id,
            @Param("status") String status,
            @Param("completedAt") OffsetDateTime completedAt,
            @Param("errorMessage") String errorMessage
    );
}
