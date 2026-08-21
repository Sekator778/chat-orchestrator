package com.example.telegramuserbot.service.config.initialization.template;

import java.time.LocalTime;

/**
 * Template for configuring linked channel-discussion chat pairs.
 * Encapsulates default configuration values for both primary channel
 * and discussion group to ensure consistent setup.
 */
public final class LinkedChatsTemplate {

    private static final String UNKNOWN_TEMPLATE = "unknown";

    private final String templateName;
    private final String resourcePath;
    private final ChannelTemplate channel;
    private final DiscussionTemplate discussion;

    /**
     * Constructs template for linked chats configuration.
     * Template name is optional and used only for logging purposes.
     *
     * @param templateName Template identifier (from YAML or loader)
     * @param resourcePath Template resource path for traceability
     * @param channel Channel configuration template
     * @param discussion Discussion group configuration template
     */
    public LinkedChatsTemplate(String templateName,
                               String resourcePath,
                               ChannelTemplate channel,
                               DiscussionTemplate discussion) {
        this.templateName = templateName != null ? templateName : UNKNOWN_TEMPLATE;
        this.resourcePath = resourcePath;
        this.channel = channel;
        this.discussion = discussion;
    }

    /**
     * Constructs template without explicit metadata.
     */
    public LinkedChatsTemplate(ChannelTemplate channel, DiscussionTemplate discussion) {
        this(UNKNOWN_TEMPLATE, null, channel, discussion);
    }

    /**
     * Returns human-readable template name (from YAML or loader).
     */
    public String templateName() {
        return templateName;
    }

    /**
     * Returns resource path used to load the template (classpath location).
     */
    public String resourcePath() {
        return resourcePath;
    }

    /**
     * Returns channel configuration template.
     *
     * @return Channel template
     */
    public ChannelTemplate channel() {
        return channel;
    }

    /**
     * Returns discussion configuration template.
     *
     * @return Discussion template
     */
    public DiscussionTemplate discussion() {
        return discussion;
    }

    /**
     * Template for primary channel configuration (minimal, typically disabled).
     */
    public static final class ChannelTemplate {
        private final boolean enabled;
        private final Integer maxDailyMessages;
        private final Integer contextWindowSize;
        private final String language;
        private final boolean autoSyncEnabled;
        private final boolean syncEnabled;
        private final SyncConfigurationTemplate syncConfiguration;

        public ChannelTemplate(boolean enabled,
                               Integer maxDailyMessages,
                               Integer contextWindowSize,
                               String language,
                               boolean autoSyncEnabled,
                               boolean syncEnabled,
                               SyncConfigurationTemplate syncConfiguration) {
            this.enabled = enabled;
            this.maxDailyMessages = maxDailyMessages;
            this.contextWindowSize = contextWindowSize;
            this.language = language;
            this.autoSyncEnabled = autoSyncEnabled;
            this.syncEnabled = syncEnabled;
            this.syncConfiguration = syncConfiguration;
        }

        public boolean enabled() {
            return enabled;
        }

        public Integer maxDailyMessages() {
            return maxDailyMessages;
        }

        public Integer contextWindowSize() {
            return contextWindowSize;
        }

        public String language() {
            return language;
        }

        public boolean autoSyncEnabled() {
            return autoSyncEnabled;
        }

        public boolean syncEnabled() {
            return syncEnabled;
        }

        public SyncConfigurationTemplate syncConfiguration() {
            return syncConfiguration;
        }
    }

    /**
     * Template for discussion group configuration (full AI response setup).
     */
    public static final class DiscussionTemplate {
        private final boolean enabled;
        private final String promptTemplate;
        private final Integer maxTokens;
        private final Double temperature;
        private final Integer maxDailyMessages;
        private final Integer contextWindowSize;
        private final String language;
        private final boolean autoSyncEnabled;
        private final boolean syncEnabled;
        private final boolean respondToForwardedBotMessages;
        private final Integer waitForHumanRepliesCount;
        private final ContextSettingsTemplate contextSettings;
        private final LlmParametersTemplate llmParameters;
        private final RateLimitsTemplate rateLimits;
        private final ResponseTemplateConfig responseTemplate;
        private final TriggerConditionConfig triggerCondition;
        private final SyncConfigurationTemplate syncConfiguration;

