package com.example.telegramuserbot.service;

import com.example.telegramuserbot.config.BotInstanceProvider;
import com.example.telegramuserbot.domain.Channel;
import com.example.telegramuserbot.domain.ChatConfig;
import com.example.telegramuserbot.domain.RateLimits;
import com.example.telegramuserbot.domain.ResponseLength;
import com.example.telegramuserbot.exception.ResourceNotFoundException;
import com.example.telegramuserbot.repository.ChannelRepository;
import com.example.telegramuserbot.repository.ChatConfigRepository;
import com.example.telegramuserbot.repository.RateLimitsRepository;
import com.example.telegramuserbot.repository.TriggerConditionRepository;
import com.example.telegramuserbot.service.cache.SyncEnabledChatsCache;
import com.example.telegramuserbot.service.startup.ChatDiscoveryService;
import com.example.telegramuserbot.util.TelegramChatIdUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

// Channel IDs on the createNewChannel save path are coerced to canonical negative form
// via TelegramChatIdUtils.ensureSupergroupPrefix before insert (FR-001, chk_channel_id_normalized).

@Service
public class ChannelService {

    private static final Logger log = LoggerFactory.getLogger(ChannelService.class);
    private final ChannelRepository channelRepository;
	private final ChatConfigRepository chatConfigRepository;
	private final RateLimitsRepository rateLimitsRepository;
	@Nullable
	private final ChannelLanguageDetectionService languageDetectionService;
    private final SyncEnabledChatsCache syncEnabledChatsCache;
    private final TriggerConditionRepository triggerConditionRepository;
    private final BotInstanceProvider botInstanceProvider;

    private static final int DEFAULT_SYNC_DEPTH_DAYS = 2;
    private static final int DEFAULT_MAX_DAILY_MESSAGES = 2;
    private static final double HIGH_SCORE_THRESHOLD = 50.0;
    private static final int COUNT_OF_SUBSCRIBERS = 10_000;
    private static final int HIGH_SCORE_SYNC_DEPTH_DAYS = 2;
    private static final int HIGH_SCORE_MAX_DAILY_MESSAGES = 50;

    private static final String JOIN_STATUS_JOINED = "joined";

	public ChannelService(ChannelRepository channelRepository,
	                      ChatConfigRepository chatConfigRepository,
	                      RateLimitsRepository rateLimitsRepository,
	                      @Autowired(required = false) @Nullable ChannelLanguageDetectionService languageDetectionService,
	                      SyncEnabledChatsCache syncEnabledChatsCache,
	                      TriggerConditionRepository triggerConditionRepository,
	                      BotInstanceProvider botInstanceProvider) {
		this.channelRepository = channelRepository;
		this.chatConfigRepository = chatConfigRepository;
		this.rateLimitsRepository = rateLimitsRepository;
		this.languageDetectionService = languageDetectionService;
		this.syncEnabledChatsCache = syncEnabledChatsCache;
		this.triggerConditionRepository = triggerConditionRepository;
		this.botInstanceProvider = botInstanceProvider;
	}

    /**
     * ИСПРАВЛЕНО: Метод теперь корректно обрабатывает все случаи.
     * Идемпотентный метод, который гарантирует наличие в БД и Channel, и ChatConfig.
     *
     * Разделен на два этапа, чтобы сохранение канала завершилось до создания ChatConfig:
     * 1. Находим или создаем Channel и фиксируем его в tgscan.channels
     * 2. Создаем ChatConfig, ссылающийся на уже существующий Channel
     *
     * @param chatInfo Информация о чате из TDLib.
     * @return Mono, которое вернет существующий или только что созданный Channel.
     */
    public Mono<Channel> findOrCreateChannelAndConfig(ChatDiscoveryService.ChatInfo chatInfo) {
        // Этап 1: Убедиться, что Channel существует в БД
        return ensureChannelExists(chatInfo)
                .flatMap(channel -> ensureChatConfigExists(channel, chatInfo));
    }

