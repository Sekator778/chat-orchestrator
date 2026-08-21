package com.example.telegramuserbot.dto.digest;

import java.time.Instant;

/**
 * Represents a published digest result.
 *
 * @param digestId unique identifier for this digest
 * @param personaId the persona that generated this digest
 * @param personaName the persona name
 * @param targetChannelId the channel where digest was published
 * @param telegramMessageId the Telegram message ID
 * @param content the published content
 * @param messagesIncluded number of messages included
 * @param clustersUsed number of clusters used
 * @param generationTimeMs time taken to generate in milliseconds
 * @param publishedAt timestamp of publication
 * @param success whether publishing succeeded
 * @param errorMessage error message if publishing failed
 */
public record PublishedDigestDto(
        String digestId,
        Long personaId,
        String personaName,
        Long targetChannelId,
        Long telegramMessageId,
        String content,
        int messagesIncluded,
        int clustersUsed,
        long generationTimeMs,
        Instant publishedAt,
        boolean success,
        String errorMessage
) {

    /**
     * Creates a successful published digest.
     *
     * @param digest the generated digest
     * @param targetChannelId target channel ID
     * @param telegramMessageId Telegram message ID
     * @return successful published digest DTO
     */
    public static PublishedDigestDto success(
            GeneratedDigestDto digest,
            Long targetChannelId,
            Long telegramMessageId
    ) {
        return new PublishedDigestDto(
                digest.digestId(),
                digest.personaId(),
                digest.personaName(),
                targetChannelId,
                telegramMessageId,
                digest.content(),
                digest.messagesIncluded(),
                digest.clustersUsed(),
                digest.generationTimeMs(),
                Instant.now(),
                true,
                null
        );
    }

    /**
     * Creates a failed published digest.
     *
     * @param digest the generated digest
     * @param targetChannelId target channel ID
     * @param error error message
     * @return failed published digest DTO
     */
    public static PublishedDigestDto failure(
            GeneratedDigestDto digest,
            Long targetChannelId,
            String error
    ) {
        return new PublishedDigestDto(
                digest.digestId(),
                digest.personaId(),
                digest.personaName(),
                targetChannelId,
                null,
                digest.content(),
                digest.messagesIncluded(),
                digest.clustersUsed(),
                digest.generationTimeMs(),
                Instant.now(),
                false,
                error
        );
    }

    /**
     * Creates a failed published digest without generated content.
     * Used when digest generation itself failed.
     *
     * @param personaId the persona ID
     * @param personaName the persona name
     * @param targetChannelId target channel ID
     * @param error error message
     * @return failed published digest DTO
     */
    public static PublishedDigestDto failureWithoutDigest(
            Long personaId,
            String personaName,
            Long targetChannelId,
            String error
    ) {
        return new PublishedDigestDto(
                null,
                personaId,
                personaName,
                targetChannelId,
                null,
                null,
                0,
                0,
                0,
                Instant.now(),
                false,
                error
        );
    }
}
