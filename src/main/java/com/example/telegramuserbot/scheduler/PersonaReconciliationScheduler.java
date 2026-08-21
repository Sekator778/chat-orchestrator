package com.example.telegramuserbot.scheduler;

import com.example.telegramuserbot.service.channels.reconciliation.PersonaMembershipReconciliationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Periodically reconciles persona memberships across all joined channels.
 * Ensures that newly added personas catch up and join all channels
 * that existing personas have already joined.
 */
@Component
@ConditionalOnProperty(name = "persona.reconciliation.enabled", havingValue = "true", matchIfMissing = true)
public final class PersonaReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(PersonaReconciliationScheduler.class);

    private final PersonaMembershipReconciliationService reconciliationService;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public PersonaReconciliationScheduler(PersonaMembershipReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    /**
     * Runs persona reconciliation every 4 hours (default).
     * First run 2 hours after startup to let initial ingestion complete.
     */
    @Scheduled(
            initialDelayString = "${persona.reconciliation.initial-delay:300000}",
            fixedRateString = "${persona.reconciliation.rate:14400000}"
    )
    public void reconcilePersonaMemberships() {
        if (!running.compareAndSet(false, true)) {
            log.debug("Persona reconciliation already running, skipping");
            return;
        }

        log.info("Starting persona membership reconciliation");

        reconciliationService.reconcile()
                .doFinally(signal -> running.set(false))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        totalJoins -> log.info("Persona reconciliation completed: {} new joins", totalJoins),
                        error -> {
                            log.error("Persona reconciliation failed", error);
                            running.set(false);
                        }
                );
    }
}
