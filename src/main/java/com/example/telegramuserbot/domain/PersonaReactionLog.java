package com.example.telegramuserbot.domain;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.Objects;

/**
 * Log entity tracking scheduled and executed persona reactions.
 * Each row represents one reaction that a persona will send or has sent.
 */
@Table(schema = "bot", name = "persona_reaction_log")
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class PersonaReactionLog {

    @Id
    private Long id;

    @Column("persona_id")
    private String personaId;

    @Column("channel_id")
    private Long channelId;

    @Column("message_id")
    private Long messageId;

    @Column("reaction_emoji")
    private String reactionEmoji;

    @Column("scheduled_at")
    private Instant scheduledAt;

    @Column("executed_at")
    private Instant executedAt;

    @Column("status")
    private String status;

    @Column("error_message")
    private String errorMessage;

    @Column("attempt_count")
    private int attemptCount;

    @Column("created_at")
    private Instant createdAt;

    /**
     * Default constructor for R2DBC mapping.
     */
    public PersonaReactionLog() {
        this.status = ReactionStatus.PENDING.name();
        this.attemptCount = 0;
    }

    /**
     * Constructor for creating a new pending reaction log entry.
     *
     * @param personaId    the persona identifier
     * @param channelId    the channel ID
     * @param messageId    the message ID to react to
     * @param reactionEmoji the emoji to use as reaction
     * @param scheduledAt  when to execute the reaction
     */
    public PersonaReactionLog(String personaId, Long channelId, Long messageId,
                               String reactionEmoji, Instant scheduledAt) {
        this();
        this.personaId = personaId;
        this.channelId = channelId;
        this.messageId = messageId;
        this.reactionEmoji = reactionEmoji;
        this.scheduledAt = scheduledAt;
    }

    /**
     * Returns the primary key identifier.
     *
     * @return the entity id
     */
    public Long id() {
        return id;
    }

    /**
     * Sets the primary key identifier.
     *
     * @param id the entity id
     */
    public void setId(Long id) {
        this.id = id;
    }

    /**
     * Returns the persona identifier.
     *
     * @return persona id string
     */
    public String personaId() {
        return personaId;
    }

    /**
     * Sets the persona identifier.
     *
     * @param personaId the persona id string
     */
    public void setPersonaId(String personaId) {
        this.personaId = personaId;
    }

    /**
     * Returns the Telegram channel ID.
     *
     * @return channel id
     */
    public Long channelId() {
        return channelId;
    }

    /**
     * Sets the Telegram channel ID.
     *
     * @param channelId the channel id
     */
    public void setChannelId(Long channelId) {
        this.channelId = channelId;
    }

    /**
     * Returns the Telegram message ID.
     *
     * @return message id
     */
    public Long messageId() {
        return messageId;
    }

    /**
     * Sets the Telegram message ID.
     *
     * @param messageId the message id
     */
    public void setMessageId(Long messageId) {
        this.messageId = messageId;
    }

    /**
     * Returns the emoji used for the reaction.
     *
     * @return reaction emoji string
     */
    public String reactionEmoji() {
        return reactionEmoji;
    }

    /**
     * Sets the emoji used for the reaction.
     *
     * @param reactionEmoji the emoji string
     */
    public void setReactionEmoji(String reactionEmoji) {
        this.reactionEmoji = reactionEmoji;
    }

    /**
     * Returns the scheduled execution time for the reaction.
     *
     * @return scheduled at instant
     */
    public Instant scheduledAt() {
        return scheduledAt;
    }

    /**
     * Sets the scheduled execution time for the reaction.
     *
     * @param scheduledAt the scheduled instant
     */
    public void setScheduledAt(Instant scheduledAt) {
        this.scheduledAt = scheduledAt;
    }

    /**
     * Returns when the reaction was actually executed.
     *
     * @return executed at instant, or null if not yet executed
     */
    public Instant executedAt() {
        return executedAt;
    }

    /**
     * Sets when the reaction was executed.
     *
     * @param executedAt the execution instant
     */
    public void setExecutedAt(Instant executedAt) {
        this.executedAt = executedAt;
    }

    /**
     * Returns the current status string of this reaction.
     *
     * @return status string (PENDING, DONE, FAILED, SKIPPED, FLOOD_WAIT)
     */
    public String status() {
        return status;
    }

    /**
     * Sets the status of this reaction.
     *
     * @param status the status string
     */
    public void setStatus(String status) {
        this.status = status;
    }

    /**
     * Returns the error message if execution failed.
     *
     * @return error message or null
     */
    public String errorMessage() {
        return errorMessage;
    }

    /**
     * Sets the error message when execution fails.
     *
     * @param errorMessage the error description
     */
    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    /**
     * Returns how many times execution was attempted.
     *
     * @return attempt count
     */
    public int attemptCount() {
        return attemptCount;
    }

    /**
     * Sets the execution attempt count.
     *
     * @param attemptCount number of attempts made
     */
    public void setAttemptCount(int attemptCount) {
        this.attemptCount = attemptCount;
    }

    /**
     * Returns the creation timestamp for this log entry.
     *
     * @return created at instant
     */
    public Instant createdAt() {
        return createdAt;
    }

    /**
     * Sets the creation timestamp.
     *
     * @param createdAt the instant of creation
     */
    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PersonaReactionLog that = (PersonaReactionLog) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return String.format(
            "PersonaReactionLog[id=%d, personaId=%s, channelId=%d, messageId=%d, emoji=%s, status=%s, scheduledAt=%s]",
            id, personaId, channelId, messageId, reactionEmoji, status, scheduledAt
        );
    }
}
