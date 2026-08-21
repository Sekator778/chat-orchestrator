package com.example.telegramuserbot.repository;

import com.example.telegramuserbot.domain.Posted;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

/**
 * Repository for tracking posted events.
 * Provides idempotency checks and audit trail queries.
 */
public interface PostedRepository extends R2dbcRepository<Posted, Long> {

    /**
     * Checks if an event was already posted to a subscription.
     * Used for idempotency across restarts.
     *
     * @param eventId event ID
     * @param subscriptionId subscription ID
     * @return true if already posted
     */
    Mono<Boolean> existsByEventIdAndSubscriptionId(Long eventId, Long subscriptionId);

    /**
     * Checks if an event topic/type combination was recently posted to a chat.
     * Used for time-based deduplication within TTL window.
     *
     * @param chatId chat ID
     * @param topic event topic
     * @param eventType event type
     * @param since time threshold (now - TTL)
     * @return true if recently posted
     */
    @Query("""
        SELECT COUNT(*) > 0
        FROM tgscan.posted p
        JOIN tgscan.events e ON e.id = p.event_id
        WHERE p.chat_id = :chatId
          AND e.topic = :topic
          AND e.event_type = :eventType
          AND p.posted_at >= :since
          AND p.status = 'sent'
        """)
    Mono<Boolean> wasRecentlyPosted(
        @Param("chatId") Long chatId,
        @Param("topic") String topic,
        @Param("eventType") String eventType,
        @Param("since") LocalDateTime since
    );

    /**
     * Counts posts by status within time range.
     *
     * @param status post status
     * @param since time threshold
     * @return count
     */
    @Query("SELECT COUNT(*) FROM tgscan.posted WHERE status = :status AND posted_at >= :since")
    Mono<Long> countByStatusSince(@Param("status") String status, @Param("since") LocalDateTime since);
}
