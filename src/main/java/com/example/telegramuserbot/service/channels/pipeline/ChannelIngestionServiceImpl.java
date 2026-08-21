package com.example.telegramuserbot.service.channels.pipeline;

import com.example.telegramuserbot.domain.Channel;
import com.example.telegramuserbot.domain.ChatConfig;
import com.example.telegramuserbot.domain.ProcessingPhase;
import com.example.telegramuserbot.repository.ChannelRepository;
import com.example.telegramuserbot.repository.ChatConfigRepository;
import com.example.telegramuserbot.repository.TelegramAccountRepository;
import com.example.telegramuserbot.config.BotInstanceProvider;
import com.example.telegramuserbot.service.ChannelService;
import com.example.telegramuserbot.service.TelegramClientManager;
import com.example.telegramuserbot.service.common.LanguageDetector;
import com.example.telegramuserbot.service.proactive.ProactiveEngagementService;
import com.example.telegramuserbot.service.startup.ChatDiscoveryService;
import com.example.telegramuserbot.telegram.TelegramClientFacade;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Implementation of Phase 1: Channel Ingestion.
 * Ensures all bot personas can access the channel, joins + mutes when needed,
 * materializes ChatConfig rows, and creates proactive engagement schedules.
 */
@Service
public final class ChannelIngestionServiceImpl implements ChannelIngestionService {

    private static final Logger log = LoggerFactory.getLogger(ChannelIngestionServiceImpl.class);
    private static final String JOIN_STATUS_JOINED = "joined";
    private static final String MUTE_STATUS_MUTED = "muted";
    private static final int MUTE_FOREVER_SECONDS = Integer.MAX_VALUE;
    private static final Duration INTER_PERSONA_DELAY_MIN = Duration.ofSeconds(30);
    private static final Duration INTER_PERSONA_DELAY_MAX = Duration.ofSeconds(60);

    private final ChannelRepository channelRepository;
    private final ChatConfigRepository chatConfigRepository;
    private final TelegramAccountRepository telegramAccountRepository;
    private final ChannelService channelService;
    private final ChatDiscoveryService chatDiscoveryService;
    private final TelegramClientManager telegramClientManager;
    private final BotInstanceProvider botInstanceProvider;
    private final ProactiveEngagementService proactiveEngagementService;
    private final LanguageDetector languageDetector;

    public ChannelIngestionServiceImpl(ChannelRepository channelRepository,
                                       ChatConfigRepository chatConfigRepository,
                                       TelegramAccountRepository telegramAccountRepository,
                                       ChannelService channelService,
                                       ChatDiscoveryService chatDiscoveryService,
                                       TelegramClientManager telegramClientManager,
                                       BotInstanceProvider botInstanceProvider,
                                       ProactiveEngagementService proactiveEngagementService,
                                       LanguageDetector languageDetector) {
        this.channelRepository = channelRepository;
        this.chatConfigRepository = chatConfigRepository;
        this.telegramAccountRepository = telegramAccountRepository;
        this.channelService = channelService;
        this.chatDiscoveryService = chatDiscoveryService;
        this.telegramClientManager = telegramClientManager;
        this.botInstanceProvider = botInstanceProvider;
        this.proactiveEngagementService = proactiveEngagementService;
        this.languageDetector = languageDetector;
    }

    @Override
    public Mono<Boolean> processChannel(Channel channel) {
        long chatId = channel.getChatId();
        log.info("Phase 1 (Ingestion): Processing channel chatId={} title={} subs={} joinStatus={} username={}",
                chatId, channel.getTitle(), channel.getSubscribers(), channel.getJoinStatus(), channel.getUsername());

        channel.setLastIngestionAttemptAt(Instant.now());

        return channelRepository.save(channel)
                .then(ensureAccessible(channel))
                .onErrorResume(error -> {
                    log.error("Phase 1 (Ingestion): Failed for channel {}: {}", chatId, error.getMessage());
                    return Mono.just(false);
                });
    }

