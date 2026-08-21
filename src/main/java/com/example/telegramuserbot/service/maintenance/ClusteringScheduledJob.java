package com.example.telegramuserbot.service.maintenance;

import com.example.telegramuserbot.service.ranking.ClusteringService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Scheduled job for clustering similar messages across channels.
 * Groups related news for digest generation.
 */
@Service
@ConditionalOnProperty(name = "clustering.job.enabled", havingValue = "true", matchIfMissing = false)
public final class ClusteringScheduledJob {

    private static final Logger log = LoggerFactory.getLogger(ClusteringScheduledJob.class);
    private final ClusteringService clusteringService;
    @Value("${clustering.job.window-hours:24}")
    private int windowHours;

    public ClusteringScheduledJob(ClusteringService clusteringService) {
        this.clusteringService = clusteringService;
        log.info("ClusteringScheduledJob initialized with window={}h", windowHours);
    }

    @Scheduled(fixedDelayString = "${clustering.job.interval-ms:3600000}")
    public void runClustering() {
        log.info("Starting scheduled clustering job");
        clusteringService.clusterRecentMessages(Duration.ofHours(windowHours))
                .doOnSuccess(count -> log.info("Clustering job completed: {} messages clustered", count))
                // Heal any cluster left without a primary, regardless of age. Chained here —
                // inside the hourly job that reliably fires — rather than on the separate 2h
                // primary-recalc timer that deploy churn truncates before it runs.
                .then(clusteringService.healHeadlessClusters())
                .doOnError(e -> log.error("Clustering job failed: {}", e.getMessage(), e))
                .subscribe();
    }

    @Scheduled(fixedDelayString = "${clustering.job.primary-interval-ms:7200000}")
    public void recalculatePrimaries() {
        log.info("Starting primary message recalculation");
        clusteringService.recalculatePrimaryMessages(Duration.ofHours(windowHours))
                .doOnSuccess(count -> log.info("Primary recalculation completed: {} clusters updated", count))
                .doOnError(e -> log.error("Primary recalculation failed: {}", e.getMessage(), e))
                .subscribe();
    }
}
