package com.example.telegramuserbot.scheduler;

import com.example.telegramuserbot.repository.PipelineSnapshotRepository;
import com.example.telegramuserbot.service.observability.PipelineObservabilityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Schedulers;

/**
 * Scheduler that drives periodic pipeline health observations.
 *
 * <p>Two tasks:</p>
 * <ul>
 *   <li>Every 6 hours — capture a health snapshot and send anomaly alert if needed</li>
 *   <li>Every 24 hours at 07:00 — log importance score distribution</li>
 * </ul>
 *
 * <p>Disabled entirely when {@code pipeline.observability.enabled=false}.</p>
 */
@Component
@ConditionalOnProperty(prefix = "pipeline.observability", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public final class PipelineObservabilityScheduler {

    private static final Logger log = LoggerFactory.getLogger(PipelineObservabilityScheduler.class);

    private final PipelineObservabilityService observability;
    private final PipelineSnapshotRepository snapshotRepository;

    @Value("${pipeline.observability.snapshot-retention-days:30}")
    private int retentionDays;

    /**
     * Constructs the scheduler.
     *
     * @param observability pipeline observability service
     * @param snapshotRepository repository for snapshot retention cleanup
     */
    public PipelineObservabilityScheduler(
            PipelineObservabilityService observability,
            PipelineSnapshotRepository snapshotRepository) {
        this.observability = observability;
        this.snapshotRepository = snapshotRepository;
    }

    /**
     * Captures a pipeline health snapshot every 6 hours.
     * Persists results to bot.pipeline_snapshots and sends a Telegram alert on anomaly.
     */
    @Scheduled(fixedRateString = "${pipeline.observability.snapshot-interval-ms:21600000}")
    public void captureSnapshot() {
        log.debug("Starting pipeline health snapshot");
        observability.captureSnapshot()
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        s -> log.info("Pipeline snapshot saved: id={}, anomaly={}", s.getId(), s.isAnomaly()),
                        e -> log.error("Pipeline snapshot failed", e));
    }

    /**
     * Logs score distribution every day at 07:00 to detect formula regressions.
     */
    @Scheduled(cron = "${pipeline.observability.distribution-cron:0 0 7 * * *}")
    public void logScoreDistribution() {
        log.info("Starting daily score distribution log");
        observability.logScoreDistribution()
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        unused -> {},
                        e -> log.error("Score distribution logging failed", e));
    }

    /**
     * Purges snapshot records older than the configured retention period.
     * Runs daily at 03:30 AM.
     */
    @Scheduled(cron = "0 30 3 * * *")
    public void purgeOldSnapshots() {
        log.debug("Purging pipeline snapshots older than {} days", retentionDays);
        snapshotRepository.deleteOlderThanDays(retentionDays)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        n -> { if (n > 0) log.info("Purged {} old pipeline snapshots", n); },
                        e -> log.error("Failed to purge old pipeline snapshots", e));
    }
}
