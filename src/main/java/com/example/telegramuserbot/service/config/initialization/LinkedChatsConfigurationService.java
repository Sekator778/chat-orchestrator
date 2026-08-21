package com.example.telegramuserbot.service.config.initialization;

import com.example.telegramuserbot.domain.*;
import com.example.telegramuserbot.repository.*;
import com.example.telegramuserbot.service.config.initialization.strategy.TemplateApplicationStrategy;
import com.example.telegramuserbot.service.config.initialization.template.LinkedChatsTemplate;
import com.example.telegramuserbot.service.config.initialization.template.LinkedChatsTemplateFactory;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalTime;
import java.util.List;

/**
 * Ensures linked channel-discussion pairs have proper configuration applied.
 * Discovers channel-discussion relationships via primary_channel_id and applies
 * standardized templates when configuration is missing or incomplete.
 *
 * <p>This service uses strategy pattern to determine which template to apply
 * based on channel characteristics (language, size, metadata). Strategies are
 * evaluated in priority order until a match is found.
 *
 * <p>This service runs at application startup and periodically to maintain
 * configuration consistency across linked chats.
 */
@Service
public class LinkedChatsConfigurationService {

    private static final Logger log = LoggerFactory.getLogger(LinkedChatsConfigurationService.class);
    private static final Duration OPERATION_TIMEOUT = Duration.ofSeconds(60);

    private final ChatConfigRepository chatConfigRepository;
    private final ContextSettingsRepository contextSettingsRepository;
    private final LlmParametersRepository llmParametersRepository;
    private final RateLimitsRepository rateLimitsRepository;
    private final ResponseTemplateRepository responseTemplateRepository;
    private final TriggerConditionRepository triggerConditionRepository;
    private final ChannelRepository channelRepository;
    private final List<TemplateApplicationStrategy> strategies;
    private final LinkedChatsTemplateFactory templateFactory;

    public LinkedChatsConfigurationService(ChatConfigRepository chatConfigRepository,
                                           ContextSettingsRepository contextSettingsRepository,
                                           LlmParametersRepository llmParametersRepository,
                                           RateLimitsRepository rateLimitsRepository,
                                           ResponseTemplateRepository responseTemplateRepository,
                                           TriggerConditionRepository triggerConditionRepository,
                                           ChannelRepository channelRepository,
                                           LinkedChatsTemplateFactory templateFactory,
                                           List<TemplateApplicationStrategy> strategies) {
        log.info("LinkedChatsConfigurationService constructor started, received {} strategies",
                strategies != null ? strategies.size() : 0);

        this.chatConfigRepository = chatConfigRepository;
        this.contextSettingsRepository = contextSettingsRepository;
        this.llmParametersRepository = llmParametersRepository;
        this.rateLimitsRepository = rateLimitsRepository;
        this.responseTemplateRepository = responseTemplateRepository;
        this.triggerConditionRepository = triggerConditionRepository;
        this.channelRepository = channelRepository;
        this.templateFactory = templateFactory;

        log.info("Sorting strategies by priority...");
        this.strategies = strategies.stream()
                .sorted((s1, s2) -> Integer.compare(s2.priority(), s1.priority()))
                .toList();

        log.info("Initialized LinkedChatsConfigurationService with {} strategies:", strategies.size());
        this.strategies.forEach(strategy -> {
            try {
                log.info("  - {} (priority {}): {}", strategy.name(), strategy.priority(), strategy.description());
            } catch (Exception e) {
                log.error("Error logging strategy info: {}", e.getMessage(), e);
            }
        });
        log.info("LinkedChatsConfigurationService constructor completed");
    }

    @PostConstruct
    public void postConstruct() {
        log.info("=== POST CONSTRUCT: LinkedChatsConfigurationService initialized with {} strategies ===",
                strategies != null ? strategies.size() : 0);
        if (strategies != null) {
            strategies.forEach(strategy -> {
                try {
                    log.info("  - Strategy: {} (priority: {})",
                            strategy.name(), strategy.priority());
                } catch (Exception e) {
                    log.error("Error in strategy toString: {}", e.getMessage());
                }
            });
        }
    }

