package com.example.telegramuserbot.scheduler;

import com.example.telegramuserbot.service.proactive.ProactiveEngagementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs every hour to trigger proactive chat engagement for due personas.
 * Disabled via {@code proactive.engagement.enabled=false}.
 */
@Component
@ConditionalOnProperty(prefix = "proactive.engagement", name = "enabled", havingValue = "true", matchIfMissing = true)
public final class ProactiveEngagementScheduler {

    private static final Logger log = LoggerFactory.getLogger(ProactiveEngagementScheduler.class);

    private final ProactiveEngagementService service;

    public ProactiveEngagementScheduler(ProactiveEngagementService service) {
        this.service = service;
    }

    /**
     * Runs at the start of every UTC hour.
     * Each due engagement is processed independently; failures are logged and swallowed.
     */
    @Scheduled(cron = "0 0 * * * *")
    public void run() {
        log.info("Proactive engagement scheduler triggered");
        service.processDueEngagements()
                .subscribe(
                        null,
                        error -> log.error("Proactive engagement batch failed: {}", error.getMessage())
                );
    }
}
