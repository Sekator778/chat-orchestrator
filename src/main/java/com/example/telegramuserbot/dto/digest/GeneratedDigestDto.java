package com.example.telegramuserbot.dto.digest;

import java.time.Instant;
import java.util.List;

/**
 * Represents a generated digest result.
 *
 * @param digestId unique identifier for this digest
 * @param personaId the persona that generated this digest
 * @param personaName the persona name
 * @param content the synthesized digest content
 * @param messagesIncluded number of messages included
 * @param clustersUsed number of clusters used
 * @param sourceSummary summary of sources used
 * @param generationTimeMs time taken to generate in milliseconds
 * @param generatedAt timestamp of generation
 */
public record GeneratedDigestDto(
        String digestId,
        Long personaId,
        String personaName,
        String content,
        int messagesIncluded,
        int clustersUsed,
        List<String> sourceSummary,
        long generationTimeMs,
        Instant generatedAt
) {

    /**
     * Creates an empty digest for when no content is available.
     *
     * @param personaId the persona ID
     * @param personaName the persona name
     * @return empty digest DTO
     */
    public static GeneratedDigestDto empty(Long personaId, String personaName) {
        return new GeneratedDigestDto(
                null,
                personaId,
                personaName,
                "No significant news in this period.",
                0,
                0,
                List.of(),
                0L,
                Instant.now()
        );
    }
}