    /**
     * Guarantees that a Channel exists in the DB (finds an existing one or creates a new one).
     * Normalization and the skip-guard are applied here, once, at the single entry point, so that
     * both the existence lookup and the create path key off the same canonical negative supergroup
     * id (FR-001, FR-002).
     *
     * WHY normalization is placed here rather than only in createNewChannel (BRD FR-001 scope):
     * Liquibase changeset 047 stores all supergroup ids in canonical negative form ("-100"+raw).
     * If normalization were applied only inside createNewChannel, findExistingChannelVariant would
     * still search by the raw positive id — missing the already-persisted negative row.  The
     * channel would appear absent, fall into createNewChannel, and the in-create duplicate guard
     * (which does use the normalized id) would find the row and throw IllegalStateException.
     * Normalizing at this single entry point keeps lookup and guard keyed to the same id, avoiding
     * that asymmetry entirely.  This is a deliberate, self-contained deviation from the BRD's
     * call-site naming (which named createNewChannel as the sole change site); the behavior is
     * correct and the two-file NFR-002 constraint is still satisfied.
     *
     * Actual reported production failure (the symptom, not the asymmetry hazard above):
     * A positive supergroup id was passed to channelRepository.save() without normalization,
     * causing a CHECK constraint violation on tgscan.channels.id (requires id < 0, per
     * chk_channel_id_normalized).  This insert was rejected with a constraint-violation error.
     * The asymmetry hazard described above is a SECONDARY hazard being proactively avoided by
     * lifting normalization here, not the error that was directly observed.
     */
    private Mono<Channel> ensureChannelExists(ChatDiscoveryService.ChatInfo chatInfo) {
        // Normalize once, early — both the lookup and the create path use this same id.
        // ensureSupergroupPrefix: positive → "-100"+id; already-prefixed negative → unchanged;
        // any other negative → unchanged; null → null;
        // parse-range failure (NumberFormatException for 19-digit ids) → original positive returned.
        Long normalizedChatId = TelegramChatIdUtils.ensureSupergroupPrefix(chatInfo.chatId());

        // Skip guard: non-supergroup chats (null id, id >= 0 after normalization) are not persisted.
        // A real positive supergroup id always coerces to a negative value; >= 0 here means the id
        // is either null, zero, or a pathologically large value that caused a parse-range failure.
        // null case is benign (unknown/unset id); non-null >= 0 is anomalous (valid TDLib ids never
        // remain positive after ensureSupergroupPrefix) and logged at WARN so operators see it.
        if (normalizedChatId == null) {
            log.debug("⏭ CHANNEL SKIPPED: chatId={} is null; skipping", chatInfo.chatId());
            return Mono.empty();
        }
        if (normalizedChatId >= 0) {
            log.warn("⏭ CHANNEL SKIPPED: chatId={} is not a supergroup id (normalizedChatId={}); " +
                            "a valid TDLib supergroup id should never remain non-negative after normalization",
                    chatInfo.chatId(), normalizedChatId);
            return Mono.empty();
        }

        return findExistingChannelVariant(normalizedChatId)
                .flatMap(existing -> updateChannelMetadata(existing, chatInfo))
                .switchIfEmpty(Mono.defer(() -> createNewChannel(chatInfo, normalizedChatId)));
    }

