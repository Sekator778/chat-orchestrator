package com.example.telegramuserbot.dto.digest;

import java.time.Instant;
import java.util.List;

/**
 * Comprehensive analytics data for the digest system.
 *
 * @param totalPersonas total number of personas
 * @param activePersonas number of enabled personas
 * @param totalDigestsGenerated total digests generated across all personas
 * @param totalDigestsPublished total successfully published digests
 * @param overallSuccessRate overall success rate percentage
 * @param averageGenerationTimeMs average generation time in milliseconds
 * @param messagesProcessedToday messages processed in the last 24 hours
 * @param clustersFormedToday clusters formed in the last 24 hours
 * @param digestsPublishedToday digests published in the last 24 hours
 * @param recentActivity recent digest activity timeline
 * @param personaStats per-persona statistics
 * @param generatedAt timestamp when analytics were generated
 */
public record DigestAnalyticsDto(
        int totalPersonas,
        int activePersonas,
        long totalDigestsGenerated,
        long totalDigestsPublished,
        double overallSuccessRate,
        double averageGenerationTimeMs,
        long messagesProcessedToday,
        long clustersFormedToday,
        long digestsPublishedToday,
        List<ActivityEntry> recentActivity,
        List<PersonaStats> personaStats,
        Instant generatedAt
) {

    /**
     * Represents a recent activity entry.
     *
     * @param timestamp when the activity occurred
     * @param personaName the persona name
     * @param action the action performed
     * @param success whether it was successful
     * @param details additional details
     */
    public record ActivityEntry(
            Instant timestamp,
            String personaName,
            String action,
            boolean success,
            String details
    ) {
        /**
         * Creates a published activity entry.
         *
         * @param timestamp when published
         * @param personaName persona name
         * @param messagesIncluded number of messages included
         * @return activity entry
         */
        public static ActivityEntry published(Instant timestamp, String personaName, int messagesIncluded) {
            return new ActivityEntry(
                    timestamp,
                    personaName,
                    "PUBLISHED",
                    true,
                    String.format("Digest with %d messages", messagesIncluded)
            );
        }

        /**
         * Creates a failed activity entry.
         *
         * @param timestamp when failed
         * @param personaName persona name
         * @param error error message
         * @return activity entry
         */
        public static ActivityEntry failed(Instant timestamp, String personaName, String error) {
            return new ActivityEntry(
                    timestamp,
                    personaName,
                    "FAILED",
                    false,
                    error != null ? error : "Unknown error"
            );
        }

        /**
         * Creates a generated activity entry.
         *
         * @param timestamp when generated
         * @param personaName persona name
         * @param messagesIncluded number of messages included
         * @return activity entry
         */
        public static ActivityEntry generated(Instant timestamp, String personaName, int messagesIncluded) {
            return new ActivityEntry(
                    timestamp,
                    personaName,
                    "GENERATED",
                    true,
                    String.format("Digest with %d messages", messagesIncluded)
            );
        }
    }

    /**
     * Per-persona statistics.
     *
     * @param personaId persona ID
     * @param personaName persona name
     * @param enabled whether enabled
     * @param totalDigests total digests generated
     * @param publishedDigests published digests
     * @param failedDigests failed digests
     * @param successRate success rate percentage
     * @param avgGenerationTimeMs average generation time
     * @param lastRunAt last run timestamp
     */
    public record PersonaStats(
            Long personaId,
            String personaName,
            boolean enabled,
            long totalDigests,
            long publishedDigests,
            long failedDigests,
            double successRate,
            double avgGenerationTimeMs,
            Instant lastRunAt
    ) {}

    /**
     * Creates empty analytics when no data is available.
     *
     * @return empty analytics DTO
     */
    public static DigestAnalyticsDto empty() {
        return new DigestAnalyticsDto(
                0,
                0,
                0,
                0,
                0.0,
                0.0,
                0,
                0,
                0,
                List.of(),
                List.of(),
                Instant.now()
        );
    }
}
