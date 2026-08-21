package com.example.telegramuserbot.service.config.initialization.template;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads linked chats configuration templates from YAML files in classpath.
 *
 * <p>Templates are located in {@code src/main/resources/templates/linked-chats/}
 * directory and loaded on first access. Once loaded, templates are cached in memory.
 *
 * <p>YAML files must follow the structure defined by {@link LinkedChatsTemplateYaml}.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * @Autowired
 * private LinkedChatsTemplateLoader loader;
 *
 * LinkedChatsTemplate template = loader.load("minimal-reaction");
 * }</pre>
 *
 * <h2>Available Templates</h2>
 * <ul>
 *   <li>{@code minimal-reaction.yaml} - Simple short reactions (Example style)</li>
 *   <li>{@code low-engagement-followup.yaml} - Neutral follow-up after human engagement</li>
 * </ul>
 */
@Component
public final class LinkedChatsTemplateLoader {

    private static final Logger log = LoggerFactory.getLogger(LinkedChatsTemplateLoader.class);
    private static final String TEMPLATES_BASE_PATH = "templates/";
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    private final Map<String, LinkedChatsTemplate> cache;
    private final Yaml yaml;

    public LinkedChatsTemplateLoader() {
        this.cache = new ConcurrentHashMap<>();
        this.yaml = new Yaml(new Constructor(LinkedChatsTemplateYaml.class, new org.yaml.snakeyaml.LoaderOptions()));
    }

    /**
     * Loads template by name from classpath.
     * Template file must be located at {@code templates/linked-chats/{templateName}.yaml}.
     *
     * @param templateName Template name without .yaml extension
     * @return Loaded template
     * @throws IllegalArgumentException if template file not found or invalid
     */
    public LinkedChatsTemplate load(String templateName) {
        return cache.computeIfAbsent(templateName, this::loadFromClasspath);
    }

    /**
     * Checks if template exists in classpath.
     *
     * @param templateName Template name without .yaml extension
     * @return true if template file exists
     */
    public boolean exists(String templateName) {
        String resourcePath = TEMPLATES_BASE_PATH + templateName + ".yaml";
        return new ClassPathResource(resourcePath).exists();
    }

