package com.example.telegramuserbot.service.maintenance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Periodically runs the full scoring refresh pipeline
 * (tgscan.fn_refresh_all: clusters → channel reliability → importance decay →
 * channel score → top aggregations). Until now the function was reachable only
 * through the manual REST endpoint, so importance decay froze at insert time.
 */
@Service
@ConditionalOnProperty(name = "scoring.refresh.enabled", havingValue = "true", matchIfMissing = false)
public final class ScoringRefreshScheduledJob {

    private static final Logger log = LoggerFactory.getLogger(ScoringRefreshScheduledJob.class);

    private final DatabaseClient databaseClient;

    @Value("${scoring.refresh.window-days:14}")
    private int windowDays;
    @Value("${scoring.refresh.half-life-hours:12.0}")
    private double halfLifeHours;
    @Value("${scoring.refresh.top-limit:500}")
    private int topLimit;

    public ScoringRefreshScheduledJob(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
        log.info("ScoringRefreshScheduledJob initialized");
    }

    @Scheduled(fixedDelayString = "${scoring.refresh.interval-ms:3600000}", initialDelayString = "${scoring.refresh.initial-delay-ms:300000}")
    public void refreshScoring() {
        Instant start = Instant.now();
        log.info("Starting scheduled scoring refresh: windowDays={}, halfLifeHours={}, topLimit={}",
                windowDays, halfLifeHours, topLimit);
        databaseClient.sql("SELECT tgscan.fn_refresh_all(:windowDays, :halfLifeHours, :limit)")
                .bind("windowDays", windowDays)
                .bind("halfLifeHours", halfLifeHours)
                .bind("limit", topLimit)
                .fetch()
                .rowsUpdated()
                .timeout(Duration.ofMinutes(5))
                .doOnSuccess(rows -> log.info("Scoring refresh completed in {} ms",
                        Duration.between(start, Instant.now()).toMillis()))
                .doOnError(e -> log.error("Scoring refresh failed: {}", e.getMessage(), e))
                .onErrorResume(e -> reactor.core.publisher.Mono.empty())
                .subscribe();
    }
}
