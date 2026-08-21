package com.example.telegramuserbot.controller;

import com.example.telegramuserbot.config.ConditionalOnHttpEnabled;
import com.example.telegramuserbot.dto.ChannelActivityEntry;
import com.example.telegramuserbot.dto.ChannelEngagementEntry;
import com.example.telegramuserbot.service.channels.ChannelActivityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

/**
 * Admin endpoint that returns a per-channel activity report for a configurable
 * recent time window.
 *
 * <p>Exposes {@code GET /api/admin/channels/activity?days=N} (FR-001).
 *
 * <p>The response is a JSON array of {@link ChannelActivityEntry} records ranked
 * by message count descending (most active first). Silent channels appear at the
 * bottom with {@code messageCount = 0} and {@code lastActivityAt = null} (FR-006, FR-008).
 *
 * <p>Access control follows the established project pattern: the bean is conditional
 * on {@code app.http.enabled} via {@link ConditionalOnHttpEnabled}; no Spring Security
 * role checks are applied on any admin controller in this application (FR-010).
 */
@RestController
@ConditionalOnHttpEnabled
@RequestMapping("/api/admin/channels")
@Tag(name = "Channel Activity", description = "Read-only channel activity report endpoint")
public class ChannelActivityController {

    private static final Logger log = LoggerFactory.getLogger(ChannelActivityController.class);
    private static final Logger uiLog = LoggerFactory.getLogger("frontend.ui");

    private static final int MIN_DAYS = 1;
    private static final int MAX_DAYS = 365;

    private final ChannelActivityService channelActivityService;

    public ChannelActivityController(ChannelActivityService channelActivityService) {
        this.channelActivityService = channelActivityService;
    }

    /**
     * Returns per-channel message count and last-activity timestamp for the
     * requested lookback window.
     *
     * @param days lookback window in days; must be in {@code [1, 365]};
     *             defaults to {@code 7} when absent (FR-002, NFR-005).
     * @return 200 with a JSON array of {@link ChannelActivityEntry};
     *         400 if {@code days} is outside {@code [1, 365]} (FR-003);
     *         503 if the underlying database query fails (FR-013).
     */
    @GetMapping("/activity")
    @Operation(
            summary = "Channel activity report",
            description = "Returns per-channel message count and last-activity timestamp "
                    + "for the requested window. Silent channels appear with messageCount=0."
    )
    public Mono<ResponseEntity<?>> activity(
            @Parameter(description = "Lookback window in days [1-365]; default 7")
            @RequestParam(defaultValue = "7") int days
    ) {
        // Validate days range (FR-003). No warning log on the default path (NFR-005).
        if (days < MIN_DAYS || days > MAX_DAYS) {
            Map<String, Object> errorBody = Map.of(
                    "error", "Parameter 'days' must be between " + MIN_DAYS + " and " + MAX_DAYS,
                    "validRange", List.of(MIN_DAYS, MAX_DAYS)
            );
            return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorBody));
        }

        uiLog.info("UI:channels:activity days={}", days);

        return channelActivityService.reportActivity(days)
                .collectList()
                .<ResponseEntity<?>>map(entries -> ResponseEntity.ok(entries))
                .onErrorResume(ex -> {
                    log.error("Channel activity query failed for days={}", days, ex);
                    Map<String, Object> errorBody = Map.of(
                            "error", "Failed to retrieve channel activity data",
                            "detail", ex.getMessage() != null ? ex.getMessage() : "unknown error"
                    );
                    return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(errorBody));
                });
    }

    /**
     * Returns per-channel engagement signals (post-frequency + views-per-subscriber)
     * for <em>joined</em> channels over the requested lookback window. Useful for
     * identifying dead/low-engagement channels that are candidates to leave.
     *
     * <p>Computed from existing {@code bot.messages.views} and
     * {@code tgscan.channels.subscribers} — no schema changes required.
     *
     * @param days lookback window in days; must be in {@code [1, 365]}; default 7.
     * @return 200 with a JSON array of {@link ChannelEngagementEntry} sorted by
     *         post frequency descending (busiest first, silent last);
     *         400 if {@code days} is out of range; 503 on DB failure.
     */
    @GetMapping("/engagement")
    @Operation(
            summary = "Joined-channel engagement report",
            description = "Per-channel post-frequency (posts/day) and engagement-per-subscriber "
                    + "(avg views / subscribers) for joined channels. Silent or low-engagement "
                    + "channels are candidates to leave, freeing membership headroom."
    )
    public Mono<ResponseEntity<?>> engagement(
            @Parameter(description = "Lookback window in days [1-365]; default 7")
            @RequestParam(defaultValue = "7") int days
    ) {
        if (days < MIN_DAYS || days > MAX_DAYS) {
            Map<String, Object> errorBody = Map.of(
                    "error", "Parameter 'days' must be between " + MIN_DAYS + " and " + MAX_DAYS,
                    "validRange", List.of(MIN_DAYS, MAX_DAYS)
            );
            return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorBody));
        }

        log.info("Collector engagement report requested for days={}", days);

        return channelActivityService.reportEngagement(days)
                .collectList()
                .<ResponseEntity<?>>map(entries -> {
                    long total = entries.size();
                    long silent = entries.stream().filter(e -> e.messageCount() == 0).count();
                    log.info("Collector engagement report: total joined={}, silent in window={}", total, silent);
                    return ResponseEntity.ok(Map.of(
                            "days", days,
                            "joinedChannelCount", total,
                            "silentInWindow", silent,
                            "channels", entries
                    ));
                })
                .onErrorResume(ex -> {
                    log.error("Channel engagement query failed for days={}", days, ex);
                    Map<String, Object> errorBody = Map.of(
                            "error", "Failed to retrieve channel engagement data",
                            "detail", ex.getMessage() != null ? ex.getMessage() : "unknown error"
                    );
                    return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(errorBody));
                });
    }
}