        public DiscussionTemplate(boolean enabled,
                                  String promptTemplate,
                                  Integer maxTokens,
                                  Double temperature,
                                  Integer maxDailyMessages,
                                  Integer contextWindowSize,
                                  String language,
                                  boolean autoSyncEnabled,
                                  boolean syncEnabled,
                                  boolean respondToForwardedBotMessages,
                                  Integer waitForHumanRepliesCount,
                                  ContextSettingsTemplate contextSettings,
                                  LlmParametersTemplate llmParameters,
                                  RateLimitsTemplate rateLimits,
                                  ResponseTemplateConfig responseTemplate,
                                  TriggerConditionConfig triggerCondition,
                                  SyncConfigurationTemplate syncConfiguration) {
            this.enabled = enabled;
            this.promptTemplate = promptTemplate;
            this.maxTokens = maxTokens;
            this.temperature = temperature;
            this.maxDailyMessages = maxDailyMessages;
            this.contextWindowSize = contextWindowSize;
            this.language = language;
            this.autoSyncEnabled = autoSyncEnabled;
            this.syncEnabled = syncEnabled;
            this.respondToForwardedBotMessages = respondToForwardedBotMessages;
            this.waitForHumanRepliesCount = waitForHumanRepliesCount;
            this.contextSettings = contextSettings;
            this.llmParameters = llmParameters;
            this.rateLimits = rateLimits;
            this.responseTemplate = responseTemplate;
            this.triggerCondition = triggerCondition;
            this.syncConfiguration = syncConfiguration;
        }

        public boolean enabled() {
            return enabled;
        }

        public String promptTemplate() {
            return promptTemplate;
        }

        public Integer maxTokens() {
            return maxTokens;
        }

        public Double temperature() {
            return temperature;
        }

        public Integer maxDailyMessages() {
            return maxDailyMessages;
        }

        public Integer contextWindowSize() {
            return contextWindowSize;
        }

        public String language() {
            return language;
        }

        public boolean autoSyncEnabled() {
            return autoSyncEnabled;
        }

        public boolean syncEnabled() {
            return syncEnabled;
        }

        public boolean respondToForwardedBotMessages() {
            return respondToForwardedBotMessages;
        }

        public Integer waitForHumanRepliesCount() {
            return waitForHumanRepliesCount;
        }

        public ContextSettingsTemplate contextSettings() {
            return contextSettings;
        }

        public LlmParametersTemplate llmParameters() {
            return llmParameters;
        }

        public RateLimitsTemplate rateLimits() {
            return rateLimits;
        }

        public ResponseTemplateConfig responseTemplate() {
            return responseTemplate;
        }

        public TriggerConditionConfig triggerCondition() {
            return triggerCondition;
        }

        public SyncConfigurationTemplate syncConfiguration() {
            return syncConfiguration;
        }
    }

    /**
     * Template for context settings configuration.
     */
    public static final class ContextSettingsTemplate {
        private final Integer historyMessageCount;
        private final Integer historyTimeWindowHours;
        private final boolean includeUserContext;
        private final boolean includeMediaDescriptions;
        private final boolean contextCompressionEnabled;
        private final Integer maxContextTokens;
        private final boolean preserveImportantMessages;

        public ContextSettingsTemplate(Integer historyMessageCount,
                                       Integer historyTimeWindowHours,
                                       boolean includeUserContext,
                                       boolean includeMediaDescriptions,
                                       boolean contextCompressionEnabled,
                                       Integer maxContextTokens,
                                       boolean preserveImportantMessages) {
            this.historyMessageCount = historyMessageCount;
            this.historyTimeWindowHours = historyTimeWindowHours;
            this.includeUserContext = includeUserContext;
            this.includeMediaDescriptions = includeMediaDescriptions;
            this.contextCompressionEnabled = contextCompressionEnabled;
            this.maxContextTokens = maxContextTokens;
            this.preserveImportantMessages = preserveImportantMessages;
        }

        public Integer historyMessageCount() {
            return historyMessageCount;
        }

        public Integer historyTimeWindowHours() {
            return historyTimeWindowHours;
        }

        public boolean includeUserContext() {
            return includeUserContext;
        }

        public boolean includeMediaDescriptions() {
            return includeMediaDescriptions;
        }

        public boolean contextCompressionEnabled() {
            return contextCompressionEnabled;
        }

        public Integer maxContextTokens() {
            return maxContextTokens;
        }

