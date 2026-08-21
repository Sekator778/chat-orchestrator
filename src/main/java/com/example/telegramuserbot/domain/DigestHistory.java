package com.example.telegramuserbot.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.Objects;

/**
 * Tracks published digest history.
 * Records each generated and published digest with metrics.
 */
@Table(schema = "bot", name = "digest_history")
public final class DigestHistory {

    @Id
    private Long id;

    @Column("persona_id")
    private Long personaId;

    @Column("digest_id")
    private String digestId;

    @Column("content")
    private String content;

    @Column("messages_included")
    private Integer messagesIncluded;

    @Column("clusters_used")
    private Integer clustersUsed;

    @Column("generation_time_ms")
    private Long generationTimeMs;

    @Column("published_at")
    private Instant publishedAt;

    @Column("telegram_message_id")
    private Long telegramMessageId;

    @Column("status")
    private String status;

    @Column("error_message")
    private String errorMessage;

    @Column("created_at")
    private Instant createdAt;

    /**
     * Default constructor for R2DBC mapping.
     */
    public DigestHistory() {
        this.status = DigestStatus.GENERATED.name();
    }

    /**
     * Constructor with required fields.
     *
     * @param personaId persona ID
     * @param digestId unique digest identifier
     * @param content digest content
     */
    public DigestHistory(Long personaId, String digestId, String content) {
        this();
        this.personaId = personaId;
        this.digestId = digestId;
        this.content = content;
    }

    public Long id() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long personaId() {
        return personaId;
    }

    public void setPersonaId(Long personaId) {
        this.personaId = personaId;
    }

    public String digestId() {
        return digestId;
    }

    public void setDigestId(String digestId) {
        this.digestId = digestId;
    }

    public String content() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Integer messagesIncluded() {
        return messagesIncluded;
    }

    public void setMessagesIncluded(Integer messagesIncluded) {
        this.messagesIncluded = messagesIncluded;
    }

    public Integer clustersUsed() {
        return clustersUsed;
    }

    public void setClustersUsed(Integer clustersUsed) {
        this.clustersUsed = clustersUsed;
    }

    public Long generationTimeMs() {
        return generationTimeMs;
    }

    public void setGenerationTimeMs(Long generationTimeMs) {
        this.generationTimeMs = generationTimeMs;
    }

    public Instant publishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    public Long telegramMessageId() {
        return telegramMessageId;
    }

    public void setTelegramMessageId(Long telegramMessageId) {
        this.telegramMessageId = telegramMessageId;
    }

    public String status() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    /**
     * Returns the status as enum.
     *
     * @return the digest status enum value
     */
    public DigestStatus statusEnum() {
        if (status == null) {
            return DigestStatus.GENERATED;
        }
        try {
            return DigestStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            return DigestStatus.GENERATED;
        }
    }

    public String errorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    /**
     * Marks digest as published.
     *
     * @param telegramMessageId Telegram message ID
     */
    public void markPublished(Long telegramMessageId) {
        this.status = DigestStatus.PUBLISHED.name();
        this.telegramMessageId = telegramMessageId;
        this.publishedAt = Instant.now();
    }

    /**
     * Marks digest as failed.
     *
     * @param error error message
     */
    public void markFailed(String error) {
        this.status = DigestStatus.FAILED.name();
        this.errorMessage = error;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DigestHistory that = (DigestHistory) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return String.format(
            "DigestHistory[id=%d, personaId=%d, digestId=%s, status=%s, messagesIncluded=%d, clustersUsed=%d]",
            id,
            personaId,
            digestId,
            status,
            messagesIncluded,
            clustersUsed
        );
    }
}