    /**
     * Creates only the Channel row (no ChatConfig) for atomicity.
     * The normalizedChatId parameter is pre-coerced by the caller (ensureChannelExists) so this
     * method never re-derives normalization — it receives the canonical negative supergroup id
     * directly. The in-method findByChatId guard is kept as defense-in-depth; it uses the same
     * normalizedChatId, so no additional repository calls are introduced (NFR-001 satisfied).
     */
    private Mono<Channel> createNewChannel(ChatDiscoveryService.ChatInfo chatInfo, Long normalizedChatId) {
        log.info("📝 CREATING NEW CHANNEL: '{}' (TDLib ID: {}, normalized: {})",
                chatInfo.title(), chatInfo.chatId(), normalizedChatId);

        // Defense-in-depth guard: if a row already exists this is unexpected — surface the error
        // rather than masking a duplicate. Uses normalizedChatId (same key as the caller's lookup).
        return channelRepository.findByChatId(normalizedChatId)
                .flatMap(existing -> {
                    log.error("❌ CHANNEL ALREADY EXISTS in tgscan.channels (chatId={} title='{}'), aborting creation",
                            existing.getChatId(), existing.getTitle());
                    return Mono.<Channel>error(new IllegalStateException("Channel already exists: " + existing.getChatId()));
                })
                .switchIfEmpty(Mono.defer(() -> {
                    Channel newChannel = new Channel().markNew();
                    newChannel.setChatId(normalizedChatId);
                    newChannel.setBotInstanceIds(List.of(botInstanceProvider.getInstanceId()));
                    newChannel.setTitle(chatInfo.title());
                    newChannel.setChannel(isChannelType(chatInfo.chatType()));
                    applyDiscoveryMetadata(newChannel, chatInfo);

                    return channelRepository.save(newChannel)
                            .doOnSuccess(saved -> log.info("✅ CHANNEL SAVED to tgscan.channels: id={}, chatId={}, title='{}'",
                                    saved.getId(), saved.getChatId(), saved.getTitle()))
                            .doOnError(error -> log.error("❌ CHANNEL SAVE FAILED: chatId={}, error={}",
                                    normalizedChatId, error.getMessage(), error))
                            .map(Channel::markPersisted);
                }));
    }


    /**
     * Проверяет наличие ChatConfig для существующего Channel и создает его, если он отсутствует.
     * Выполняется отдельным шагом после сохранения Channel, чтобы FK constraint сработал корректно.
     */
    private Mono<Channel> ensureChatConfigExists(Channel channel, ChatDiscoveryService.ChatInfo chatInfo) {
        return ensureChatConfigExistsInternal(channel, chatInfo);
    }

    private Mono<Channel> ensureChatConfigExistsInternal(Channel channel, ChatDiscoveryService.ChatInfo chatInfo) {
        // Если chatInfo указывает, что бот присоединен - обновить join_status в канале
        boolean shouldBeJoined = shouldMarkAsJoined(chatInfo);
        boolean alreadyJoined = isChannelJoined(channel);

        if (shouldBeJoined && !alreadyJoined) {
            log.info("📌 Updating join_status for channel '{}' (ID: {}) to 'joined' based on chatInfo",
                    channel.getTitle(), channel.getChatId());
            channel.setJoinStatus(JOIN_STATUS_JOINED);
            if (channel.getJoinedAt() == null) {
                channel.setJoinedAt(Instant.now());
            }
            channel.markPersisted();
            // Сохранить обновленный статус перед созданием конфига
            return channelRepository.save(channel)
                    .flatMap(updatedChannel -> createChatConfigForJoinedChannel(updatedChannel, chatInfo));
        }

        if (!alreadyJoined && !shouldBeJoined) {
            log.debug("Skipping ChatConfig creation for '{}' (ID: {}) because bot is not joined",
                    channel.getTitle(), channel.getChatId());
            return Mono.just(channel);
        }

        // Канал уже joined - создать конфиг если его нет
        return createChatConfigForJoinedChannel(channel, chatInfo);
    }

    private Mono<Channel> createChatConfigForJoinedChannel(Channel channel, ChatDiscoveryService.ChatInfo chatInfo) {
        // Using original TDLib ID directly
        Long tdlibChatId = channel.getChatId();

        return chatConfigRepository.findByChannelChatId(tdlibChatId)
                .flatMap(existing -> {
                    return ensureChatConfigDefaults(existing, chatInfo).thenReturn(channel);
                })
                .switchIfEmpty(Mono.defer(() -> createDefaultChatConfig(channel, chatInfo)
                        .doOnSuccess(cfg -> log.info("✅ CHAT_CONFIG CREATED in bot.chat_configs: configId={}, channelId={}",
                                cfg.getId(), cfg.getChannelId()))
                        .doOnError(error -> log.error("❌ CHAT_CONFIG CREATE FAILED for channelId={}: {}",
                                tdlibChatId, error.getMessage(), error))
                        .thenReturn(channel)))
                .onErrorResume(error -> {
                    log.warn("Failed to create ChatConfig for channel {} ({}): {}. Channel exists in DB, config will be created later.",
                            channel.getTitle(), tdlibChatId, error.getMessage());
                    return Mono.just(channel);
                });
    }