    /**
     * Initializes configuration for all linked chats that lack proper setup.
     * Finds channel-discussion pairs and applies minimal reaction template.
     *
     * @return Mono emitting count of chat pairs configured
     */
    public Mono<Integer> initializeLinkedChatsConfiguration() {
        log.info("Starting linked chats configuration initialization");

        return findLinkedChatPairs()
                .doOnNext(pair -> log.debug("Found linked pair: channel={} <-> discussion={}",
                        pair.channel.getChannelId(), pair.discussion.getChannelId()))
                .concatMap(this::ensureConfigurationApplied)
                .reduce(0, Integer::sum)
                .doOnSuccess(count -> log.info("Configured {} linked chat pairs", count))
                .doOnError(error -> log.error("Failed to initialize linked chats configuration", error))
                .timeout(Duration.ofMinutes(5));
    }

    /**
     * Finds all channel-discussion chat pairs that have primary_channel_id set.
     *
     * @return Flux of linked chat pairs
     */
    private Flux<LinkedChatPair> findLinkedChatPairs() {
        return chatConfigRepository.findAllForInstance()
                .filter(config -> config.getPrimaryChannelId() != null)
                .flatMap(discussionConfig ->
                        chatConfigRepository.findByChannelChatId(discussionConfig.getPrimaryChannelId())
                                .map(channelConfig -> new LinkedChatPair(
                                        channelConfig,
                                        discussionConfig
                                ))
                                .doOnNext(pair -> log.debug("Found linked pair: channel {} <-> discussion {}",
                                        pair.channel.getChannelId(), pair.discussion.getChannelId()))
                )
                .timeout(OPERATION_TIMEOUT);
    }

    /**
     * Ensures full configuration is applied to both channel and discussion.
     * Checks if configuration is complete, finds matching strategy, and applies template.
     *
     * @param pair Linked chat pair
     * @return Mono emitting 1 if configuration was applied, 0 otherwise
     */
    private Mono<Integer> ensureConfigurationApplied(LinkedChatPair pair) {
        log.debug("Checking configuration for pair: channel {} <-> discussion {}",
                pair.channel.getChannelId(), pair.discussion.getChannelId());

        return isConfigurationComplete(pair.discussion)
                .flatMap(isComplete -> {
                    if (isComplete) {
                        log.debug("Discussion {} already has complete configuration, skipping",
                                pair.discussion.getChannelId());
                        return Mono.just(0);
                    }

                    log.info("Configuration incomplete for pair: channel {} <-> discussion {}, finding strategy",
                            pair.channel.getChannelId(), pair.discussion.getChannelId());

                    // Load channel metadata from tgscan.channels for strategy evaluation
                    return loadChannelMetadata(pair)
                            .flatMap(this::findMatchingStrategy)
                            .flatMap(strategyMatch -> {
                                log.info("Applying strategy '{}' to linked pair: channel {} <-> discussion {}",
                                        strategyMatch.strategy.name(),
                                        pair.channel.getChannelId(),
                                        pair.discussion.getChannelId());

                                LinkedChatsTemplate enforcedTemplate = enforceLowEngagementDiscussionTemplate(
                                        strategyMatch.strategy.template());

                                return applyTemplateToLinkedPair(pair, enforcedTemplate, strategyMatch.strategy.name())
                                        .thenReturn(1);
                            })
                            .switchIfEmpty(Mono.defer(() -> {
                                log.warn("No matching strategy found for pair: channel {} <-> discussion {}",
                                        pair.channel.getChannelId(), pair.discussion.getChannelId());
                                return Mono.just(0);
                            }));
                })
                .timeout(OPERATION_TIMEOUT)
                .onErrorResume(error -> {
                    log.warn("Failed to apply configuration to pair channel {} <-> discussion {}: {}",
                            pair.channel.getChannelId(), pair.discussion.getChannelId(), error.getMessage());
                    return Mono.just(0);
                });
    }

