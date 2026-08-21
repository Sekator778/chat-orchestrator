package com.example.telegramuserbot.dto;

import com.example.telegramuserbot.domain.ResponseFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

public record LlmParametersDto(
        Long id,
        @JsonProperty("chat_config_id") Long chatConfigId,
        @JsonProperty("model_name") String modelName,
        Double temperature,
        @JsonProperty("max_tokens") Integer maxTokens,
        @JsonProperty("top_p") Double topP,
        @JsonProperty("frequency_penalty") Double frequencyPenalty,
        @JsonProperty("presence_penalty") Double presencePenalty,
        @JsonProperty("system_prompt") String systemPrompt,
        @JsonProperty("custom_instructions") String customInstructions,
        @JsonProperty("response_format") ResponseFormat responseFormat
) {
    // Factory method to create DTO from entity
    public static LlmParametersDto fromEntity(com.example.telegramuserbot.domain.LlmParameters parameters) {
        return new LlmParametersDto(
                parameters.getId(),
                parameters.getChatConfigId(),
                parameters.getModelName(),
                parameters.getTemperature(),
                parameters.getMaxTokens(),
                parameters.getTopP(),
                parameters.getFrequencyPenalty(),
                parameters.getPresencePenalty(),
                parameters.getSystemPrompt(),
                parameters.getCustomInstructions(),
                parameters.getResponseFormat()
        );
    }

    // Factory method for creation with defaults
    public static LlmParametersDto withDefaults(Long chatConfigId) {
        return new LlmParametersDto(
                null, chatConfigId, "deepseek-chat", 0.7, 1000, 0.9,
                0.0, 0.0, null, null, ResponseFormat.TEXT
        );
    }
}