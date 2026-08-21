package com.example.telegramuserbot.repository;

import com.example.telegramuserbot.domain.SyncJob;
import org.springframework.data.domain.Pageable;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Repository for managing SyncJob entities.
 */
@Repository
public interface SyncJobRepository extends R2dbcRepository<SyncJob, Long> {

    @Query("""
            SELECT *
              FROM sync_jobs
             WHERE channel_id = :channelId
               AND bot_instance_id = :#{@botInstanceProvider.instanceId}
             ORDER BY created_at DESC
            """)
    Flux<SyncJob> findByChannelIdOrderByCreatedAtDesc(@Param("channelId") Long channelId);

    @Query("""
            SELECT *
              FROM sync_jobs
             WHERE channel_id = :channelId
               AND bot_instance_id = :#{@botInstanceProvider.instanceId}
             ORDER BY created_at DESC
            """)
    Flux<SyncJob> findByChannelIdOrderByCreatedAtDesc(@Param("channelId") Long channelId, Pageable pageable);

    @Query("""
            SELECT *
              FROM sync_jobs
             WHERE status = :status
               AND bot_instance_id = :#{@botInstanceProvider.instanceId}
            """)
    Flux<SyncJob> findByStatus(@Param("status") String status);

    @Query("""
            SELECT *
              FROM sync_jobs
             WHERE channel_id = :channelId
               AND status IN ('PENDING', 'IN_PROGRESS')
               AND bot_instance_id = :#{@botInstanceProvider.instanceId}
            """)
    Flux<SyncJob> findActiveJobsByChannelId(@Param("channelId") Long channelId);

    @Query("""
            SELECT COUNT(*)
              FROM sync_jobs
             WHERE channel_id = :channelId
               AND status IN ('PENDING', 'IN_PROGRESS')
               AND bot_instance_id = :#{@botInstanceProvider.instanceId}
            """)
    Mono<Long> countActiveJobsByChannelId(@Param("channelId") Long channelId);

    @Query("""
            SELECT *
              FROM sync_jobs
             WHERE channel_id = :channelId
               AND bot_instance_id = :#{@botInstanceProvider.instanceId}
             ORDER BY created_at DESC
             LIMIT 1
            """)
    Mono<SyncJob> findFirstByChannelIdOrderByCreatedAtDesc(@Param("channelId") Long channelId);

    @Query("""
            SELECT *
              FROM sync_jobs
             WHERE status = 'IN_PROGRESS'
               AND started_at < :cutoffTime
               AND bot_instance_id = :#{@botInstanceProvider.instanceId}
            """)
    Flux<SyncJob> findStuckJobs(@Param("cutoffTime") LocalDateTime cutoffTime);

    @Query("""
            SELECT *
              FROM sync_jobs
             WHERE created_by_user_id = :userId
               AND bot_instance_id = :#{@botInstanceProvider.instanceId}
             ORDER BY created_at DESC
            """)
    Flux<SyncJob> findByCreatedByUserIdOrderByCreatedAtDesc(@Param("userId") Long userId);

    @Query("""
            SELECT *
              FROM sync_jobs
             WHERE created_at >= :since
               AND bot_instance_id = :#{@botInstanceProvider.instanceId}
             ORDER BY created_at DESC
            """)
    Flux<SyncJob> findRecentJobs(@Param("since") LocalDateTime since);

    @Query("""
            SELECT status, COUNT(*) as count
              FROM sync_jobs
             WHERE channel_id = :channelId
               AND bot_instance_id = :#{@botInstanceProvider.instanceId}
             GROUP BY status
            """)
    Flux<Map<String, Object>> getJobStatsByChannelId(@Param("channelId") Long channelId);

    @Query("""
            SELECT *
              FROM sync_jobs
             WHERE status IN ('PENDING', 'IN_PROGRESS')
               AND bot_instance_id = :#{@botInstanceProvider.instanceId}
             ORDER BY created_at DESC
            """)
    Flux<SyncJob> findActiveJobs();

    @Modifying
    @Query("""
            UPDATE sync_jobs
               SET status = 'CANCELLED', completed_at = NOW()
             WHERE status IN ('PENDING', 'IN_PROGRESS')
               AND created_at < :cutoffTime
               AND bot_instance_id = :#{@botInstanceProvider.instanceId}
            """)
    Mono<Integer> cancelStalePendingJobs(@Param("cutoffTime") LocalDateTime cutoffTime);

    @Query("""
            SELECT *
              FROM sync_jobs
             WHERE status IN ('COMPLETED', 'FAILED', 'CANCELLED')
               AND completed_at < :cutoffTime
               AND bot_instance_id = :#{@botInstanceProvider.instanceId}
            """)
    Flux<SyncJob> findJobsForCleanup(@Param("cutoffTime") LocalDateTime cutoffTime);

    @Modifying
    @Query("""
            DELETE FROM sync_jobs
             WHERE status IN ('COMPLETED', 'FAILED', 'CANCELLED')
               AND completed_at < :cutoffTime
               AND bot_instance_id = :#{@botInstanceProvider.instanceId}
            """)
    Mono<Integer> deleteOldCompletedJobs(@Param("cutoffTime") LocalDateTime cutoffTime);

    @Query("""
            SELECT *
              FROM sync_jobs
             WHERE channel_id = :channelChatId
               AND bot_instance_id = :#{@botInstanceProvider.instanceId}
             ORDER BY created_at DESC
            """)
    Flux<SyncJob> findByChannelChatIdOrderByCreatedAtDescInternal(@Param("channelChatId") Long channelChatId);

    default Flux<SyncJob> findByChannelChatIdOrderByCreatedAtDesc(Long channelChatId) {
        if (channelChatId == null) {
            return Flux.empty();
        }
        // Using original TDLib chat ID directly - no normalization needed
        return findByChannelChatIdOrderByCreatedAtDescInternal(channelChatId);
    }
}
