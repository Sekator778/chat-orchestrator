package com.example.telegramuserbot.dto;

import com.example.telegramuserbot.domain.TriggerType;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalTime;

public record TriggerConditionDto(
        Long id,
        @JsonProperty("chat_config_id") Long chatConfigId,
        @JsonProperty("condition_name") String conditionName,
        @JsonProperty("trigger_type") TriggerType triggerType,
        String keywords,
        @JsonProperty("mention_required") boolean mentionRequired,
        @JsonProperty("time_delay_seconds") Integer timeDelaySeconds,
        @JsonProperty("probability_percent") Integer probabilityPercent,
        @JsonProperty("active_hours_start") LocalTime activeHoursStart,
        @JsonProperty("active_hours_end") LocalTime activeHoursEnd,
        @JsonProperty("active_days_of_week") String activeDaysOfWeek,
        @JsonProperty("minimum_gap_minutes") Integer minimumGapMinutes,
        Integer priority,
        boolean active
) {
    // Factory method to create DTO from entity
    public static TriggerConditionDto fromEntity(com.example.telegramuserbot.domain.TriggerCondition condition) {
        return new TriggerConditionDto(
                condition.getId(),
                condition.getChatConfigId(),
                condition.getConditionName(),
                condition.getTriggerType(),
                condition.getKeywords(),
                condition.isMentionRequired(),
                condition.getTimeDelaySeconds(),
                condition.getProbabilityPercent(),
                condition.getActiveHoursStart(),
                condition.getActiveHoursEnd(),
                condition.getActiveDaysOfWeek(),
                condition.getMinimumGapMinutes(),
                condition.getPriority(),
                condition.isActive()
        );
    }

    // Factory method for creation requests (without ID)
    public static TriggerConditionDto forCreation(
            Long chatConfigId,
            String conditionName,
            TriggerType triggerType,
            String keywords,
            boolean mentionRequired,
            Integer timeDelaySeconds,
            Integer probabilityPercent
    ) {
        return new TriggerConditionDto(
                null, chatConfigId, conditionName, triggerType, keywords,
                mentionRequired, timeDelaySeconds, probabilityPercent,
                null, null, "1,2,3,4,5,6,7", 0, 1, true
        );
    }
}