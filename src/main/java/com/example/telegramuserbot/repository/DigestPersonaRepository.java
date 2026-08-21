package com.example.telegramuserbot.repository;

import com.example.telegramuserbot.domain.DigestPersona;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * Repository for managing digest personas.
 * Provides CRUD operations and specialized queries.
 */
public interface DigestPersonaRepository extends R2dbcRepository<DigestPersona, Long> {

    /**
     * Finds all enabled personas.
     *
     * @return flux of enabled personas
     */
    @Query("SELECT * FROM bot.digest_personas WHERE enabled = true")
    Flux<DigestPersona> findAllEnabled();

    /**
     * Finds persona by bot ID.
     *
     * @param botId bot user ID
     * @return flux of personas for this bot
     */
    @Query("SELECT * FROM bot.digest_personas WHERE bot_id = :botId")
    Flux<DigestPersona> findByBotId(@Param("botId") Long botId);

    /**
     * Finds persona by name.
     *
     * @param name persona name
     * @return the persona if found
     */
    @Query("SELECT * FROM bot.digest_personas WHERE name = :name")
    Mono<DigestPersona> findByName(@Param("name") String name);

    /**
     * Finds persona by target channel ID.
     *
     * @param targetChannelId target channel ID
     * @return the persona if found
     */
    @Query("SELECT * FROM bot.digest_personas WHERE target_channel_id = :targetChannelId")
    Mono<DigestPersona> findByTargetChannelId(@Param("targetChannelId") Long targetChannelId);

    /**
     * Counts enabled personas.
     *
     * @return count of enabled personas
     */
    @Query("SELECT COUNT(*) FROM bot.digest_personas WHERE enabled = true")
    Mono<Long> countEnabled();

    /**
     * Updates the last run timestamp.
     *
     * @param id persona ID
     * @param lastRunAt last run timestamp
     * @return number of updated rows
     */
    @Modifying
    @Query("""
            UPDATE bot.digest_personas
            SET last_run_at = :lastRunAt, updated_at = NOW()
            WHERE id = :id
            """)
    Mono<Integer> updateLastRunAt(@Param("id") Long id, @Param("lastRunAt") Instant lastRunAt);

    /**
     * Updates the last published digest ID and increments counter.
     *
     * @param id persona ID
     * @param digestId digest ID
     * @return number of updated rows
     */
    @Modifying
    @Query("""
            UPDATE bot.digest_personas
            SET last_published_digest_id = :digestId,
                total_digests_published = total_digests_published + 1,
                last_run_at = NOW(),
                updated_at = NOW()
            WHERE id = :id
            """)
    Mono<Integer> updateLastPublished(@Param("id") Long id, @Param("digestId") String digestId);

    /**
     * Enables or disables a persona.
     *
     * @param id persona ID
     * @param enabled enabled flag
     * @return number of updated rows
     */
    @Modifying
    @Query("""
            UPDATE bot.digest_personas
            SET enabled = :enabled, updated_at = NOW()
            WHERE id = :id
            """)
    Mono<Integer> updateEnabled(@Param("id") Long id, @Param("enabled") Boolean enabled);

    /**
     * Checks if persona exists by name.
     *
     * @param name persona name
     * @return true if exists
     */
    @Query("SELECT COUNT(*) > 0 FROM bot.digest_personas WHERE name = :name")
    Mono<Boolean> existsByName(@Param("name") String name);

    /**
     * Finds all personas ordered by name.
     *
     * @return flux of all personas
     */
    @Query("SELECT * FROM bot.digest_personas ORDER BY name")
    Flux<DigestPersona> findAllOrderByName();

    /**
     * Bulk-resets {@code last_run_at} to the given instant for all enabled personas.
     * Called on startup so the min-interval clock is anchored to restart time rather than
     * the pre-downtime last post — preventing an immediate catch-up burst.
     *
     * @param now timestamp to set (typically {@code Instant.now()} at startup)
     * @return count of rows updated
     */
    @Modifying
    @Query("UPDATE bot.digest_personas SET last_run_at = :now WHERE enabled = true")
    Mono<Integer> resyncEnabledLastRunAt(@Param("now") java.time.Instant now);
}