    private Mono<Boolean> ensureAccessible(Channel channel) {
        long chatId = channel.getChatId();
        String primaryId = botInstanceProvider.getInstanceId();

        return chatDiscoveryService.getChatDetails(chatId)
                .flatMap(chatInfo -> markChannelIngested(channel, chatInfo))
                .switchIfEmpty(Mono.defer(() -> joinAndMuteAllPersonas(channel, primaryId)
                        .then(chatDiscoveryService.getChatDetails(chatId))
                        .flatMap(chatInfo -> markChannelIngested(channel, chatInfo))
                        .switchIfEmpty(Mono.fromRunnable(() ->
                                        log.warn("Phase 1 (Ingestion): Unable to fetch chat {} after join attempt", chatId))
                                .thenReturn(false))));
    }

    private Mono<Boolean> markChannelIngested(Channel channel, ChatDiscoveryService.ChatInfo chatInfo) {
        long chatId = chatInfo.chatId();
        String title = chatInfo.title();
        String language = detectLanguage(title);

        return channelService.findOrCreateChannelAndConfig(chatInfo)
                .then(markConfigIngested(chatId))
                .flatMap(configUpdated -> configUpdated
                        ? updateJoinMetadata(channel)
                                .then(ensureAllPersonasJoinedAndScheduled(channel, language))
                                .thenReturn(true)
                        : Mono.just(false));
    }

    private String detectLanguage(String title) {
        if (title == null || title.isBlank()) {
            return "ru";
        }
        String detected = languageDetector.detectLanguage(title);
        return detected != null ? detected : "ru";
    }

    /**
     * Ensures secondary personas join the channel and registers proactive engagement
     * schedules for every persona (primary + secondary).
     *
     * <p>Topology rule (owner 0.7): only the designated collector account joins
     * broadcast/news channels ({@code is_channel = true}).  Non-collector personas
     * must NOT be force-joined into broadcast channels to avoid a join storm at
     * scale.  Proactive engagement scheduling is intentionally kept for all
     * personas regardless of channel type — only the Telegram join is gated.
     */
    private Mono<Void> ensureAllPersonasJoinedAndScheduled(Channel channel, String language) {
        List<String> allIds = botInstanceProvider.getInstanceIds();
        long chatId = channel.getChatId();
        boolean isBroadcast = Boolean.TRUE.equals(channel.isChannel());

        return Flux.fromIterable(allIds)
                .index()
                .concatMap(indexed -> {
                    String personaId = indexed.getT2();
                    Mono<Void> joinPart = isBroadcast
                            ? telegramAccountRepository.isCollector(personaId)
                                    .defaultIfEmpty(false)
                                    .flatMap(isCollector -> {
                                        if (!isCollector) {
                                            log.debug("Phase 1 (Ingestion): Skipping join for non-collector persona={} on broadcast chatId={}",
                                                    personaId, chatId);
                                            return Mono.<Void>empty();
                                        }
                                        return joinAndMutePersona(channel, personaId);
                                    })
                            : joinAndMutePersona(channel, personaId);

                    Mono<Void> work = joinPart
                            .then(proactiveEngagementService.ensureEngagement(chatId, personaId, language))
                            .doOnSuccess(e -> log.debug("Proactive engagement ensured chatId={} persona={}", chatId, personaId))
                            .then()
                            .onErrorResume(ex -> {
                                log.warn("Failed to ensure join/engagement chatId={} persona={}: {}",
                                        chatId, personaId, ex.getMessage());
                                return Mono.empty();
                            });
                    if (indexed.getT1() == 0) {
                        return work;
                    }
                    return Mono.delay(randomInterPersonaDelay()).then(work);
                })
                .then();
    }

    private Mono<Void> joinAndMuteAllPersonas(Channel channel, String primaryId) {
        List<String> allIds = botInstanceProvider.getInstanceIds();
        long chatId = channel.getChatId();
        boolean isBroadcast = Boolean.TRUE.equals(channel.isChannel());

        return Flux.fromIterable(allIds)
                .index()
                .concatMap(indexed -> {
                    String personaId = indexed.getT2();

                    Mono<Void> joinOp = isBroadcast
                            ? telegramAccountRepository.isCollector(personaId)
                                    .defaultIfEmpty(false)
                                    .flatMap(isCollector -> {
                                        if (!isCollector) {
                                            log.debug("Phase 1 (Ingestion): Skipping join for non-collector persona={} on broadcast chatId={}",
                                                    personaId, chatId);
                                            return Mono.<Void>empty();
                                        }
                                        return joinAndMutePersona(channel, personaId);
                                    })
                            : joinAndMutePersona(channel, personaId);

                    Mono<Void> join = joinOp
                            .onErrorResume(ex -> {
                                log.warn("Phase 1 (Ingestion): Join failed chatId={} persona={}: {}",
                                        chatId, personaId, ex.getMessage());
                                return Mono.empty();
                            });
                    if (indexed.getT1() == 0) {
                        return join;
                    }
                    return Mono.delay(randomInterPersonaDelay()).then(join);
                })
                .then();
    }

