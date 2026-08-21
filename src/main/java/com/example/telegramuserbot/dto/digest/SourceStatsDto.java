package com.example.telegramuserbot.dto.digest;

import java.time.Instant;
import java.util.List;

/**
 * Statistics about source trust and channel contributions.
 *
 * @param totalSources total number of monitored sources
 * @param highTrustSources sources with trust score >= 0.7
 * @param lowTrustSources sources with trust score < 0.3
 * @param averageTrustScore average trust score across all sources
 * @param sourceDetails detailed stats per source
 * @param trustDistribution distribution of trust scores
 * @param generatedAt timestamp when stats were generated
 */
public record SourceStatsDto(
        long totalSources,
        long highTrustSources,
        long lowTrustSources,
        double averageTrustScore,
        List<SourceDetail> sourceDetails,
        TrustDistribution trustDistribution,
        Instant generatedAt
) {

    /**
     * Detailed information about a source.
     *
     * @param channelId channel ID
     * @param channelTitle channel title
     * @param trustScore trust score (0.0-1.0)
     * @param isOfficial whether source is official
     * @param category source category
     * @param messageCount messages from this source
     * @param clustersContributed clusters this source contributed to
     * @param lastMessageAt last message timestamp
     */
    public record SourceDetail(
            Long channelId,
            String channelTitle,
            double trustScore,
            boolean isOfficial,
            String category,
            long messageCount,
            long clustersContributed,
            Instant lastMessageAt
    ) {}

    /**
     * Distribution of trust scores across sources.
     *
     * @param veryHigh 0.9-1.0
     * @param high 0.7-0.9
     * @param medium 0.5-0.7
     * @param low 0.3-0.5
     * @param veryLow 0.0-0.3
     */
    public record TrustDistribution(
            long veryHigh,
            long high,
            long medium,
            long low,
            long veryLow
    ) {
        /**
         * Creates empty distribution.
         *
         * @return empty trust distribution
         */
        public static TrustDistribution empty() {
            return new TrustDistribution(0, 0, 0, 0, 0);
        }
    }

    /**
     * Creates empty stats when no data is available.
     *
     * @return empty source stats DTO
     */
    public static SourceStatsDto empty() {
        return new SourceStatsDto(
                0,
                0,
                0,
                0.0,
                List.of(),
                TrustDistribution.empty(),
                Instant.now()
        );
    }
}
