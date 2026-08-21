package com.example.telegramuserbot.repository;

import com.example.telegramuserbot.domain.SearchProvider;
import com.example.telegramuserbot.domain.SearchResult;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

/**
 * Repository for SearchResult entities
 */
@Repository
public interface SearchResultRepository extends R2dbcRepository<SearchResult, Long> {

    @Query("SELECT * FROM search_results WHERE query_hash = :queryHash AND search_provider = :provider AND expires_at > :now")
    Mono<SearchResult> findValidCachedResult(@Param("queryHash") String queryHash,
                                             @Param("provider") SearchProvider provider,
                                             @Param("now") Instant now);

    @Query("SELECT * FROM search_results WHERE query_hash = :queryHash AND expires_at > :now ORDER BY created_at DESC")
    Flux<SearchResult> findValidCachedResultsForQuery(@Param("queryHash") String queryHash,
                                                      @Param("now") Instant now);

    @Query("SELECT * FROM search_results WHERE expires_at <= :now")
    Flux<SearchResult> findExpiredResults(@Param("now") Instant now);

    @Modifying
    @Query("DELETE FROM search_results WHERE expires_at <= :now")
    Mono<Integer> deleteExpiredResults(@Param("now") Instant now);

    @Query("SELECT * FROM search_results WHERE expires_at > :now ORDER BY access_count DESC")
    Flux<SearchResult> findMostAccessedResults(@Param("now") Instant now);

    @Query("SELECT * FROM search_results WHERE search_provider = :provider AND created_at BETWEEN :startDate AND :endDate ORDER BY created_at DESC")
    Flux<SearchResult> findByProviderAndDateRange(@Param("provider") SearchProvider provider,
                                                  @Param("startDate") Instant startDate,
                                                  @Param("endDate") Instant endDate);

    @Query("SELECT COUNT(*) FROM search_results WHERE search_provider = :provider AND expires_at > :now")
    Mono<Long> countValidResultsByProvider(@Param("provider") SearchProvider provider,
                                           @Param("now") Instant now);

    @Modifying
    @Query("UPDATE search_results SET access_count = access_count + 1, last_accessed_at = :accessTime WHERE id = :id")
    Mono<Integer> updateAccessStatistics(@Param("id") Long id, @Param("accessTime") Instant accessTime);

    @Query("SELECT * FROM search_results WHERE last_accessed_at < :cutoffDate OR last_accessed_at IS NULL")
    Flux<SearchResult> findUnusedResults(@Param("cutoffDate") Instant cutoffDate);

    @Query("SELECT normalized_query, SUM(access_count) as total_access FROM search_results WHERE expires_at > :now GROUP BY normalized_query ORDER BY total_access DESC")
    Flux<Map<String, Object>> findTopQueriesByAccessCount(@Param("now") Instant now);

    @Query("SELECT " +
           "COUNT(*) AS totalResults, " +
           "COUNT(CASE WHEN expires_at > :now THEN 1 END) AS validResults, " +
           "COALESCE(AVG(access_count)::double precision, 0) AS avgAccessCount, " +
           "COALESCE(AVG(search_time_ms)::double precision, 0) AS avgSearchTime " +
           "FROM search_results")
    Mono<SearchCacheStatistics> getCacheStatistics(@Param("now") Instant now);
}