    /**
     * Создает и сохраняет ChatConfig по умолчанию для данного канала.
     */
    private Mono<ChatConfig> createDefaultChatConfig(Channel channel, ChatDiscoveryService.ChatInfo chatInfo) {
        // Using original TDLib ID directly
        Long tdlibChannelId = channel.getChatId();
        log.info("🔧 Creating default ChatConfig for channel '{}' (TDLib chatId={})",
                channel.getTitle(), tdlibChannelId);

        Mono<String> languageMono = languageDetectionService != null
                ? languageDetectionService.detectLanguage(chatInfo)
                : Mono.empty();

        return languageMono
                .defaultIfEmpty(ChannelLanguageDetectionService.DEFAULT_LANGUAGE)
                .map(language -> {
                    ChatConfig defaultConfig = new ChatConfig();
                    defaultConfig.setChannelId(tdlibChannelId);
                    defaultConfig.setEnabled(false);
                    defaultConfig.setSyncEnabled(false);
	                    defaultConfig.setAutoSyncEnabled(false);
	                    defaultConfig.setRespondToForwardedBotMessages(false);
                    defaultConfig.setContextWindowSize(10);
                    defaultConfig.setTemperature(0.7);
                    defaultConfig.setDefaultSyncDepthDays(DEFAULT_SYNC_DEPTH_DAYS);
                    defaultConfig.setLanguage(language);
                    log.info("💾 About to save ChatConfig with channelId={} for channel '{}'",
                            defaultConfig.getChannelId(), channel.getTitle());
                    return defaultConfig;
	                })
	                .flatMap(chatConfigRepository::save)
	                .flatMap(saved -> ensureMaxDailyMessages(saved.getId(), DEFAULT_MAX_DAILY_MESSAGES).thenReturn(saved))
	                .doOnSuccess(config -> {
	                    // Invalidate cache when new config is created
	                    syncEnabledChatsCache.invalidate(tdlibChannelId);
	                    log.debug("Invalidated sync cache for chat {} after config creation", tdlibChannelId);
	                });
	    }

	    private Mono<ChatConfig> ensureChatConfigDefaults(ChatConfig chatConfig, ChatDiscoveryService.ChatInfo chatInfo) {
	        AtomicBoolean updated = new AtomicBoolean(false);

        if (chatConfig.getDefaultSyncDepthDays() == null) {
            chatConfig.setDefaultSyncDepthDays(DEFAULT_SYNC_DEPTH_DAYS);
            updated.set(true);
        }

	        Mono<ChatConfig> languageUpdate;
	        if (chatConfig.getLanguage() == null || chatConfig.getLanguage().isBlank()) {
            Mono<String> languageMono = languageDetectionService != null
                    ? languageDetectionService.detectLanguage(chatInfo)
                    : Mono.empty();
            languageUpdate = languageMono
                    .defaultIfEmpty(ChannelLanguageDetectionService.DEFAULT_LANGUAGE)
                    .map(language -> {
                        chatConfig.setLanguage(language);
                        updated.set(true);
                        return chatConfig;
                    });
	        } else {
	            languageUpdate = Mono.just(chatConfig);
	        }

	        return languageUpdate
	                .flatMap(cfg -> updated.get() ? chatConfigRepository.save(cfg) : Mono.just(cfg))
	                .flatMap(cfg -> ensureMaxDailyMessages(cfg.getId(), DEFAULT_MAX_DAILY_MESSAGES).thenReturn(cfg));
	    }

	    private Mono<Void> ensureMaxDailyMessages(Long chatConfigId, int maxMessagesPerDay) {
	        if (chatConfigId == null) {
	            return Mono.empty();
	        }
	        return rateLimitsRepository.findByChatConfigId(chatConfigId)
	                .defaultIfEmpty(new RateLimits(chatConfigId))
	                .flatMap(limits -> {
	                    boolean shouldSave = limits.getId() == null;
	                    if (limits.getChatConfigId() == null) {
	                        limits.setChatConfigId(chatConfigId);
	                        shouldSave = true;
	                    }
	                    Integer current = limits.getMaxMessagesPerDay();
	                    if (current == null || current == 100) {
	                        limits.setMaxMessagesPerDay(maxMessagesPerDay);
	                        shouldSave = true;
	                    }
	                    return shouldSave ? rateLimitsRepository.save(limits).then() : Mono.empty();
	                });
	    }

