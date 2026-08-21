package com.example.telegramuserbot.service.channels.pipeline;

import com.example.telegramuserbot.domain.ProcessingPhase;
import com.example.telegramuserbot.repository.ChannelRepository;
import com.example.telegramuserbot.repository.ChatConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;

/**
 * Central coordinator for channel processing pipeline.
 * Orchestrates 3-phase sequential processing: RAW → INGESTED → LINKED → CONFIGURED.
 */
@Service
public final class ChannelProcessingCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ChannelProcessingCoordinator.class);
    private static final Duration OPERATION_TIMEOUT = Duration.ofMinutes(20);
    private static final int DEFAULT_BATCH_SIZE = 50;
    private static final int INGESTION_LOOKBACK_DAYS = 5;
    private static final Duration JOIN_CAP_WINDOW = Duration.ofHours(24);

    /**
     * Channels Phase 1 (Ingestion) may attempt to join per pipeline run. Kept small because
     * {@code StartupOrchestrator} runs this pipeline on EVERY boot — a large per-boot batch
     * turns frequent restarts into a join storm that trips Telegram's FLOOD_WAIT and parks
     * the accounts. Overridable via {@code channel.ingestion.phase1.startup-batch-size}.
     */
    @Value("${channel.ingestion.phase1.startup-batch-size:10}")
    private int phase1BatchSize;

    /**
     * Rolling 24h cap on NEW channel joins (the owner's "join N per 24h, defer the rest as
     * candidates"). Counts {@code joined_at} within the last 24h; once reached, Phase 1 skips
     * joins this run and the remaining channels stay deferred. This is behavioural rate-limiting
     * for the healthy case — flood-safety itself is enforced at the client boundary
     * (FloodWaitTelegramClientFacade parks JoinChat during backoff).
     */
    @Value("${channel.ingestion.daily-join-cap:30}")
    private int dailyJoinCap;

    /**
     * Minimum KNOWN subscriber count required to attempt joining a not-yet-joined channel
     * (owner: "join by subscriber count + activity, skip noise"). Matches the posting threshold —
     * a channel below it can never contribute a post, so joining it is pure noise. Already-joined
     * channels are re-ingested regardless; unenriched (NULL-subscriber) channels are deferred until
     * enrichment fills the count. Overridable via {@code channel.ingestion.min-subscribers}.
     */
    @Value("${channel.ingestion.min-subscribers:1000}")
    private int minSubscribers;

    private final ChatConfigRepository chatConfigRepository;
    private final ChannelRepository channelRepository;
    private final ChannelIngestionService ingestionService;
    private final ChannelLinkingService linkingService;
    private final ChannelTemplateApplicationService templateApplicationService;

    public ChannelProcessingCoordinator(
            ChatConfigRepository chatConfigRepository,
            ChannelRepository channelRepository,
            ChannelIngestionService ingestionService,
            ChannelLinkingService linkingService,
            ChannelTemplateApplicationService templateApplicationService) {
        this.chatConfigRepository = chatConfigRepository;
        this.channelRepository = channelRepository;
        this.ingestionService = ingestionService;
        this.linkingService = linkingService;
        this.templateApplicationService = templateApplicationService;
    }

    public Mono<PipelineResult> processPendingChannels() {
        log.info("=================================================================");
        log.info("🔄 Starting channel processing pipeline");
        log.info("=================================================================");

        Instant startTime = Instant.now();

        return runPhase1Batch(phase1BatchSize)
                .flatMap(phase1Count -> runPhase2Batch(DEFAULT_BATCH_SIZE)
                        .flatMap(phase2Count -> runPhase3Batch(DEFAULT_BATCH_SIZE)
                                .map(phase3Count -> new PipelineResult(
                                        phase1Count,
                                        phase2Count,
                                        phase3Count,
                                        Duration.between(startTime, Instant.now())
                                ))
                        )
                )
                .doOnSuccess(result -> {
                    log.info("=================================================================");
                    log.info("✅ Channel processing pipeline completed");
                    log.info("Phase 1 (Ingestion): {} channels processed", result.phase1Count());
                    log.info("Phase 2 (Linking): {} channels processed", result.phase2Count());
                    log.info("Phase 3 (Template): {} channels processed", result.phase3Count());
                    log.info("Total duration: {}", result.duration());
                    log.info("=================================================================");
                })
                .timeout(OPERATION_TIMEOUT);
    }

    public Mono<Integer> runPhase1Batch(int batchSize) {
        // Rolling 24h join cap. Fail-OPEN: if the count query errors we proceed with the full
        // batch rather than wedge the pipeline — flood-safety does not depend on this cap
        // (JoinChat is parked at the client boundary during FLOOD_WAIT backoff).
        return channelRepository.countJoinedSince(Instant.now().minus(JOIN_CAP_WINDOW))
                .defaultIfEmpty(0L)
                .onErrorResume(error -> {
                    log.warn("Phase 1 (Ingestion): daily-join-cap count failed, proceeding without cap: {}",
                            error.getMessage());
                    return Mono.just(0L);
                })
                .flatMap(joinedLast24h -> {
                    int remaining = dailyJoinCap - joinedLast24h.intValue();
                    int effectiveBatch = Math.max(0, Math.min(batchSize, remaining));
                    if (effectiveBatch <= 0) {
                        log.info(">>> Phase 1 (Ingestion): daily join cap reached ({}/{} joined in last 24h) — "
                                + "deferring remaining channels as candidates", joinedLast24h, dailyJoinCap);
                        return Mono.just(0);
                    }
                    log.info(">>> Phase 1 (Ingestion): Processing up to {} tgscan channels "
                                    + "(last attempt > {} days; daily-join-cap {}, joined-last-24h {}, min-subscribers {})",
                            effectiveBatch, INGESTION_LOOKBACK_DAYS, dailyJoinCap, joinedLast24h, minSubscribers);

                    return channelRepository.findChannelsNeedingIngestionGated(INGESTION_LOOKBACK_DAYS, minSubscribers, effectiveBatch)
                            .concatMap(channel -> ingestionService.processChannel(channel)
                                    .map(success -> success ? 1 : 0)
                                    .onErrorResume(error -> {
                                        log.error("Phase 1 failed for tgscan channel {}: {}",
                                                channel.getChatId(), error.getMessage());
                                        return Mono.just(0);
                                    }))
                            .reduce(0, Integer::sum)
                            .doOnSuccess(count -> log.info(">>> Phase 1 completed: {} channels processed", count))
                            .timeout(OPERATION_TIMEOUT);
                });
    }

    public Mono<Integer> runPhase2Batch(int batchSize) {
        log.info(">>> Phase 2 (Linking): Processing up to {} INGESTED channels", batchSize);

        return chatConfigRepository.findByProcessingPhase(ProcessingPhase.INGESTED)
                .take(batchSize)
                .concatMap(config -> linkingService.processChannel(config)
                        .flatMap(success -> {
                            if (success) {
                                config.setProcessingPhase(ProcessingPhase.LINKED);
                                config.setLastPhase2At(Instant.now());
                                config.setLastProcessingError(null);
                                return chatConfigRepository.save(config).thenReturn(1);
                            }
                            return Mono.just(0);
                        })
                        .onErrorResume(error -> {
                            log.error("Phase 2 failed for channel {}: {}", config.getChannelId(), error.getMessage());
                            config.setLastProcessingError("Phase 2: " + error.getMessage());
                            return chatConfigRepository.save(config).thenReturn(0);
                        })
                )
                .reduce(0, Integer::sum)
                .doOnSuccess(count -> log.info(">>> Phase 2 completed: {} channels processed", count))
                .timeout(OPERATION_TIMEOUT);
    }

    public Mono<Integer> runPhase3Batch(int batchSize) {
        log.info(">>> Phase 3 (Template Application): Processing up to {} LINKED channels", batchSize);

        return chatConfigRepository.findByProcessingPhase(ProcessingPhase.LINKED)
                .take(batchSize)
                .concatMap(config -> templateApplicationService.processChannel(config)
                        .flatMap(success -> {
                            if (success) {
                                config.setProcessingPhase(ProcessingPhase.CONFIGURED);
                                config.setLastPhase3At(Instant.now());
                                config.setLastProcessingError(null);
                                return chatConfigRepository.save(config).thenReturn(1);
                            }
                            return Mono.just(0);
                        })
                        .onErrorResume(error -> {
                            log.error("Phase 3 failed for channel {}: {}", config.getChannelId(), error.getMessage());
                            config.setLastProcessingError("Phase 3: " + error.getMessage());
                            return chatConfigRepository.save(config).thenReturn(0);
                        })
                )
                .reduce(0, Integer::sum)
                .doOnSuccess(count -> log.info(">>> Phase 3 completed: {} channels processed", count))
                .timeout(OPERATION_TIMEOUT);
    }

    public record PipelineResult(
            int phase1Count,
            int phase2Count,
            int phase3Count,
            Duration duration
    ) {}
}
