package com.example.telegramuserbot.dto;

import com.example.telegramuserbot.domain.ActionType;
import com.example.telegramuserbot.domain.RestrictionType;
import com.fasterxml.jackson.annotation.JsonProperty;

public record TopicRestrictionDto(
        Long id,
        @JsonProperty("chat_config_id") Long chatConfigId,
        @JsonProperty("restriction_name") String restrictionName,
        @JsonProperty("restriction_type") RestrictionType restrictionType,
        String keywords,
        String categories,
        @JsonProperty("action_type") ActionType actionType,
        @JsonProperty("custom_response") String customResponse,
        boolean active
) {
    // Factory method to create DTO from entity
    public static TopicRestrictionDto fromEntity(com.example.telegramuserbot.domain.TopicRestriction restriction) {
        return new TopicRestrictionDto(
                restriction.getId(),
                restriction.getChatConfigId(),
                restriction.getRestrictionName(),
                restriction.getRestrictionType(),
                restriction.getKeywords(),
                restriction.getCategories(),
                restriction.getActionType(),
                restriction.getCustomResponse(),
                restriction.isActive()
        );
    }

    // Factory method for creation requests (without ID)
    public static TopicRestrictionDto forCreation(
            Long chatConfigId,
            String restrictionName,
            RestrictionType restrictionType,
            String keywords,
            ActionType actionType
    ) {
        return new TopicRestrictionDto(
                null, chatConfigId, restrictionName, restrictionType,
                keywords, null, actionType, null, true
        );
    }
}