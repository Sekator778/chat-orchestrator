package com.example.telegramuserbot.repository;

import com.example.telegramuserbot.domain.PersonaReactionConfig;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Reactive repository for persona reaction configuration records.
 * Provides access to channel-level reaction limits per persona.
 */
@Repository
public interface PersonaReactionConfigRepository extends ReactiveCrudRepository<PersonaReactionConfig, Long> {

    /**
     * Finds all enabled configurations for a given channel.
     *
     * @param channelId the Telegram channel ID
     * @return flux of enabled reaction configs for this channel
     */
    Flux<PersonaReactionConfig> findByChannelIdAndEnabledTrue(Long channelId);

    /**
     * Finds all configurations for a given persona.
     *
     * @param personaId the persona identifier
     * @return flux of all reaction configs for this persona
     */
    Flux<PersonaReactionConfig> findByPersonaId(String personaId);

    /**
     * Finds the configuration for a specific persona and channel combination.
     *
     * @param personaId the persona identifier
     * @param channelId the Telegram channel ID
     * @return mono of the reaction config, or empty if not found
     */
    Mono<PersonaReactionConfig> findByPersonaIdAndChannelId(String personaId, Long channelId);
}