        public boolean preserveImportantMessages() {
            return preserveImportantMessages;
        }
    }

    /**
     * Template for LLM parameters configuration.
     */
    public static final class LlmParametersTemplate {
        private final String modelName;
        private final Double temperature;
        private final Integer maxTokens;
        private final Double topP;
        private final Double frequencyPenalty;
        private final Double presencePenalty;
        private final String systemPrompt;
        private final String customInstructions;
        private final String responseFormat;

        public LlmParametersTemplate(String modelName,
                                     Double temperature,
                                     Integer maxTokens,
                                     Double topP,
                                     Double frequencyPenalty,
                                     Double presencePenalty,
                                     String systemPrompt,
                                     String customInstructions,
                                     String responseFormat) {
            this.modelName = modelName;
            this.temperature = temperature;
            this.maxTokens = maxTokens;
            this.topP = topP;
            this.frequencyPenalty = frequencyPenalty;
            this.presencePenalty = presencePenalty;
            this.systemPrompt = systemPrompt;
            this.customInstructions = customInstructions;
            this.responseFormat = responseFormat;
        }

        public String modelName() {
            return modelName;
        }

        public Double temperature() {
            return temperature;
        }

        public Integer maxTokens() {
            return maxTokens;
        }

        public Double topP() {
            return topP;
        }

        public Double frequencyPenalty() {
            return frequencyPenalty;
        }

        public Double presencePenalty() {
            return presencePenalty;
        }

        public String systemPrompt() {
            return systemPrompt;
        }

        public String customInstructions() {
            return customInstructions;
        }

        public String responseFormat() {
            return responseFormat;
        }
    }

    /**
     * Template for rate limits configuration.
     */
    public static final class RateLimitsTemplate {
        private final Integer maxMessagesPerMinute;
        private final Integer maxMessagesPerHour;
        private final Integer maxMessagesPerDay;
        private final Integer maxTokensPerDay;
        private final Integer cooldownAfterLimitMinutes;
        private final Integer burstLimit;
        private final Integer burstWindowSeconds;
        private final boolean userSpecificLimits;

        public RateLimitsTemplate(Integer maxMessagesPerMinute,
                                  Integer maxMessagesPerHour,
                                  Integer maxMessagesPerDay,
                                  Integer maxTokensPerDay,
                                  Integer cooldownAfterLimitMinutes,
                                  Integer burstLimit,
                                  Integer burstWindowSeconds,
                                  boolean userSpecificLimits) {
            this.maxMessagesPerMinute = maxMessagesPerMinute;
            this.maxMessagesPerHour = maxMessagesPerHour;
            this.maxMessagesPerDay = maxMessagesPerDay;
            this.maxTokensPerDay = maxTokensPerDay;
            this.cooldownAfterLimitMinutes = cooldownAfterLimitMinutes;
            this.burstLimit = burstLimit;
            this.burstWindowSeconds = burstWindowSeconds;
            this.userSpecificLimits = userSpecificLimits;
        }

        public Integer maxMessagesPerMinute() {
            return maxMessagesPerMinute;
        }

        public Integer maxMessagesPerHour() {
            return maxMessagesPerHour;
        }

        public Integer maxMessagesPerDay() {
            return maxMessagesPerDay;
        }

        public Integer maxTokensPerDay() {
            return maxTokensPerDay;
        }

        public Integer cooldownAfterLimitMinutes() {
            return cooldownAfterLimitMinutes;
        }

        public Integer burstLimit() {
            return burstLimit;
        }

        public Integer burstWindowSeconds() {
            return burstWindowSeconds;
        }

        public boolean userSpecificLimits() {
            return userSpecificLimits;
        }
    }

    /**
     * Template for response template configuration.
     */
    public static final class ResponseTemplateConfig {
        private final String templateName;
        private final String templateContent;
        private final String responseStyle;
        private final String responseTone;
        private final Integer maxResponseLength;
        private final boolean isDefault;
        private final Integer priority;
        private final boolean active;

        public ResponseTemplateConfig(String templateName,
                                      String templateContent,
                                      String responseStyle,
                                      String responseTone,
                                      Integer maxResponseLength,
                                      boolean isDefault,
                                      Integer priority,
                                      boolean active) {
            this.templateName = templateName;
            this.templateContent = templateContent;
            this.responseStyle = responseStyle;
            this.responseTone = responseTone;
            this.maxResponseLength = maxResponseLength;
            this.isDefault = isDefault;
            this.priority = priority;
            this.active = active;
        }

