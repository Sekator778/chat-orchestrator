package com.example.telegramuserbot.service.validation;

import com.example.telegramuserbot.domain.DigestPersona;
import com.example.telegramuserbot.dto.EnhancedChatConfigDto;
import com.example.telegramuserbot.dto.validation.*;
import com.example.telegramuserbot.service.config.ConfigurationService;
import com.example.telegramuserbot.service.digest.DigestPersonaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;

/**
 * Implementation of ConfigValidationService that validates chat configurations,
 * LLM parameters, rate limits, triggers, and digest personas.
 */
@Service
public final class ConfigValidationServiceImpl implements ConfigValidationService {

    private static final Logger log = LoggerFactory.getLogger(ConfigValidationServiceImpl.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final ConfigurationService configurationService;
    private final DigestPersonaService digestPersonaService;

    public ConfigValidationServiceImpl(
            ConfigurationService configurationService,
            DigestPersonaService digestPersonaService
    ) {
        this.configurationService = configurationService;
        this.digestPersonaService = digestPersonaService;
    }

    @Override
    public Mono<ConfigValidationResponseDto> validate(ConfigValidationRequestDto request) {
        log.info("Validating configuration for {} channels", request.channelIds().size());
        Flux<Map.Entry<String, EntityValidationResultDto>> channelValidations = Flux.fromIterable(request.channelIds())
                .flatMap(this::validateChannelWithDetails)
                .timeout(TIMEOUT);
        Flux<Map.Entry<String, EntityValidationResultDto>> digestValidations;
        if (request.includeDigestPersonas()) {
            digestValidations = digestPersonaService.findAll()
                    .flatMap(persona -> validateDigestPersonaEntity(persona)
                            .map(result -> Map.entry("digestPersona-" + persona.id(), result)))
                    .timeout(TIMEOUT);
        } else {
            digestValidations = Flux.empty();
        }
        return Flux.concat(channelValidations, digestValidations)
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .map(ConfigValidationResponseDto::fromResults);
    }

    @Override
    public Mono<EntityValidationResultDto> validateChannel(Long channelId) {
        return configurationService.getEnhancedConfig(channelId)
                .map(config -> validateChatConfig(channelId, config))
                .defaultIfEmpty(EntityValidationResultDto.fromIssues(
                        "chatConfig-" + channelId,
                        "chatConfig",
                        List.of(ValidationIssueDto.missing(
                                "chatConfig",
                                null,
                                "Configuration not found for channel " + channelId,
                                "Initialize configuration for this channel"
                        ))
                ))
                .timeout(TIMEOUT);
    }

    @Override
    public Mono<EntityValidationResultDto> validateDigestPersona(Long personaId) {
        return digestPersonaService.findById(personaId)
                .map(this::validateDigestPersonaEntityDirect)
                .defaultIfEmpty(EntityValidationResultDto.fromIssues(
                        "digestPersona-" + personaId,
                        "digestPersona",
                        List.of(ValidationIssueDto.missing(
                                "digestPersona",
                                null,
                                "Digest persona not found with ID " + personaId,
                                "Create a new digest persona"
                        ))
                ))
                .timeout(TIMEOUT);
    }

    private Flux<Map.Entry<String, EntityValidationResultDto>> validateChannelWithDetails(Long channelId) {
        return configurationService.getEnhancedConfig(channelId)
                .flatMapMany(config -> {
                    List<Map.Entry<String, EntityValidationResultDto>> results = new ArrayList<>();
                    results.add(Map.entry("chatConfig-" + channelId, validateChatConfig(channelId, config)));
                    if (config.llmParameters() != null) {
                        results.add(Map.entry("llmParams-" + channelId, validateLlmParams(channelId, config)));
                    }
                    if (config.rateLimits() != null) {
                        results.add(Map.entry("rateLimits-" + channelId, validateRateLimits(channelId, config)));
                    }
                    if (config.contextSettings() != null) {
                        results.add(Map.entry("contextSettings-" + channelId, validateContextSettings(channelId, config)));
                    }
                    return Flux.fromIterable(results);
                })
                .switchIfEmpty(Flux.just(Map.entry(
                        "chatConfig-" + channelId,
                        EntityValidationResultDto.fromIssues(
                                "chatConfig-" + channelId,
                                "chatConfig",
                                List.of(ValidationIssueDto.missing(
                                        "chatConfig",
                                        null,
                                        "Configuration not found",
                                        "Initialize configuration for this channel"
                                ))
                        )
                )));
    }

    private EntityValidationResultDto validateChatConfig(Long channelId, EnhancedChatConfigDto config) {
        List<ValidationIssueDto> issues = new ArrayList<>();
        String entityId = "chatConfig-" + channelId;
        if (config.enabled()) {
            if (config.llmParameters() == null) {
                issues.add(ValidationIssueDto.missing(
                        "chatConfig",
                        "llm_parameters",
                        "LLM parameters not configured",
                        "Configure LLM parameters to enable AI responses"
                ));
            }
            if (config.promptTemplate() == null || config.promptTemplate().isBlank()) {
                issues.add(ValidationIssueDto.incomplete(
                        "chatConfig",
                        "prompt_template",
                        "No prompt template set",
                        "Add a prompt template for better response quality"
                ));
            }
            if (config.triggerConditions() == null || config.triggerConditions().isEmpty()) {
                issues.add(ValidationIssueDto.incomplete(
                        "chatConfig",
                        "trigger_conditions",
                        "No trigger conditions defined",
                        "Add triggers to define when the bot should respond"
                ));
            }
        }
        if (config.language() == null || config.language().isBlank()) {
            issues.add(ValidationIssueDto.suggestion(
                    "chatConfig",
                    "language",
                    "Language not set",
                    "Set language for better response localization"
            ));
        }
        return EntityValidationResultDto.fromIssues(entityId, "chatConfig", issues);
    }

    private EntityValidationResultDto validateLlmParams(Long channelId, EnhancedChatConfigDto config) {
        List<ValidationIssueDto> issues = new ArrayList<>();
        String entityId = "llmParams-" + channelId;
        var params = config.llmParameters();
        if (params == null) {
            issues.add(ValidationIssueDto.missing(
                    "llmParams",
                    null,
                    "LLM parameters not configured",
                    "Configure LLM parameters to enable AI responses"
            ));
            return EntityValidationResultDto.fromIssues(entityId, "llmParams", issues);
        }
        if (params.modelName() == null || params.modelName().isBlank()) {
            issues.add(ValidationIssueDto.missing(
                    "llmParams",
                    "model_name",
                    "Model name not specified",
                    "Select an LLM model (e.g., deepseek-chat)"
            ));
        }
        if (params.temperature() == null) {
            issues.add(ValidationIssueDto.suggestion(
                    "llmParams",
                    "temperature",
                    "Temperature not set (using default)",
                    "Set temperature to control response creativity"
            ));
        } else if (params.temperature() < 0 || params.temperature() > 2) {
            issues.add(ValidationIssueDto.warning(
                    "llmParams",
                    "temperature",
                    "Temperature out of recommended range (0-2)",
                    "Use temperature between 0 and 2 for best results"
            ));
        }
        if (params.maxTokens() == null) {
            issues.add(ValidationIssueDto.suggestion(
                    "llmParams",
                    "max_tokens",
                    "Max tokens not set (using default)",
                    "Set max tokens to control response length"
            ));
        }
        return EntityValidationResultDto.fromIssues(entityId, "llmParams", issues);
    }

    private EntityValidationResultDto validateRateLimits(Long channelId, EnhancedChatConfigDto config) {
        List<ValidationIssueDto> issues = new ArrayList<>();
        String entityId = "rateLimits-" + channelId;
        var limits = config.rateLimits();
        if (limits == null) {
            issues.add(ValidationIssueDto.suggestion(
                    "rateLimits",
                    null,
                    "Rate limits not configured",
                    "Configure rate limits to prevent spam and manage costs"
            ));
            return EntityValidationResultDto.fromIssues(entityId, "rateLimits", issues);
        }
        boolean hasAnyLimit = limits.maxMessagesPerHour() != null ||
                limits.maxMessagesPerDay() != null ||
                limits.maxTokensPerDay() != null;
        if (!hasAnyLimit) {
            issues.add(ValidationIssueDto.incomplete(
                    "rateLimits",
                    null,
                    "No rate limits defined",
                    "Set at least one rate limit to control usage"
            ));
        }
        if (limits.cooldownAfterLimitMinutes() != null && limits.cooldownAfterLimitMinutes() < 0) {
            issues.add(ValidationIssueDto.warning(
                    "rateLimits",
                    "cooldown_after_limit_minutes",
                    "Invalid cooldown value",
                    "Cooldown must be a positive number"
            ));
        }
        return EntityValidationResultDto.fromIssues(entityId, "rateLimits", issues);
    }

    private EntityValidationResultDto validateContextSettings(Long channelId, EnhancedChatConfigDto config) {
        List<ValidationIssueDto> issues = new ArrayList<>();
        String entityId = "contextSettings-" + channelId;
        var settings = config.contextSettings();
        if (settings == null) {
            issues.add(ValidationIssueDto.suggestion(
                    "contextSettings",
                    null,
                    "Context settings not configured",
                    "Configure context settings to control conversation memory"
            ));
            return EntityValidationResultDto.fromIssues(entityId, "contextSettings", issues);
        }
        boolean noHistoryLimits = settings.historyMessageCount() == null &&
                settings.historyTimeWindowHours() == null;
        if (noHistoryLimits) {
            issues.add(ValidationIssueDto.incomplete(
                    "contextSettings",
                    null,
                    "No history limits defined",
                    "Set message count or time window to limit context size"
            ));
        }
        if (settings.historyMessageCount() != null && settings.historyMessageCount() > 100) {
            issues.add(ValidationIssueDto.warning(
                    "contextSettings",
                    "history_message_count",
                    "Large history may increase token usage",
                    "Consider limiting to 50 messages or less for efficiency"
            ));
        }
        return EntityValidationResultDto.fromIssues(entityId, "contextSettings", issues);
    }

    private Mono<EntityValidationResultDto> validateDigestPersonaEntity(DigestPersona persona) {
        return Mono.just(validateDigestPersonaEntityDirect(persona));
    }

    private EntityValidationResultDto validateDigestPersonaEntityDirect(DigestPersona persona) {
        List<ValidationIssueDto> issues = new ArrayList<>();
        String entityId = "digestPersona-" + persona.id();
        if (persona.enabled()) {
            if (persona.scheduleCron() == null || persona.scheduleCron().isBlank()) {
                issues.add(ValidationIssueDto.missing(
                        "digestPersona",
                        "scheduleCron",
                        "Schedule not configured",
                        "Set a cron schedule for digest generation"
                ));
            }
            if (persona.targetChannelId() == null) {
                issues.add(ValidationIssueDto.missing(
                        "digestPersona",
                        "targetChannelId",
                        "Target channel not set",
                        "Select a channel to publish digests to"
                ));
            }
            if (persona.botId() == null) {
                issues.add(ValidationIssueDto.missing(
                        "digestPersona",
                        "botId",
                        "Bot ID not set",
                        "Configure bot ID for digest publishing"
                ));
            }
            if (persona.personaStyle() == null || persona.personaStyle().isBlank()) {
                issues.add(ValidationIssueDto.incomplete(
                        "digestPersona",
                        "personaStyle",
                        "Persona style not selected",
                        "Choose a style for digest presentation"
                ));
            }
        }
        if (persona.name() == null || persona.name().isBlank()) {
            issues.add(ValidationIssueDto.missing(
                    "digestPersona",
                    "name",
                    "Persona name not set",
                    "Provide a name for this digest persona"
            ));
        }
        return EntityValidationResultDto.fromIssues(entityId, "digestPersona", issues);
    }
}
