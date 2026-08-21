package com.example.telegramuserbot.dto.digest;

import com.example.telegramuserbot.domain.DigestPersona;
import com.example.telegramuserbot.domain.DigestPersonaStyle;

import java.time.Instant;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;

/**
 * Data transfer object for digest persona operations.
 * Used for create, update, and read API operations.
 *
 * @param id persona ID (null for create)
 * @param name persona name
 * @param description persona description
 * @param botId Telegram bot user ID
 * @param targetChannelId target channel for publishing
 * @param enabled whether persona is enabled
 * @param personaStyle persona style type
 * @param customSystemPrompt custom LLM prompt for CUSTOM style
 * @param scheduleCron cron expression for scheduling
 * @param scheduleTimezone timezone for schedule
 * @param activeHoursStart active hours start time
 * @param activeHoursEnd active hours end time
 * @param lookbackHours hours of messages to look back
 * @param maxMessages maximum messages to include
 * @param language digest language
 * @param minClusterSize minimum cluster size to include
 * @param minImportanceScore minimum importance score filter
 * @param sourceTrustThreshold minimum source trust score
 * @param excludedChannelIds channel IDs to exclude
 * @param topicKeywords keywords to include
 * @param negativeKeywords keywords to exclude
 * @param modelName LLM model name
 * @param temperature LLM temperature parameter
 * @param maxTokens LLM max tokens
 * @param lastRunAt last execution timestamp
 * @param totalDigestsPublished total published digests count
 * @param createdAt creation timestamp
 * @param updatedAt last update timestamp
 */
public record DigestPersonaDto(
        Long id,
        String name,
        String description,
        Long botId,
        Long targetChannelId,
        Boolean enabled,
        String personaStyle,
        String customSystemPrompt,
        String scheduleCron,
        String scheduleTimezone,
        String activeHoursStart,
        String activeHoursEnd,
        Integer lookbackHours,
        Integer maxMessages,
        String language,
        Integer minClusterSize,
        Double minImportanceScore,
        Double sourceTrustThreshold,
        List<Long> excludedChannelIds,
        List<String> topicKeywords,
        List<String> negativeKeywords,
        String modelName,
        Double temperature,
        Integer maxTokens,
        Instant lastRunAt,
        Integer totalDigestsPublished,
        Instant createdAt,
        Instant updatedAt
) {

    /**
     * Creates a DTO from a domain entity.
     *
     * @param entity the domain entity
     * @return the DTO
     */
    public static DigestPersonaDto from(DigestPersona entity) {
        return new DigestPersonaDto(
                entity.id(),
                entity.name(),
                entity.description(),
                entity.botId(),
                entity.targetChannelId(),
                entity.enabled(),
                entity.personaStyle(),
                entity.customSystemPrompt(),
                entity.scheduleCron(),
                entity.scheduleTimezone(),
                entity.activeHoursStart() != null ? entity.activeHoursStart().toString() : null,
                entity.activeHoursEnd() != null ? entity.activeHoursEnd().toString() : null,
                entity.lookbackHours(),
                entity.maxMessages(),
                entity.language(),
                entity.minClusterSize(),
                entity.minImportanceScore(),
                entity.sourceTrustThreshold(),
                entity.excludedChannelIds() != null ? Arrays.asList(entity.excludedChannelIds()) : List.of(),
                entity.topicKeywords() != null ? Arrays.asList(entity.topicKeywords()) : List.of(),
                entity.negativeKeywords() != null ? Arrays.asList(entity.negativeKeywords()) : List.of(),
                entity.modelName(),
                entity.temperature(),
                entity.maxTokens(),
                entity.lastRunAt(),
                entity.totalDigestsPublished(),
                entity.createdAt(),
                entity.updatedAt()
        );
    }

    /**
     * Converts DTO to domain entity for creation.
     *
     * @return domain entity
     */
    public DigestPersona toEntity() {
        DigestPersona entity = new DigestPersona();
        entity.setId(id);
        entity.setName(name);
        entity.setDescription(description);
        entity.setBotId(botId);
        entity.setTargetChannelId(targetChannelId);
        entity.setEnabled(enabled != null ? enabled : false);
        entity.setPersonaStyle(personaStyle != null ? personaStyle : DigestPersonaStyle.PROFESSIONAL.name());
        entity.setCustomSystemPrompt(customSystemPrompt);
        entity.setScheduleCron(scheduleCron);
        entity.setScheduleTimezone(scheduleTimezone != null ? scheduleTimezone : "UTC");
        if (activeHoursStart != null) {
            entity.setActiveHoursStart(LocalTime.parse(activeHoursStart));
        }
        if (activeHoursEnd != null) {
            entity.setActiveHoursEnd(LocalTime.parse(activeHoursEnd));
        }
        entity.setLookbackHours(lookbackHours != null ? lookbackHours : 24);
        entity.setMaxMessages(maxMessages != null ? maxMessages : 10);
        entity.setLanguage(language != null ? language : "en");
        entity.setMinClusterSize(minClusterSize != null ? minClusterSize : 2);
        entity.setMinImportanceScore(minImportanceScore != null ? minImportanceScore : 0.0);
        entity.setSourceTrustThreshold(sourceTrustThreshold != null ? sourceTrustThreshold : 0.0);
        if (excludedChannelIds != null && !excludedChannelIds.isEmpty()) {
            entity.setExcludedChannelIds(excludedChannelIds.toArray(new Long[0]));
        }
        if (topicKeywords != null && !topicKeywords.isEmpty()) {
            entity.setTopicKeywords(topicKeywords.toArray(new String[0]));
        }
        if (negativeKeywords != null && !negativeKeywords.isEmpty()) {
            entity.setNegativeKeywords(negativeKeywords.toArray(new String[0]));
        }
        entity.setModelName(modelName);
        entity.setTemperature(temperature != null ? temperature : 0.7);
        entity.setMaxTokens(maxTokens != null ? maxTokens : 1000);
        return entity;
    }

    /**
     * Creates a minimal DTO for creation with required fields only.
     *
     * @param name persona name
     * @param botId bot user ID
     * @param targetChannelId target channel ID
     * @return minimal DTO
     */
    public static DigestPersonaDto createMinimal(String name, Long botId, Long targetChannelId) {
        return new DigestPersonaDto(
                null,
                name,
                null,
                botId,
                targetChannelId,
                false,
                DigestPersonaStyle.PROFESSIONAL.name(),
                null,
                null,
                "UTC",
                null,
                null,
                24,
                10,
                "en",
                2,
                0.0,
                0.0,
                List.of(),
                List.of(),
                List.of(),
                null,
                0.7,
                1000,
                null,
                0,
                null,
                null
        );
    }
}
