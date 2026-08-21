package com.example.telegramuserbot.service.observability;

import com.example.telegramuserbot.domain.PipelineSnapshot;
import reactor.core.publisher.Mono;

/**
 * Service responsible for monitoring the scoring and publishing pipeline health.
 *
 * <p>Provides three observability mechanisms:</p>
 * <ol>
 *   <li>Periodic health snapshots persisted to bot.pipeline_snapshots</li>
 *   <li>Anomaly alerts sent to a configured Telegram chat</li>
 *   <li>Score distribution logs written every 24 hours</li>
 * </ol>
 */
public interface PipelineObservabilityService {

    /**
     * Captures a pipeline health snapshot, persists it, and sends a Telegram
     * alert if an anomaly is detected.
     *
     * @return mono containing the saved snapshot
     */
    Mono<PipelineSnapshot> captureSnapshot();

    /**
     * Logs the importance score distribution for the last 24 hours.
     * Output goes to SLF4J at INFO level.
     *
     * @return mono completing when logging is done
     */
    Mono<Void> logScoreDistribution();
}
