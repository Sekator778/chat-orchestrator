package com.example.telegramuserbot.dto.digest;

import com.example.telegramuserbot.domain.DigestHistory;

import java.time.Instant;

/**
 * Data transfer object for digest history entries.
 * Used for API responses when retrieving digest history.
 *
 * @param id history record ID
 * @param personaId persona ID that generated the digest
 * @param personaName persona name
 * @param digestId unique digest identifier
 * @param content digest content text
 * @param messagesIncluded number of messages included
 * @param clustersUsed number of clusters used
 * @param generationTimeMs generation time in milliseconds
 * @param publishedAt publication timestamp
 * @param telegramMessageId Telegram message ID if published
 * @param status digest status
 * @param errorMessage error message if failed
 * @param createdAt creation timestamp
 */
public record DigestHistoryDto(
        Long id,
        Long personaId,
        String personaName,
        String digestId,
        String content,
        Integer messagesIncluded,
        Integer clustersUsed,
        Long generationTimeMs,
        Instant publishedAt,
        Long telegramMessageId,
        String status,
        String errorMessage,
        Instant createdAt
) {

    /**
     * Creates a DTO from a domain entity.
     *
     * @param entity the domain entity
     * @return the DTO
     */
    public static DigestHistoryDto from(DigestHistory entity) {
        return new DigestHistoryDto(
                entity.id(),
                entity.personaId(),
                null,
                entity.digestId(),
                entity.content(),
                entity.messagesIncluded(),
                entity.clustersUsed(),
                entity.generationTimeMs(),
                entity.publishedAt(),
                entity.telegramMessageId(),
                entity.status(),
                entity.errorMessage(),
                entity.createdAt()
        );
    }

    /**
     * Creates a DTO from a domain entity with persona name.
     *
     * @param entity the domain entity
     * @param personaName the persona name
     * @return the DTO
     */
    public static DigestHistoryDto from(DigestHistory entity, String personaName) {
        return new DigestHistoryDto(
                entity.id(),
                entity.personaId(),
                personaName,
                entity.digestId(),
                entity.content(),
                entity.messagesIncluded(),
                entity.clustersUsed(),
                entity.generationTimeMs(),
                entity.publishedAt(),
                entity.telegramMessageId(),
                entity.status(),
                entity.errorMessage(),
                entity.createdAt()
        );
    }

    /**
     * Checks if the digest was successfully published.
     *
     * @return true if status is PUBLISHED
     */
    public boolean isPublished() {
        return "PUBLISHED".equals(status);
    }

    /**
     * Checks if the digest failed.
     *
     * @return true if status is FAILED
     */
    public boolean isFailed() {
        return "FAILED".equals(status);
    }

    /**
     * Gets a truncated preview of the content.
     *
     * @param maxLength maximum length
     * @return truncated content
     */
    public String contentPreview(int maxLength) {
        if (content == null || content.length() <= maxLength) {
            return content;
        }
        return content.substring(0, maxLength) + "...";
    }
}
