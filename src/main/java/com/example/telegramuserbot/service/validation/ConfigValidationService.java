package com.example.telegramuserbot.service.validation;

import com.example.telegramuserbot.dto.validation.ConfigValidationRequestDto;
import com.example.telegramuserbot.dto.validation.ConfigValidationResponseDto;
import com.example.telegramuserbot.dto.validation.EntityValidationResultDto;
import reactor.core.publisher.Mono;

/**
 * Service interface for validating configuration entities.
 * Provides comprehensive validation with detailed issue reporting.
 */
public interface ConfigValidationService {

    /**
     * Validates configurations based on the request.
     *
     * @param request validation request with channel IDs and options
     * @return validation response with all issues found
     */
    Mono<ConfigValidationResponseDto> validate(ConfigValidationRequestDto request);

    /**
     * Validates a single channel configuration.
     *
     * @param channelId the channel ID to validate
     * @return validation result for the channel and its related entities
     */
    Mono<EntityValidationResultDto> validateChannel(Long channelId);

    /**
     * Validates a digest persona configuration.
     *
     * @param personaId the persona ID to validate
     * @return validation result for the persona
     */
    Mono<EntityValidationResultDto> validateDigestPersona(Long personaId);
}
