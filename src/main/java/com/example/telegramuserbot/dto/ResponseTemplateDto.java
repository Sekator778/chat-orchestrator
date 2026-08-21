package com.example.telegramuserbot.dto;

import com.example.telegramuserbot.domain.ResponseStyle;
import com.example.telegramuserbot.domain.ResponseTone;
import com.fasterxml.jackson.annotation.JsonProperty;

public record ResponseTemplateDto(
        Long id,
        @JsonProperty("chat_config_id") Long chatConfigId,
        @JsonProperty("template_name") String templateName,
        @JsonProperty("template_content") String templateContent,
        @JsonProperty("response_style") ResponseStyle responseStyle,
        @JsonProperty("response_tone") ResponseTone responseTone,
        @JsonProperty("max_response_length") Integer maxResponseLength,
        @JsonProperty("is_default") boolean isDefault,
        Integer priority,
        boolean active
) {
    // Factory method to create DTO from entity
    public static ResponseTemplateDto fromEntity(com.example.telegramuserbot.domain.ResponseTemplate template) {
        return new ResponseTemplateDto(
                template.getId(),
                template.getChatConfigId(),
                template.getTemplateName(),
                template.getTemplateContent(),
                template.getResponseStyle(),
                template.getResponseTone(),
                template.getMaxResponseLength(),
                template.isDefault(),
                template.getPriority(),
                template.isActive()
        );
    }

    // Factory method for creation requests (without ID)
    public static ResponseTemplateDto forCreation(
            Long chatConfigId,
            String templateName,
            String templateContent,
            ResponseStyle responseStyle,
            ResponseTone responseTone,
            Integer maxResponseLength,
            boolean isDefault,
            Integer priority
    ) {
        return new ResponseTemplateDto(
                null, chatConfigId, templateName, templateContent,
                responseStyle, responseTone, maxResponseLength,
                isDefault, priority, true
        );
    }
}