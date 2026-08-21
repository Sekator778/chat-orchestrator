package com.example.telegramuserbot.service.events;

import com.example.telegramuserbot.domain.Event;
import com.example.telegramuserbot.repository.EventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

/**
 * Service responsible for watching and processing detected events.
 * Implements Event Watcher pattern from Stage 2.x requirements:
 * - Polls events table for new events
 * - Applies severity/confidence thresholds
 * - Updates event status through lifecycle (new → ready → sent/suppressed/failed)
 * - Logs processing for audit trail
 */
@Service
public final class EventWatcherService {

    private static final Logger log = LoggerFactory.getLogger(EventWatcherService.class);

    private final EventRepository repository;
    private final EventWatcherProperties properties;

    /**
     * Constructs EventWatcherService with dependencies.
     *
     * @param repository event repository
     * @param properties configuration properties
     */
    public EventWatcherService(EventRepository repository,
                              EventWatcherProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /**
     * Processes new events from the events table.
     * Finds events matching threshold criteria and transitions them to 'ready' status.
     * Phase 2: Logs events to structured output for visibility.
     *
     * @return mono with count of events processed
     */
    public Mono<Integer> process() {
        if (!properties.enabled()) {
            log.debug("Event watcher is disabled");
            return Mono.just(0);
        }

        log.trace("Starting event watcher processing cycle");

        return repository.findNewEvents(
                properties.minConfidence(),
                properties.batchSize()
            )
            .doOnNext(event -> log.debug("Found new event: id={} topic={} type={}",
                event.id(), event.topic(), event.eventType()))
            .flatMap(this::transition)
            .count()
            .map(Long::intValue)
            .doOnSuccess(count -> {
                if (count > 0) {
                    log.info("Event watcher processed {} events", count);
                } else {
                    log.trace("Event watcher processing completed: no new events");
                }
            })
            .doOnError(error -> log.error("Error during event watcher processing", error));
    }

    /**
     * Transitions event from 'new' to 'ready' status and logs output.
     * Phase 2: Structured logging output for visibility.
     * Future: Will trigger alert generation.
     *
     * @param event event to process
     * @return mono with processed event
     */
    private Mono<Event> transition(Event event) {
        // Apply severity filter
        if (!meetsThreshold(event)) {
            log.debug("Event id={} below threshold, skipping", event.id());
            return Mono.empty();
        }

        return repository.updateEventStatus(
                event.id(),
                "ready",
                LocalDateTime.now()
            )
            .doOnSuccess(updated -> {
                if (updated > 0) {
                    output(event);
                }
            })
            .thenReturn(event)
            .onErrorResume(error -> {
                log.error("Failed to update event id={}: {}", event.id(), error.getMessage());
                return repository.updateEventStatusWithError(
                        event.id(),
                        "failed",
                        error.getMessage(),
                        LocalDateTime.now()
                    )
                    .thenReturn(event);
            });
    }

    /**
     * Checks if event meets configured severity/confidence thresholds.
     *
     * @param event event to check
     * @return true if event should be processed
     */
    private boolean meetsThreshold(Event event) {
        String minSeverity = properties.minSeverity();
        double eventConfidence = event.confidence() != null ? event.confidence() : 0.0;

        // Check confidence threshold
        if (eventConfidence < properties.minConfidence()) {
            return false;
        }

        // Check severity threshold
        return switch (minSeverity.toLowerCase()) {
            case "low" -> true; // All severities pass
            case "medium" -> !"low".equalsIgnoreCase(event.severity());
            case "high" -> "high".equalsIgnoreCase(event.severity());
            default -> true;
        };
    }

    /**
     * Outputs event information to structured log.
     * Phase 2: Console logging for visibility.
     * Phase 3: Will trigger alert generation instead.
     *
     * @param event event to output
     */
    private void output(Event event) {
        log.info("""
            [EVENT-READY] \
            id={} type={} topic={} severity={} confidence={:.2f} \
            messages={} sources={} panic_ratio={:.2f} spike_ratio={:.2f} \
            window={} to {} | {}""",
            event.id(),
            event.eventType(),
            event.topic(),
            event.severity(),
            event.confidence() != null ? event.confidence() : 0.0,
            event.messageCount(),
            event.uniqueSources(),
            event.panicRatio() != null ? event.panicRatio() : 0.0,
            event.spikeRatio() != null ? event.spikeRatio() : 0.0,
            event.windowStart(),
            event.windowEnd(),
            event.rootCause()
        );
    }

    /**
     * Gets statistics on event processing.
     *
     * @return mono with statistics map
     */
    public Mono<EventStatistics> statistics() {
        return Mono.zip(
                repository.countByStatus("new"),
                repository.countByStatus("ready"),
                repository.countByStatus("sent"),
                repository.countByStatus("failed"),
                repository.countByStatus("suppressed")
            )
            .map(tuple -> new EventStatistics(
                tuple.getT1(),
                tuple.getT2(),
                tuple.getT3(),
                tuple.getT4(),
                tuple.getT5()
            ));
    }

    /**
     * Statistics record for event processing metrics.
     */
    public record EventStatistics(
        long newCount,
        long readyCount,
        long sentCount,
        long failedCount,
        long suppressedCount
    ) {
        public long total() {
            return newCount + readyCount + sentCount + failedCount + suppressedCount;
        }

        @Override
        public String toString() {
            return String.format(
                "total=%d new=%d ready=%d sent=%d failed=%d suppressed=%d",
                total(), newCount, readyCount, sentCount, failedCount, suppressedCount
            );
        }
    }
}