    /**
     * Loads template from YAML file in classpath.
     */
    private LinkedChatsTemplate loadFromClasspath(String templateName) {
        String resourcePath = TEMPLATES_BASE_PATH + templateName + ".yaml";

        log.info("Loading linked chats template '{}' from classpath: {}", templateName, resourcePath);

        try {
            ClassPathResource resource = new ClassPathResource(resourcePath);

            if (!resource.exists()) {
                throw new IllegalArgumentException(
                        "Template file not found in classpath: " + resourcePath);
            }

            try (InputStream inputStream = resource.getInputStream()) {
                LinkedChatsTemplateYaml yamlTemplate = yaml.load(inputStream);

                LinkedChatsTemplate template = convertFromYaml(yamlTemplate, templateName, resourcePath);

                log.info("Successfully loaded template '{}' (yaml name: {}, description: {}, resource: {})",
                        templateName, yamlTemplate.templateName, yamlTemplate.description, resourcePath);

                return template;
            }

        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Failed to read template file: " + resourcePath, e);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Failed to parse template file: " + resourcePath, e);
        }
    }

    /**
     * Converts YAML representation to domain model.
     */
    private LinkedChatsTemplate convertFromYaml(LinkedChatsTemplateYaml yaml,
                                                String lookupName,
                                                String resourcePath) {
        String templateName = yaml.templateName != null ? yaml.templateName : lookupName;

        LinkedChatsTemplate.ChannelTemplate channel = new LinkedChatsTemplate.ChannelTemplate(
                yaml.channel.enabled,
                yaml.channel.maxDailyMessages,
                yaml.channel.contextWindowSize,
                yaml.channel.language,
                yaml.channel.autoSyncEnabled,
                yaml.channel.syncEnabled,
                convertSyncConfiguration(yaml.channel.syncConfiguration)
        );

        LinkedChatsTemplate.DiscussionTemplate discussion = new LinkedChatsTemplate.DiscussionTemplate(
                yaml.discussion.enabled,
                yaml.discussion.promptTemplate,
                yaml.discussion.maxTokens,
                yaml.discussion.temperature,
                yaml.discussion.maxDailyMessages,
                yaml.discussion.contextWindowSize,
                yaml.discussion.language,
                yaml.discussion.autoSyncEnabled,
                yaml.discussion.syncEnabled,
                yaml.discussion.respondToForwardedBotMessages,
                yaml.discussion.waitForHumanRepliesCount == null ? -1 : yaml.discussion.waitForHumanRepliesCount,
                convertContextSettings(yaml.discussion.contextSettings),
                convertLlmParameters(yaml.discussion.llmParameters),
                convertRateLimits(yaml.discussion.rateLimits),
                convertResponseTemplate(yaml.discussion.responseTemplate),
                convertTriggerCondition(yaml.discussion.triggerCondition),
                convertSyncConfiguration(yaml.discussion.syncConfiguration)
        );

        return new LinkedChatsTemplate(templateName, resourcePath, channel, discussion);
    }

    private LinkedChatsTemplate.ContextSettingsTemplate convertContextSettings(
            LinkedChatsTemplateYaml.ContextSettingsYaml yaml) {
        return new LinkedChatsTemplate.ContextSettingsTemplate(
                yaml.historyMessageCount,
                yaml.historyTimeWindowHours,
                yaml.includeUserContext,
                yaml.includeMediaDescriptions,
                yaml.contextCompressionEnabled,
                yaml.maxContextTokens,
                yaml.preserveImportantMessages
        );
    }

    private LinkedChatsTemplate.LlmParametersTemplate convertLlmParameters(
            LinkedChatsTemplateYaml.LlmParametersYaml yaml) {
        return new LinkedChatsTemplate.LlmParametersTemplate(
                yaml.modelName,
                yaml.temperature,
                yaml.maxTokens,
                yaml.topP,
                yaml.frequencyPenalty,
                yaml.presencePenalty,
                yaml.systemPrompt,
                yaml.customInstructions,
                yaml.responseFormat
        );
    }

    private LinkedChatsTemplate.RateLimitsTemplate convertRateLimits(
            LinkedChatsTemplateYaml.RateLimitsYaml yaml) {
        return new LinkedChatsTemplate.RateLimitsTemplate(
                yaml.maxMessagesPerMinute,
                yaml.maxMessagesPerHour,
                yaml.maxMessagesPerDay,
                yaml.maxTokensPerDay,
                yaml.cooldownAfterLimitMinutes,
                yaml.burstLimit,
                yaml.burstWindowSeconds,
                yaml.userSpecificLimits
        );
    }

    private LinkedChatsTemplate.ResponseTemplateConfig convertResponseTemplate(
            LinkedChatsTemplateYaml.ResponseTemplateYaml yaml) {
        return new LinkedChatsTemplate.ResponseTemplateConfig(
                yaml.templateName,
                yaml.templateContent,
                yaml.responseStyle,
                yaml.responseTone,
                yaml.maxResponseLength,
                yaml.isDefault,
                yaml.priority,
                yaml.active
        );
    }

    private LinkedChatsTemplate.TriggerConditionConfig convertTriggerCondition(
            LinkedChatsTemplateYaml.TriggerConditionYaml yaml) {
        LocalTime activeHoursStart = LocalTime.parse(yaml.activeHoursStart, TIME_FORMATTER);
        LocalTime activeHoursEnd = LocalTime.parse(yaml.activeHoursEnd, TIME_FORMATTER);

        return new LinkedChatsTemplate.TriggerConditionConfig(
                yaml.conditionName,
                yaml.triggerType,
                yaml.keywords,
                yaml.mentionRequired,
                yaml.timeDelaySeconds,
                yaml.probabilityPercent,
                activeHoursStart,
                activeHoursEnd,
                yaml.activeDaysOfWeek,
                yaml.minimumGapMinutes,
                yaml.priority,
                yaml.active,
                yaml.responseLength
        );
    }

    private LinkedChatsTemplate.SyncConfigurationTemplate convertSyncConfiguration(
            LinkedChatsTemplateYaml.SyncConfigurationYaml yaml) {
        if (yaml == null) {
            return null;
        }

        return new LinkedChatsTemplate.SyncConfigurationTemplate(
                yaml.defaultSyncDepthDays,
                yaml.maxSyncDepthDays,
                Boolean.TRUE.equals(yaml.autoSyncEnabled),
                yaml.autoSyncIntervalDays,
                yaml.maxConcurrentSyncs
        );
    }
}
