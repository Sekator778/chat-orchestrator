package com.example.telegramuserbot.service.events;

import com.example.telegramuserbot.domain.Event;
import com.example.telegramuserbot.integration.BaseIntegrationTest;
import com.example.telegramuserbot.repository.EventRepository;
import io.r2dbc.postgresql.codec.Json;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for EventWatcherService.
 * Tests event processing lifecycle: new → ready status transitions.
 */
@SpringBootTest(classes = EventWatcherServiceIntegrationTest.TestApplication.class)
final class EventWatcherServiceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private DatabaseClient databaseClient;

    @Autowired
    private EventRepository repository;

    @Autowired
    private EventWatcherService watcher;

    @Test
    void processesNewEventsAboveConfidenceThreshold() {
        // Arrange: Insert events with varying confidence
        createEvent("btc", "SPIKE", 0.75, "high");    // Above threshold
        createEvent("eth", "FUD/PANIC", 0.60, "medium"); // Above threshold
        createEvent("ada", "SPIKE", 0.40, "low");     // Below threshold

        // Act: Process events
        StepVerifier.create(watcher.process())
            .assertNext(count -> assertThat(count).isEqualTo(2))
            .expectComplete()
            .verify(Duration.ofSeconds(5));

        // Assert: Check status transitions
        StepVerifier.create(repository.findAll().collectList())
            .assertNext(events -> {
                assertThat(events).hasSize(3);

                Event btcEvent = events.stream()
                    .filter(e -> "btc".equals(e.topic()))
                    .findFirst()
                    .orElseThrow();
                assertThat(btcEvent.status()).isEqualTo("ready");
                assertThat(btcEvent.processedAt()).isNotNull();

                Event ethEvent = events.stream()
                    .filter(e -> "eth".equals(e.topic()))
                    .findFirst()
                    .orElseThrow();
                assertThat(ethEvent.status()).isEqualTo("ready");
                assertThat(ethEvent.processedAt()).isNotNull();

                Event adaEvent = events.stream()
                    .filter(e -> "ada".equals(e.topic()))
                    .findFirst()
                    .orElseThrow();
                assertThat(adaEvent.status()).isEqualTo("new"); // Not processed
                assertThat(adaEvent.processedAt()).isNull();
            })
            .expectComplete()
            .verify(Duration.ofSeconds(5));
    }

    @Test
    void respectsSeverityThreshold() {
        // Arrange: Create EventWatcherProperties with high severity requirement
        EventWatcherProperties props = new EventWatcherProperties();
        props.setMinSeverity("high");
        props.setMinConfidence(0.5);
        props.setBatchSize(10);
        props.setEnabled(true);

        EventWatcherService strictWatcher = new EventWatcherService(repository, props);

        createEvent("btc", "SPIKE", 0.80, "high");    // Meets threshold
        createEvent("eth", "SPIKE", 0.75, "medium");  // Below severity threshold
        createEvent("sol", "SPIKE", 0.70, "low");     // Below severity threshold

        // Act
        StepVerifier.create(strictWatcher.process())
            .assertNext(count -> assertThat(count).isEqualTo(1)) // Only high severity
            .expectComplete()
            .verify(Duration.ofSeconds(5));

        // Assert
        StepVerifier.create(repository.countByStatus("ready"))
            .assertNext(count -> assertThat(count).isEqualTo(1L))
            .expectComplete()
            .verify(Duration.ofSeconds(5));
    }

    @Test
    void returnsZeroWhenNoNewEvents() {
        // Arrange: No events in database

        // Act
        StepVerifier.create(watcher.process())
            .assertNext(count -> assertThat(count).isZero())
            .expectComplete()
            .verify(Duration.ofSeconds(5));
    }

    @Test
    void respectsBatchSizeLimit() {
        // Arrange: Create more events than batch size
        for (int i = 0; i < 15; i++) {
            createEvent("topic" + i, "SPIKE", 0.75, "high");
        }

        // Act: Process with batch size of 10
        StepVerifier.create(watcher.process())
            .assertNext(count -> assertThat(count).isEqualTo(10)) // Batch limit
            .expectComplete()
            .verify(Duration.ofSeconds(5));

        // Assert: 10 processed, 5 still new
        StepVerifier.create(repository.countByStatus("ready"))
            .assertNext(count -> assertThat(count).isEqualTo(10L))
            .expectComplete()
            .verify(Duration.ofSeconds(5));

        StepVerifier.create(repository.countByStatus("new"))
            .assertNext(count -> assertThat(count).isEqualTo(5L))
            .expectComplete()
            .verify(Duration.ofSeconds(5));
    }

    @Test
    void providesAccurateStatistics() {
        // Arrange: Create events in different states
        createEvent("btc", "SPIKE", 0.80, "high");
        createEvent("eth", "SPIKE", 0.75, "high");

        // Process events
        watcher.process().block(Duration.ofSeconds(5));

        // Act: Get statistics
        StepVerifier.create(watcher.statistics())
            .assertNext(stats -> {
                assertThat(stats.newCount()).isZero();
                assertThat(stats.readyCount()).isEqualTo(2L);
                assertThat(stats.total()).isEqualTo(2L);
            })
            .expectComplete()
            .verify(Duration.ofSeconds(5));
    }

    @Test
    void skipsAlreadyProcessedEvents() {
        // Arrange: Create event and mark as ready
        Event event = createEvent("btc", "SPIKE", 0.80, "high");
        repository.updateEventStatus(event.id(), "new", "ready", LocalDateTime.now())
            .block(Duration.ofSeconds(5));

        // Act: Process again
        StepVerifier.create(watcher.process())
            .assertNext(count -> assertThat(count).isZero())
            .expectComplete()
            .verify(Duration.ofSeconds(5));
    }

    private Event createEvent(String topic, String eventType, double confidence, String severity) {
        LocalDateTime windowEnd = LocalDateTime.now();
        LocalDateTime windowStart = windowEnd.minusMinutes(15);

        Long eventId = databaseClient.sql("""
                INSERT INTO tgscan.events (
                    event_type,
                    topic,
                    window_start,
                    window_end,
                    message_count,
                    unique_sources,
                    avg_importance,
                    panic_ratio,
                    spike_ratio,
                    top_sources,
                    root_cause,
                    confidence,
                    severity,
                    evidence,
                    rate_limit_key,
                    status,
                    created_at,
                    updated_at
                )
                VALUES (
                    :eventType,
                    :topic,
                    :windowStart,
                    :windowEnd,
                    :messageCount,
                    :uniqueSources,
                    :avgImportance,
                    :panicRatio,
                    :spikeRatio,
                    :topSources,
                    :rootCause,
                    :confidence,
                    :severity,
                    :evidence,
                    :rateLimitKey,
                    'new',
                    NOW(),
                    NOW()
                )
                RETURNING id
                """)
            .bind("eventType", eventType)
            .bind("topic", topic)
            .bind("windowStart", windowStart)
            .bind("windowEnd", windowEnd)
            .bind("messageCount", 8)
            .bind("uniqueSources", 4)
            .bind("avgImportance", 0.75)
            .bind("panicRatio", 0.2)
            .bind("spikeRatio", 3.0)
            .bind("topSources", Json.of("[]"))
            .bind("rootCause", "Test event")
            .bind("confidence", confidence)
            .bind("severity", severity)
            .bind("evidence", Json.of("[]"))
            .bind("rateLimitKey", eventType + ":" + topic)
            .map(row -> row.get("id", Long.class))
            .one()
            .block(Duration.ofSeconds(5));

        return repository.findById(eventId).block(Duration.ofSeconds(5));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({EventWatcherService.class, EventWatcherProperties.class})
    @EnableR2dbcRepositories(basePackages = "com.example.telegramuserbot.repository")
    static class TestApplication { }
}
