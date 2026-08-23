package com.example.telegramuserbot.scheduler;

import com.example.telegramuserbot.service.events.EventWatcherService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scheduler for automatic event processing from tgscan.events table.
 * Polls for new events and transitions them through their lifecycle.
 * Implements Stage 2.x "Event Watcher" requirements.
 */
@Component
@ConditionalOnProperty(prefix = "events.watcher", name = "enabled", havingValue = "true", matchIfMissing = true)
public final class EventWatcherScheduler {

    private static final Logger log = LoggerFactory.getLogger(EventWatcherScheduler.class);

    /** One cycle at a time: fixedDelay alone cannot serialize a fire-and-forget subscribe. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final EventWatcherService watcher;

    /**
     * Constructs scheduler with event watcher service.
     *
     * @param watcher event watcher service
     */
    public EventWatcherScheduler(EventWatcherService watcher) {
        this.watcher = watcher;
    }

    /**
     * Processes new events from the events table.
     * <p>
     * fixedDelay plus a re-entrancy guard, for the same reason as the publisher:
     * the cycle is fire-and-forget, so nothing else keeps two of them apart.
     * Events are transitioned from 'new' → 'ready' status and logged.
     */
    @Scheduled(fixedDelayString = "${events.watcher.poll-interval-ms:30000}")
    public void processEvents() {
        if (!running.compareAndSet(false, true)) {
            log.debug("Previous watcher cycle still running - skipping this tick");
            return;
        }
        log.debug("Starting scheduled event watcher cycle");

        watcher.process()
            .doFinally(signal -> running.set(false))
            .subscribe(
                eventsProcessed -> {
                    if (eventsProcessed > 0) {
                        log.info("Scheduled event watcher completed: {} events processed", eventsProcessed);
                    } else {
                        log.debug("Scheduled event watcher completed: no events");
                    }
                },
                error -> log.error("Error during scheduled event watcher", error)
            );
    }

    /**
     * Reports event processing statistics.
     * Runs every 5 minutes to provide visibility into event pipeline health.
     */
    @Scheduled(fixedRate = 300000) // Every 5 minutes
    public void reportStatistics() {
        log.debug("Reporting event watcher statistics");

        watcher.statistics()
            .subscribe(
                stats -> {
                    if (stats.total() > 0) {
                        log.info("Event statistics: {}", stats);
                    }
                },
                error -> log.warn("Error retrieving event statistics", error)
            );
    }
}
