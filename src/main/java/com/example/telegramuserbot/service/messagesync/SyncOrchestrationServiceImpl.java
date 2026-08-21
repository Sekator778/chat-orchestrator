package com.example.telegramuserbot.service.messagesync;

import com.example.telegramuserbot.config.BotInstanceProvider;
import com.example.telegramuserbot.controller.SyncController.BulkSyncEnableRequest;
import com.example.telegramuserbot.service.TelegramClientManager;
import com.example.telegramuserbot.telegram.TelegramClientFacade;
import com.example.telegramuserbot.controller.SyncController.BulkSyncResultDto;
import com.example.telegramuserbot.controller.SyncController.ChannelSyncInfoDto;
import com.example.telegramuserbot.domain.*;
import com.example.telegramuserbot.dto.*;
import com.example.telegramuserbot.exception.ResourceNotFoundException;
import com.example.telegramuserbot.exception.SyncDisabledException;
import com.example.telegramuserbot.repository.*;
import com.example.telegramuserbot.service.cache.SyncEnabledChatsCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Service
public class SyncOrchestrationServiceImpl implements SyncOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(SyncOrchestrationServiceImpl.class);

    private final SyncJobRepository syncJobRepository;
    private final SyncConfigurationRepository syncConfigRepository;
    private final ChannelRepository channelRepository;
    private final ChatConfigRepository chatConfigRepository;
    private final SyncExecutionService syncExecutionService;
    private final SyncEnabledChatsCache syncEnabledChatsCache;
    private final BotInstanceProvider botInstanceProvider;
    private final DatabaseClient databaseClient;
    private final TelegramClientManager clientManager;

    private final Map<Long, Sinks.Many<SyncProgressDto>> progressSinks = new ConcurrentHashMap<>();

    public SyncOrchestrationServiceImpl(
            SyncJobRepository syncJobRepository,
            SyncConfigurationRepository syncConfigRepository,
            ChannelRepository channelRepository,
            ChatConfigRepository chatConfigRepository,
            SyncExecutionService syncExecutionService,
            SyncEnabledChatsCache syncEnabledChatsCache,
            BotInstanceProvider botInstanceProvider,
            DatabaseClient databaseClient,
            TelegramClientManager clientManager) {
        this.syncJobRepository = syncJobRepository;
        this.syncConfigRepository = syncConfigRepository;
        this.channelRepository = channelRepository;
        this.chatConfigRepository = chatConfigRepository;
        this.syncExecutionService = syncExecutionService;
        this.syncEnabledChatsCache = syncEnabledChatsCache;
        this.botInstanceProvider = botInstanceProvider;
        this.databaseClient = databaseClient;
        this.clientManager = clientManager;
    }

    @Override
    public Mono<SyncJobDto> initiateSync(SyncRequestDto request, Long initiatorUserId) {
        log.info("Initiating sync for channel {} with depth {} days by user {}", request.channelId(), request.syncDepthDays(), initiatorUserId);

        return channelRepository.findByChatId(request.channelId())
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Channel not found: " + request.channelId())))
                .flatMap(channel -> syncEnabledChatsCache.find(request.channelId())
                        .switchIfEmpty(Mono.error(new IllegalStateException("ChatConfig not found or sync disabled for channel " + request.channelId())))
                        .flatMap(chatConfig -> {
                            if (!chatConfig.isSyncEnabled()) {
                                return Mono.error(new SyncDisabledException(channel.getChatId(), channel.getTitle()));
                            }
                            return syncConfigRepository.findByChannelId(channel.getId())
                                    .switchIfEmpty(Mono.defer(() -> createDefaultSyncConfiguration(channel)))
                                    .flatMap(syncConfig -> {
                                        if (!syncConfig.isValidSyncDepth(request.syncDepthDays())) {
                                            return Mono.error(new IllegalArgumentException(String.format("Invalid sync depth %d days. Maximum allowed: %d days", request.syncDepthDays(), syncConfig.getMaxSyncDepthDays())));
                                        }
                                        return syncJobRepository.countActiveJobsByChannelId(channel.getId())
                                                .flatMap(activeSyncs -> {
                                                    if (!request.forceSync() && activeSyncs >= syncConfig.getMaxConcurrentSyncs()) {
                                                        return Mono.error(new IllegalStateException(String.format("Channel %s already has %d active sync(s). Maximum allowed: %d", channel.getTitle(), activeSyncs, syncConfig.getMaxConcurrentSyncs())));
                                                    }
                                                    SyncJob syncJob = new SyncJob(channel.getId(), request.syncDepthDays(), initiatorUserId);
                                                    syncJob.setBotInstanceId(botInstanceProvider.getInstanceId());
                                                    return syncJobRepository.save(syncJob)
                                                            .doOnSuccess(this::startAsyncSync)
                                                            .map(savedJob -> SyncJobDto.fromEntity(savedJob, channel));
                                                });
                                    });
                        }));
    }

    @Override
    public Mono<SyncJobDto> getSyncJobStatus(Long jobId) {
        return syncJobRepository.findById(jobId)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Sync job not found: " + jobId)))
                .flatMap(job -> channelRepository.findByIdForInstance(job.getChannelId())
                        .switchIfEmpty(Mono.error(new ResourceNotFoundException("Channel not found for job: " + jobId)))
                        .map(channel -> SyncJobDto.fromEntity(job, channel)));
    }

    @Override
    public Flux<SyncProgressDto> getSyncProgress(Long jobId) {
        Sinks.Many<SyncProgressDto> sink = progressSinks.computeIfAbsent(jobId, id -> Sinks.many().replay().limit(100));
        return sink.asFlux()
                .doFinally(signal -> {
                    if (sink.currentSubscriberCount() == 0) {
                        progressSinks.remove(jobId);
                    }
                });
    }

    @Override
    public Mono<SyncJobDto> cancelSync(Long jobId, Long userId) {
        return syncJobRepository.findById(jobId)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Sync job not found: " + jobId)))
                .flatMap(job -> {
                    if (!job.getStatus().isActive()) {
                        return Mono.error(new IllegalStateException("Cannot cancel job in status: " + job.getStatus()));
                    }
                    return syncExecutionService.cancelSync(jobId)
                            .then(Mono.fromCallable(() -> {
                                job.setStatus(SyncStatus.CANCELLED);
                                job.setCompletedAt(LocalDateTime.now());
                                return job;
                            }))
                            .flatMap(syncJobRepository::save)
                            .flatMap(savedJob -> channelRepository.findByIdForInstance(savedJob.getChannelId())
                                    .map(channel -> {
                                        SyncProgressDto cancelled = SyncProgressDto.failed(jobId, channel.getChatId(), "Sync cancelled by user");
                                        notifyProgress(cancelled);
                                        Sinks.Many<SyncProgressDto> jobSink = progressSinks.get(jobId);
                                        if (jobSink != null) jobSink.tryEmitComplete();
                                        log.info("Sync job {} cancelled by user {}", jobId, userId);
                                        return SyncJobDto.fromEntity(savedJob, channel);
                                    }));
                });
    }

    @Override
    public Mono<List<SyncJobDto>> getChannelSyncHistory(Long channelId) {
        return channelRepository.findByChatId(channelId)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Channel not found: " + channelId)))
                .flatMapMany(channel -> syncJobRepository.findByChannelIdOrderByCreatedAtDesc(channel.getId())
                        .map(job -> SyncJobDto.summaryFromEntity(job, channel)))
                .collectList();
    }

    @Override
    public Mono<List<SyncJobDto>> getUserSyncHistory(Long userId) {
        return syncJobRepository.findByCreatedByUserIdOrderByCreatedAtDesc(userId)
                .collectList()
                .flatMap(this::mapJobsToDtosWithChannels);
    }

    @Override
    public Mono<List<SyncJobDto>> getActiveSyncJobs() {
        return syncJobRepository.findActiveJobs()
                .collectList()
                .flatMap(this::mapJobsToDtosWithChannels);
    }

    private Mono<List<SyncJobDto>> mapJobsToDtosWithChannels(List<SyncJob> jobs) {
        if (jobs.isEmpty()) {
            return Mono.just(List.of());
        }
        Set<Long> channelIds = jobs.stream().map(SyncJob::getChannelId).collect(Collectors.toSet());
        return channelRepository.findAllByIdForInstance(channelIds)
                .collectMap(Channel::getId)
                .map(channelMap -> jobs.stream()
                        .map(job -> {
                            Channel channel = channelMap.get(job.getChannelId());
                            return channel != null ? SyncJobDto.summaryFromEntity(job, channel) : null;
                        })
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList()));
    }

    @Override
    public Mono<SyncJobDto> retrySync(Long jobId, Long userId) {
        return syncJobRepository.findById(jobId)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Sync job not found: " + jobId)))
                .flatMap(originalJob -> {
                    if (originalJob.getStatus() != SyncStatus.FAILED) {
                        return Mono.error(new IllegalStateException("Can only retry failed jobs"));
                    }
                    return channelRepository.findByIdForInstance(originalJob.getChannelId())
                            .flatMap(channel -> {
                                SyncRequestDto retryRequest = new SyncRequestDto(channel.getChatId(), originalJob.getSyncDepthDays(), false);
                                return initiateSync(retryRequest, userId);
                            });
                });
    }

    @Override
    public Mono<SyncConfigurationDto> getSyncConfiguration(Long channelId) {
        return channelRepository.findByChatId(channelId)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Channel not found: " + channelId)))
                .flatMap(channel -> syncConfigRepository.findByChannelId(channel.getId())
                        .switchIfEmpty(Mono.defer(() -> createDefaultSyncConfiguration(channel)))
                        .map(syncConfig -> SyncConfigurationDto.fromEntity(syncConfig, channel)));
    }

    @Override
    public Mono<SyncConfigurationDto> updateSyncConfiguration(Long channelId, SyncConfigurationDto configDto) {
        return channelRepository.findByChatId(channelId)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Channel not found: " + channelId)))
                .flatMap(channel -> syncConfigRepository.findByChannelId(channel.getId())
                        .defaultIfEmpty(new SyncConfiguration(channel.getId()))
                        .flatMap(config -> {
                            if (config.getBotInstanceId() == null) {
                                config.setBotInstanceId(botInstanceProvider.getInstanceId());
                            }
                            config.setDefaultSyncDepthDays(configDto.defaultSyncDepthDays());
                            config.setMaxSyncDepthDays(configDto.maxSyncDepthDays());
                            config.setAutoSyncEnabled(configDto.autoSyncEnabled());
                            config.setAutoSyncIntervalDays(configDto.autoSyncIntervalDays());
                            config.setMaxConcurrentSyncs(configDto.maxConcurrentSyncs());
                            return syncConfigRepository.save(config);
                        })
                        .map(syncConfig -> SyncConfigurationDto.fromEntity(syncConfig, channel)));
    }

    @Override
    public Mono<Integer> processAutoSyncs() {
        return syncConfigRepository.findConfigurationsDueForAutoSync(LocalDateTime.now())
                .flatMap(config -> channelRepository.findByIdForInstance(config.getChannelId())
                        .flatMap(channel -> chatConfigRepository.findByChannelChatId(channel.getChatId())
                                .filter(ChatConfig::isSyncEnabled)
                                .flatMap(chatConfig -> {
                                    if (config.getDefaultSyncDepthDays() != null) {
                                        SyncRequestDto request = new SyncRequestDto(channel.getChatId(), config.getDefaultSyncDepthDays(), false);
                                        return initiateSync(request, null)
                                                .doOnSuccess(job -> {
                                                    config.markAutoSyncCompleted();
                                                    syncConfigRepository.save(config).subscribe();
                                                })
                                                .thenReturn(1);
                                    }
                                    return Mono.just(0);
                                })
                                .defaultIfEmpty(0))
                        .defaultIfEmpty(0))
                .reduce(0, Integer::sum)
                .doOnSuccess(initiated -> log.info("Processed auto-syncs: {} initiated.", initiated));
    }

    @Override
    public Mono<Integer> performMaintenance() {
        Mono<Integer> cleanedMono = syncJobRepository.findJobsForCleanup(LocalDateTime.now().minusDays(30))
                .map(SyncJob::getId)
                .collectList()
                .flatMap(jobIds -> {
                    jobIds.forEach(progressSinks::remove);
                    return syncJobRepository.deleteAllById(jobIds).thenReturn(jobIds.size());
                });

        Mono<Integer> stuckMono = syncJobRepository.findStuckJobs(LocalDateTime.now().minusHours(6))
                .flatMap(stuckJob -> {
                    stuckJob.markAsFailed("Job timed out");
                    return syncJobRepository.save(stuckJob)
                            .doOnSuccess(job -> channelRepository.findByIdForInstance(job.getChannelId()).subscribe(channel ->
                                    notifyProgress(SyncProgressDto.failed(job.getId(), channel.getChatId(), "Job timed out"))));
                })
                .count()
                .map(Long::intValue);

        return Mono.zip(cleanedMono, stuckMono)
                .doOnSuccess(tuple -> log.info("Maintenance completed: {} old jobs cleaned, {} stuck jobs marked as failed", tuple.getT1(), tuple.getT2()))
                .map(tuple -> tuple.getT1() + tuple.getT2());
    }

    private void startAsyncSync(SyncJob job) {
        // Create replay sink EAGERLY before the job starts executing.
        // Fast-completing jobs (e.g. "already covered") emit all events within ~15ms,
        // before the frontend's EventSource subscriber has connected. Using a replay
        // sink buffers those events so late subscribers still receive them.
        Sinks.Many<SyncProgressDto> sink = progressSinks.computeIfAbsent(
                job.getId(), id -> Sinks.many().replay().limit(100));

        job.markAsStarted();
        AtomicReference<SyncProgressDto> lastProgress = new AtomicReference<>();

        syncJobRepository.save(job)
                .flatMapMany(savedJob -> syncExecutionService.executeSync(savedJob)
                        .doOnNext(progress -> {
                            lastProgress.set(progress);
                            if (progress.messagesProcessed() != null && progress.messagesProcessed() > 0) {
                                job.setMessagesProcessed(progress.messagesProcessed());
                            }
                            sink.tryEmitNext(progress);
                        }))
                .then(Mono.defer(() -> {
                    // Determine final status from the last emitted progress event.
                    SyncProgressDto last = lastProgress.get();
                    boolean failed = last != null && last.status() == SyncStatus.FAILED;
                    if (failed) {
                        job.markAsFailed(last.errorMessage() != null ? last.errorMessage() : "Sync failed");
                    } else {
                        job.markAsCompleted();
                    }
                    // Chain the DB save — completes before SSE sink is terminated,
                    // ensuring refreshActiveJobs() sees the final status.
                    return syncJobRepository.save(job)
                            .doOnSuccess(s -> {
                                log.info("Sync job {} finished: status={} messages={}",
                                        job.getId(), job.getStatus(), job.getMessagesProcessed());
                                sink.tryEmitComplete();
                            });
                }))
                .onErrorResume(e -> {
                    log.error("Sync job {} hit unexpected error", job.getId(), e);
                    job.markAsFailed(e.getMessage());
                    sink.tryEmitNext(SyncProgressDto.failed(job.getId(), 0L, e.getMessage()));
                    return syncJobRepository.save(job)
                            .doOnSuccess(s -> sink.tryEmitComplete());
                })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void cancelStalePendingJobs() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(2);
        syncJobRepository.cancelStalePendingJobs(cutoff)
                .subscribe(count -> {
                    if (count > 0) {
                        log.warn("Cancelled {} stale PENDING sync jobs from previous session", count);
                    }
                });
    }

    private void notifyProgress(SyncProgressDto progress) {
        Sinks.Many<SyncProgressDto> sink = progressSinks.get(progress.jobId());
        if (sink != null) {
            sink.tryEmitNext(progress);
        }
    }

    private Mono<SyncConfiguration> createDefaultSyncConfiguration(Channel channel) {
        SyncConfiguration config = new SyncConfiguration(channel.getId());
        config.setDefaultSyncDepthDays(30);
        config.setMaxSyncDepthDays(365);
        config.setAutoSyncEnabled(false);
        config.setAutoSyncIntervalDays(7);
        config.setMaxConcurrentSyncs(1);
        config.setBotInstanceId(botInstanceProvider.getInstanceId());
        return syncConfigRepository.save(config);
    }

    @Override
    public Mono<List<ChannelSyncInfoDto>> getAvailableChannelsForSync(Integer minSubscribers, Double minWeight, Integer limit) {
        String sql = """
            SELECT c.id, c.title, c.username, c.subscribers, c.weight, c.join_status,
                   COALESCE(cc.sync_enabled, false) as sync_enabled
            FROM tgscan.channels c
            LEFT JOIN bot.chat_configs cc ON cc.channel_chat_id = c.id
            WHERE c.join_status = 'joined'
              AND (c.subscribers >= :minSubscribers OR c.subscribers IS NULL)
              AND (c.weight >= :minWeight OR c.weight IS NULL)
            ORDER BY c.subscribers DESC NULLS LAST, c.weight DESC NULLS LAST
            LIMIT :limit
            """;
        return databaseClient.sql(sql)
                .bind("minSubscribers", minSubscribers != null ? minSubscribers : 0)
                .bind("minWeight", minWeight != null ? minWeight : 0.0)
                .bind("limit", limit != null ? limit : 50)
                .map((row, meta) -> new ChannelSyncInfoDto(
                        row.get("id", Long.class),
                        row.get("title", String.class),
                        row.get("username", String.class),
                        row.get("subscribers", Integer.class),
                        row.get("weight", Double.class),
                        Boolean.TRUE.equals(row.get("sync_enabled", Boolean.class)),
                        row.get("join_status", String.class)
                ))
                .all()
                .collectList();
    }

    @Override
    public Mono<BulkSyncResultDto> bulkEnableSync(BulkSyncEnableRequest request) {
        List<Long> channelIds = request.channelIds();
        if (channelIds == null || channelIds.isEmpty()) {
            if (request.minSubscribers() == null && request.minWeight() == null) {
                return Mono.just(new BulkSyncResultDto(0, 0, 0, "No channels specified and no criteria provided"));
            }
            return getAvailableChannelsForSync(request.minSubscribers(), request.minWeight(), 100)
                    .map(channels -> channels.stream().map(ChannelSyncInfoDto::channelId).toList())
                    .flatMap(ids -> processBulkSyncEnable(ids, request.enable()));
        }
        return processBulkSyncEnable(channelIds, request.enable());
    }

    private Mono<BulkSyncResultDto> processBulkSyncEnable(List<Long> channelIds, boolean enable) {
        AtomicInteger enabled = new AtomicInteger(0);
        AtomicInteger disabled = new AtomicInteger(0);
        return Flux.fromIterable(channelIds)
                .flatMap(channelId -> toggleChannelSync(channelId, enable)
                        .doOnSuccess(info -> {
                            if (info.syncEnabled()) {
                                enabled.incrementAndGet();
                            } else {
                                disabled.incrementAndGet();
                            }
                        })
                        .onErrorResume(e -> {
                            log.warn("Failed to toggle sync for channel {}: {}", channelId, e.getMessage());
                            return Mono.empty();
                        }))
                .collectList()
                .map(results -> new BulkSyncResultDto(
                        results.size(),
                        enabled.get(),
                        disabled.get(),
                        String.format("Processed %d channels: %d enabled, %d disabled", results.size(), enabled.get(), disabled.get())
                ));
    }

    @Override
    public Mono<ChannelSyncInfoDto> toggleChannelSync(Long channelId, boolean enabled) {
        return chatConfigRepository.findByChannelChatId(channelId)
                .flatMap(config -> {
                    config.setSyncEnabled(enabled);
                    return chatConfigRepository.save(config);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    ChatConfig newConfig = new ChatConfig();
                    newConfig.setChannelId(channelId);
                    newConfig.setSyncEnabled(enabled);
                    newConfig.setEnabled(false);
                    return chatConfigRepository.save(newConfig);
                }))
                .flatMap(config -> databaseClient.sql("""
                        SELECT c.id, c.title, c.username, c.subscribers, c.weight, c.join_status
                        FROM tgscan.channels c WHERE c.id = :channelId
                        """)
                        .bind("channelId", channelId)
                        .map((row, meta) -> new ChannelSyncInfoDto(
                                row.get("id", Long.class),
                                row.get("title", String.class),
                                row.get("username", String.class),
                                row.get("subscribers", Integer.class),
                                row.get("weight", Double.class),
                                config.isSyncEnabled(),
                                row.get("join_status", String.class)
                        ))
                        .one()
                        .switchIfEmpty(Mono.just(new ChannelSyncInfoDto(
                                channelId, "Unknown", null, null, null, config.isSyncEnabled(), null
                        ))))
                .doOnSuccess(info -> {
                    log.info("Toggled sync for channel {}: enabled={}", channelId, enabled);
                    if (enabled) {
                        syncEnabledChatsCache.invalidate(channelId);
                    }
                });
    }

    @Override
    public Mono<SyncJobDto> quickScan(QuickScanRequestDto request) {
        log.info("Quick scan requested: chatId={} syncDepthDays={}", request.chatId(), request.syncDepthDays());
        return channelRepository.findByChatId(request.chatId())
                .flatMap(channel -> startQuickScanJob(request, channel, resolveBotForChannel(channel)))
                .switchIfEmpty(Mono.defer(() -> {
                    log.info("Channel {} not in tgscan.channels, probing all bots...", request.chatId());
                    return probeBotsForChat(request.chatId())
                            .flatMap(botId -> {
                                log.info("Bot {} can access chat {}, creating stub channel and starting scan", botId, request.chatId());
                                Channel stub = new Channel();
                                stub.setChatId(request.chatId());
                                stub.setBotInstanceIds(java.util.List.of(botId));
                                stub.markNew();
                                return channelRepository.save(stub)
                                        .onErrorResume(e -> {
                                            log.warn("Failed to save stub channel {}, trying to find existing: {}", request.chatId(), e.getMessage());
                                            return channelRepository.findByChatId(request.chatId());
                                        })
                                        .flatMap(savedChannel -> startQuickScanJob(request, savedChannel, botId));
                            });
                }));
    }

    private Mono<SyncJobDto> startQuickScanJob(QuickScanRequestDto request, Channel channel, String botId) {
        log.info("Quick scan using bot={} for channel={}", botId, channel.getTitle() != null ? channel.getTitle() : channel.getChatId());
        SyncJob job = new SyncJob(channel.getId(), request.syncDepthDays(), null);
        job.setBotInstanceId(botId);
        return syncJobRepository.save(job)
                .doOnSuccess(this::startAsyncSync)
                .map(savedJob -> SyncJobDto.fromEntity(savedJob, channel));
    }

    private Mono<String> probeBotsForChat(Long chatId) {
        return Flux.fromIterable(clientManager.getAllBotIds())
                .concatMap(botId -> {
                    TelegramClientFacade client = clientManager.getClient(botId);
                    if (client == null) return Mono.empty();
                    return Mono.<String>create(sink -> {
                        client.send(new it.tdlight.jni.TdApi.GetChat(chatId), result -> {
                            if (result.isError()) {
                                log.debug("Bot {} cannot access chat {}: {}", botId, chatId, result.getError().message);
                                sink.success();
                            } else {
                                sink.success(botId);
                            }
                        });
                    });
                })
                .filter(botId -> botId != null)
                .next()
                .switchIfEmpty(Mono.error(new ResourceNotFoundException(
                        "No bot persona has access to chat " + chatId)));
    }

    private String resolveBotForChannel(Channel channel) {
        List<String> channelBots = channel.getBotInstanceIds();
        if (channelBots != null && !channelBots.isEmpty()) {
            List<String> knownBots = botInstanceProvider.getInstanceIds();
            return channelBots.stream()
                    .filter(knownBots::contains)
                    .findFirst()
                    .orElse(botInstanceProvider.getInstanceId());
        }
        return botInstanceProvider.getInstanceId();
    }
}
