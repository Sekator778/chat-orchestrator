package com.example.telegramuserbot.service.config;

import com.example.telegramuserbot.domain.*;
import com.example.telegramuserbot.dto.*;
import com.example.telegramuserbot.repository.*;
import com.example.telegramuserbot.service.cache.ChatAdminCacheInvalidationService;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

@Service
public class ConfigurationServiceImpl implements ConfigurationService {

    private final ChatConfigRepository chatConfigRepository;
    private final ResponseTemplateRepository responseTemplateRepository;
    private final TriggerConditionRepository triggerConditionRepository;
    private final ContextSettingsRepository contextSettingsRepository;
    private final LlmParametersRepository llmParametersRepository;
    private final RateLimitsRepository rateLimitsRepository;
    private final TopicRestrictionRepository topicRestrictionRepository;
    private final ChannelRepository channelRepository;
    private final DatabaseClient databaseClient;
    private final ChatAdminCacheInvalidationService chatAdminCacheInvalidationService;

    public ConfigurationServiceImpl(
            ChatConfigRepository chatConfigRepository,
            ResponseTemplateRepository responseTemplateRepository,
            TriggerConditionRepository triggerConditionRepository,
            ContextSettingsRepository contextSettingsRepository,
            LlmParametersRepository llmParametersRepository,
            RateLimitsRepository rateLimitsRepository,
            TopicRestrictionRepository topicRestrictionRepository,
            ChannelRepository channelRepository,
            DatabaseClient databaseClient,
            ChatAdminCacheInvalidationService chatAdminCacheInvalidationService) {
        this.chatConfigRepository = chatConfigRepository;
        this.responseTemplateRepository = responseTemplateRepository;
        this.triggerConditionRepository = triggerConditionRepository;
        this.contextSettingsRepository = contextSettingsRepository;
        this.llmParametersRepository = llmParametersRepository;
        this.rateLimitsRepository = rateLimitsRepository;
        this.topicRestrictionRepository = topicRestrictionRepository;
        this.channelRepository = channelRepository;
        this.databaseClient = databaseClient;
        this.chatAdminCacheInvalidationService = chatAdminCacheInvalidationService;
    }

    @Override
    public Mono<EnhancedChatConfigDto> getEnhancedConfig(Long channelId) {
        Mono<ChatConfig> chatConfigMono = chatConfigRepository.findByChannelChatId(channelId);
        Mono<Channel> channelMono = channelRepository.findByChatId(channelId);

        return chatConfigMono.zipWith(channelMono)
                .flatMap(tuple -> {
                    ChatConfig chatConfig = tuple.getT1();
                    Channel channel = tuple.getT2();

                    Mono<List<ResponseTemplateDto>> templatesMono = getResponseTemplates(channelId).collectList();
                    Mono<List<TriggerConditionDto>> triggersMono = getTriggerConditions(channelId).collectList();
                    Mono<ContextSettingsDto> contextMono = getContextSettings(channelId).defaultIfEmpty(ContextSettingsDto.withDefaults(chatConfig.getId()));
                    Mono<LlmParametersDto> llmParamsMono = getLlmParameters(channelId).defaultIfEmpty(LlmParametersDto.withDefaults(chatConfig.getId()));
                    Mono<RateLimitsDto> rateLimitsMono = getRateLimits(channelId).defaultIfEmpty(RateLimitsDto.withDefaults(chatConfig.getId()));
                    Mono<List<TopicRestrictionDto>> restrictionsMono = getTopicRestrictions(channelId).collectList();

                    return Mono.zip(templatesMono, triggersMono, contextMono, llmParamsMono, rateLimitsMono, restrictionsMono)
                            .map(relatedTuple -> EnhancedChatConfigDto.fromEntity(
                                    chatConfig,
                                    channel,
                                    relatedTuple.getT1(),
                                    relatedTuple.getT2(),
                                    relatedTuple.getT3(),
                                    relatedTuple.getT4(),
                                    relatedTuple.getT5(),
                                    relatedTuple.getT6()
                            ));
                });
    }