        public String templateName() {
            return templateName;
        }

        public String templateContent() {
            return templateContent;
        }

        public String responseStyle() {
            return responseStyle;
        }

        public String responseTone() {
            return responseTone;
        }

        public Integer maxResponseLength() {
            return maxResponseLength;
        }

        public boolean isDefault() {
            return isDefault;
        }

        public Integer priority() {
            return priority;
        }

        public boolean active() {
            return active;
        }
    }

    /**
     * Template for trigger condition configuration.
     */
    public static final class TriggerConditionConfig {
        private final String conditionName;
        private final String triggerType;
        private final String keywords;
        private final boolean mentionRequired;
        private final Integer timeDelaySeconds;
        private final Integer probabilityPercent;
        private final LocalTime activeHoursStart;
        private final LocalTime activeHoursEnd;
        private final String activeDaysOfWeek;
        private final Integer minimumGapMinutes;
        private final Integer priority;
        private final boolean active;
        private final String responseLength;

        public TriggerConditionConfig(String conditionName,
                                      String triggerType,
                                      String keywords,
                                      boolean mentionRequired,
                                      Integer timeDelaySeconds,
                                      Integer probabilityPercent,
                                      LocalTime activeHoursStart,
                                      LocalTime activeHoursEnd,
                                      String activeDaysOfWeek,
                                      Integer minimumGapMinutes,
                                      Integer priority,
                                      boolean active,
                                      String responseLength) {
            this.conditionName = conditionName;
            this.triggerType = triggerType;
            this.keywords = keywords;
            this.mentionRequired = mentionRequired;
            this.timeDelaySeconds = timeDelaySeconds;
            this.probabilityPercent = probabilityPercent;
            this.activeHoursStart = activeHoursStart;
            this.activeHoursEnd = activeHoursEnd;
            this.activeDaysOfWeek = activeDaysOfWeek;
            this.minimumGapMinutes = minimumGapMinutes;
            this.priority = priority;
            this.active = active;
            this.responseLength = responseLength;
        }

        public String conditionName() {
            return conditionName;
        }

        public String triggerType() {
            return triggerType;
        }

        public String keywords() {
            return keywords;
        }

        public boolean mentionRequired() {
            return mentionRequired;
        }

        public Integer timeDelaySeconds() {
            return timeDelaySeconds;
        }

        public Integer probabilityPercent() {
            return probabilityPercent;
        }

        public LocalTime activeHoursStart() {
            return activeHoursStart;
        }

        public LocalTime activeHoursEnd() {
            return activeHoursEnd;
        }

        public String activeDaysOfWeek() {
            return activeDaysOfWeek;
        }

        public Integer minimumGapMinutes() {
            return minimumGapMinutes;
        }

        public Integer priority() {
            return priority;
        }

        public boolean active() {
            return active;
        }

        public String responseLength() {
            return responseLength;
        }
    }

    /**
     * Template for sync configuration creation/upsert.
     */
    public static final class SyncConfigurationTemplate {
        private final Integer defaultSyncDepthDays;
        private final Integer maxSyncDepthDays;
        private final boolean autoSyncEnabled;
        private final Integer autoSyncIntervalDays;
        private final Integer maxConcurrentSyncs;

        public SyncConfigurationTemplate(Integer defaultSyncDepthDays,
                                         Integer maxSyncDepthDays,
                                         boolean autoSyncEnabled,
                                         Integer autoSyncIntervalDays,
                                         Integer maxConcurrentSyncs) {
            this.defaultSyncDepthDays = defaultSyncDepthDays;
            this.maxSyncDepthDays = maxSyncDepthDays;
            this.autoSyncEnabled = autoSyncEnabled;
            this.autoSyncIntervalDays = autoSyncIntervalDays;
            this.maxConcurrentSyncs = maxConcurrentSyncs;
        }

        public Integer defaultSyncDepthDays() {
            return defaultSyncDepthDays;
        }

        public Integer maxSyncDepthDays() {
            return maxSyncDepthDays;
        }

        public boolean autoSyncEnabled() {
            return autoSyncEnabled;
        }

        public Integer autoSyncIntervalDays() {
            return autoSyncIntervalDays;
        }

        public Integer maxConcurrentSyncs() {
            return maxConcurrentSyncs;
        }
    }
}
