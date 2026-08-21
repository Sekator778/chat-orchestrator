package com.example.telegramuserbot.repository;

import com.example.telegramuserbot.domain.PostSubscription;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Flux;

/**
 * Repository for managing post subscriptions.
 * Provides queries to find subscriptions matching event criteria.
 */
public interface PostSubscriptionRepository extends R2dbcRepository<PostSubscription, Long> {

    /**
     * Finds all enabled subscriptions.
     *
     * @return flux of enabled subscriptions
     */
    Flux<PostSubscription> findByEnabledTrue();

    /**
     * Finds subscriptions that match an event's topic, type, and severity.
     * Uses regex pattern matching for topics and severity ranking for filtering.
     *
     * @param eventTopic topic of the event
     * @param eventType type of the event
     * @param eventSeverity severity of the event
     * @return flux of matching subscriptions
     */
    @Query("""
        SELECT s.*
        FROM tgscan.post_subscriptions s
        WHERE s.enabled = TRUE
          AND lower(:eventTopic) ~ lower(s.topic_pattern)
          AND :eventType = ANY(s.event_types)
          AND tgscan.severity_rank(:eventSeverity) >= tgscan.severity_rank(s.min_severity)
        ORDER BY s.id
        """)
    Flux<PostSubscription> findMatchingSubscriptions(
        @Param("eventTopic") String eventTopic,
        @Param("eventType") String eventType,
        @Param("eventSeverity") String eventSeverity
    );
}
