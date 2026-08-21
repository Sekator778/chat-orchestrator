package com.example.telegramuserbot.service.channels.pipeline;

import com.example.telegramuserbot.config.BotInstanceProvider;
import com.example.telegramuserbot.domain.*;
import com.example.telegramuserbot.repository.*;
import com.example.telegramuserbot.service.config.initialization.strategy.TemplateApplicationStrategy;
import com.example.telegramuserbot.service.config.initialization.template.LinkedChatsTemplate;
import com.example.telegramuserbot.service.config.initialization.template.LinkedChatsTemplateFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Implementation of Phase 3: Template Application.
 * Applies configuration templates to channels based on strategy pattern.
 */
@Service
public final class ChannelTemplateApplicationServiceImpl implements ChannelTemplateApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ChannelTemplateApplicationServiceImpl.class);
    private static final Duration OPERATION_TIMEOUT = Duration.ofSeconds(60);

    private final ChatConfigRepository chatConfigRepository;
    private final ContextSettingsRepository contextSettingsRepository;
    private final LlmParametersRepository llmParametersRepository;
    private final RateLimitsRepository rateLimitsRepository;
    private final ResponseTemplateRepository responseTemplateRepository;
    private final TriggerConditionRepository triggerConditionRepository;
    private final ChannelRepository channelRepository;
    private final SyncConfigurationRepository syncConfigurationRepository;
    private final LinkedChatsTemplateFactory templateFactory;
    private final BotInstanceProvider botInstanceProvider;
    private final List<TemplateApplicationStrategy> strategies;

    public ChannelTemplateApplicationServiceImpl(ChatConfigRepository chatConfigRepository,
                                                 ContextSettingsRepository contextSettingsRepository,
                                                 LlmParametersRepository llmParametersRepository,
                                                 RateLimitsRepository rateLimitsRepository,
                                                 ResponseTemplateRepository responseTemplateRepository,
                                                 TriggerConditionRepository triggerConditionRepository,
                                                 ChannelRepository channelRepository,
                                                 SyncConfigurationRepository syncConfigurationRepository,
                                                 LinkedChatsTemplateFactory templateFactory,
                                                 BotInstanceProvider botInstanceProvider,
                                                 List<TemplateApplicationStrategy> strategies) {
        this.chatConfigRepository = chatConfigRepository;
        this.contextSettingsRepository = contextSettingsRepository;
        this.llmParametersRepository = llmParametersRepository;
        this.rateLimitsRepository = rateLimitsRepository;
        this.responseTemplateRepository = responseTemplateRepository;
        this.triggerConditionRepository = triggerConditionRepository;
        this.channelRepository = channelRepository;
        this.syncConfigurationRepository = syncConfigurationRepository;
        this.templateFactory = templateFactory;
        this.botInstanceProvider = botInstanceProvider;
        this.strategies = strategies.stream()
                .sorted((s1, s2) -> Integer.compare(s2.priority(), s1.priority()))
                .toList();
    }

    @Override
    public Mono<Boolean> processChannel(ChatConfig config) {
        log.debug("Phase 3 (Template Application): Processing channel {} (ID: {})",
                config.getChannelId(), config.getId());

        return isConfigurationComplete(config)
                .flatMap(complete -> {
                    if (complete) {
                        log.info("Phase 3: Configuration already complete for chat {}, skipping template reapply",
                                config.getChannelId());
                        return Mono.just(true);
                    }

                    // Load channel metadata and determine if it's a dependent or standalone/primary channel
                    return loadChannelMetadata(config)
                            .flatMap(channelMetadata -> {
                                // If this config has a primary_channel_id, it's a dependent/discussion channel
                                // We need to load the primary channel metadata to pass to strategies
                                if (config.getPrimaryChannelId() != null) {
                                    return loadPrimaryChannelMetadata(config.getPrimaryChannelId())
                                            .map(primaryMetadata -> new ChannelPair(primaryMetadata, channelMetadata))
                                            .defaultIfEmpty(new ChannelPair(null, channelMetadata));
                                } else {
                                    // Standalone or primary channel - pass same metadata for both
                                    return Mono.just(new ChannelPair(channelMetadata, channelMetadata));
                                }
                            })
                            .flatMap(pair -> findMatchingStrategy(pair.primary, pair.discussion)
                                    .flatMap(strategyMatch -> {
                                        log.info("Phase 3: Applying strategy '{}' to channel {}",
                                                strategyMatch.strategy.name(), config.getChannelId());
                                        return applyTemplate(config, strategyMatch.strategy.template(), pair)
                                                .thenReturn(true);
                                    })
                                    .switchIfEmpty(Mono.defer(() -> {
                                        log.warn("Phase 3: No matching strategy found for channel {}", config.getChannelId());
                                        return Mono.just(false);
                                    }))
                            );
                })
                .onErrorResume(error -> {
                    log.warn("Phase 3 failed for channel {}: {}", config.getChannelId(), error.getMessage());
                    return Mono.just(false);
                });
    }

    private Mono<Channel> loadChannelMetadata(ChatConfig config) {
        return channelRepository.findByChatId(config.getChannelId())
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("Channel {} not found in tgscan.channels, creating empty metadata",
                            config.getChannelId());
                    return Mono.just(createEmptyChannel(config.getChannelId()));
                }))
                .timeout(OPERATION_TIMEOUT);
    }

    private Mono<Channel> loadPrimaryChannelMetadata(Long primaryChannelId) {
        return channelRepository.findByChatId(primaryChannelId)
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("Primary channel {} not found in tgscan.channels, creating empty metadata",
                            primaryChannelId);
                    return Mono.just(createEmptyChannel(primaryChannelId));
                }))
                .timeout(OPERATION_TIMEOUT);
    }

    private Mono<StrategyMatch> findMatchingStrategy(Channel primary, Channel discussion) {
        return Flux.fromIterable(strategies)
                .concatMap(strategy ->
                        strategy.shouldApply(primary, discussion)
                                .map(matches -> new StrategyMatch(strategy, matches))
                                .onErrorResume(error -> {
                                    log.warn("Strategy {} evaluation failed: {}", strategy.name(), error.getMessage());
                                    return Mono.just(new StrategyMatch(strategy, false));
                                })
                )
                .filter(match -> match.matches)
                .next()
                .timeout(OPERATION_TIMEOUT);
    }

    private Mono<Void> applyTemplate(ChatConfig config, LinkedChatsTemplate template, ChannelPair pair) {
        if (template.discussion().enabled()) {
            Channel discussionChannel = pair.discussion != null ? pair.discussion : pair.primary;
            boolean canSend = discussionChannel != null && Boolean.TRUE.equals(discussionChannel.getCanSendMessages());
            if (!canSend) {
                log.warn("Phase 3: Skipping template '{}' for chat {} because bot cannot send messages to this discussion chat",
                        template.templateName(), config.getChannelId());
                return Mono.empty();
            }
        }

        // Determine if this channel is a primary channel (referenced by other channels)
        // or a standalone/dependent channel (should have full discussion template)
        if (config.getPrimaryChannelId() != null) {
            // This is a dependent/discussion channel - apply full discussion template
            log.debug("Applying discussion template to dependent channel {}", config.getChannelId());
            LinkedChatsTemplate linkedTemplate = resolveLinkedTemplate(template);
            String templateName = linkedTemplate.templateName();
            String templateSource = linkedTemplate.resourcePath() != null ? linkedTemplate.resourcePath() : "unknown";
            log.info("Phase 3: Applying template '{}' (resource: {}) to linked discussion chat {}",
                    templateName, templateSource, config.getChannelId());

            return updateDiscussionConfig(config, linkedTemplate.discussion())
                    .doOnSuccess(ignored -> log.info("Phase 3: Template '{}' applied to dependent chat {}",
                            templateName, config.getChannelId()))
                    .timeout(OPERATION_TIMEOUT);
        } else {
            // primary_channel_id is NULL - could be either:
            // 1. A primary channel (referenced by others) - should use channel template (no prompt)
            // 2. A standalone channel (not referenced by anyone) - should use discussion template

            // Check if this channel is referenced as primary by any other channel
            return chatConfigRepository.existsByPrimaryChannelId(config.getChannelId())
                    .flatMap(isPrimaryForOthers -> {
                        if (isPrimaryForOthers) {
                            log.info("Phase 3: Skipping template application for primary chat {} (is parent for linked discussions)",
                                    config.getChannelId());
                            return Mono.empty();
                        } else {
                            log.debug("Applying discussion template to standalone channel {}",
                                    config.getChannelId());
                            LinkedChatsTemplate standaloneTemplate = resolveStandaloneTemplate();
                            String templateName = standaloneTemplate.templateName();
                            String templateSource = standaloneTemplate.resourcePath() != null ? standaloneTemplate.resourcePath() : "unknown";
                            log.info("Phase 3: Applying standalone template '{}' (resource: {}) to chat {}",
                                    templateName, templateSource, config.getChannelId());
                            return updateDiscussionConfig(config, standaloneTemplate.discussion())
                                    .doOnSuccess(ignored -> log.info("Phase 3: Template '{}' applied to standalone chat {}",
                                            templateName, config.getChannelId()));
                        }
                    })
                    .timeout(OPERATION_TIMEOUT);
        }
    }

    private LinkedChatsTemplate resolveLinkedTemplate(LinkedChatsTemplate strategyTemplate) {
        String resourcePath = strategyTemplate.resourcePath();
        if (resourcePath == null || resourcePath.contains("standalone/")) {
            return templateFactory.linkedChatsMinimalReactionTemplate();
        }
        return strategyTemplate;
    }

    private LinkedChatsTemplate resolveStandaloneTemplate() {
        return templateFactory.minimalReactionTemplate();
    }

    private Mono<Void> updateChannelConfig(ChatConfig config, LinkedChatsTemplate.ChannelTemplate template) {
        config.setEnabled(template.enabled());
        config.setContextWindowSize(template.contextWindowSize());
        // PRESERVE existing language - do NOT overwrite from template
        // Language is set during channel discovery by ChannelLanguageDetectionService
        config.setAutoSyncEnabled(template.autoSyncEnabled());
        config.setSyncEnabled(template.syncEnabled());
        // Explicitly do NOT set promptTemplate for primary channels

        return chatConfigRepository.save(config)
                .flatMap(saved -> createOrUpdateRateLimits(saved.getId(), new LinkedChatsTemplate.RateLimitsTemplate(
                                null,
                                null,
                                template.maxDailyMessages(),
                                null,
                                null,
                                null,
                                null,
                                false
                        ))
                        .then(applySyncConfiguration(saved.getChannelId(), template.syncConfiguration()))
                        .thenReturn(saved))
                .doOnSuccess(saved -> log.debug("Updated primary channel config for chat {} (language preserved: {})",
                        saved.getChannelId(), saved.getLanguage()))
                .then()
                .timeout(OPERATION_TIMEOUT);
    }

    private Mono<Void> updateDiscussionConfig(ChatConfig config, LinkedChatsTemplate.DiscussionTemplate template) {
        config.setEnabled(template.enabled());
        config.setPromptTemplate(template.promptTemplate());
        config.setMaxTokens(template.maxTokens());
        config.setTemperature(template.temperature());
        config.setContextWindowSize(template.contextWindowSize());
        // PRESERVE existing language - do NOT overwrite from template
        // Language is set during channel discovery by ChannelLanguageDetectionService
        config.setAutoSyncEnabled(template.autoSyncEnabled());
        config.setSyncEnabled(template.syncEnabled());
        config.setRespondToForwardedBotMessages(template.respondToForwardedBotMessages());
        config.setWaitForHumanRepliesCount(template.waitForHumanRepliesCount());

        return chatConfigRepository.save(config)
                .flatMap(savedConfig ->
                        createOrUpdateContextSettings(savedConfig.getId(), template.contextSettings())
                                .then(createOrUpdateLlmParameters(savedConfig.getId(), template.llmParameters()))
                                .then(createOrUpdateRateLimits(savedConfig.getId(), template.rateLimits()))
                                .then(createOrUpdateResponseTemplate(savedConfig.getId(), template.responseTemplate()))
                                .then(createOrUpdateTriggerCondition(savedConfig.getId(), template.triggerCondition()))
                                .then(applySyncConfiguration(savedConfig.getChannelId(), template.syncConfiguration()))
                )
                .doOnSuccess(v -> log.info("Phase 3: Applied full configuration to channel {}", config.getChannelId()))
                .then()
                .timeout(OPERATION_TIMEOUT);
    }

    private Mono<Void> applySyncConfiguration(Long channelChatId, LinkedChatsTemplate.SyncConfigurationTemplate template) {
        if (template == null) {
            return Mono.empty();
        }

        return channelRepository.findByChatId(channelChatId)
                .switchIfEmpty(Mono.fromRunnable(() ->
                                log.warn("Phase 3: Cannot apply sync configuration - channel {} not found in tgscan.channels", channelChatId))
                        .then(Mono.empty()))
                .flatMap(channel -> syncConfigurationRepository.findByChannelId(channel.getId())
                        .defaultIfEmpty(new SyncConfiguration(channel.getId()))
                        .map(syncConfig -> {
                            int depth = template.defaultSyncDepthDays() != null ? template.defaultSyncDepthDays() : 2;
                            syncConfig.setDefaultSyncDepthDays(depth);
                            if (template.maxSyncDepthDays() != null) {
                                syncConfig.setMaxSyncDepthDays(template.maxSyncDepthDays());
                            }
                            syncConfig.setAutoSyncEnabled(template.autoSyncEnabled());
                            if (template.autoSyncIntervalDays() != null) {
                                syncConfig.setAutoSyncIntervalDays(template.autoSyncIntervalDays());
                            }
                            if (template.maxConcurrentSyncs() != null) {
                                syncConfig.setMaxConcurrentSyncs(template.maxConcurrentSyncs());
                            }
                            if (syncConfig.getBotInstanceId() == null) {
                                syncConfig.setBotInstanceId(botInstanceProvider.getInstanceId());
                            }
                            syncConfig.setUpdatedAt(LocalDateTime.now());
                            return syncConfig;
                        })
                        .flatMap(syncConfigurationRepository::save)
                        .doOnSuccess(saved -> log.info("Phase 3: Applied sync configuration for chat {} (channelId={}, depthDays={}, autoSync={})",
                                channelChatId, channel.getId(), saved.getDefaultSyncDepthDays(), saved.isAutoSyncEnabled()))
                        .then())
                .timeout(OPERATION_TIMEOUT);
    }

    private Mono<Boolean> isConfigurationComplete(ChatConfig config) {
        return Mono.zip(
                        contextSettingsRepository.existsByChatConfigChannelChatId(config.getChannelId()),
                        llmParametersRepository.existsByChatConfigId(config.getId()),
                        rateLimitsRepository.findByChatConfigId(config.getId()).hasElement(),
                        responseTemplateRepository.countByChatConfigIdAndActiveTrue(config.getId()).map(count -> count > 0),
                        triggerConditionRepository.countByChatConfigChannelChatIdAndActive(config.getChannelId(), true).map(count -> count > 0)
                )
                .map(tuple -> tuple.getT1() && tuple.getT2() && tuple.getT3() && tuple.getT4() && tuple.getT5())
                .timeout(OPERATION_TIMEOUT);
    }

    private Channel createEmptyChannel(Long chatId) {
        Channel channel = new Channel();
        channel.setChatId(chatId);
        channel.setTitle("");
        channel.setDescription("");
        channel.setUsername("");
        channel.setSampleMessage("");
        return channel;
    }

    private Mono<Void> createOrUpdateContextSettings(Long configId, LinkedChatsTemplate.ContextSettingsTemplate template) {
        return contextSettingsRepository.findByChatConfigId(configId)
                .switchIfEmpty(Mono.defer(() -> Mono.just(new ContextSettings(configId))))
                .map(settings -> {
                    settings.setChatConfigId(configId);
                    settings.setHistoryMessageCount(template.historyMessageCount());
                    settings.setHistoryTimeWindowHours(template.historyTimeWindowHours());
                    settings.setIncludeUserContext(template.includeUserContext());
                    settings.setIncludeMediaDescriptions(template.includeMediaDescriptions());
                    settings.setContextCompressionEnabled(template.contextCompressionEnabled());
                    settings.setMaxContextTokens(template.maxContextTokens());
                    settings.setPreserveImportantMessages(template.preserveImportantMessages());
                    return settings;
                })
                .flatMap(contextSettingsRepository::save)
                .then()
                .timeout(OPERATION_TIMEOUT);
    }

    private Mono<Void> createOrUpdateLlmParameters(Long configId, LinkedChatsTemplate.LlmParametersTemplate template) {
        return llmParametersRepository.findByChatConfigId(configId)
                .switchIfEmpty(Mono.defer(() -> Mono.just(new LlmParameters(configId))))
                .map(params -> {
                    params.setChatConfigId(configId);
                    params.setModelName(template.modelName());
                    params.setTemperature(template.temperature());
                    params.setMaxTokens(template.maxTokens());
                    params.setTopP(template.topP());
                    params.setFrequencyPenalty(template.frequencyPenalty());
                    params.setPresencePenalty(template.presencePenalty());
                    params.setSystemPrompt(template.systemPrompt());
                    params.setCustomInstructions(template.customInstructions());
                    params.setResponseFormat(ResponseFormat.valueOf(template.responseFormat()));
                    return params;
                })
                .flatMap(llmParametersRepository::save)
                .then()
                .timeout(OPERATION_TIMEOUT);
    }

    private Mono<Void> createOrUpdateRateLimits(Long configId, LinkedChatsTemplate.RateLimitsTemplate template) {
        return rateLimitsRepository.findByChatConfigId(configId)
                .switchIfEmpty(Mono.defer(() -> Mono.just(new RateLimits(configId))))
                .map(limits -> {
                    limits.setChatConfigId(configId);
                    limits.setMaxMessagesPerMinute(template.maxMessagesPerMinute());
                    limits.setMaxMessagesPerHour(template.maxMessagesPerHour());
                    limits.setMaxMessagesPerDay(template.maxMessagesPerDay());
                    limits.setMaxTokensPerDay(template.maxTokensPerDay());
                    limits.setCooldownAfterLimitMinutes(template.cooldownAfterLimitMinutes());
                    limits.setBurstLimit(template.burstLimit());
                    limits.setBurstWindowSeconds(template.burstWindowSeconds());
                    limits.setUserSpecificLimits(template.userSpecificLimits());
                    return limits;
                })
                .flatMap(rateLimitsRepository::save)
                .then()
                .timeout(OPERATION_TIMEOUT);
    }

    private Mono<Void> createOrUpdateResponseTemplate(Long configId, LinkedChatsTemplate.ResponseTemplateConfig template) {
        return responseTemplateRepository.findByChatConfigId(configId)
                .filter(existing -> existing.getTemplateName().equals(template.templateName()))
                .flatMap(responseTemplateRepository::delete)
                .then(Mono.defer(() -> {
                    ResponseTemplate responseTemplate = new ResponseTemplate(configId, template.templateName(), template.templateContent());
                    responseTemplate.setResponseStyle(ResponseStyle.valueOf(template.responseStyle()));
                    responseTemplate.setResponseTone(ResponseTone.valueOf(template.responseTone()));
                    responseTemplate.setMaxResponseLength(template.maxResponseLength());
                    responseTemplate.setDefault(template.isDefault());
                    responseTemplate.setPriority(template.priority());
                    responseTemplate.setActive(template.active());
                    return responseTemplateRepository.save(responseTemplate);
                }))
                .then()
                .timeout(OPERATION_TIMEOUT);
    }

    private Mono<Void> createOrUpdateTriggerCondition(Long configId, LinkedChatsTemplate.TriggerConditionConfig template) {
        return triggerConditionRepository.findByChatConfigIdAndConditionName(configId, template.conditionName())
                .flatMap(triggerConditionRepository::delete)
                .then(Mono.defer(() -> {
                    TriggerCondition condition = new TriggerCondition(configId, template.conditionName(),
                            TriggerType.valueOf(template.triggerType()));
                    condition.setKeywords(template.keywords());
                    condition.setMentionRequired(template.mentionRequired());
                    condition.setTimeDelaySeconds(template.timeDelaySeconds());
                    condition.setProbabilityPercent(template.probabilityPercent());
                    condition.setActiveHoursStart(template.activeHoursStart());
                    condition.setActiveHoursEnd(template.activeHoursEnd());
                    condition.setActiveDaysOfWeek(template.activeDaysOfWeek());
                    condition.setMinimumGapMinutes(template.minimumGapMinutes());
                    condition.setPriority(template.priority());
                    condition.setActive(template.active());
                    condition.setResponseLength(ResponseLength.valueOf(template.responseLength()));
                    return triggerConditionRepository.save(condition);
                }))
                .then()
                .timeout(OPERATION_TIMEOUT);
    }

    private record StrategyMatch(TemplateApplicationStrategy strategy, boolean matches) {
    }

    private record ChannelPair(Channel primary, Channel discussion) {
    }
}
