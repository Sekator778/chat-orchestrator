package com.example.telegramuserbot.dto;

import com.example.telegramuserbot.domain.Channel;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * Comprehensive DTO for chat configuration including all related settings
 */
public record EnhancedChatConfigDto(
        Long id,
        @JsonProperty("channel_id") Long channelId,
        @JsonProperty("channel_title") String channelTitle,
        @JsonProperty("prompt_template") String promptTemplate,
        boolean enabled,
        @JsonProperty("multi_stage_enabled") boolean multiStageEnabled,
        @JsonProperty("default_sync_depth_days") Integer defaultSyncDepthDays,
        @JsonProperty("auto_sync_enabled") Boolean autoSyncEnabled,
        @JsonProperty("language") String language,
        @JsonProperty("primary_channel_id") Long primaryChannelId,
        @JsonProperty("primary_channel_checked_at") Instant primaryChannelCheckedAt,
        @JsonProperty("context_window_size") Integer contextWindowSize,
        @JsonProperty("respond_to_forwarded_bot_messages") Boolean respondToForwardedBotMessages,
        @JsonProperty("wait_for_human_replies_count") Integer waitForHumanRepliesCount,
        @JsonProperty("sync_enabled") boolean syncEnabled,
        @JsonProperty("max_tokens") Integer maxTokens,
        @JsonProperty("temperature") Double temperature,

        // Related configurations
        @JsonProperty("response_templates") List<ResponseTemplateDto> responseTemplates,
        @JsonProperty("trigger_conditions") List<TriggerConditionDto> triggerConditions,
        @JsonProperty("context_settings") ContextSettingsDto contextSettings,
        @JsonProperty("llm_parameters") LlmParametersDto llmParameters,
        @JsonProperty("rate_limits") RateLimitsDto rateLimits,
        @JsonProperty("topic_restrictions") List<TopicRestrictionDto> topicRestrictions
) {
    // Factory method to create comprehensive DTO from ChatConfig entity
    public static EnhancedChatConfigDto fromEntity(
            com.example.telegramuserbot.domain.ChatConfig chatConfig,
            Channel channel, // Pass Channel separately
            List<ResponseTemplateDto> responseTemplates,
            List<TriggerConditionDto> triggerConditions,
            ContextSettingsDto contextSettings,
            LlmParametersDto llmParameters,
            RateLimitsDto rateLimits,
            List<TopicRestrictionDto> topicRestrictions
    ) {
        return new EnhancedChatConfigDto(
                chatConfig.getId(),
                channel.getChatId(), // Use channel object
                channel.getTitle(), // Use channel object
                chatConfig.getPromptTemplate(),
                chatConfig.isEnabled(),
                chatConfig.isMultiStageEnabled(),
                chatConfig.getDefaultSyncDepthDays(),
                chatConfig.getAutoSyncEnabled(),
                chatConfig.getLanguage(),
                chatConfig.getPrimaryChannelId(),
                chatConfig.getPrimaryChannelCheckedAt(),
                chatConfig.getContextWindowSize(),
                chatConfig.isRespondToForwardedBotMessages(),
                chatConfig.getWaitForHumanRepliesCount(),
                chatConfig.isSyncEnabled(),
                chatConfig.getMaxTokens(),
                chatConfig.getTemperature(),
                responseTemplates,
                triggerConditions,
                contextSettings,
                llmParameters,
                rateLimits,
                topicRestrictions
        );
    }

    // Factory method for basic config without related entities
    public static EnhancedChatConfigDto basicFromEntity(com.example.telegramuserbot.domain.ChatConfig chatConfig, Channel channel) {
        return new EnhancedChatConfigDto(
                chatConfig.getId(),
                channel.getChatId(), // Use channel object
                channel.getTitle(), // Use channel object
                chatConfig.getPromptTemplate(),
                chatConfig.isEnabled(),
                chatConfig.isMultiStageEnabled(),
                chatConfig.getDefaultSyncDepthDays(),
                chatConfig.getAutoSyncEnabled(),
                chatConfig.getLanguage(),
                chatConfig.getPrimaryChannelId(),
                chatConfig.getPrimaryChannelCheckedAt(),
                chatConfig.getContextWindowSize(),
                chatConfig.isRespondToForwardedBotMessages(),
                chatConfig.getWaitForHumanRepliesCount(),
                chatConfig.isSyncEnabled(),
                chatConfig.getMaxTokens(),
                chatConfig.getTemperature(),
                List.of(),
                List.of(),
                null,
                null,
                null,
                List.of()
        );
    }
}
