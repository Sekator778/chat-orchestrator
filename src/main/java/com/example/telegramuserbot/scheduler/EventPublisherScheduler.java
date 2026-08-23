package com.example.telegramuserbot.scheduler;

import com.example.telegramuserbot.repository.PostedRepository;
import com.example.telegramuserbot.service.publishing.EventPublisherService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

import java.time.LocalDateTime;

/**
 * Scheduler for automatic event publishing to Telegram.
 * Polls for ready events and sends them to matching subscriptions.
 * Implements Stage 3 "Event Publishing" requirements described in
 * tasks_and_manuals/events_and_alerts_pipeline.md.
 */
@Component
@ConditionalOnProperty(prefix = "events.publisher", name = "enabled", havingValue = "true", matchIfMissing = true)
public final class EventPublisherScheduler {

    private static final Logger log = LoggerFactory.getLogger(EventPublisherScheduler.class);

    /** One cycle at a time: fixedDelay alone cannot serialize a fire-and-forget subscribe. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final EventPublisherService publisher;
    private final PostedRepository posted;

    /**
     * Constructs scheduler with event publisher service.
     *
     * @param publisher event publisher service
     * @param posted posted repository for statistics
     */
    public EventPublisherScheduler(EventPublisherService publisher, PostedRepository posted) {
        this.publisher = publisher;
        this.posted = posted;
    }

    /**
     * Publishes ready events to Telegram chats.
     * <p>
     * fixedDelay, not fixedRate: the cycle is subscribed and returns immediately, so
     * a rate-based trigger would start the next one while this one is still sending.
     * The guard covers the rest - a cycle slower than the delay no longer overlaps
     * itself, which with a non-CAS status write meant publishing an event twice.
     */
    @Scheduled(fixedDelayString = "${events.publisher.poll-interval-ms:5000}")
    public void publishEvents() {
        if (!running.compareAndSet(false, true)) {
            log.debug("Previous publisher cycle still running - skipping this tick");
            return;
        }
        log.debug("Starting scheduled event publisher cycle");

        publisher.process()
            .doFinally(signal -> running.set(false))
            .subscribe(
                postsPublished -> {
                    if (postsPublished > 0) {
                        log.info("Scheduled event publisher completed: {} posts sent", postsPublished);
                    } else {
                        log.debug("Scheduled event publisher completed: no posts sent");
                    }
                },
                error -> log.error("Error during scheduled event publishing", error)
            );
    }

    /**
     * Reports publishing statistics.
     * Runs every 5 minutes to provide visibility into publishing pipeline health.
     */
    @Scheduled(fixedRate = 300000) // Every 5 minutes
    public void reportStatistics() {
        log.debug("Reporting event publisher statistics");

        LocalDateTime since = LocalDateTime.now().minusHours(1);

        posted.countByStatusSince("sent", since)
            .zipWith(posted.countByStatusSince("failed", since))
            .subscribe(
                tuple -> {
                    long sentCount = tuple.getT1();
                    long failedCount = tuple.getT2();
                    long total = sentCount + failedCount;

                    if (total > 0) {
                        double successRate = total > 0 ? (sentCount * 100.0 / total) : 0;
                        log.info("Publisher statistics (last 1h): sent={}, failed={}, success_rate={:.1f}%",
                            sentCount, failedCount, successRate);
                    }
                },
                error -> log.warn("Error retrieving publisher statistics", error)
            );
    }
}