    private Mono<Channel> updateChannelMetadata(Channel channel, ChatDiscoveryService.ChatInfo chatInfo) {
        boolean isChannelType = isChannelType(chatInfo.chatType());
        boolean updated = false;

        if (channel.getBotInstanceId() == null) {
            channel.addBotInstanceId(botInstanceProvider.getInstanceId());
            updated = true;
        }

        if (!Objects.equals(channel.getTitle(), chatInfo.title())) {
            channel.setTitle(chatInfo.title());
            updated = true;
        }
        if (!Objects.equals(channel.isChannel(), isChannelType)) {
            channel.setChannel(isChannelType);
            updated = true;
        }
        if (applyDiscoveryMetadata(channel, chatInfo)) {
            updated = true;
        }

        if (updated) {
            channel.markPersisted();
            return channelRepository.save(channel)
                    .map(Channel::markPersisted);
        }
        return Mono.just(channel);
    }

    private boolean isChannelType(it.tdlight.jni.TdApi.ChatType chatType) {
        if (chatType instanceof it.tdlight.jni.TdApi.ChatTypeSupergroup supergroup) {
            return supergroup.isChannel;
        }
        return false;
    }

    private boolean applyDiscoveryMetadata(Channel channel, ChatDiscoveryService.ChatInfo chatInfo) {
        boolean updated = false;
        if (chatInfo.lastMessageDate() != null) {
            Instant lastSeen = channel.getLastSeen();
            if (lastSeen == null || chatInfo.lastMessageDate().isAfter(lastSeen)) {
                channel.setLastSeen(chatInfo.lastMessageDate());
                updated = true;
            }
        }

        if (shouldMarkAsJoined(chatInfo) && !isChannelJoined(channel)) {
            channel.setJoinStatus(JOIN_STATUS_JOINED);
            if (channel.getJoinedAt() == null) {
                channel.setJoinedAt(Instant.now());
            }
            updated = true;
        }

        Boolean currentCanSend = channel.getCanSendMessages();
        if (currentCanSend == null || !Objects.equals(currentCanSend, chatInfo.canSendMessages())) {
            channel.setCanSendMessages(chatInfo.canSendMessages());
            updated = true;
        }

        return updated;
    }

    private Mono<Channel> findExistingChannelVariant(Long chatId) {
        List<Long> candidates = buildChatIdCandidates(chatId);
        if (candidates.isEmpty()) {
            return Mono.empty();
        }

        return Flux.fromIterable(candidates)
                .concatMap(candidateId -> channelRepository.findByChatId(candidateId)
                        .doOnNext(channel -> {
                            if (!Objects.equals(candidateId, chatId)) {
                                log.debug("Resolved existing channel {} using legacy chat id variant {} (requested {})",
                                        channel.getChatId(), candidateId, chatId);
                            }
                        }))
                .next();
    }

    private List<Long> buildChatIdCandidates(Long chatId) {
        if (chatId == null || chatId == 0L) {
            return List.of();
        }
        // УПРОЩЕНО: используем только оригинальный TDLib ID
        // Больше не нужны варианты - ID всегда в оригинальном формате
        return List.of(chatId);
    }

    private boolean shouldMarkAsJoined(ChatDiscoveryService.ChatInfo chatInfo) {
        return chatInfo.canReadMessages() || chatInfo.isAccessible();
    }

    private boolean isChannelJoined(Channel channel) {
        return channel.getJoinStatus() != null
                && channel.getJoinStatus().equalsIgnoreCase(JOIN_STATUS_JOINED);
    }

