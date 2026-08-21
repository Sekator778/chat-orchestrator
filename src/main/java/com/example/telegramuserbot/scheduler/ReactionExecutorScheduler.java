package com.example.telegramuserbot.scheduler;

import com.example.telegramuserbot.service.reaction.ReactionExecutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduler that periodically executes queued persona reactions via TDLib.
 * Only activated when the persona reaction system is enabled in configuration.
 */
@Component
@ConditionalOnProperty(name = "persona.reaction.enabled", havingValue = "true", matchIfMissing = false)
public final class ReactionExecutorScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReactionExecutorScheduler.class);

    private final ReactionExecutionService executionService;

    /**
     * Constructs the scheduler with the reaction execution service.
     *
     * @param executionService the service that performs TDLib reaction calls
     */
    public ReactionExecutorScheduler(ReactionExecutionService executionService) {
        this.executionService = executionService;
    }

    /**
     * Executes all pending reactions that are due.
     * Runs at the configured interval (default: 60 seconds).
     */
    @Scheduled(fixedDelayString = "${persona.reaction.executor-interval-ms:60000}")
    public void executeReactions() {
        log.debug("Starting scheduled reaction executor cycle");
        executionService.executePendingReactions()
            .subscribe(
                count -> {
                    if (count > 0) {
                        log.info("Reaction executor cycle completed: {} reactions executed", count);
                    } else {
                        log.debug("Reaction executor cycle completed: no reactions due");
                    }
                },
                error -> log.error("Error during scheduled reaction execution", error)
            );
    }
}
