package com.example.telegramuserbot.repository;

/**
 * Projection for aggregated search cache statistics.
 */
public record SearchCacheStatistics(
        Long totalResults,
        Long validResults,
        Double avgAccessCount,
        Double avgSearchTime
) {
}
