package com.example.telegramuserbot.service.maintenance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Periodically runs the unified ranking brain (bot.fn_recompute_importance).
 *
 * <p>The old additive trigger is gone (removed by changeset 072). This job replaces it
 * with a configurable, set-based recompute on the full window defined in app_settings.
 * All formula coefficients live in bot.app_settings — no redeploy needed to tune them.
 *
 * <p>After every successful recompute, logs a value_score distribution over the last
 * 24 h so the maintainer can calibrate downstream thresholds
 * (news.proactive-posting.min-value, news.web-enrich.min-value) after observing
 * real output. Those thresholds are NOT touched here.
 */
@Service
@ConditionalOnProperty(name = "ranking.recompute.enabled", havingValue = "true", matchIfMissing = false)
public final class ImportanceRecomputeScheduledJob {

    private static final Logger log = LoggerFactory.getLogger(ImportanceRecomputeScheduledJob.class);

    /** Distribution query: value_score = importance * ln(max(subscribers, 2)) — mirrors the live consumer query. */
    private static final String DISTRIBUTION_SQL = """
            SELECT
              round(min(v)::numeric, 3)                                       AS v_min,
              round(percentile_cont(0.5) WITHIN GROUP (ORDER BY v)::numeric, 3) AS v_p50,
              round(percentile_cont(0.9) WITHIN GROUP (ORDER BY v)::numeric, 3) AS v_p90,
              round(max(v)::numeric, 3)                                       AS v_max
            FROM (
              SELECT m.importance * ln(greatest(tc.subscribers, 2)) AS v
              FROM bot.messages m
              JOIN tgscan.channels tc ON tc.id = m.chat_id
              WHERE m.chat_id < 0
                AND m.date > now() - interval '24 hours'
                AND m.content IS NOT NULL
                AND COALESCE(m.is_primary_in_cluster, true)
            ) s
            """;

    private final DatabaseClient databaseClient;

    public ImportanceRecomputeScheduledJob(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
        log.info("ImportanceRecomputeScheduledJob initialized");
    }

    @Scheduled(
            fixedDelayString   = "${ranking.recompute.interval-ms:3600000}",
            initialDelayString = "${ranking.recompute.initial-delay-ms:120000}"
    )
    public void recomputeImportance() {
        Instant start = Instant.now();
        log.info("Starting importance recompute (bot.fn_recompute_importance)");

        databaseClient.sql("SELECT bot.fn_recompute_importance()")
                .fetch()
                .rowsUpdated()
                .timeout(Duration.ofMinutes(5))
                .doOnSuccess(ignored -> log.info("Importance recompute completed in {} ms",
                        Duration.between(start, Instant.now()).toMillis()))
                .doOnError(e -> log.error("Importance recompute failed: {}", e.getMessage(), e))
                .onErrorResume(e -> reactor.core.publisher.Mono.empty())
                .thenMany(
                        databaseClient.sql(DISTRIBUTION_SQL)
                                .fetch()
                                .all()
                                .doOnNext(row -> log.info(
                                        "value_score distribution (24h): min={} p50={} p90={} max={}",
                                        row.get("v_min"),
                                        row.get("v_p50"),
                                        row.get("v_p90"),
                                        row.get("v_max")
                                ))
                                .doOnError(e -> log.warn("value_score distribution query failed: {}", e.getMessage()))
                                .onErrorResume(e -> reactor.core.publisher.Flux.empty())
                )
                .subscribe();
    }
}
