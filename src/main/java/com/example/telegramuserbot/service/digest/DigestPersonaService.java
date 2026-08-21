package com.example.telegramuserbot.service.digest;

import com.example.telegramuserbot.domain.DigestPersona;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Service for managing digest generation personas.
 * Provides CRUD operations and persona lifecycle management.
 */
public interface DigestPersonaService {

    /**
     * Creates a new digest persona.
     *
     * @param persona the persona to create
     * @return the created persona with generated ID
     */
    Mono<DigestPersona> create(DigestPersona persona);

    /**
     * Updates an existing digest persona.
     *
     * @param id the persona ID
     * @param persona the updated persona data
     * @return the updated persona
     */
    Mono<DigestPersona> update(Long id, DigestPersona persona);

    /**
     * Deletes a digest persona.
     *
     * @param id the persona ID to delete
     * @return completion signal
     */
    Mono<Void> delete(Long id);

    /**
     * Finds a persona by ID.
     *
     * @param id the persona ID
     * @return the persona if found
     */
    Mono<DigestPersona> findById(Long id);

    /**
     * Finds a persona by name.
     *
     * @param name the persona name
     * @return the persona if found
     */
    Mono<DigestPersona> findByName(String name);

    /**
     * Lists all digest personas.
     *
     * @return flux of all personas
     */
    Flux<DigestPersona> findAll();

    /**
     * Lists all enabled digest personas.
     *
     * @return flux of enabled personas
     */
    Flux<DigestPersona> findAllEnabled();

    /**
     * Finds personas by bot ID.
     *
     * @param botId the bot user ID
     * @return flux of personas for this bot
     */
    Flux<DigestPersona> findByBotId(Long botId);

    /**
     * Enables a digest persona.
     *
     * @param id the persona ID
     * @return the updated persona
     */
    Mono<DigestPersona> enable(Long id);

    /**
     * Disables a digest persona.
     *
     * @param id the persona ID
     * @return the updated persona
     */
    Mono<DigestPersona> disable(Long id);

    /**
     * Counts all personas.
     *
     * @return total count
     */
    Mono<Long> count();

    /**
     * Counts enabled personas.
     *
     * @return count of enabled personas
     */
    Mono<Long> countEnabled();

    /**
     * Checks if a persona name is available.
     *
     * @param name the name to check
     * @return true if name is available
     */
    Mono<Boolean> isNameAvailable(String name);
}
