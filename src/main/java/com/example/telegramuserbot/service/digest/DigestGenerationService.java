package com.example.telegramuserbot.service.digest;

import com.example.telegramuserbot.domain.DigestPersona;
import com.example.telegramuserbot.dto.digest.GeneratedDigestDto;
import reactor.core.publisher.Mono;

/**
 * Service for generating news digests based on persona configuration.
 * Integrates with clustering, filtering, and LLM synthesis services.
 */
public interface DigestGenerationService {

    /**
     * Generates a digest for a specific persona.
     * Applies persona-specific filtering, clustering, and synthesis.
     *
     * @param personaId the persona ID to generate digest for
     * @return the generated digest
     */
    Mono<GeneratedDigestDto> generateDigest(Long personaId);

    /**
     * Generates a digest using provided persona configuration.
     * Useful for testing with custom parameters.
     *
     * @param persona the persona configuration
     * @return the generated digest
     */
    Mono<GeneratedDigestDto> generateDigest(DigestPersona persona);

    /**
     * Generates a test digest without persisting history.
     * Used for preview functionality.
     *
     * @param personaId the persona ID
     * @return the generated digest for preview
     */
    Mono<GeneratedDigestDto> generateTestDigest(Long personaId);

    /**
     * Generates a digest for a specific time range.
     * Overrides persona's default lookback window.
     *
     * @param personaId the persona ID
     * @param lookbackHours custom lookback window in hours
     * @return the generated digest
     */
    Mono<GeneratedDigestDto> generateDigest(Long personaId, int lookbackHours);

    /**
     * Gets the system prompt for a persona.
     * Combines persona style with custom prompts.
     *
     * @param persona the persona configuration
     * @return the system prompt for LLM
     */
    String buildSystemPrompt(DigestPersona persona);
}