    @Override
    public Flux<ChannelOverviewDto> listChannelOverview() {
        String sql = """
                SELECT c.id          AS chat_id,
                       c.title       AS title,
                       c.description AS description,
                       c.join_status AS join_status,
                       c.mute_status AS mute_status,
                       c.last_seen   AS last_seen,
                       c.channel_score AS channel_score,
                       c.subscribers   AS subscribers,
                       cc.id         AS config_id,
                       cc.channel_chat_id AS config_channel_chat_id,
                       cc.enabled    AS enabled,
                       cc.auto_sync_enabled AS auto_sync_enabled,
                       cc.language   AS language,
                       cc.context_window_size AS context_window_size,
                       cc.processing_phase AS processing_phase,
                       COALESCE(trig.trigger_count, 0) AS trigger_count,
                       COALESCE(res.restriction_count, 0) AS restriction_count
                  FROM tgscan.channels c
                  LEFT JOIN bot.chat_configs cc
                    ON cc.channel_chat_id = c.id
                  LEFT JOIN (
                       SELECT chat_config_id, COUNT(*) AS trigger_count
                         FROM bot.trigger_conditions
                        GROUP BY chat_config_id
                  ) trig ON trig.chat_config_id = cc.id
                  LEFT JOIN (
                       SELECT chat_config_id, COUNT(*) AS restriction_count
                         FROM bot.topic_restrictions
                        GROUP BY chat_config_id
                  ) res ON res.chat_config_id = cc.id
                 ORDER BY c.title NULLS LAST, c.id
                """;

        return databaseClient.sql(sql)
                .map((row, metadata) -> new ChannelOverviewDto(
                        row.get("chat_id", Long.class),
                        row.get("title", String.class),
                        row.get("description", String.class),
                        row.get("join_status", String.class),
                        row.get("mute_status", String.class),
                        row.get("last_seen", java.time.Instant.class),
                        row.get("channel_score", Double.class),
                        row.get("subscribers", Long.class),
                        row.get("config_id", Long.class) != null,
                        row.get("config_channel_chat_id", Long.class),
                        row.get("enabled", Boolean.class),
                        row.get("auto_sync_enabled", Boolean.class),
                        row.get("language", String.class),
                        row.get("context_window_size", Integer.class),
                        row.get("processing_phase", String.class),
                        row.get("trigger_count", Integer.class),
                        row.get("restriction_count", Integer.class)
                ))
                .all();
    }

