package com.example.telegramuserbot.controller;

import com.example.telegramuserbot.config.ConditionalOnHttpEnabled;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

/**
 * REST controller for managing source trust and categories.
 * Allows configuration of channel credibility scores and topic categories.
 */
@RestController
@ConditionalOnHttpEnabled
@RequestMapping("/api/sources")
@Tag(name = "Source Trust", description = "Manage channel trust scores and categories")
public final class SourceTrustController {

    private static final Logger uiLog = LoggerFactory.getLogger("frontend.ui");
    private final DatabaseClient databaseClient;

    public SourceTrustController(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    /**
     * Lists all channels with their trust scores.
     *
     * @return List of channels with trust information
     */
    @GetMapping
    @Operation(summary = "List sources with trust", description = "Get all channels with trust scores and categories")
    public Flux<SourceTrustView> listSources() {
        uiLog.info("UI:sources:list");
        String sql = """
                SELECT c.id, c.title, c.username,
                       st.trust_score, st.is_official, st.category, st.manual_override
                FROM tgscan.channels c
                LEFT JOIN tgscan.source_trust st ON st.channel_id = c.id
                WHERE c.join_status = 'JOINED'
                ORDER BY st.trust_score DESC NULLS LAST, c.title
                LIMIT 100
                """;

        return databaseClient.sql(sql)
                .map(row -> new SourceTrustView(
                        row.get("id", Long.class),
                        row.get("title", String.class),
                        row.get("username", String.class),
                        row.get("trust_score", Double.class),
                        row.get("is_official", Boolean.class),
                        row.get("category", String.class),
                        row.get("manual_override", Boolean.class)
                ))
                .all()
                .timeout(Duration.ofSeconds(10))
                .onErrorResume(e -> {
                    uiLog.warn("UI:sources:list error msg={}", e.getMessage());
                    return Flux.empty();
                });
    }

    /**
     * Updates trust score for a channel.
     *
     * @param channelId Channel ID
     * @param request Trust update request
     * @return Updated trust information
     */
    @PutMapping("/{channelId}/trust")
    @Operation(summary = "Update trust score", description = "Set or update trust score for a channel")
    public Mono<ResponseEntity<String>> updateTrust(
            @PathVariable Long channelId,
            @RequestBody TrustUpdateRequest request) {
        uiLog.info("UI:sources:updateTrust channelId={} score={} category={}",
                channelId, request.trustScore(), request.category());

        String sql = """
                INSERT INTO tgscan.source_trust (channel_id, trust_score, is_official, category, manual_override, created_at, last_updated)
                VALUES (:channelId, :trustScore, :isOfficial, :category, true, NOW(), NOW())
                ON CONFLICT (channel_id) DO UPDATE
                SET trust_score = EXCLUDED.trust_score,
                    is_official = EXCLUDED.is_official,
                    category = EXCLUDED.category,
                    manual_override = true,
                    last_updated = NOW()
                """;

        return databaseClient.sql(sql)
                .bind("channelId", channelId)
                .bind("trustScore", request.trustScore())
                .bind("isOfficial", request.isOfficial() != null ? request.isOfficial() : false)
                .bind("category", request.category() != null ? request.category() : "COMMUNITY")
                .fetch()
                .rowsUpdated()
                .map(rows -> {
                    uiLog.info("UI:sources:updateTrust success channelId={} rows={}", channelId, rows);
                    return ResponseEntity.ok("Updated");
                })
                .timeout(Duration.ofSeconds(5))
                .onErrorResume(e -> {
                    uiLog.warn("UI:sources:updateTrust error channelId={} msg={}", channelId, e.getMessage());
                    return Mono.just(ResponseEntity.status(500).body("Error: " + e.getMessage()));
                });
    }

    /**
     * Lists available categories.
     *
     * @return List of category names
     */
    @GetMapping("/categories")
    @Operation(summary = "List categories", description = "Get all available source categories")
    public Flux<String> listCategories() {
        uiLog.info("UI:sources:categories");
        String sql = """
                SELECT DISTINCT category
                FROM tgscan.source_trust
                WHERE category IS NOT NULL
                ORDER BY category
                """;

        return databaseClient.sql(sql)
                .map(row -> row.get("category", String.class))
                .all()
                .timeout(Duration.ofSeconds(5))
                .onErrorResume(e -> {
                    uiLog.warn("UI:sources:categories error msg={}", e.getMessage());
                    return Flux.empty();
                });
    }

    /**
     * View of a source with trust information.
     */
    public record SourceTrustView(
            Long channelId,
            String title,
            String username,
            Double trustScore,
            Boolean isOfficial,
            String category,
            Boolean manualOverride
    ) {}

    /**
     * Request to update trust settings.
     */
    public record TrustUpdateRequest(
            Double trustScore,
            Boolean isOfficial,
            String category
    ) {}
}