    private Mono<Void> joinAndMutePersona(Channel channel, String personaId) {
        TelegramClientFacade client = telegramClientManager.getClient(personaId);
        if (client == null) {
            log.warn("Phase 1 (Ingestion): No TDLib client for persona={}, skipping join", personaId);
            return Mono.empty();
        }
        return sendJoinRequest(channel, client, personaId)
                .then(muteChannel(channel, client, personaId));
    }

    private Mono<Boolean> markConfigIngested(long chatId) {
        return chatConfigRepository.findByChannelChatId(chatId)
                .flatMap(config -> setConfigIngested(config).thenReturn(true))
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("Phase 1 (Ingestion): Chat {} missing ChatConfig after creation attempt", chatId);
                    return Mono.just(false);
                }));
    }

    private Mono<ChatConfig> setConfigIngested(ChatConfig config) {
        ProcessingPhase currentPhase = config.getProcessingPhase();
        if (currentPhase == null || currentPhase == ProcessingPhase.RAW) {
            config.setProcessingPhase(ProcessingPhase.INGESTED);
        } else {
            log.debug("Phase 1 (Ingestion): Preserving processing phase {} for chat {}",
                    currentPhase, config.getChannelId());
        }
        config.setLastPhase1At(Instant.now());
        if (currentPhase == null || currentPhase == ProcessingPhase.RAW || currentPhase == ProcessingPhase.INGESTED) {
            config.setLastProcessingError(null);
        }
        return chatConfigRepository.save(config)
                .doOnSuccess(saved -> log.info("Phase 1 (Ingestion): Updated config {} for chat {} (phase={})",
                        saved.getId(), saved.getChannelId(), saved.getProcessingPhase()));
    }

    private Mono<Channel> updateJoinMetadata(Channel channel) {
        boolean needsUpdate = false;
        if (channel.getJoinStatus() == null || !JOIN_STATUS_JOINED.equalsIgnoreCase(channel.getJoinStatus())) {
            channel.setJoinStatus(JOIN_STATUS_JOINED);
            needsUpdate = true;
        }
        if (channel.getJoinedAt() == null) {
            channel.setJoinedAt(Instant.now());
            needsUpdate = true;
        }

        if (!needsUpdate) {
            return Mono.just(channel);
        }

        return channelRepository.save(channel);
    }

    private Mono<Void> sendJoinRequest(Channel channel, TelegramClientFacade client, String personaId) {
        long chatId = channel.getChatId();
        String username = channel.getUsername();
        log.info("Phase 1 (Ingestion): Joining chat={} title={} username={} subs={} persona={}",
                chatId, channel.getTitle(), username, channel.getSubscribers(), personaId);

        int attempts = channel.getJoinAttempts() == null ? 0 : channel.getJoinAttempts();
        channel.setJoinAttempts(attempts + 1);

        return channelRepository.save(channel)
                .then(resolveBeforeJoin(client, chatId, username))
                .then(Mono.fromFuture(() -> client.send(new TdApi.JoinChat(chatId))))
                .onErrorResume(error -> {
                    String msg = error.getMessage();
                    if (isAlreadyParticipantError(error)) {
                        log.info("Phase 1 (Ingestion): Already joined chat={} persona={}", chatId, personaId);
                        return Mono.empty();
                    }
                    log.warn("Phase 1 (Ingestion): JoinChat FAILED chat={} title={} subs={} persona={}: {}",
                            chatId, channel.getTitle(), channel.getSubscribers(), personaId, msg);
                    return Mono.error(error);
                })
                .doOnSuccess(ignored -> log.info("Phase 1 (Ingestion): JoinChat OK chat={} title={} persona={}",
                        chatId, channel.getTitle(), personaId))
                .then(Mono.defer(() -> {
                    channel.setJoinStatus(JOIN_STATUS_JOINED);
                    if (channel.getJoinedAt() == null) {
                        channel.setJoinedAt(Instant.now());
                    }
                    channel.addBotInstanceId(personaId);
                    return channelRepository.save(channel).then();
                }));
    }

    private Mono<Void> resolveBeforeJoin(TelegramClientFacade client, long chatId, String username) {
        if (username != null && !username.isBlank()) {
            log.info("Phase 1 (Ingestion): Resolving chat via SearchPublicChat username={}", username);
            return Mono.fromFuture(() -> client.send(new TdApi.SearchPublicChat(username)))
                    .doOnSuccess(r -> log.info("Phase 1 (Ingestion): Resolved username={} OK", username))
                    .onErrorResume(ex -> {
                        log.warn("Phase 1 (Ingestion): SearchPublicChat failed for {}: {}", username, ex.getMessage());
                        return Mono.empty();
                    })
                    .then();
        }
        return resolveByTitle(client, chatId);
    }

    private Mono<Void> resolveByTitle(TelegramClientFacade client, long chatId) {
        return channelRepository.findById(chatId)
                .flatMap(channel -> {
                    String title = channel.getTitle();
                    if (title == null || title.isBlank()) {
                        return Mono.empty();
                    }
                    String query = title.length() > 30 ? title.substring(0, 30) : title;
                    log.info("Phase 1 (Ingestion): Trying SearchPublicChats by title='{}' for chatId={}", query, chatId);
                    return Mono.fromFuture(() -> client.send(new TdApi.SearchPublicChats(query)))
                            .flatMap(result -> {
                                if (result instanceof TdApi.Chats chats && chats.chatIds.length > 0) {
                                    for (long foundId : chats.chatIds) {
                                        if (foundId == chatId) {
                                            log.info("Phase 1 (Ingestion): Found chatId={} via title search", chatId);
                                            return Mono.fromFuture(() -> client.send(new TdApi.GetChat(chatId))).then();
                                        }
                                    }
                                    log.debug("Phase 1 (Ingestion): Title search found {} chats but none matched chatId={}", chats.chatIds.length, chatId);
                                }
                                return Mono.<Void>empty();
                            })
                            .onErrorResume(ex -> {
                                log.debug("Phase 1 (Ingestion): SearchPublicChats failed for '{}': {}", query, ex.getMessage());
                                return Mono.empty();
                            });
                })
                .then();
    }

    private Mono<Void> muteChannel(Channel channel, TelegramClientFacade client, String personaId) {
        long chatId = channel.getChatId();
        TdApi.ChatNotificationSettings settings = new TdApi.ChatNotificationSettings();
        settings.muteFor = MUTE_FOREVER_SECONDS;
        TdApi.SetChatNotificationSettings request =
                new TdApi.SetChatNotificationSettings(chatId, settings);

        return Mono.fromFuture(() -> client.send(request))
                .doOnSuccess(ignored -> log.info("Phase 1 (Ingestion): Muted chat {} (persona={})", chatId, personaId))
                .then(Mono.defer(() -> {
                    channel.setMuteStatus(MUTE_STATUS_MUTED);
                    return channelRepository.save(channel).then();
                }))
                .onErrorResume(ex -> {
                    log.warn("Phase 1 (Ingestion): Mute failed for chatId={} persona={}: {}", chatId, personaId, ex.getMessage());
                    return Mono.empty();
                });
    }

    private boolean isAlreadyParticipantError(Throwable error) {
        String message = error.getMessage();
        return message != null && message.contains("USER_ALREADY_PARTICIPANT");
    }

    private Duration randomInterPersonaDelay() {
        long minMs = INTER_PERSONA_DELAY_MIN.toMillis();
        long maxMs = INTER_PERSONA_DELAY_MAX.toMillis();
        return Duration.ofMillis(ThreadLocalRandom.current().nextLong(minMs, maxMs + 1));
    }
}
