package com.example.telegramuserbot.repository;

import com.example.telegramuserbot.domain.SearchConfig;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Repository for SearchConfig entities
 */
@Repository
public interface SearchConfigRepository extends R2dbcRepository<SearchConfig, Long> {

    /**
     * Find search configuration by chat ID
     */
    Mono<SearchConfig> findByChatId(Long chatId);

    /**
     * Check if search configuration exists for chat ID
     */
    Mono<Boolean> existsByChatId(Long chatId);

    /**
     * Find all configurations with search enabled
     */
    @Query("SELECT * FROM search_configs WHERE search_enabled = true")
    Flux<SearchConfig> findAllWithSearchEnabled();

    /**
     * Find all configurations with auto-search enabled
     */
    @Query("SELECT * FROM search_configs WHERE auto_search_enabled = true")
    Flux<SearchConfig> findAllWithAutoSearchEnabled();

    /**
     * Find configurations by search provider
     */
    @Query("SELECT * FROM search_configs WHERE search_provider = :provider")
    Flux<SearchConfig> findBySearchProvider(@Param("provider") String provider);

    /**
     * Count configurations with search enabled
     */
    @Query("SELECT COUNT(*) FROM search_configs WHERE search_enabled = true")
    Mono<Long> countSearchEnabledConfigurations();

    /**
     * Find configurations that need rate limit reset (for maintenance tasks)
     */
    @Query("SELECT * FROM search_configs WHERE search_enabled = true AND rate_limit_per_hour > 0")
    Flux<SearchConfig> findConfigurationsForRateLimitReset();
}