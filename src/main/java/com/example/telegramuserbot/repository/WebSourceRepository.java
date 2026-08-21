package com.example.telegramuserbot.repository;

import com.example.telegramuserbot.domain.WebSource;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;

/**
 * R2DBC repository for {@link WebSource} — the registry of RSS/Atom feed endpoints
 * in {@code bot.web_sources}.
 *
 * <p>The {@code findByEnabledTrue()} finder returns all sources whose {@code enabled}
 * flag is {@code true}, which is the only query needed by the harvester on each tick.
 */
public interface WebSourceRepository extends R2dbcRepository<WebSource, Long> {

    /**
     * Returns all enabled feed sources, ordered by id (stable iteration order).
     *
     * @return flux of enabled {@link WebSource} rows
     */
    @Query("SELECT * FROM bot.web_sources WHERE enabled = true ORDER BY id")
    Flux<WebSource> findByEnabledTrue();
}
