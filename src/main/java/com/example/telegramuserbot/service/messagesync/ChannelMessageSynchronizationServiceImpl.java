package com.example.telegramuserbot.service.messagesync;

import com.example.telegramuserbot.domain.Channel;
import com.example.telegramuserbot.domain.SyncConfiguration;
import com.example.telegramuserbot.dto.SyncRequestDto;
import com.example.telegramuserbot.repository.ChannelRepository;
import com.example.telegramuserbot.repository.SyncConfigurationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Default implementation that uses sync_configurations table to determine which
 * channels require historical synchronization.
 */
@Service
public class ChannelMessageSynchronizationServiceImpl implements ChannelMessageSynchronizationService {

    private static final Logger log = LoggerFactory.getLogger(ChannelMessageSynchronizationServiceImpl.class);
    private static final long SYSTEM_USER_ID = -1L;

    private final SyncConfigurationRepository syncConfigurationRepository;
    private final ChannelRepository channelRepository;
    private final SyncOrchestrationService syncOrchestrationService;
    private final AtomicReference<MessageSyncSummary> lastSummary = new AtomicReference<>();

    @Value("${startup.sync.default-depth-days:30}")
    private int defaultSyncDepthDays;

    public ChannelMessageSynchronizationServiceImpl(
            SyncConfigurationRepository syncConfigurationRepository,
            ChannelRepository channelRepository,
            SyncOrchestrationService syncOrchestrationService) {
        this.syncConfigurationRepository = syncConfigurationRepository;
        this.channelRepository = channelRepository;
        this.syncOrchestrationService = syncOrchestrationService;
    }

    @Override
    public Mono<MessageSyncSummary> synchronizeAutoSyncChannels() {
        Instant start = Instant.now();
        return syncConfigurationRepository.findByAutoSyncEnabledTrue()
                .collectList()
                .flatMap(configurations -> {
                    if (configurations.isEmpty()) {
                        MessageSyncSummary summary = new MessageSyncSummary(0, 0, 0, 0, Duration.ZERO, List.of());
                        lastSummary.set(summary);
                        return Mono.just(summary);
                    }

                    AtomicInteger attempted = new AtomicInteger();
                    AtomicInteger succeeded = new AtomicInteger();
                    AtomicInteger failed = new AtomicInteger();
                    List<Long> failedChats = new ArrayList<>();

                    return Flux.fromIterable(configurations)
                            .concatMap(config -> processConfiguration(config)
                                    .doOnNext(result -> {
                                        attempted.incrementAndGet();
                                        if (result.success()) {
                                            succeeded.incrementAndGet();
                                        } else {
                                            failed.incrementAndGet();
                                            failedChats.add(result.chatId());
                                        }
                                    }))
                            .then(Mono.fromSupplier(() -> {
                                Duration duration = Duration.between(start, Instant.now());
                                MessageSyncSummary summary = new MessageSyncSummary(
                                        configurations.size(),
                                        attempted.get(),
                                        succeeded.get(),
                                        failed.get(),
                                        duration,
                                        List.copyOf(failedChats)
                                );
                                lastSummary.set(summary);
                                log.info("Auto message synchronization completed: {} configs, {} jobs started, {} failed, duration {}",
                                        summary.autoSyncChannels(),
                                        summary.syncJobsAttempted(),
                                        summary.syncJobsFailed(),
                                        summary.duration());
                                return summary;
                            }));
                });
    }

    @Override
    public Mono<MessageSyncSummary> getLastSummary() {
        MessageSyncSummary summary = lastSummary.get();
        return summary != null ? Mono.just(summary) : Mono.empty();
    }

    @Override
    public Mono<Boolean> isChannelMarkedForSync(Long chatId) {
        if (chatId == null) {
            return Mono.just(false);
        }
        return syncConfigurationRepository.findByChannelChatId(chatId)
                .map(SyncConfiguration::isAutoSyncEnabled)
                .defaultIfEmpty(false);
    }

    private Mono<SyncAttemptResult> processConfiguration(SyncConfiguration configuration) {
        return channelRepository.findByIdForInstance(configuration.getChannelId())
                .flatMap(channel -> triggerSync(channel, configuration))
                .switchIfEmpty(Mono.fromSupplier(() -> {
                    log.warn("Auto sync configuration {} references missing channel {}", configuration.getId(), configuration.getChannelId());
                    return SyncAttemptResult.failed(configuration.getChannelId(), "CHANNEL_NOT_FOUND");
                }));
    }

    private Mono<SyncAttemptResult> triggerSync(Channel channel, SyncConfiguration configuration) {
        int syncDepthDays = resolveSyncDepth(configuration);
        SyncRequestDto request = new SyncRequestDto(channel.getChatId(), syncDepthDays, true);

        return syncOrchestrationService.initiateSync(request, SYSTEM_USER_ID)
                .flatMap(job -> {
                    log.info("Auto-sync job started for chat {} (depth {} days, jobId={})",
                            channel.getChatId(), syncDepthDays, job.id());
                    configuration.markAutoSyncCompleted();
                    return syncConfigurationRepository.save(configuration)
                            .thenReturn(SyncAttemptResult.success(channel.getChatId()));
                })
                .onErrorResume(error -> {
                    log.warn("Auto-sync job failed to start for chat {}: {}", channel.getChatId(), error.getMessage());
                    return Mono.just(SyncAttemptResult.failed(channel.getChatId(), error.getMessage()));
                });
    }

    private int resolveSyncDepth(SyncConfiguration configuration) {
        Integer depth = configuration.getDefaultSyncDepthDays();
        if (depth != null && depth > 0) {
            return depth;
        }
        return Math.max(defaultSyncDepthDays, 1);
    }

    private record SyncAttemptResult(long chatId, boolean success, String error) {
        static SyncAttemptResult success(long chatId) {
            return new SyncAttemptResult(chatId, true, null);
        }

        static SyncAttemptResult failed(long chatId, String error) {
            return new SyncAttemptResult(chatId, false, error);
        }
    }
}
