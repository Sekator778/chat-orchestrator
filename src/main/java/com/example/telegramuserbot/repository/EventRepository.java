package com.example.telegramuserbot.repository;

import com.example.telegramuserbot.domain.Event;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

/**
 * R2DBC repository for accessing and managing events in tgscan.events table.
 * Supports event lifecycle management: new → ready → sent/suppressed/failed.
 */
public interface EventRepository extends R2dbcRepository<Event, Long> {

    /**
     * Finds events by status.
     *
     * @param status status value
     * @return flux of events with matching status
     */
    @Query("SELECT * FROM tgscan.events WHERE status = :status ORDER BY created_at")
    Flux<Event> findByStatus(@Param("status") String status);

    /**
     * Finds new events that haven't been processed yet.
     * Events are considered "new" if status = 'new'.
     *
     * @param minConfidence minimum confidence threshold
     * @param limit maximum number of events to return
     * @return flux of new events ordered by confidence (highest first)
     */
    @Query("""
        SELECT * FROM tgscan.events
        WHERE status = 'new'
          AND confidence >= :minConfidence
        ORDER BY confidence DESC, created_at
        LIMIT :limit
        """)
    Flux<Event> findNewEvents(@Param("minConfidence") Double minConfidence,
                              @Param("limit") Integer limit);

    /**
     * Finds events by severity level.
     *
     * @param severity severity level (low, medium, high)
     * @param status event status filter
     * @param limit maximum number of events
     * @return flux of events matching criteria
     */
    @Query("""
        SELECT * FROM tgscan.events
        WHERE severity = :severity
          AND status = :status
        ORDER BY confidence DESC, created_at DESC
        LIMIT :limit
        """)
    Flux<Event> findBySeverityAndStatus(@Param("severity") String severity,
                                        @Param("status") String status,
                                        @Param("limit") Integer limit);

    /**
     * Updates event status and processing timestamp.
     *
     * @param eventId event ID
     * @param expectedStatus status the row must still be in; the update is a
     *                       compare-and-set, so a row another cycle already moved
     *                       stays untouched
     * @param newStatus new status value
     * @param processedAt processing timestamp
     * @return rows updated: 0 means somebody else won the race
     */
    @Modifying
    @Query("""
        UPDATE tgscan.events
        SET status = :newStatus,
            processed_at = :processedAt,
            updated_at = NOW()
        WHERE id = :eventId
          AND status = :expectedStatus
        """)
    Mono<Integer> updateEventStatus(@Param("eventId") Long eventId,
                                    @Param("expectedStatus") String expectedStatus,
                                    @Param("newStatus") String newStatus,
                                    @Param("processedAt") LocalDateTime processedAt);

    /**
     * Updates event status with error information.
     *
     * @param eventId event ID
     * @param expectedStatus status the row must still be in (compare-and-set)
     * @param newStatus new status value
     * @param error error message
     * @param processedAt processing timestamp
     * @return rows updated: 0 means somebody else won the race
     */
    @Modifying
    @Query("""
        UPDATE tgscan.events
        SET status = :newStatus,
            processing_error = :error,
            processed_at = :processedAt,
            updated_at = NOW()
        WHERE id = :eventId
          AND status = :expectedStatus
        """)
    Mono<Integer> updateEventStatusWithError(@Param("eventId") Long eventId,
                                             @Param("expectedStatus") String expectedStatus,
                                             @Param("newStatus") String newStatus,
                                             @Param("error") String error,
                                             @Param("processedAt") LocalDateTime processedAt);

    /**
     * Counts events by status.
     *
     * @param status status to count
     * @return mono with count
     */
    @Query("SELECT COUNT(*) FROM tgscan.events WHERE status = :status")
    Mono<Long> countByStatus(@Param("status") String status);

    /**
     * Finds events created within time range.
     *
     * @param afterTime start of time range
     * @return flux of events
     */
    @Query("""
        SELECT * FROM tgscan.events
        WHERE created_at >= :afterTime
        ORDER BY created_at DESC
        """)
    Flux<Event> findRecentEvents(@Param("afterTime") LocalDateTime afterTime);

    /**
     * Checks if an event with the same deduplication key exists within TTL window.
     *
     * @param rateLimitKey rate limit key for deduplication
     * @param ttlMinutes TTL window in minutes
     * @return mono with true if duplicate exists
     */
    @Query("""
        SELECT COUNT(*) > 0 FROM tgscan.events
        WHERE rate_limit_key = :rateLimitKey
          AND created_at >= NOW() - INTERVAL ':ttlMinutes minutes'
        """)
    Mono<Boolean> existsWithinTtl(@Param("rateLimitKey") String rateLimitKey,
                                  @Param("ttlMinutes") Integer ttlMinutes);
}
