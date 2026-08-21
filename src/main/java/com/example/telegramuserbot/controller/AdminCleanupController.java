package com.example.telegramuserbot.controller;

import com.example.telegramuserbot.service.cleanup.JunkChannelCleanupService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * On-demand channel-hygiene cleanup. Three endpoints:
 *
 * <ul>
 *   <li>{@code POST /api/admin/cleanup/leave-junk?dryRun=true&minSubscribers=1000&limit=200} —
 *       leaves channels whose known subscriber count is below {@code minSubscribers}.</li>
 *   <li>{@code POST /api/admin/cleanup/leave-offtopic?dryRun=true} — leaves channels whose
 *       title/username/description matches the topical denylist in {@code bot.app_settings}
 *       ({@code discovery.join.title-denylist}), regardless of subscriber count. The
 *       dry-run response includes the matched token for each channel.</li>
 *   <li>{@code POST /api/admin/cleanup/leave-inactive?dryRun=true&minDaysJoined=14
 *       &minPostsPerDay=0.2&minEngagementPerSub=0.005} — leaves channels that are both
 *       past the grace period ({@code minDaysJoined} days since joining, to avoid
 *       recency-contamination) AND below the activity floor: post-frequency below
 *       {@code minPostsPerDay} OR engagement-per-subscriber below {@code minEngagementPerSub}
 *       (when non-null). Dry-run response includes per-channel metrics and the reason flag.</li>
 * </ul>
 *
 * <p>{@code dryRun=true} (default) returns the exact list that WOULD be left — review it first.
 * {@code dryRun=false} fires the rate-limited live run asynchronously (one LeaveChat every
 * {@code channel-cleanup.inter-leave-seconds}) and returns immediately; watch app.log for
 * {@code [JunkCleanup] LEFT ...} lines. Protected via the existing {@code AdminApiKeyFilter}.
 */
@RestController
@RequestMapping("/api/admin/cleanup")
public class AdminCleanupController {

    private final JunkChannelCleanupService cleanupService;

    public AdminCleanupController(JunkChannelCleanupService cleanupService) {
        this.cleanupService = cleanupService;
    }

    @PostMapping("/leave-junk")
    public Mono<ResponseEntity<Map<String, Object>>> leaveJunk(
            @RequestParam(defaultValue = "true") boolean dryRun,
            @RequestParam(defaultValue = "1000") int minSubscribers,
            @RequestParam(defaultValue = "200") int limit) {

        return cleanupService.preview(minSubscribers, limit).map(candidates -> {
            if (dryRun) {
                return ResponseEntity.ok(Map.<String, Object>of(
                        "dryRun", true,
                        "minSubscribers", minSubscribers,
                        "wouldLeaveCount", candidates.size(),
                        "candidates", candidates));
            }
            cleanupService.executeAsync(minSubscribers, limit);
            return ResponseEntity.ok(Map.<String, Object>of(
                    "dryRun", false,
                    "started", true,
                    "candidateCount", candidates.size(),
                    "note", "leaving rate-limited; watch app.log for [JunkCleanup] lines"));
        });
    }

    /**
     * Leaves off-topic channels regardless of subscriber count.
     * Matches title/username/description against the configurable topical denylist
     * ({@code discovery.join.title-denylist} in {@code bot.app_settings}).
     *
     * <p>Dry-run (default) returns the list of channels that WOULD be left, each annotated
     * with the denylist token that triggered the match — review before running live.
     */
    @PostMapping("/leave-offtopic")
    public Mono<ResponseEntity<Map<String, Object>>> leaveOffTopic(
            @RequestParam(defaultValue = "true") boolean dryRun) {

        return cleanupService.previewOffTopic().map(candidates -> {
            if (dryRun) {
                return ResponseEntity.ok(Map.<String, Object>of(
                        "dryRun", true,
                        "wouldLeaveCount", candidates.size(),
                        "candidates", candidates));
            }
            cleanupService.executeOffTopicAsync();
            return ResponseEntity.ok(Map.<String, Object>of(
                    "dryRun", false,
                    "started", true,
                    "candidateCount", candidates.size(),
                    "note", "leaving rate-limited; watch app.log for [JunkCleanup] lines"));
        });
    }

    /**
     * Leaves inactive / low-engagement broadcast channels using B0 activity metrics.
     *
     * <p>A channel is a candidate when it is:
     * <ol>
     *   <li>Past the grace period — joined at least {@code minDaysJoined} days ago
     *       (freshly-joined channels look dead due to harvest recency; bias to keep).</li>
     *   <li>Below the composite activity floor: {@code postFrequencyPerDay < minPostsPerDay}
     *       OR ({@code engagementPerSub} is non-null AND below {@code minEngagementPerSub}).</li>
     * </ol>
     *
     * <p>Dry-run (default) returns each candidate's metrics + {@code reasonFlag} so the owner
     * can review why each channel was flagged before running live.
     *
     * @param dryRun              when {@code true} (default), returns candidates without leaving
     * @param minDaysJoined       grace period in days (default 14); must be ≥ 1
     * @param minPostsPerDay      post-frequency floor (default 0.2 posts/day)
     * @param minEngagementPerSub engagement-per-subscriber floor (default 0.005)
     */
    @PostMapping("/leave-inactive")
    public Mono<ResponseEntity<Map<String, Object>>> leaveInactive(
            @RequestParam(defaultValue = "true") boolean dryRun,
            @RequestParam(defaultValue = "14") int minDaysJoined,
            @RequestParam(defaultValue = "0.2") double minPostsPerDay,
            @RequestParam(defaultValue = "0.005") double minEngagementPerSub) {

        if (minDaysJoined < 1 || minDaysJoined > 365) {
            return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .<Map<String, Object>>body(Map.of(
                            "error", "minDaysJoined must be between 1 and 365")));
        }
        if (minPostsPerDay < 0) {
            return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .<Map<String, Object>>body(Map.of(
                            "error", "minPostsPerDay must be >= 0")));
        }
        if (minEngagementPerSub < 0) {
            return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .<Map<String, Object>>body(Map.of(
                            "error", "minEngagementPerSub must be >= 0")));
        }

        return cleanupService.previewInactive(minDaysJoined, minPostsPerDay, minEngagementPerSub)
                .map(candidates -> {
                    if (dryRun) {
                        return ResponseEntity.ok(Map.<String, Object>of(
                                "dryRun", true,
                                "minDaysJoined", minDaysJoined,
                                "minPostsPerDay", minPostsPerDay,
                                "minEngagementPerSub", minEngagementPerSub,
                                "wouldLeaveCount", candidates.size(),
                                "candidates", candidates));
                    }
                    cleanupService.executeInactiveAsync(minDaysJoined, minPostsPerDay, minEngagementPerSub);
                    return ResponseEntity.ok(Map.<String, Object>of(
                            "dryRun", false,
                            "started", true,
                            "candidateCount", candidates.size(),
                            "note", "leaving rate-limited; watch app.log for [JunkCleanup] lines"));
                });
    }
}
