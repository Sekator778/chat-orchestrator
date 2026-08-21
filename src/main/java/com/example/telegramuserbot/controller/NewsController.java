package com.example.telegramuserbot.controller;

import com.example.telegramuserbot.config.ConditionalOnHttpEnabled;
import com.example.telegramuserbot.service.ranking.NewsSynthesisService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * REST controller for news system operations.
 * Provides endpoints for manual digest generation and testing.
 */
@RestController
@ConditionalOnHttpEnabled
@RequestMapping("/api/news")
@Tag(name = "News System", description = "News scoring, clustering and digest generation")
public final class NewsController {

    private static final Logger uiLog = LoggerFactory.getLogger("frontend.ui");
    private final NewsSynthesisService newsSynthesisService;

    public NewsController(NewsSynthesisService newsSynthesisService) {
        this.newsSynthesisService = newsSynthesisService;
    }

    /**
     * Generates a news digest for testing.
     *
     * @param request Digest generation request
     * @return Generated digest text
     */
    @PostMapping("/digest/generate")
    @Operation(summary = "Generate news digest", description = "Manually generate digest from recent messages")
    public Mono<ResponseEntity<DigestResponse>> generateDigest(@RequestBody DigestRequest request) {
        uiLog.info("UI:news:generate lookback={} max={} lang={}",
                request.lookbackHours(), request.maxMessages(), request.language());

        Duration window = Duration.ofHours(request.lookbackHours());
        int maxMessages = request.maxMessages() > 0 ? request.maxMessages() : 10;
        String language = request.language() != null ? request.language() : "en";

        return newsSynthesisService.generateDigest(window, maxMessages, language)
                .map(digest -> {
                    uiLog.info("UI:news:generate success length={}", digest.length());
                    return ResponseEntity.ok(new DigestResponse(digest, maxMessages, language));
                })
                .onErrorResume(e -> {
                    uiLog.warn("UI:news:generate error msg={}", e.getMessage());
                    return Mono.just(ResponseEntity.status(500)
                            .body(new DigestResponse("Error: " + e.getMessage(), 0, language)));
                });
    }

    /**
     * Summarizes a specific cluster by ID.
     *
     * @param clusterId Cluster ID to summarize
     * @param language Target language (en, ru, uk)
     * @return Cluster summary
     */
    @GetMapping("/cluster/{clusterId}/summary")
    @Operation(summary = "Summarize cluster", description = "Generate summary for specific message cluster")
    public Mono<ResponseEntity<String>> summarizeCluster(
            @PathVariable String clusterId,
            @RequestParam(defaultValue = "en") String language) {
        uiLog.info("UI:news:cluster clusterId={} lang={}", clusterId, language);

        Mono<ResponseEntity<String>> result = newsSynthesisService.summarizeCluster(clusterId, language)
                .map(summary -> {
                    if (summary == null || summary.isBlank()) {
                        return ResponseEntity.<String>notFound().build();
                    }
                    return ResponseEntity.ok(summary);
                });

        return result.switchIfEmpty(Mono.just(ResponseEntity.notFound().build()))
                .onErrorResume(e -> {
                    uiLog.warn("UI:news:cluster error clusterId={} msg={}", clusterId, e.getMessage());
                    return Mono.just(ResponseEntity.internalServerError().build());
                });
    }

    /**
     * Request for digest generation.
     *
     * @param lookbackHours How far back to look for news
     * @param maxMessages Maximum messages to include
     * @param language Target language
     */
    public record DigestRequest(
            int lookbackHours,
            int maxMessages,
            String language
    ) {}

    /**
     * Response containing generated digest.
     *
     * @param digest Generated digest text
     * @param messagesIncluded Number of messages included
     * @param language Language used
     */
    public record DigestResponse(
            String digest,
            int messagesIncluded,
            String language
    ) {}
}