    @Override
    public Mono<ChatConfigDto> updateBasicConfig(Long channelId, ChatConfigUpdateDto updateDto) {
        return chatConfigRepository.findByChannelChatId(channelId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Chat configuration not found for channel: " + channelId)))
                .flatMap(chatConfig -> {
                    Integer maxDailyAlias = updateDto.maxDailyMessages();

                    // Apply updates
                    if (updateDto.promptTemplate() != null) chatConfig.setPromptTemplate(updateDto.promptTemplate());
                    if (updateDto.enabled() != null) chatConfig.setEnabled(updateDto.enabled());
                    if (updateDto.maxTokens() != null) chatConfig.setMaxTokens(updateDto.maxTokens());
                    if (updateDto.temperature() != null) chatConfig.setTemperature(updateDto.temperature());
                    if (updateDto.language() != null) chatConfig.setLanguage(normalizeLanguage(updateDto.language()));
                    if (updateDto.primaryChannelId() != null) chatConfig.setPrimaryChannelId(updateDto.primaryChannelId());
                    if (updateDto.contextWindowSize() != null) chatConfig.setContextWindowSize(Math.max(1, updateDto.contextWindowSize()));
                    if (updateDto.respondToForwardedBotMessages() != null) chatConfig.setRespondToForwardedBotMessages(updateDto.respondToForwardedBotMessages());
                    if (updateDto.multiStageEnabled() != null) chatConfig.setMultiStageEnabled(updateDto.multiStageEnabled());

                    return chatConfigRepository.save(chatConfig)
                            .flatMap(saved -> {
                                if (maxDailyAlias == null || saved == null || saved.getId() == null) {
                                    return Mono.just(saved);
                                }
                                return rateLimitsRepository.findByChatConfigId(saved.getId())
                                        .defaultIfEmpty(new RateLimits(saved.getId()))
                                        .flatMap(limits -> {
                                            limits.setMaxMessagesPerDay(maxDailyAlias);
                                            return rateLimitsRepository.save(limits).thenReturn(saved);
                                        });
                            })
                            .doOnSuccess(saved -> {
                                if (saved != null && saved.getChannelId() != null) {
                                    chatAdminCacheInvalidationService.invalidateChat(saved.getChannelId(), "updateBasicConfig");
                                }
                            });
                })
                .flatMap(savedConfig -> channelRepository.findByIdForInstance(savedConfig.getChannelId())
	                        .map(channel -> new ChatConfigDto(
	                                savedConfig.getId(),
	                                channel.getChatId(),
	                                channel.getTitle(),
	                                savedConfig.getPromptTemplate(),
	                                savedConfig.isEnabled(),
	                                savedConfig.isMultiStageEnabled(),
	                                savedConfig.getDefaultSyncDepthDays(),
	                                savedConfig.getAutoSyncEnabled(),
	                                savedConfig.getLanguage(),
	                                savedConfig.getPrimaryChannelId(),
	                                savedConfig.getPrimaryChannelCheckedAt(),
	                                savedConfig.getContextWindowSize(),
	                                savedConfig.isRespondToForwardedBotMessages(),
	                                savedConfig.isSyncEnabled(),
	                                savedConfig.getMaxTokens(),
	                                savedConfig.getTemperature()
	                        )));
    }

    @Override
    public Mono<PendingResponseConfigDto> updatePendingResponseConfig(Long channelId, PendingResponseConfigUpdateDto updateDto) {
        return chatConfigRepository.findByChannelChatId(channelId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Chat configuration not found for channel: " + channelId)))
                .flatMap(chatConfig -> {
                    Integer pendingDelaySeconds = updateDto.pendingResponseDelaySeconds() != null
                            ? Math.max(0, updateDto.pendingResponseDelaySeconds())
                            : null;

                    if (updateDto.waitForHumanRepliesCount() != null) {
                        chatConfig.setWaitForHumanRepliesCount(Math.max(-1, updateDto.waitForHumanRepliesCount()));
                    }

                    Mono<ChatConfig> savedChatMono = chatConfigRepository.save(chatConfig);

                    Mono<RateLimits> savedLimitsMono = rateLimitsRepository.findByChatConfigId(chatConfig.getId())
                            .defaultIfEmpty(new RateLimits(chatConfig.getId()))
                            .flatMap(limits -> {
                                if (pendingDelaySeconds != null) {
                                    limits.setPendingResponseDelaySeconds(pendingDelaySeconds);
                                }
                                return rateLimitsRepository.save(limits);
                            });

                    return Mono.zip(savedChatMono, savedLimitsMono)
                            .doOnSuccess(tuple -> {
                                ChatConfig saved = tuple != null ? tuple.getT1() : null;
                                if (saved != null && saved.getChannelId() != null) {
                                    chatAdminCacheInvalidationService.invalidateChat(saved.getChannelId(), "updatePendingResponseConfig");
                                }
                            });
                })
                .map(tuple -> PendingResponseConfigDto.fromEntities(tuple.getT1(), tuple.getT2()));
    }

    private String normalizeLanguage(String language) {
        if (language == null) return null;
        String trimmed = language.trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase();
    }

    // ===== RESPONSE TEMPLATES =====

    @Override
    public Flux<ResponseTemplateDto> getResponseTemplates(Long channelId) {
        return chatConfigRepository.findByChannelChatId(channelId)
                .flatMapMany(config -> responseTemplateRepository.findByChatConfigId(config.getId()))
                .map(ResponseTemplateDto::fromEntity);
    }

    @Override
    public Mono<ResponseTemplateDto> createResponseTemplate(Long channelId, ResponseTemplateDto templateDto) {
        return chatConfigRepository.findByChannelChatId(channelId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Chat configuration not found for channel: " + channelId)))
                .flatMap(chatConfig -> {
                    ResponseTemplate template = new ResponseTemplate(chatConfig.getId(), templateDto.templateName(), templateDto.templateContent());
                    template.setResponseStyle(templateDto.responseStyle());
                    template.setResponseTone(templateDto.responseTone());
                    template.setMaxResponseLength(templateDto.maxResponseLength());
                    template.setDefault(templateDto.isDefault());
                    template.setPriority(templateDto.priority());
                    template.setActive(templateDto.active());

                    Mono<ResponseTemplate> saveMono = responseTemplateRepository.save(template);
                    if (templateDto.isDefault()) {
                        return responseTemplateRepository.resetDefaultTemplates(chatConfig.getId())
                                .then(Mono.fromRunnable(() -> template.setDefault(true)))
                                .then(responseTemplateRepository.save(template));
                    }
                    return saveMono;
                })
                .doOnSuccess(saved -> chatAdminCacheInvalidationService.invalidateBotContext(channelId, "createResponseTemplate"))
                .map(ResponseTemplateDto::fromEntity);
    }

    @Override
    public Mono<ResponseTemplateDto> updateResponseTemplate(Long templateId, ResponseTemplateDto templateDto) {
        return responseTemplateRepository.findById(templateId)
                .switchIfEmpty(Mono.empty())
                .flatMap(template -> {
                    template.setTemplateName(templateDto.templateName());
                    template.setTemplateContent(templateDto.templateContent());
                    template.setResponseStyle(templateDto.responseStyle());
                    template.setResponseTone(templateDto.responseTone());
                    template.setMaxResponseLength(templateDto.maxResponseLength());
                    template.setPriority(templateDto.priority());
                    template.setActive(templateDto.active());

                    Mono<ResponseTemplate> saveMono = Mono.just(template);
                    if (templateDto.isDefault()) {
                        saveMono = responseTemplateRepository.resetDefaultTemplates(template.getChatConfigId())
                                .then(Mono.fromRunnable(() -> template.setDefault(true)))
                                .then(Mono.just(template));
                    }
                    return saveMono.flatMap(responseTemplateRepository::save);
                })
                .flatMap(saved -> chatConfigRepository.findById(saved.getChatConfigId())
                        .doOnNext(cfg -> chatAdminCacheInvalidationService.invalidateBotContext(cfg.getChannelId(), "updateResponseTemplate"))
                        .thenReturn(saved))
                .map(ResponseTemplateDto::fromEntity);
    }

    @Override
    public Mono<Void> deleteResponseTemplate(Long templateId) {
        return responseTemplateRepository.findById(templateId)
                .flatMap(existing -> responseTemplateRepository.deleteById(templateId)
                        .then(chatConfigRepository.findById(existing.getChatConfigId())
                                .doOnNext(cfg -> chatAdminCacheInvalidationService.invalidateBotContext(cfg.getChannelId(), "deleteResponseTemplate"))
                                .then()))
                .switchIfEmpty(responseTemplateRepository.deleteById(templateId));
    }

    @Override
    public Mono<ResponseTemplateDto> setDefaultTemplate(Long templateId) {
        return responseTemplateRepository.findById(templateId)
                .switchIfEmpty(Mono.empty())
                .flatMap(template -> responseTemplateRepository.resetDefaultTemplates(template.getChatConfigId())
                        .then(responseTemplateRepository.setDefaultTemplate(templateId, template.getChatConfigId()))
                        .then(responseTemplateRepository.findById(templateId)))
                .flatMap(saved -> chatConfigRepository.findById(saved.getChatConfigId())
                        .doOnNext(cfg -> chatAdminCacheInvalidationService.invalidateBotContext(cfg.getChannelId(), "setDefaultTemplate"))
                        .thenReturn(saved))
                .map(ResponseTemplateDto::fromEntity);
    }

    // ===== TRIGGER CONDITIONS =====

    @Override
    public Flux<TriggerConditionDto> getTriggerConditions(Long channelId) {
        return chatConfigRepository.findByChannelChatId(channelId)
                .flatMapMany(config -> triggerConditionRepository.findByChatConfigIdOrderByPriorityDesc(config.getId()))
                .map(TriggerConditionDto::fromEntity);
    }

    @Override
    public Mono<TriggerConditionDto> createTriggerCondition(Long channelId, TriggerConditionDto conditionDto) {
        return chatConfigRepository.findByChannelChatId(channelId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Chat configuration not found for channel: " + channelId)))
                .flatMap(chatConfig -> {
                    TriggerCondition condition = new TriggerCondition(chatConfig.getId(), conditionDto.conditionName(), conditionDto.triggerType());
                    condition.setKeywords(conditionDto.keywords());
                    condition.setMentionRequired(conditionDto.mentionRequired());
                    condition.setTimeDelaySeconds(conditionDto.timeDelaySeconds());
                    condition.setProbabilityPercent(conditionDto.probabilityPercent());
                    condition.setActiveHoursStart(conditionDto.activeHoursStart());
                    condition.setActiveHoursEnd(conditionDto.activeHoursEnd());
                    condition.setActiveDaysOfWeek(conditionDto.activeDaysOfWeek());
                    condition.setMinimumGapMinutes(conditionDto.minimumGapMinutes());
                    condition.setPriority(conditionDto.priority());
                    condition.setActive(conditionDto.active());
                    return triggerConditionRepository.save(condition);
                })
                .map(TriggerConditionDto::fromEntity);
    }

    @Override
    public Mono<TriggerConditionDto> updateTriggerCondition(Long triggerId, TriggerConditionDto conditionDto) {
        return triggerConditionRepository.findById(triggerId)
                .switchIfEmpty(Mono.empty())
                .flatMap(condition -> {
                    condition.setConditionName(conditionDto.conditionName());
                    condition.setTriggerType(conditionDto.triggerType());
                    condition.setKeywords(conditionDto.keywords());
                    condition.setMentionRequired(conditionDto.mentionRequired());
                    condition.setTimeDelaySeconds(conditionDto.timeDelaySeconds());
                    condition.setProbabilityPercent(conditionDto.probabilityPercent());
                    condition.setActiveHoursStart(conditionDto.activeHoursStart());
                    condition.setActiveHoursEnd(conditionDto.activeHoursEnd());
                    condition.setActiveDaysOfWeek(conditionDto.activeDaysOfWeek());
                    condition.setMinimumGapMinutes(conditionDto.minimumGapMinutes());
                    condition.setPriority(conditionDto.priority());
                    condition.setActive(conditionDto.active());
                    return triggerConditionRepository.save(condition);
                })
                .map(TriggerConditionDto::fromEntity);
    }

    @Override
    public Mono<Void> deleteTriggerCondition(Long triggerId) {
        return triggerConditionRepository.deleteById(triggerId);
    }

    @Override
    public Mono<TriggerConditionDto> toggleTriggerCondition(Long triggerId) {
        return triggerConditionRepository.toggleActiveStatus(triggerId)
                .then(triggerConditionRepository.findById(triggerId))
                .map(TriggerConditionDto::fromEntity);
    }

    // ===== CONTEXT SETTINGS =====

    @Override
    public Mono<ContextSettingsDto> getContextSettings(Long channelId) {
        return chatConfigRepository.findByChannelChatId(channelId)
                .flatMap(config -> contextSettingsRepository.findByChatConfigId(config.getId()))
                .map(ContextSettingsDto::fromEntity);
    }

    @Override
    public Mono<ContextSettingsDto> updateContextSettings(Long channelId, ContextSettingsDto settingsDto) {
        return chatConfigRepository.findByChannelChatId(channelId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Chat configuration not found for channel: " + channelId)))
                .flatMap(chatConfig -> contextSettingsRepository.findByChatConfigId(chatConfig.getId())
                        .defaultIfEmpty(new ContextSettings(chatConfig.getId()))
                        .flatMap(settings -> {
                            settings.setHistoryMessageCount(settingsDto.historyMessageCount());
                            settings.setHistoryTimeWindowHours(settingsDto.historyTimeWindowHours());
                            settings.setIncludeUserContext(settingsDto.includeUserContext());
                            settings.setIncludeMediaDescriptions(settingsDto.includeMediaDescriptions());
                            settings.setContextCompressionEnabled(settingsDto.contextCompressionEnabled());
                            settings.setMaxContextTokens(settingsDto.maxContextTokens());
                            settings.setPreserveImportantMessages(settingsDto.preserveImportantMessages());
                            return contextSettingsRepository.save(settings);
                        }))
                .map(ContextSettingsDto::fromEntity);
    }

    // ===== LLM PARAMETERS =====

    @Override
    public Mono<LlmParametersDto> getLlmParameters(Long channelId) {
        return chatConfigRepository.findByChannelChatId(channelId)
                .flatMap(config -> llmParametersRepository.findByChatConfigId(config.getId()))
                .map(LlmParametersDto::fromEntity);
    }

    @Override
    public Mono<LlmParametersDto> updateLlmParameters(Long channelId, LlmParametersDto paramsDto) {
        return chatConfigRepository.findByChannelChatId(channelId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Chat configuration not found for channel: " + channelId)))
                .flatMap(chatConfig -> llmParametersRepository.findByChatConfigId(chatConfig.getId())
                        .defaultIfEmpty(new LlmParameters(chatConfig.getId()))
                        .flatMap(params -> {
                            params.setModelName(paramsDto.modelName());
                            params.setTemperature(paramsDto.temperature());
                            params.setMaxTokens(paramsDto.maxTokens());
                            params.setTopP(paramsDto.topP());
                            params.setFrequencyPenalty(paramsDto.frequencyPenalty());
                            params.setPresencePenalty(paramsDto.presencePenalty());
                            params.setSystemPrompt(paramsDto.systemPrompt());
                            params.setCustomInstructions(paramsDto.customInstructions());
                            params.setResponseFormat(paramsDto.responseFormat());
                            return llmParametersRepository.save(params);
                        }))
                .map(LlmParametersDto::fromEntity);
    }

    // ===== RATE LIMITS =====

    @Override
    public Mono<RateLimitsDto> getRateLimits(Long channelId) {
        return chatConfigRepository.findByChannelChatId(channelId)
                .flatMap(config -> rateLimitsRepository.findByChatConfigId(config.getId()))
                .map(RateLimitsDto::fromEntity);
    }

    @Override
    public Mono<RateLimitsDto> updateRateLimits(Long channelId, RateLimitsDto limitsDto) {
        return chatConfigRepository.findByChannelChatId(channelId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Chat configuration not found for channel: " + channelId)))
                .flatMap(chatConfig -> rateLimitsRepository.findByChatConfigId(chatConfig.getId())
                        .defaultIfEmpty(new RateLimits(chatConfig.getId()))
                        .flatMap(limits -> {
                            limits.setMaxMessagesPerMinute(limitsDto.maxMessagesPerMinute());
                            limits.setMaxMessagesPerHour(limitsDto.maxMessagesPerHour());
                            limits.setMaxMessagesPerDay(limitsDto.maxMessagesPerDay());
                            limits.setMaxTokensPerDay(limitsDto.maxTokensPerDay());
                            limits.setPendingResponseDelaySeconds(limitsDto.pendingResponseDelaySeconds());
                            limits.setCooldownAfterLimitMinutes(limitsDto.cooldownAfterLimitMinutes());
                            limits.setBurstLimit(limitsDto.burstLimit());
                            limits.setBurstWindowSeconds(limitsDto.burstWindowSeconds());
                            limits.setUserSpecificLimits(limitsDto.userSpecificLimits());
                            return rateLimitsRepository.save(limits);
                        }))
                .doOnSuccess(saved -> {
                    if (channelId != null) {
                        chatAdminCacheInvalidationService.invalidateChat(channelId, "updateRateLimits");
                    }
                })
                .map(RateLimitsDto::fromEntity);
    }

    @Override
    public Mono<Void> resetRateLimits(Long channelId) {
        return rateLimitsRepository.resetDailyCountByChatId(channelId)
                .doOnSuccess(updated -> chatAdminCacheInvalidationService.invalidateChat(channelId, "resetRateLimits"))
                .then();
    }

    // ===== TOPIC RESTRICTIONS =====

    @Override
    public Flux<TopicRestrictionDto> getTopicRestrictions(Long channelId) {
        return chatConfigRepository.findByChannelChatId(channelId)
                .flatMapMany(config -> topicRestrictionRepository.findByChatConfigId(config.getId()))
                .map(TopicRestrictionDto::fromEntity);
    }

    @Override
    public Mono<TopicRestrictionDto> createTopicRestriction(Long channelId, TopicRestrictionDto restrictionDto) {
        return chatConfigRepository.findByChannelChatId(channelId)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Chat configuration not found for channel: " + channelId)))
                .flatMap(chatConfig -> {
                    TopicRestriction restriction = new TopicRestriction(chatConfig.getId(), restrictionDto.restrictionName(), restrictionDto.restrictionType());
                    restriction.setKeywords(restrictionDto.keywords());
                    restriction.setCategories(restrictionDto.categories());
                    restriction.setActionType(restrictionDto.actionType());
                    restriction.setCustomResponse(restrictionDto.customResponse());
                    restriction.setActive(restrictionDto.active());
                    return topicRestrictionRepository.save(restriction);
                })
                .map(TopicRestrictionDto::fromEntity);
    }

    @Override
    public Mono<TopicRestrictionDto> updateTopicRestriction(Long restrictionId, TopicRestrictionDto restrictionDto) {
        return topicRestrictionRepository.findById(restrictionId)
                .switchIfEmpty(Mono.empty())
                .flatMap(restriction -> {
                    restriction.setRestrictionName(restrictionDto.restrictionName());
                    restriction.setRestrictionType(restrictionDto.restrictionType());
                    restriction.setKeywords(restrictionDto.keywords());
                    restriction.setCategories(restrictionDto.categories());
                    restriction.setActionType(restrictionDto.actionType());
                    restriction.setCustomResponse(restrictionDto.customResponse());
                    restriction.setActive(restrictionDto.active());
                    return topicRestrictionRepository.save(restriction);
                })
                .map(TopicRestrictionDto::fromEntity);
    }

    @Override
    public Mono<Void> deleteTopicRestriction(Long restrictionId) {
        return topicRestrictionRepository.deleteById(restrictionId);
    }

    @Override
    public Mono<TopicRestrictionDto> toggleTopicRestriction(Long restrictionId) {
        return topicRestrictionRepository.toggleActiveStatus(restrictionId)
                .then(topicRestrictionRepository.findById(restrictionId))
                .map(TopicRestrictionDto::fromEntity);
    }

    // ===== BULK OPERATIONS =====

    @Override
    public Mono<EnhancedChatConfigDto> copyConfiguration(Long sourceChannelId, Long targetChannelId) {
        return getEnhancedConfig(sourceChannelId)
                .switchIfEmpty(Mono.empty())
                .flatMap(sourceConfig -> importConfiguration(targetChannelId, sourceConfig));
    }

    @Override
    public Mono<EnhancedChatConfigDto> resetToDefaults(Long channelId) {
        return chatConfigRepository.findByChannelChatId(channelId)
                .switchIfEmpty(Mono.empty())
                .flatMap(chatConfig -> {
                    chatConfig.setPromptTemplate(null);
                    chatConfig.setEnabled(true);
                    chatConfig.setMaxTokens(null);
                    chatConfig.setTemperature(null);
                    chatConfig.setWaitForHumanRepliesCount(-1);

                    return chatConfigRepository.save(chatConfig)
                            .then(deleteRelatedConfigs(chatConfig.getId()))
                            .then(initializeDefaultConfig(channelId));
                })
                .doOnSuccess(updated -> {
                    if (channelId != null) {
                        chatAdminCacheInvalidationService.invalidateChat(channelId, "resetToDefaults");
                    }
                });
    }

    private Mono<Void> deleteRelatedConfigs(Long configId) {
        return responseTemplateRepository.deleteByChatConfigId(configId)
                .then(triggerConditionRepository.deleteByChatConfigId(configId))
                .then(contextSettingsRepository.deleteByChatConfigId(configId))
                .then(llmParametersRepository.deleteByChatConfigId(configId))
                .then(rateLimitsRepository.deleteByChatConfigId(configId))
                .then(topicRestrictionRepository.deleteByChatConfigId(configId));
    }

    @Override
    public Mono<EnhancedChatConfigDto> importConfiguration(Long channelId, EnhancedChatConfigDto configDto) {
        // This is a complex operation. For now, it returns the existing config.
        return getEnhancedConfig(channelId);
    }

    // ===== UTILITY METHODS =====

    @Override
    public Mono<EnhancedChatConfigDto> initializeDefaultConfig(Long channelId) {
        return chatConfigRepository.findByChannelChatId(channelId)
                .switchIfEmpty(Mono.empty())
                .flatMap(chatConfig -> {
                    Long configId = chatConfig.getId();
                    Mono<ContextSettingsDto> contextMono = updateContextSettings(channelId, ContextSettingsDto.withDefaults(configId));
                    Mono<LlmParametersDto> llmMono = updateLlmParameters(channelId, LlmParametersDto.withDefaults(configId));
                    Mono<RateLimitsDto> limitsMono = updateRateLimits(channelId, RateLimitsDto.withDefaults(configId));
                    Mono<ResponseTemplateDto> templateMono = createResponseTemplate(channelId, ResponseTemplateDto.forCreation(
                            configId, "Default", "ВАЖЛИВО: ...", ResponseStyle.CONVERSATIONAL, ResponseTone.FRIENDLY, 500, true, 1));
                    Mono<TriggerConditionDto> triggerMono = createTriggerCondition(channelId, TriggerConditionDto.forCreation(
                            configId, "Default Trigger", TriggerType.MENTION_ONLY, null, true, 0, 100));

                    return Mono.when(contextMono, llmMono, limitsMono, templateMono, triggerMono)
                            .then(getEnhancedConfig(channelId));
                });
    }

    @Override
    public Mono<Boolean> shouldTriggerResponse(Long channelId, String messageText, boolean isMention, Long userId) {
        return getTriggerConditions(channelId)
                .any(condition -> condition.active() && (!condition.mentionRequired() || isMention));
    }

    @Override
    public Mono<ResponseTemplateDto> getResponseTemplateForMessage(Long channelId, String messageText, String context) {
        return getResponseTemplates(channelId)
                .filter(ResponseTemplateDto::active)
                .filter(ResponseTemplateDto::isDefault)
                .next()
                .switchIfEmpty(getResponseTemplates(channelId).filter(ResponseTemplateDto::active).next());
    }

    @Override
    public Mono<Boolean> isTopicRestricted(Long channelId, String messageText) {
        return getTopicRestrictions(channelId)
                .filter(restriction -> restriction.active() && restriction.restrictionType() == RestrictionType.FORBIDDEN)
                .any(restriction -> messageText.toLowerCase().contains(restriction.keywords() != null ? restriction.keywords().toLowerCase() : ""));
    }

    @Override
    public Mono<Boolean> isRateLimited(Long channelId) {
        return chatConfigRepository.findByChannelChatId(channelId)
                .flatMap(chatConfig -> rateLimitsRepository.findByChatConfigId(chatConfig.getId())
                        .map(limits -> {
                            Integer maxDaily = limits.getMaxMessagesPerDay();
                            Integer currentDaily = limits.getCurrentDailyMessages();
                            if (maxDaily == null || maxDaily <= 0) {
                                return false;
                            }
                            return (currentDaily != null ? currentDaily : 0) >= maxDaily;
                        })
                        .defaultIfEmpty(false))
                .defaultIfEmpty(false);
    }
}
