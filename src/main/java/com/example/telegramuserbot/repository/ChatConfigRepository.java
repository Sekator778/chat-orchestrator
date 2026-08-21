package com.example.telegramuserbot.repository;

import com.example.telegramuserbot.domain.ChatConfig;
import com.example.telegramuserbot.domain.ProcessingPhase;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

public interface ChatConfigRepository extends R2dbcRepository<ChatConfig, Long> {
    @Query("""
            SELECT *
              FROM bot.chat_configs
             WHERE channel_chat_id = :chatId
            """)
    Mono<ChatConfig> findByChannelChatIdInternal(@Param("chatId") long chatId);

    @Query("""
            SELECT *
              FROM bot.chat_configs
             WHERE channel_chat_id = :chatId
            """)
    Flux<ChatConfig> findAllByChannelChatId(@Param("chatId") long chatId);

    default Mono<ChatConfig> findByChannelChatId(long chatId) {
        // Using original TDLib chat ID directly - no normalization needed
        return findByChannelChatIdInternal(chatId);
    }

    @Query("""
            SELECT *
              FROM bot.chat_configs
            """)
    Flux<ChatConfig> findAllForInstance();

    @Query("""
            SELECT channel_chat_id
              FROM bot.chat_configs
             WHERE channel_chat_id IN (:chatIds)
            """)
    Flux<Long> findExistingChatIds(@Param("chatIds") Iterable<Long> chatIds);

    @Query("""
            SELECT *
              FROM bot.chat_configs
             WHERE primary_channel_id IS NULL
               AND (primary_channel_checked_at IS NULL OR primary_channel_checked_at < :threshold)
             ORDER BY primary_channel_checked_at NULLS FIRST, id
             LIMIT :limit
            """)
    Flux<ChatConfig> findConfigsMissingPrimaryChannel(@Param("threshold") Instant threshold, @Param("limit") int limit);

    @Modifying
    @Query("""
            UPDATE bot.chat_configs
               SET primary_channel_id = :primaryChannelId,
                   primary_channel_checked_at = :checkedAt
             WHERE id = :configId
            """)
    Mono<Integer> updatePrimaryChannelLink(@Param("configId") Long configId,
                                           @Param("primaryChannelId") Long primaryChannelId,
                                           @Param("checkedAt") Instant checkedAt);

    @Modifying
    @Query("""
            UPDATE bot.chat_configs
               SET primary_channel_checked_at = :checkedAt
             WHERE id = :configId
            """)
    Mono<Integer> updatePrimaryChannelCheckedAt(@Param("configId") Long configId,
                                                @Param("checkedAt") Instant checkedAt);

    /**
     * Finds all ChatConfigs in a specific processing phase.
     * Used by ChannelProcessingCoordinator to batch process channels through the pipeline.
     *
     * @param phase Processing phase to filter by
     * @return Flux of ChatConfigs in the specified phase
     */
    @Query("""
            SELECT *
              FROM bot.chat_configs
             WHERE processing_phase = :phase
             ORDER BY id
            """)
    Flux<ChatConfig> findByProcessingPhase(@Param("phase") ProcessingPhase phase);

    /**
     * Checks if any chat config has the specified channel as its primary channel.
     * Used to determine if a channel is a primary channel (referenced by others)
     * or a standalone channel (not referenced by anyone).
     *
     * @param channelId Channel ID to check
     * @return Mono emitting true if at least one config references this channel as primary
     */
    @Query("""
            SELECT COUNT(*) > 0
              FROM bot.chat_configs
             WHERE primary_channel_id = :channelId
            """)
    Mono<Boolean> existsByPrimaryChannelId(@Param("channelId") Long channelId);
}
