package com.example.telegramuserbot.service.config;

import com.example.telegramuserbot.dto.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Service interface for managing comprehensive chat configurations in a reactive way.
 */
public interface ConfigurationService {

    // ===== COMPREHENSIVE CONFIGURATION =====

    Mono<EnhancedChatConfigDto> getEnhancedConfig(Long channelId);
    Flux<ChannelOverviewDto> listChannelOverview();
    Mono<ChatConfigDto> updateBasicConfig(Long channelId, ChatConfigUpdateDto updateDto);
    Mono<PendingResponseConfigDto> updatePendingResponseConfig(Long channelId, PendingResponseConfigUpdateDto updateDto);

    // ===== RESPONSE TEMPLATES =====

    Flux<ResponseTemplateDto> getResponseTemplates(Long channelId);
    Mono<ResponseTemplateDto> createResponseTemplate(Long channelId, ResponseTemplateDto templateDto);
    Mono<ResponseTemplateDto> updateResponseTemplate(Long templateId, ResponseTemplateDto templateDto);
    Mono<Void> deleteResponseTemplate(Long templateId);
    Mono<ResponseTemplateDto> setDefaultTemplate(Long templateId);

    // ===== TRIGGER CONDITIONS =====

    Flux<TriggerConditionDto> getTriggerConditions(Long channelId);
    Mono<TriggerConditionDto> createTriggerCondition(Long channelId, TriggerConditionDto conditionDto);
    Mono<TriggerConditionDto> updateTriggerCondition(Long triggerId, TriggerConditionDto conditionDto);
    Mono<Void> deleteTriggerCondition(Long triggerId);
    Mono<TriggerConditionDto> toggleTriggerCondition(Long triggerId);

    // ===== CONTEXT SETTINGS =====

    Mono<ContextSettingsDto> getContextSettings(Long channelId);
    Mono<ContextSettingsDto> updateContextSettings(Long channelId, ContextSettingsDto settingsDto);

    // ===== LLM PARAMETERS =====

    Mono<LlmParametersDto> getLlmParameters(Long channelId);
    Mono<LlmParametersDto> updateLlmParameters(Long channelId, LlmParametersDto paramsDto);

    // ===== RATE LIMITS =====

    Mono<RateLimitsDto> getRateLimits(Long channelId);
    Mono<RateLimitsDto> updateRateLimits(Long channelId, RateLimitsDto limitsDto);
    Mono<Void> resetRateLimits(Long channelId);

    // ===== TOPIC RESTRICTIONS =====

    Flux<TopicRestrictionDto> getTopicRestrictions(Long channelId);
    Mono<TopicRestrictionDto> createTopicRestriction(Long channelId, TopicRestrictionDto restrictionDto);
    Mono<TopicRestrictionDto> updateTopicRestriction(Long restrictionId, TopicRestrictionDto restrictionDto);
    Mono<Void> deleteTopicRestriction(Long restrictionId);
    Mono<TopicRestrictionDto> toggleTopicRestriction(Long restrictionId);

    // ===== BULK OPERATIONS =====

    Mono<EnhancedChatConfigDto> copyConfiguration(Long sourceChannelId, Long targetChannelId);
    Mono<EnhancedChatConfigDto> resetToDefaults(Long channelId);
    Mono<EnhancedChatConfigDto> importConfiguration(Long channelId, EnhancedChatConfigDto configDto);

    // ===== UTILITY METHODS =====

    Mono<EnhancedChatConfigDto> initializeDefaultConfig(Long channelId);
    Mono<Boolean> shouldTriggerResponse(Long channelId, String messageText, boolean isMention, Long userId);
    Mono<ResponseTemplateDto> getResponseTemplateForMessage(Long channelId, String messageText, String context);
    Mono<Boolean> isTopicRestricted(Long channelId, String messageText);
    Mono<Boolean> isRateLimited(Long channelId);
}
