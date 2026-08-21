package com.example.telegramuserbot.controller;

import com.example.telegramuserbot.config.ConditionalOnHttpEnabled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Controller for message scoring and clustering operations.
 * Provides manual trigger for the scoring pipeline.
 */
@RestController
@ConditionalOnHttpEnabled
@RequestMapping("/api/scoring")
public class ScoringController {

    private static final Logger log = LoggerFactory.getLogger(ScoringController.class);

    private final DatabaseClient databaseClient;

    public ScoringController(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    /**
     * Triggers the full scoring refresh pipeline.
     * Calls tgscan.fn_refresh_all(window_days, half_life_hours, limit)
     *
     * <p>This function performs:</p>
     * <ul>
     *   <li>fn_update_clusters() - Group similar messages</li>
     *   <li>fn_update_channel_reliability(window_days) - Update channel reliability metrics</li>
     *   <li>fn_recalc_importance(half_life_hours) - Recalculate message importance</li>
     *   <li>fn_recalc_channel_score(window_days) - Update channel scores</li>
     *   <li>fn_build_agg_top_daily(limit) - Build top daily aggregations</li>
     * </ul>
     *
     * @param windowDays Number of days for the analysis window (default: 14)
     * @param halfLifeHours Half-life in hours for time decay (default: 12.0)
     * @param limit Maximum number of records for top aggregations (default: 500)
     * @return Status message with execution details
     */
    @PostMapping("/refresh")
    public Mono<ResponseEntity<Map<String, Object>>> refreshScoring(
            @RequestParam(defaultValue = "14") int windowDays,
            @RequestParam(defaultValue = "12.0") double halfLifeHours,
            @RequestParam(defaultValue = "500") int limit
    ) {
        log.info("Manual scoring refresh triggered: windowDays={}, halfLifeHours={}, limit={}",
                windowDays, halfLifeHours, limit);

        Instant startTime = Instant.now();

        return databaseClient.sql("SELECT tgscan.fn_refresh_all(:windowDays, :halfLifeHours, :limit)")
                .bind("windowDays", windowDays)
                .bind("halfLifeHours", halfLifeHours)
                .bind("limit", limit)
                .fetch()
                .rowsUpdated()
                .timeout(Duration.ofMinutes(5))
                .map(rowsUpdated -> {
                    Duration elapsed = Duration.between(startTime, Instant.now());
                    log.info("Scoring refresh completed in {} ms", elapsed.toMillis());

                    Map<String, Object> params = new java.util.HashMap<>();
                    params.put("windowDays", windowDays);
                    params.put("halfLifeHours", halfLifeHours);
                    params.put("limit", limit);

                    Map<String, Object> result = new java.util.HashMap<>();
                    result.put("status", "success");
                    result.put("message", "Scoring pipeline completed successfully");
                    result.put("parameters", params);
                    result.put("executionTimeMs", elapsed.toMillis());

                    return ResponseEntity.ok(result);
                })
                .onErrorResume(error -> {
                    log.error("Scoring refresh failed", error);
                    Duration elapsed = Duration.between(startTime, Instant.now());

                    Map<String, Object> errorBody = new java.util.HashMap<>();
                    errorBody.put("status", "error");
                    errorBody.put("message", error.getMessage());
                    errorBody.put("executionTimeMs", elapsed.toMillis());

                    return Mono.just(ResponseEntity.internalServerError().body(errorBody));
                });
    }

    /**
     * Gets the current scoring statistics from the database.
     * Useful for monitoring the state of the scoring system.
     */
    @GetMapping("/status")
    public Mono<ResponseEntity<Map<String, Object>>> getScoringStatus() {
        return Mono.zip(
                countMessagesWithImportance(),
                countClusteredMessages(),
                countChannelsWithScore(),
                getLastRefreshTime()
        ).map(tuple -> {
            Map<String, Object> result = new java.util.HashMap<>();
            result.put("messagesWithImportance", tuple.getT1());
            result.put("clusteredMessages", tuple.getT2());
            result.put("channelsWithScore", tuple.getT3());
            result.put("lastPythonRun", tuple.getT4() != null ? tuple.getT4() : "never");
            return ResponseEntity.ok(result);
        }).onErrorResume(error -> {
            log.error("Failed to get scoring status", error);
            Map<String, Object> errorBody = new java.util.HashMap<>();
            errorBody.put("status", "error");
            errorBody.put("message", error.getMessage());
            return Mono.just(ResponseEntity.internalServerError().body(errorBody));
        });
    }

    private Mono<Long> countMessagesWithImportance() {
        return databaseClient.sql("SELECT COUNT(*) as cnt FROM bot.messages WHERE importance IS NOT NULL")
                .map((row, meta) -> {
                    Object value = row.get("cnt");
                    if (value == null) return 0L;
                    if (value instanceof Number) return ((Number) value).longValue();
                    return 0L;
                })
                .one()
                .defaultIfEmpty(0L)
                .onErrorReturn(0L);
    }

    private Mono<Long> countClusteredMessages() {
        return databaseClient.sql("SELECT COUNT(*) as cnt FROM bot.messages WHERE cluster_id IS NOT NULL")
                .map((row, meta) -> {
                    Object value = row.get("cnt");
                    if (value == null) return 0L;
                    if (value instanceof Number) return ((Number) value).longValue();
                    return 0L;
                })
                .one()
                .defaultIfEmpty(0L)
                .onErrorReturn(0L);
    }

    private Mono<Long> countChannelsWithScore() {
        return databaseClient.sql("SELECT COUNT(*) as cnt FROM tgscan.channels WHERE channel_score IS NOT NULL")
                .map((row, meta) -> {
                    Object value = row.get("cnt");
                    if (value == null) return 0L;
                    if (value instanceof Number) return ((Number) value).longValue();
                    return 0L;
                })
                .one()
                .defaultIfEmpty(0L)
                .onErrorReturn(0L);
    }

    private Mono<String> getLastRefreshTime() {
        return databaseClient.sql("SELECT MAX(run_at) as last_run FROM tgscan.run_log WHERE step = 'app'")
                .map((row, meta) -> {
                    Object value = row.get("last_run");
                    return value != null ? value.toString() : "never";
                })
                .one()
                .defaultIfEmpty("never")
                .onErrorReturn("error");
    }
}