    /**
     * Loads channel and discussion metadata from tgscan.channels table.
     * Required for strategy evaluation based on channel characteristics.
     *
     * @param pair Linked chat pair configuration
     * @return Mono emitting enriched pair with channel metadata
     */
    private Mono<EnrichedLinkedChatPair> loadChannelMetadata(LinkedChatPair pair) {
        return Mono.zip(
                        channelRepository.findByChatId(pair.channel.getChannelId())
                                .switchIfEmpty(Mono.defer(() -> {
                                    log.warn("Channel {} not found in tgscan.channels, creating empty metadata",
                                            pair.channel.getChannelId());
                                    return Mono.just(createEmptyChannel(pair.channel.getChannelId()));
                                })),
                        channelRepository.findByChatId(pair.discussion.getChannelId())
                                .switchIfEmpty(Mono.defer(() -> {
                                    log.warn("Discussion {} not found in tgscan.channels, creating empty metadata",
                                            pair.discussion.getChannelId());
                                    return Mono.just(createEmptyChannel(pair.discussion.getChannelId()));
                                }))
                )
                .map(tuple -> new EnrichedLinkedChatPair(pair, tuple.getT1(), tuple.getT2()))
                .timeout(OPERATION_TIMEOUT);
    }

    /**
     * Finds first matching strategy for given channel-discussion pair.
     * Strategies are evaluated in priority order (highest first).
     *
     * @param enrichedPair Pair with channel metadata
     * @return Mono emitting strategy match or empty if no strategy matches
     */
    private Mono<StrategyMatch> findMatchingStrategy(EnrichedLinkedChatPair enrichedPair) {
        log.debug("Finding matching strategy, evaluating {} strategies", strategies.size());
        return Flux.fromIterable(strategies)
                .concatMap(strategy -> strategy.shouldApply(enrichedPair.channelMetadata, enrichedPair.discussionMetadata)
                        .map(matches -> {
                            if (matches) {
                                log.debug("Strategy {} matched", strategy.name());
                            }
                            return new StrategyMatch(strategy, matches);
                        })
                        .onErrorResume(error -> {
                            log.warn("Strategy {} evaluation failed: {}", strategy.name(), error.getMessage());
                            return Mono.just(new StrategyMatch(strategy, false));
                        }))
                .filter(match -> match.matches)
                .next()
                .timeout(OPERATION_TIMEOUT);
    }

    /**
     * Creates empty Channel object for chats not found in tgscan.channels.
     */
    private Channel createEmptyChannel(Long chatId) {
        Channel channel = new Channel();
        channel.setChatId(chatId);
        channel.setTitle("");
        channel.setDescription("");
        channel.setUsername("");
        channel.setSampleMessage("");
        return channel;
    }

    /**
     * Checks if discussion chat has complete configuration (all required entities).
     *
     * @param config Discussion chat configuration
     * @return Mono emitting true if configuration is complete
     */
    private Mono<Boolean> isConfigurationComplete(ChatConfig config) {
        return Mono.zip(
                        contextSettingsRepository.existsByChatConfigChannelChatId(config.getChannelId()),
                        llmParametersRepository.existsByChatConfigId(config.getId()),
                        rateLimitsRepository.findByChatConfigId(config.getId())
                                .hasElement(),
                        responseTemplateRepository.countByChatConfigIdAndActiveTrue(config.getId())
                                .map(count -> count > 0),
                        triggerConditionRepository.countByChatConfigChannelChatIdAndActive(config.getChannelId(), true)
                                .map(count -> count > 0)
                )
                .map(tuple -> tuple.getT1() && tuple.getT2() && tuple.getT3() && tuple.getT4() && tuple.getT5())
                .timeout(OPERATION_TIMEOUT);
    }

    /**
     * Applies configuration template to linked channel-discussion pair.
     *
     * @param pair         Linked chat pair
     * @param template     Configuration template
     * @param strategyName Name of strategy being applied
     * @return Mono completing when template is applied
     */
    private Mono<Void> applyTemplateToLinkedPair(LinkedChatPair pair, LinkedChatsTemplate template, String strategyName) {
        String templateName = template.templateName();
        String templateSource = template.resourcePath() != null ? template.resourcePath() : "unknown";
        log.info("Applying template '{}' (resource: {}) from strategy '{}' to channel {} and discussion {}",
                templateName, templateSource, strategyName, pair.channel.getChannelId(), pair.discussion.getChannelId());

        return updateChannelConfig(pair.channel, template.channel())
                .then(updateDiscussionConfig(pair.discussion, template.discussion()))
                .then(ensureChannelMarkedInRepository(pair.channel.getChannelId()))
                .then(ensureDiscussionMarkedInRepository(pair.discussion.getChannelId()))
                .doOnSuccess(ignored -> log.debug("Template applied successfully to pair"))
                .doOnError(error -> log.error("Template application failed: {}", error.getMessage()))
                .timeout(OPERATION_TIMEOUT);
    }