    /**
     * Automatically configures high-scoring channels from tgscan.channels
     * that are joined but not yet configured in bot.chat_configs.
     * These channels will be configured with sync and auto-sync enabled.
     *
     * @return Mono containing the count of newly configured channels
     */
    public Mono<Integer> autoConfigureHighScoringChannels() {
//        log.info("Starting auto-configuration of high-scoring channels (channel_score > {})", HIGH_SCORE_THRESHOLD);
        log.info("Starting auto-configuration of channels with many subscribers (subscribers > {})", HIGH_SCORE_THRESHOLD);

        return channelRepository.findUnconfiguredChannelsWithSubscribers(COUNT_OF_SUBSCRIBERS)
                .flatMap(channel -> {
//                    log.info("Auto-configuring high-scoring channel: '{}' (ID: {}, channel_score: {}, raw_keyword_score: {})",
                    log.info("Auto-configuring channel: '{}' (ID: {}, subscribers: {}, raw_keyword_score: {})",
                            channel.getTitle(), channel.getChatId(), channel.getSubscribers(), channel.getRawKeywordScore());
                    return createHighScoreChatConfig(channel)
                            .doOnSuccess(config -> log.info("Successfully auto-configured channel '{}' with sync enabled",
                                    channel.getTitle()))
                            .onErrorResume(error -> {
                                log.error("Failed to auto-configure channel '{}' (ID: {}): {}",
                                        channel.getTitle(), channel.getChatId(), error.getMessage());
                                return Mono.empty();
                            });
                })
                .count()
                .map(Long::intValue)
                .doOnSuccess(count -> {
                    if (count > 0) {
                        log.info("Auto-configuration completed: {} high-scoring channels configured", count);
                    } else {
                        log.debug("No high-scoring channels to auto-configure");
                    }
                });
    }

    /**
     * Creates a ChatConfig for high-scoring channels with sync enabled.
     * Unlike default configs, these are configured to be active with sync capabilities.
     */
	    private Mono<ChatConfig> createHighScoreChatConfig(Channel channel) {
        // Using original TDLib ID directly
        Long tdlibChannelId = channel.getChatId();
        log.debug("Creating high-score ChatConfig for channel '{}' (ID: {})", channel.getTitle(), tdlibChannelId);

        ChatConfig config = new ChatConfig();
        config.setChannelId(tdlibChannelId);
        config.setEnabled(false);
        config.setSyncEnabled(true);
	        config.setAutoSyncEnabled(true);
	        config.setRespondToForwardedBotMessages(false);
	        config.setContextWindowSize(1);
	        config.setTemperature(0.7);
	        config.setDefaultSyncDepthDays(HIGH_SCORE_SYNC_DEPTH_DAYS);
	        config.setLanguage(detectLanguageFromChannel(channel));

        String promptTemplate = """
                Отвечай всегда только на русском языке, строго одним коротким предложением без лишних слов. \
                Никогда не упоминай, что ты бот, программа или искусственный интеллект — веди себя как обычный человек. \
                Не используй технические термины и сложные формулировки.""";
        config.setPromptTemplate(promptTemplate);

	        return chatConfigRepository.save(config)
	                .flatMap(saved -> ensureMaxDailyMessages(saved.getId(), HIGH_SCORE_MAX_DAILY_MESSAGES).thenReturn(saved));
	    }

    /**
     * Attempts to detect language from channel metadata.
     * Falls back to Russian as default for high-scoring channels.
     */
    private String detectLanguageFromChannel(Channel channel) {
        String title = channel.getTitle();
        String description = channel.getDescription();

        if (title != null && (title.contains("UA") || title.contains("Ukraine"))) {
            return "uk";
        }

        if (description != null && description.matches(".*[А-Яа-яЁёЇїІіЄєҐґ].*")) {
            return "ru";
        }

        return "ru";
    }

    public Mono<SetupChannelResult> setupChannelConfiguration(long channelId, boolean syncEnabled) {
        // Using original TDLib ID directly - no normalization
        return channelRepository.findByChatId(channelId)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Канал з ID " + channelId + " не знайдено.")))
                .flatMap(channel -> chatConfigRepository.findByChannelChatId(channelId)
                        .flatMap(existing -> updateExistingConfig(existing, channel, syncEnabled)
                                .map(updated -> new SetupChannelResult(channel, updated, false)))
                        .switchIfEmpty(Mono.defer(() -> createManualConfig(channel, syncEnabled)
                                .map(created -> new SetupChannelResult(channel, created, true)))))
                .doOnSuccess(result -> syncEnabledChatsCache.invalidate(result.channel().getChatId()));
    }

