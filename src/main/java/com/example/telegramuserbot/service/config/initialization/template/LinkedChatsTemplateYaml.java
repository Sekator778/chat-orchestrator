package com.example.telegramuserbot.service.config.initialization.template;

/**
 * YAML representation of linked chats configuration template.
 * Used by SnakeYAML to deserialize YAML files into Java objects.
 *
 * <p>This is a pure data class with public fields for SnakeYAML compatibility.
 * After deserialization, {@link LinkedChatsTemplateLoader} converts this to immutable
 * domain model {@link LinkedChatsTemplate}.
 *
 * <p><b>Note:</b> This class violates Elegant Objects principles (public mutable fields)
 * but is necessary for SnakeYAML deserialization. It's used only during loading phase
 * and never exposed outside the template package.
 */
public final class LinkedChatsTemplateYaml {

    public String templateName;
    public String description;
    public ChannelYaml channel;
    public DiscussionYaml discussion;

    public static final class ChannelYaml {
        public boolean enabled;
        public int maxDailyMessages;
        public int contextWindowSize;
        public String language;
        public boolean autoSyncEnabled;
        public boolean syncEnabled;
        public SyncConfigurationYaml syncConfiguration;
    }

    public static final class DiscussionYaml {
        public boolean enabled;
        public String promptTemplate;
        public int maxTokens;
        public double temperature;
        public int maxDailyMessages;
        public int contextWindowSize;
        public String language;
        public boolean autoSyncEnabled;
        public boolean syncEnabled;
        public boolean respondToForwardedBotMessages;
        public Integer waitForHumanRepliesCount;
        public ContextSettingsYaml contextSettings;
        public LlmParametersYaml llmParameters;
        public RateLimitsYaml rateLimits;
        public ResponseTemplateYaml responseTemplate;
        public TriggerConditionYaml triggerCondition;
        public SyncConfigurationYaml syncConfiguration;
    }

    public static final class ContextSettingsYaml {
        public int historyMessageCount;
        public int historyTimeWindowHours;
        public boolean includeUserContext;
        public boolean includeMediaDescriptions;
        public boolean contextCompressionEnabled;
        public int maxContextTokens;
        public boolean preserveImportantMessages;
    }

    public static final class LlmParametersYaml {
        public String modelName;
        public double temperature;
        public int maxTokens;
        public double topP;
        public double frequencyPenalty;
        public double presencePenalty;
        public String systemPrompt;
        public String customInstructions;
        public String responseFormat;
    }

    public static final class RateLimitsYaml {
        public int maxMessagesPerMinute;
        public int maxMessagesPerHour;
        public int maxMessagesPerDay;
        public int maxTokensPerDay;
        public int cooldownAfterLimitMinutes;
        public int burstLimit;
        public int burstWindowSeconds;
        public boolean userSpecificLimits;
    }

    public static final class ResponseTemplateYaml {
        public String templateName;
        public String templateContent;
        public String responseStyle;
        public String responseTone;
        public int maxResponseLength;
        public boolean isDefault;
        public int priority;
        public boolean active;
    }

    public static final class TriggerConditionYaml {
        public String conditionName;
        public String triggerType;
        public String keywords;
        public boolean mentionRequired;
        public int timeDelaySeconds;
        public int probabilityPercent;
        public String activeHoursStart;
        public String activeHoursEnd;
        public String activeDaysOfWeek;
        public int minimumGapMinutes;
        public int priority;
        public boolean active;
        public String responseLength;
    }

    public static final class SyncConfigurationYaml {
        public Integer defaultSyncDepthDays;
        public Integer maxSyncDepthDays;
        public Boolean autoSyncEnabled;
        public Integer autoSyncIntervalDays;
        public Integer maxConcurrentSyncs;
    }
}