    /**
     * Ensures that every linked discussion chat receives the low-engagement-followup discussion settings.
     * Channel portion of the original strategy template is preserved, while the discussion part is replaced.
     */
    private LinkedChatsTemplate enforceLowEngagementDiscussionTemplate(LinkedChatsTemplate originalTemplate) {
        LinkedChatsTemplate lowEngagementTemplate = templateFactory.lowEngagementFollowupTemplate();
        String mergedTemplateName = String.format("%s + %s",
                originalTemplate.templateName(),
                lowEngagementTemplate.templateName());
        String mergedResourcePath = lowEngagementTemplate.resourcePath() != null
                ? lowEngagementTemplate.resourcePath()
                : originalTemplate.resourcePath();

        return new LinkedChatsTemplate(mergedTemplateName, mergedResourcePath,
                originalTemplate.channel(), lowEngagementTemplate.discussion());
    }

    /**
     * Updates channel configuration with template.
     *
     * @param config   Channel configuration
     * @param template Channel template
     * @return Mono completing when updated
     */
    private Mono<Void> updateChannelConfig(ChatConfig config, LinkedChatsTemplate.ChannelTemplate template) {
        config.setEnabled(template.enabled());
        config.setContextWindowSize(template.contextWindowSize());
        config.setLanguage(template.language());
        config.setAutoSyncEnabled(template.autoSyncEnabled());
        config.setSyncEnabled(template.syncEnabled());

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
                )).thenReturn(saved))
                .doOnSuccess(saved -> log.debug("Updated channel config for chat {}", saved.getChannelId()))
                .then()
                .timeout(OPERATION_TIMEOUT);
    }

    /**
     * Updates discussion configuration with template and creates all related entities.
     *
     * @param config   Discussion configuration
     * @param template Discussion template
     * @return Mono completing when all entities are created
     */
    private Mono<Void> updateDiscussionConfig(ChatConfig config, LinkedChatsTemplate.DiscussionTemplate template) {
        log.debug("Updating discussion config for chat {}", config.getChannelId());
        config.setEnabled(template.enabled());
        config.setPromptTemplate(template.promptTemplate());
        config.setMaxTokens(template.maxTokens());
        config.setTemperature(template.temperature());
        config.setContextWindowSize(template.contextWindowSize());
        config.setLanguage(template.language());
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
                                .thenReturn(savedConfig))
                .doOnSuccess(savedConfig -> log.info("Applied full configuration to discussion chat {}", savedConfig.getChannelId()))
                .doOnError(error -> log.error("Error in updateDiscussionConfig: {}", error.getMessage()))
                .then()
                .timeout(OPERATION_TIMEOUT);
    }

    /**
     * Creates or updates context settings for discussion chat.
     */
    private Mono<Void> createOrUpdateContextSettings(Long configId,
                                                     LinkedChatsTemplate.ContextSettingsTemplate template) {
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
                .doOnSuccess(saved -> log.debug("ContextSettings saved for config {}", configId))
                .doOnError(error -> log.error("ContextSettings error for config {}: {}", configId, error.getMessage()))
                .then()
                .timeout(OPERATION_TIMEOUT);
    }

    /**
     * Creates or updates LLM parameters for discussion chat.
     */
    private Mono<Void> createOrUpdateLlmParameters(Long configId,
                                                   LinkedChatsTemplate.LlmParametersTemplate template) {
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
                .doOnSuccess(saved -> log.debug("LlmParameters saved for config {}", configId))
                .doOnError(error -> log.error("LlmParameters error for config {}: {}", configId, error.getMessage()))
                .then()
                .timeout(OPERATION_TIMEOUT);
    }

    /**
     * Creates or updates rate limits for discussion chat.
     */
    private Mono<Void> createOrUpdateRateLimits(Long configId,
                                                LinkedChatsTemplate.RateLimitsTemplate template) {
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
                .doOnSuccess(saved -> log.debug("RateLimits saved for config {}", configId))
                .doOnError(error -> log.error("RateLimits error for config {}: {}", configId, error.getMessage()))
                .then()
                .timeout(OPERATION_TIMEOUT);
    }

    /**
     * Creates or updates response template for discussion chat.
     * Deletes existing template with same name before creating new one.
     */
    private Mono<Void> createOrUpdateResponseTemplate(Long configId,
                                                      LinkedChatsTemplate.ResponseTemplateConfig template) {
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
                .doOnSuccess(saved -> log.debug("ResponseTemplate saved for config {}", configId))
                .doOnError(error -> log.error("ResponseTemplate error for config {}: {}", configId, error.getMessage()))
                .then()
                .timeout(OPERATION_TIMEOUT);
    }

    /**
     * Creates or updates trigger condition for discussion chat.
     * Deletes existing condition with same name before creating new one.
     */
    private Mono<Void> createOrUpdateTriggerCondition(Long configId,
                                                      LinkedChatsTemplate.TriggerConditionConfig template) {
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
                .doOnSuccess(saved -> log.debug("TriggerCondition saved for config {}", configId))
                .doOnError(error -> log.error("TriggerCondition error for config {}: {}", configId, error.getMessage()))
                .then()
                .timeout(OPERATION_TIMEOUT);
    }

    /**
     * Ensures channel is marked as is_channel=true in tgscan.channels table.
     */
    private Mono<Void> ensureChannelMarkedInRepository(Long channelId) {
        return channelRepository.findByChatId(channelId)
                .flatMap(channel -> {
                    Boolean isChannel = channel.isChannel();
                    if (isChannel == null || !isChannel) {
                        channel.setChannel(true);
                        return channelRepository.save(channel)
                                .doOnSuccess(saved -> log.debug("Marked chat {} as channel in repository", channelId))
                                .then();
                    }
                    return Mono.empty();
                })
                .switchIfEmpty(Mono.defer(() -> {
                    log.debug("Channel {} not found in repository, creating new entry", channelId);
                    Channel channel = new Channel();
                    channel.setChatId(channelId);
                    channel.setChannel(true);
                    channel.setJoinStatus("joined");
                    return channelRepository.save(channel).then();
                }))
                .timeout(OPERATION_TIMEOUT)
                .onErrorResume(error -> {
                    log.warn("Failed to mark channel {} in repository: {}", channelId, error.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * Ensures discussion group is marked as is_channel=false in tgscan.channels table.
     */
    private Mono<Void> ensureDiscussionMarkedInRepository(Long discussionId) {
        return channelRepository.findByChatId(discussionId)
                .flatMap(channel -> {
                    Boolean isChannel = channel.isChannel();
                    if (isChannel == null || isChannel) {
                        channel.setChannel(false);
                        return channelRepository.save(channel)
                                .doOnSuccess(saved -> log.debug("Marked chat {} as discussion in repository", discussionId))
                                .then();
                    }
                    return Mono.empty();
                })
                .switchIfEmpty(Mono.defer(() -> {
                    log.debug("Discussion {} not found in repository, creating new entry", discussionId);
                    Channel channel = new Channel();
                    channel.setChatId(discussionId);
                    channel.setChannel(false);
                    channel.setJoinStatus("joined");
                    return channelRepository.save(channel).then();
                }))
                .timeout(OPERATION_TIMEOUT)
                .onErrorResume(error -> {
                    log.warn("Failed to mark discussion {} in repository: {}", discussionId, error.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * Represents a linked channel-discussion chat pair.
     */
    private record LinkedChatPair(ChatConfig channel, ChatConfig discussion) {
    }

    /**
     * Represents an enriched linked chat pair with channel metadata from tgscan.channels.
     * Used for strategy evaluation based on channel characteristics.
     */
    private record EnrichedLinkedChatPair(
            LinkedChatPair pair,
            Channel channelMetadata,
            Channel discussionMetadata
    ) {
    }

    /**
     * Represents a strategy evaluation result.
     */
    private record StrategyMatch(
            TemplateApplicationStrategy strategy,
            boolean matches
    ) {
    }
}