	    private Mono<ChatConfig> updateExistingConfig(ChatConfig config, Channel channel, boolean syncEnabled) {
	        boolean previousSync = config.isSyncEnabled();
	        config.setSyncEnabled(syncEnabled);
	        config.setAutoSyncEnabled(syncEnabled);
        if (!syncEnabled) {
            config.setEnabled(false);
        }
	        if (config.getDefaultSyncDepthDays() == null) {
	            config.setDefaultSyncDepthDays(HIGH_SCORE_SYNC_DEPTH_DAYS);
	        }
	        if (config.getTemperature() == null) {
	            config.setTemperature(0.7);
	        }
        if (config.getContextWindowSize() == null || config.getContextWindowSize() <= 0) {
            config.setContextWindowSize(1);
        }
        if (config.getLanguage() == null || config.getLanguage().isBlank()) {
            config.setLanguage(detectLanguageFromChannel(channel));
        }
	        // Using original TDLib ID directly
	        Long tdlibChatId = channel.getChatId();
	        log.info("Manual channel setup: updating config for '{}' (TDLib ID: {}) syncEnabled {} -> {}", channel.getTitle(), tdlibChatId, previousSync, syncEnabled);
	        return chatConfigRepository.save(config)
	                .flatMap(saved -> ensureMaxDailyMessages(saved.getId(), HIGH_SCORE_MAX_DAILY_MESSAGES).thenReturn(saved));
	    }

	    private Mono<ChatConfig> createManualConfig(Channel channel, boolean syncEnabled) {
	        ChatConfig config = new ChatConfig();
        // Using original TDLib ID directly
        Long tdlibChatId = channel.getChatId();
        config.setChannelId(tdlibChatId);
        config.setEnabled(false);
        config.setSyncEnabled(syncEnabled);
	        config.setAutoSyncEnabled(syncEnabled);
	        config.setRespondToForwardedBotMessages(false);
	        config.setContextWindowSize(1);
	        config.setTemperature(0.7);
	        config.setDefaultSyncDepthDays(HIGH_SCORE_SYNC_DEPTH_DAYS);
	        config.setLanguage(detectLanguageFromChannel(channel));
	        config.setPromptTemplate("""
	                Отвечай всегда только на русском языке, строго одним коротким предложением без лишних слов. \
	                Никогда не упоминай, что ты бот, программа или искусственный интеллект — веди себя как обычный человек. \
	                Не используй технические термины и сложные формулировки.""");
	        log.info("Manual channel setup: creating config for '{}' (TDLib ID: {}), syncEnabled={}", channel.getTitle(), tdlibChatId, syncEnabled);
	        return chatConfigRepository.save(config)
	                .flatMap(saved -> ensureMaxDailyMessages(saved.getId(), HIGH_SCORE_MAX_DAILY_MESSAGES).thenReturn(saved));
	    }

    public record SetupChannelResult(Channel channel, ChatConfig config, boolean created) {}

    public Mono<ResponseLengthUpdateResult> updateResponseLength(long channelId, ResponseLength responseLength) {
        return chatConfigRepository.findByChannelChatId(channelId)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Канал з ID " + channelId + " не знайдено.")))
                .flatMap(config -> triggerConditionRepository.findByChatConfigIdOrderByPriorityDesc(config.getId())
                        .collectList()
                        .flatMap(conditions -> {
                            if (conditions.isEmpty()) {
                                return Mono.error(new IllegalStateException("Для каналу " + channelId + " не налаштовано жодного тригера."));
                            }
                            return Flux.fromIterable(conditions)
                                    .flatMap(condition -> {
                                        condition.setResponseLength(responseLength);
                                        return triggerConditionRepository.save(condition);
                                    })
                                    .count()
                                    .map(updated -> new ResponseLengthUpdateResult(config, responseLength, updated.intValue()))
                                    .doOnSuccess(result -> log.info("Оновлено довжину відповідей для каналу {}: {} (тригерів={})",
                                            channelId, responseLength.name(), result.updatedTriggerCount()));
                        }));
    }

    public record ResponseLengthUpdateResult(ChatConfig chatConfig, ResponseLength responseLength, int updatedTriggerCount) {}
}
