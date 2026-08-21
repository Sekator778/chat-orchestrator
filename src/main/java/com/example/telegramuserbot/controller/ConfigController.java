package com.example.telegramuserbot.controller;

import com.example.telegramuserbot.config.ConditionalOnHttpEnabled;
import com.example.telegramuserbot.dto.*;
import com.example.telegramuserbot.service.config.ConfigurationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;


@RestController
@ConditionalOnHttpEnabled
@RequestMapping("/api/admin/config")
@Tag(name = "Configuration Management", description = "Enhanced chat configuration management endpoints")
public class ConfigController {
    private static final Logger uiLog = LoggerFactory.getLogger("frontend.ui");
    private final ConfigurationService configurationService;

    public ConfigController(ConfigurationService configurationService) {
        this.configurationService = configurationService;
    }

    // ===== COMPREHENSIVE CONFIGURATION ENDPOINTS =====

    @GetMapping("/channels/overview")
    @Operation(summary = "List channels with config status", description = "Returns channel metadata, config flags, and counters to drive admin UI lists")
    public Flux<ChannelOverviewDto> listChannelsOverview() {
        uiLog.info("UI:listChannelsOverview");
        return configurationService.listChannelOverview();
    }

    @GetMapping("/channels/{channelId}/enhanced")
    @Operation(summary = "Get enhanced configuration", description = "Get complete configuration with all related settings")
    public Mono<ResponseEntity<EnhancedChatConfigDto>> getEnhancedConfig(
            @Parameter(description = "Channel ID (original TDLib format)") @PathVariable Long channelId) {
        uiLog.info("UI:getEnhancedConfig channelId={}", channelId);
        // Using original TDLib channel ID directly - no normalization needed
        return configurationService.getEnhancedConfig(channelId)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build())
                .onErrorResume(e -> {
                    uiLog.warn("UI:getEnhancedConfig error channelId={}, msg={}", channelId, e.getMessage());
                    return Mono.just(ResponseEntity.notFound().build());
                });
    }

    @PutMapping("/channels/{channelId}/basic")
    @Operation(summary = "Update basic configuration", description = "Update basic chat configuration settings")
    public Mono<ResponseEntity<ChatConfigDto>> updateBasicConfig(
            @Parameter(description = "Channel ID (original TDLib format)") @PathVariable Long channelId,
            @RequestBody ChatConfigUpdateDto updateDto) {
        if (!updateDto.hasUpdates()) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
        // Using original TDLib channel ID directly
        uiLog.info("UI:updateBasicConfig channelId={} enabled={} language={} maxTokens={} temperature={}",
                channelId, updateDto.enabled(), updateDto.language(), updateDto.maxTokens(), updateDto.temperature());
        return configurationService.updateBasicConfig(channelId, updateDto)
                .map(ResponseEntity::ok);
    }

    @PutMapping("/channels/{channelId}/pending-response")
    @Operation(summary = "Update pending response configuration", description = "Update pending response settings (human replies + delay)")
    public Mono<ResponseEntity<PendingResponseConfigDto>> updatePendingResponseConfig(
            @Parameter(description = "Channel ID (original TDLib format)") @PathVariable Long channelId,
            @RequestBody PendingResponseConfigUpdateDto updateDto) {
        if (!updateDto.hasUpdates()) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
        uiLog.info("UI:updatePendingResponseConfig channelId={} waitForHumanReplies={} delaySeconds={}",
                channelId, updateDto.waitForHumanRepliesCount(), updateDto.pendingResponseDelaySeconds());
        return configurationService.updatePendingResponseConfig(channelId, updateDto)
                .map(ResponseEntity::ok);
    }

    // ===== RESPONSE TEMPLATES ENDPOINTS =====

    @GetMapping("/channels/{channelId}/templates")
    @Operation(summary = "List response templates", description = "Get all response templates for a channel")
    public Flux<ResponseTemplateDto> listResponseTemplates(
            @Parameter(description = "Channel ID (original TDLib format)") @PathVariable Long channelId) {
        return configurationService.getResponseTemplates(channelId);
    }

    @PostMapping("/channels/{channelId}/templates")
    @Operation(summary = "Create response template", description = "Create a new response template")
    public Mono<ResponseEntity<ResponseTemplateDto>> createResponseTemplate(
            @Parameter(description = "Channel ID (original TDLib format)") @PathVariable Long channelId,
            @RequestBody ResponseTemplateDto templateDto) {
        uiLog.info("UI:createResponseTemplate channelId={} name={} default={} active={}",
                channelId, templateDto.templateName(), templateDto.isDefault(), templateDto.active());
        return configurationService.createResponseTemplate(channelId, templateDto)
                .map(ResponseEntity::ok);
    }

    @PutMapping("/templates/{templateId}")
    @Operation(summary = "Update response template", description = "Update an existing response template")
    public Mono<ResponseEntity<ResponseTemplateDto>> updateResponseTemplate(
            @Parameter(description = "Template ID") @PathVariable Long templateId,
            @RequestBody ResponseTemplateDto templateDto) {
        uiLog.info("UI:updateResponseTemplate templateId={} default={} active={}",
                templateId, templateDto.isDefault(), templateDto.active());
        return configurationService.updateResponseTemplate(templateId, templateDto)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/templates/{templateId}")
    @Operation(summary = "Delete response template", description = "Delete a response template")
    public Mono<ResponseEntity<Void>> deleteResponseTemplate(
            @Parameter(description = "Template ID") @PathVariable Long templateId) {
        uiLog.info("UI:deleteResponseTemplate templateId={}", templateId);
        return configurationService.deleteResponseTemplate(templateId)
                .then(Mono.just(ResponseEntity.ok().<Void>build()));
    }

    @PostMapping("/templates/{templateId}/set-default")
    @Operation(summary = "Set default template", description = "Set a template as the default for its chat")
    public Mono<ResponseEntity<ResponseTemplateDto>> setDefaultTemplate(
            @Parameter(description = "Template ID") @PathVariable Long templateId) {
        uiLog.info("UI:setDefaultTemplate templateId={}", templateId);
        return configurationService.setDefaultTemplate(templateId)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    // ===== TRIGGER CONDITIONS ENDPOINTS =====

    @GetMapping("/channels/{channelId}/triggers")
    @Operation(summary = "List trigger conditions", description = "Get all trigger conditions for a channel")
    public Flux<TriggerConditionDto> listTriggerConditions(
            @Parameter(description = "Channel ID") @PathVariable Long channelId) {
        return configurationService.getTriggerConditions(canonical(channelId));
    }

    @PostMapping("/channels/{channelId}/triggers")
    @Operation(summary = "Create trigger condition", description = "Create a new trigger condition")
    public Mono<ResponseEntity<TriggerConditionDto>> createTriggerCondition(
            @Parameter(description = "Channel ID") @PathVariable Long channelId,
            @RequestBody TriggerConditionDto conditionDto) {
        uiLog.info("UI:createTriggerCondition channelId={} name={} type={} active={}",
                channelId, conditionDto.conditionName(), conditionDto.triggerType(), conditionDto.active());
        return configurationService.createTriggerCondition(canonical(channelId), conditionDto)
                .map(ResponseEntity::ok);
    }

    @PutMapping("/triggers/{triggerId}")
    @Operation(summary = "Update trigger condition", description = "Update an existing trigger condition")
    public Mono<ResponseEntity<TriggerConditionDto>> updateTriggerCondition(
            @Parameter(description = "Trigger ID") @PathVariable Long triggerId,
            @RequestBody TriggerConditionDto conditionDto) {
        uiLog.info("UI:updateTriggerCondition triggerId={} name={} type={} active={}",
                triggerId, conditionDto.conditionName(), conditionDto.triggerType(), conditionDto.active());
        return configurationService.updateTriggerCondition(triggerId, conditionDto)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/triggers/{triggerId}")
    @Operation(summary = "Delete trigger condition", description = "Delete a trigger condition")
    public Mono<ResponseEntity<Void>> deleteTriggerCondition(
            @Parameter(description = "Trigger ID") @PathVariable Long triggerId) {
        uiLog.info("UI:deleteTriggerCondition triggerId={}", triggerId);
        return configurationService.deleteTriggerCondition(triggerId)
                .then(Mono.just(ResponseEntity.ok().<Void>build()));
    }

    @PostMapping("/triggers/{triggerId}/toggle")
    @Operation(summary = "Toggle trigger condition", description = "Enable or disable a trigger condition")
    public Mono<ResponseEntity<TriggerConditionDto>> toggleTriggerCondition(
            @Parameter(description = "Trigger ID") @PathVariable Long triggerId) {
        uiLog.info("UI:toggleTriggerCondition triggerId={}", triggerId);
        return configurationService.toggleTriggerCondition(triggerId)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    // ===== CONTEXT SETTINGS ENDPOINTS =====

    @GetMapping("/channels/{channelId}/context")
    @Operation(summary = "Get context settings", description = "Get context settings for a channel")
    public Mono<ResponseEntity<ContextSettingsDto>> getContextSettings(
            @Parameter(description = "Channel ID") @PathVariable Long channelId) {
        return configurationService.getContextSettings(canonical(channelId))
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @PutMapping("/channels/{channelId}/context")
    @Operation(summary = "Update context settings", description = "Update context settings for a channel")
    public Mono<ResponseEntity<ContextSettingsDto>> updateContextSettings(
            @Parameter(description = "Channel ID") @PathVariable Long channelId,
            @RequestBody ContextSettingsDto settingsDto) {
        uiLog.info("UI:updateContextSettings channelId={} historyMessages={} historyHours={}",
                channelId, settingsDto.historyMessageCount(), settingsDto.historyTimeWindowHours());
        return configurationService.updateContextSettings(canonical(channelId), settingsDto)
                .map(ResponseEntity::ok);
    }

    // ===== LLM PARAMETERS ENDPOINTS =====

    @GetMapping("/channels/{channelId}/llm-params")
    @Operation(summary = "Get LLM parameters", description = "Get LLM parameters for a channel")
    public Mono<ResponseEntity<LlmParametersDto>> getLlmParameters(
            @Parameter(description = "Channel ID") @PathVariable Long channelId) {
        return configurationService.getLlmParameters(canonical(channelId))
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @PutMapping("/channels/{channelId}/llm-params")
    @Operation(summary = "Update LLM parameters", description = "Update LLM parameters for a channel")
    public Mono<ResponseEntity<LlmParametersDto>> updateLlmParameters(
            @Parameter(description = "Channel ID") @PathVariable Long channelId,
            @RequestBody LlmParametersDto paramsDto) {
        uiLog.info("UI:updateLlmParameters channelId={} model={} temperature={} maxTokens={}",
                channelId, paramsDto.modelName(), paramsDto.temperature(), paramsDto.maxTokens());
        return configurationService.updateLlmParameters(canonical(channelId), paramsDto)
                .map(ResponseEntity::ok);
    }

    // ===== RATE LIMITS ENDPOINTS =====

    @GetMapping("/channels/{channelId}/rate-limits")
    @Operation(summary = "Get rate limits", description = "Get rate limits for a channel")
    public Mono<ResponseEntity<RateLimitsDto>> getRateLimits(
            @Parameter(description = "Channel ID") @PathVariable Long channelId) {
        return configurationService.getRateLimits(canonical(channelId))
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @PutMapping("/channels/{channelId}/rate-limits")
    @Operation(summary = "Update rate limits", description = "Update rate limits for a channel")
    public Mono<ResponseEntity<RateLimitsDto>> updateRateLimits(
            @Parameter(description = "Channel ID") @PathVariable Long channelId,
            @RequestBody RateLimitsDto limitsDto) {
        uiLog.info("UI:updateRateLimits channelId={} perHour={} perDay={} tokensPerDay={}",
                channelId, limitsDto.maxMessagesPerHour(), limitsDto.maxMessagesPerDay(), limitsDto.maxTokensPerDay());
        return configurationService.updateRateLimits(canonical(channelId), limitsDto)
                .map(ResponseEntity::ok);
    }

    @PostMapping("/channels/{channelId}/rate-limits/reset")
    @Operation(summary = "Reset rate limits", description = "Reset current rate limit counters for a channel")
    public Mono<ResponseEntity<Void>> resetRateLimits(
            @Parameter(description = "Channel ID") @PathVariable Long channelId) {
        uiLog.info("UI:resetRateLimits channelId={}", channelId);
        return configurationService.resetRateLimits(canonical(channelId))
                .then(Mono.just(ResponseEntity.ok().<Void>build()));
    }

    // ===== TOPIC RESTRICTIONS ENDPOINTS =====

    @GetMapping("/channels/{channelId}/restrictions")
    @Operation(summary = "List topic restrictions", description = "Get all topic restrictions for a channel")
    public Flux<TopicRestrictionDto> listTopicRestrictions(
            @Parameter(description = "Channel ID (original TDLib format)") @PathVariable Long channelId) {
        return configurationService.getTopicRestrictions(channelId);
    }

    @PostMapping("/channels/{channelId}/restrictions")
    @Operation(summary = "Create topic restriction", description = "Create a new topic restriction")
    public Mono<ResponseEntity<TopicRestrictionDto>> createTopicRestriction(
            @Parameter(description = "Channel ID") @PathVariable Long channelId,
            @RequestBody TopicRestrictionDto restrictionDto) {
        uiLog.info("UI:createTopicRestriction channelId={} name={} type={} action={} active={}",
                channelId, restrictionDto.restrictionName(), restrictionDto.restrictionType(), restrictionDto.actionType(), restrictionDto.active());
        return configurationService.createTopicRestriction(canonical(channelId), restrictionDto)
                .map(ResponseEntity::ok);
    }

    @PutMapping("/restrictions/{restrictionId}")
    @Operation(summary = "Update topic restriction", description = "Update an existing topic restriction")
    public Mono<ResponseEntity<TopicRestrictionDto>> updateTopicRestriction(
            @Parameter(description = "Restriction ID") @PathVariable Long restrictionId,
            @RequestBody TopicRestrictionDto restrictionDto) {
        uiLog.info("UI:updateTopicRestriction restrictionId={} name={} type={} action={} active={}",
                restrictionId, restrictionDto.restrictionName(), restrictionDto.restrictionType(), restrictionDto.actionType(), restrictionDto.active());
        return configurationService.updateTopicRestriction(restrictionId, restrictionDto)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/restrictions/{restrictionId}")
    @Operation(summary = "Delete topic restriction", description = "Delete a topic restriction")
    public Mono<ResponseEntity<Void>> deleteTopicRestriction(
            @Parameter(description = "Restriction ID") @PathVariable Long restrictionId) {
        uiLog.info("UI:deleteTopicRestriction restrictionId={}", restrictionId);
        return configurationService.deleteTopicRestriction(restrictionId)
                .then(Mono.just(ResponseEntity.ok().<Void>build()));
    }

    @PostMapping("/restrictions/{restrictionId}/toggle")
    @Operation(summary = "Toggle topic restriction", description = "Enable or disable a topic restriction")
    public Mono<ResponseEntity<TopicRestrictionDto>> toggleTopicRestriction(
            @Parameter(description = "Restriction ID") @PathVariable Long restrictionId) {
        uiLog.info("UI:toggleTopicRestriction restrictionId={}", restrictionId);
        return configurationService.toggleTopicRestriction(restrictionId)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    // ===== BULK OPERATIONS ENDPOINTS =====

    @PostMapping("/channels/{channelId}/copy-from/{sourceChannelId}")
    @Operation(summary = "Copy configuration", description = "Copy configuration from another channel")
    public Mono<ResponseEntity<EnhancedChatConfigDto>> copyConfiguration(
            @Parameter(description = "Target Channel ID") @PathVariable Long channelId,
            @Parameter(description = "Source Channel ID") @PathVariable Long sourceChannelId) {
        return configurationService.copyConfiguration(canonical(sourceChannelId), canonical(channelId))
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.badRequest().build());
    }

    @PostMapping("/channels/{channelId}/reset-to-defaults")
    @Operation(summary = "Reset configuration", description = "Reset configuration to default values")
    public Mono<ResponseEntity<EnhancedChatConfigDto>> resetToDefaults(
            @Parameter(description = "Channel ID") @PathVariable Long channelId) {
        return configurationService.resetToDefaults(canonical(channelId))
                .map(ResponseEntity::ok);
    }

    @GetMapping("/channels/{channelId}/export")
    @Operation(summary = "Export configuration", description = "Export configuration as JSON for backup")
    public Mono<ResponseEntity<EnhancedChatConfigDto>> exportConfiguration(
            @Parameter(description = "Channel ID") @PathVariable Long channelId) {
        return configurationService.getEnhancedConfig(canonical(channelId))
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @PostMapping("/channels/{channelId}/import")
    @Operation(summary = "Import configuration", description = "Import configuration from JSON backup")
    public Mono<ResponseEntity<EnhancedChatConfigDto>> importConfiguration(
            @Parameter(description = "Channel ID") @PathVariable Long channelId,
            @RequestBody EnhancedChatConfigDto configDto) {
        return configurationService.importConfiguration(canonical(channelId), configDto)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.badRequest().build());
    }

    // Using original TDLib channel IDs directly - no normalization needed
    private Long canonical(Long channelId) {
        return channelId;
    }
}
