package com.example.telegramuserbot.dto.digest;

import java.time.Instant;
import java.util.List;

/**
 * Statistics about message clustering.
 *
 * @param totalClusters total number of clusters
 * @param clustersToday clusters formed in the last 24 hours
 * @param averageClusterSize average messages per cluster
 * @param deduplicationRate percentage of messages deduplicated
 * @param unclusteredMessages messages not yet clustered
 * @param processingTimeMs average processing time
 * @param topClusters top clusters by size
 * @param generatedAt timestamp when stats were generated
 */
public record ClusterStatsDto(
        long totalClusters,
        long clustersToday,
        double averageClusterSize,
        double deduplicationRate,
        long unclusteredMessages,
        double processingTimeMs,
        List<ClusterInfo> topClusters,
        Instant generatedAt
) {

    /**
     * Information about a specific cluster.
     *
     * @param clusterId cluster identifier
     * @param messageCount number of messages in cluster
     * @param primaryMessagePreview preview of primary message
     * @param avgImportance average importance score
     * @param createdAt when cluster was created
     */
    public record ClusterInfo(
            String clusterId,
            int messageCount,
            String primaryMessagePreview,
            double avgImportance,
            Instant createdAt
    ) {}

    /**
     * Creates empty stats when no data is available.
     *
     * @return empty cluster stats DTO
     */
    public static ClusterStatsDto empty() {
        return new ClusterStatsDto(
                0,
                0,
                0.0,
                0.0,
                0,
                0.0,
                List.of(),
                Instant.now()
        );
    }
}
