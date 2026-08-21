package com.example.telegramuserbot.service.digest;

import com.example.telegramuserbot.domain.DigestPersona;
import com.example.telegramuserbot.dto.digest.GeneratedDigestDto;
import com.example.telegramuserbot.dto.digest.PublishedDigestDto;
import reactor.core.publisher.Mono;

/**
 * Service for publishing digests to Telegram channels.
 * Handles formatting, sending, and tracking publication status.
 */
public interface DigestPublishingService {

    /**
     * Publishes a generated digest to the target channel.
     *
     * @param digest the generated digest to publish
     * @param persona the persona configuration
     * @return mono with published digest details
     */
    Mono<PublishedDigestDto> publish(GeneratedDigestDto digest, DigestPersona persona);

    /**
     * Publishes a generated digest using persona ID lookup.
     *
     * @param digest the generated digest to publish
     * @return mono with published digest details
     */
    Mono<PublishedDigestDto> publish(GeneratedDigestDto digest);

    /**
     * Generates and publishes a digest for a persona.
     *
     * @param personaId the persona ID
     * @return mono with published digest details
     */
    Mono<PublishedDigestDto> generateAndPublish(Long personaId);

    /**
     * Generates and publishes a digest with custom lookback.
     *
     * @param personaId the persona ID
     * @param lookbackHours hours of messages to include
     * @return mono with published digest details
     */
    Mono<PublishedDigestDto> generateAndPublish(Long personaId, int lookbackHours);

    /**
     * Republishes an existing digest by its ID.
     *
     * @param digestId the digest ID to republish
     * @return mono with published digest details
     */
    Mono<PublishedDigestDto> republish(String digestId);

    /**
     * Formats digest content for Telegram.
     *
     * @param digest the digest to format
     * @param persona the persona for styling
     * @return formatted message text
     */
    String format(GeneratedDigestDto digest, DigestPersona persona);
}
