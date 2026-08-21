package com.example.telegramuserbot.repository;

import com.example.telegramuserbot.domain.SyncConfiguration;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

/**
 * Repository for managing SyncConfiguration entities.
 */
@Repository
public interface SyncConfigurationRepository extends R2dbcRepository<SyncConfiguration, Long> {

    @Query("""
            SELECT *
              FROM bot.sync_configurations
             WHERE channel_id = :channelId
               AND bot_instance_id = :#{@botInstanceProvider.instanceId}
            """)
    Mono<SyncConfiguration> findByChannelId(@Param("channelId") Long channelId);

    @Query("""
            SELECT *
              FROM bot.sync_configurations
             WHERE auto_sync_enabled = true
               AND bot_instance_id = :#{@botInstanceProvider.instanceId}
            """)
    Flux<SyncConfiguration> findByAutoSyncEnabledTrue();

    @Query("""
            SELECT *
              FROM bot.sync_configurations
             WHERE auto_sync_enabled = true
               AND (last_auto_sync_at IS NULL OR last_auto_sync_at < :dueBefore)
               AND bot_instance_id = :#{@botInstanceProvider.instanceId}
            """)
    Flux<SyncConfiguration> findConfigurationsDueForAutoSync(@Param("dueBefore") LocalDateTime dueBefore);

    @Query("""
            SELECT *
              FROM bot.sync_configurations
             WHERE channel_id = :channelId
               AND bot_instance_id = :#{@botInstanceProvider.instanceId}
            """)
    Mono<SyncConfiguration> findByChannelChatIdInternal(@Param("channelId") Long channelId);

    default Mono<SyncConfiguration> findByChannelChatId(Long channelId) {
        if (channelId == null) {
            return Mono.empty();
        }
        // Using original TDLib chat ID directly - no normalization needed
        return findByChannelChatIdInternal(channelId);
    }

    @Query("""
            SELECT EXISTS(
                SELECT 1
                  FROM bot.sync_configurations
                 WHERE channel_id = :channelId
                   AND bot_instance_id = :#{@botInstanceProvider.instanceId}
            )
            """)
    Mono<Boolean> existsByChannelId(@Param("channelId") Long channelId);

    @Query("""
            SELECT *
              FROM bot.sync_configurations
             WHERE default_sync_depth_days IS NOT NULL
               AND bot_instance_id = :#{@botInstanceProvider.instanceId}
            """)
    Flux<SyncConfiguration> findByDefaultSyncDepthDaysIsNotNull();

    @Query("""
            SELECT *
              FROM bot.sync_configurations
             WHERE updated_at < :cutoffTime
               AND bot_instance_id = :#{@botInstanceProvider.instanceId}
            """)
    Flux<SyncConfiguration> findStaleConfigurations(@Param("cutoffTime") LocalDateTime cutoffTime);
}
