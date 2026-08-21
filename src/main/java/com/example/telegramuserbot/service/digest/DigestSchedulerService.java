package com.example.telegramuserbot.service.digest;

import com.example.telegramuserbot.domain.DigestPersona;
import com.example.telegramuserbot.dto.digest.PublishedDigestDto;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * Service for scheduling and triggering digest generation.
 * Manages scheduled execution based on persona configurations.
 */
public interface DigestSchedulerService {

    /**
     * Triggers digest generation and publishing for a specific persona.
     * Bypasses schedule check and active hours.
     *
     * @param personaId the persona ID to trigger
     * @return published digest result
     */
    Mono<PublishedDigestDto> triggerNow(Long personaId);

    /**
     * Triggers digest generation for all enabled personas.
     * Bypasses schedule check and active hours.
     *
     * @return flux of published digest results
     */
    Flux<PublishedDigestDto> triggerAllEnabled();

    /**
     * Checks if a persona should run based on its schedule.
     * Evaluates cron expression and active hours.
     *
     * @param persona the persona to check
     * @return true if digest should be generated
     */
    boolean shouldRun(DigestPersona persona);

    /**
     * Checks if current time is within persona active hours.
     *
     * @param persona the persona to check
     * @return true if within active hours
     */
    boolean isWithinActiveHours(DigestPersona persona);

    /**
     * Calculates next scheduled run time for a persona.
     *
     * @param persona the persona to check
     * @return next scheduled run time or empty if no schedule
     */
    Mono<Instant> nextScheduledRun(DigestPersona persona);

    /**
     * Calculates next scheduled runs for a persona.
     *
     * @param persona the persona to check
     * @param count number of next runs to calculate
     * @return flux of next scheduled run times
     */
    Flux<Instant> nextScheduledRuns(DigestPersona persona, int count);

    /**
     * Runs the scheduled digest check for all enabled personas.
     * Called by the scheduled job to process due personas.
     *
     * @return flux of published digest results
     */
    Flux<PublishedDigestDto> processScheduledDigests();

    /**
     * Gets the scheduler status including enabled state and statistics.
     *
     * @return scheduler status summary
     */
    Mono<SchedulerStatus> status();

    /**
     * Scheduler status information.
     *
     * @param enabled whether scheduler is enabled
     * @param totalPersonas total number of personas
     * @param enabledPersonas number of enabled personas
     * @param lastRunAt last scheduler run timestamp
     * @param digestsGeneratedToday count of digests generated today
     */
    record SchedulerStatus(
        boolean enabled,
        long totalPersonas,
        long enabledPersonas,
        Instant lastRunAt,
        long digestsGeneratedToday
    ) {}
}
