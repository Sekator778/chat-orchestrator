package com.example.telegramuserbot.dto.validation;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Request DTO for configuration validation.
 * Allows validating multiple entities in a single request.
 */
public record ConfigValidationRequestDto(
        @NotEmpty(message = "At least one channel ID is required")
        @JsonProperty("channel_ids") List<Long> channelIds,
        @JsonProperty("include_digest_personas") boolean includeDigestPersonas,
        @JsonProperty("include_bot_personas") boolean includeBotPersonas
) {
    /**
     * Creates a request for validating a single channel
     */
    public static ConfigValidationRequestDto forChannel(Long channelId) {
        return new ConfigValidationRequestDto(List.of(channelId), false, false);
    }

    /**
     * Creates a request for validating all configurations
     */
    public static ConfigValidationRequestDto all(List<Long> channelIds) {
        return new ConfigValidationRequestDto(channelIds, true, true);
    }
}
