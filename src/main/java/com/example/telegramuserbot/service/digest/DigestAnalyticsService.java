package com.example.telegramuserbot.service.digest;

import com.example.telegramuserbot.dto.digest.ClusterStatsDto;
import com.example.telegramuserbot.dto.digest.DigestAnalyticsDto;
import com.example.telegramuserbot.dto.digest.SourceStatsDto;
import reactor.core.publisher.Mono;

/**
 * Service for retrieving digest system analytics and statistics.
 * Provides comprehensive metrics about digest generation, publishing,
 * clustering, and source trust.
 */
public interface DigestAnalyticsService {

    /**
     * Gets comprehensive analytics for the digest system.
     * Includes persona stats, recent activity, and overall metrics.
     *
     * @return comprehensive analytics data
     */
    Mono<DigestAnalyticsDto> getAnalytics();

    /**
     * Gets analytics for a specific time range.
     *
     * @param lookbackHours number of hours to look back
     * @return analytics data for the specified period
     */
    Mono<DigestAnalyticsDto> getAnalytics(int lookbackHours);

    /**
     * Gets statistics about message clustering.
     * Includes cluster counts, sizes, and deduplication rates.
     *
     * @return cluster statistics
     */
    Mono<ClusterStatsDto> getClusterStats();

    /**
     * Gets cluster statistics for a specific time range.
     *
     * @param lookbackHours number of hours to look back
     * @return cluster statistics for the specified period
     */
    Mono<ClusterStatsDto> getClusterStats(int lookbackHours);

    /**
     * Gets statistics about source trust and channel contributions.
     * Includes trust score distribution and per-source metrics.
     *
     * @return source statistics
     */
    Mono<SourceStatsDto> getSourceStats();

    /**
     * Gets statistics for a specific persona.
     *
     * @param personaId persona ID
     * @return persona-specific analytics
     */
    Mono<DigestAnalyticsDto.PersonaStats> getPersonaStats(Long personaId);

    /**
     * Gets recent activity entries.
     *
     * @param limit maximum number of entries
     * @return recent activity list
     */
    Mono<java.util.List<DigestAnalyticsDto.ActivityEntry>> getRecentActivity(int limit);
}
