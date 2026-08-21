package com.example.telegramuserbot.repository;

import com.example.telegramuserbot.domain.SearchKeyword;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Flux;

/**
 * Repository for {@code tgscan.search_keywords}.
 * Used by {@code ChannelDiscoverySearchScheduler} to load the active keyword set.
 */
public interface SearchKeywordRepository extends R2dbcRepository<SearchKeyword, Long> {

    @Query("""
            SELECT *
              FROM tgscan.search_keywords
             WHERE enabled = true
             ORDER BY id
            """)
    Flux<SearchKeyword> findAllEnabled();
}
