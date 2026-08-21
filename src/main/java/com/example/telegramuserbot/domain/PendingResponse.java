package com.example.telegramuserbot.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.Objects;

/**
 * Domain entity representing a pending response in the queue.
 *
 * This entity stores AI-generated responses that should not be sent immediately,
 * but rather wait for a configurable number of human participants to reply first.
 * This ensures more natural conversation flow and reduces bot detection risk.
 */
@Table(name = "pending_responses", schema = "bot")
public final class PendingResponse {

    @Id
    private Long id;

    @Column("chat_id")
    private Long chatId;

    @Column("triggering_message_id")
    private Long triggeringMessageId;

    @Column("prepared_response")
    private String preparedResponse;

    @Column("response_intent")
    private String responseIntent;

    @Column("response_tone")
    private String responseTone;

    @Column("response_length")
    private String responseLength;

    @Column("status")
    private PendingResponseStatus status;

    @Column("base_count")
    private Long baseCount;

    @Column("required_delta")
    private Integer requiredDelta;

    @Column("created_at")
    private Instant createdAt;

    @Column("eligible_at")
    private Instant eligibleAt;

    @Column("sent_at")
    private Instant sentAt;

    @Column("expires_at")
    private Instant expiresAt;

    @Column("bot_instance_id")
    private String botInstanceId;

    public PendingResponse() {
        this.status = PendingResponseStatus.PENDING;
        this.baseCount = 0L;
        this.requiredDelta = 0;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getChatId() {
        return chatId;
    }

    public void setChatId(Long chatId) {
        this.chatId = chatId;
    }

    public Long getTriggeringMessageId() {
        return triggeringMessageId;
    }

    public void setTriggeringMessageId(Long triggeringMessageId) {
        this.triggeringMessageId = triggeringMessageId;
    }

    public String getPreparedResponse() {
        return preparedResponse;
    }

    public void setPreparedResponse(String preparedResponse) {
        this.preparedResponse = preparedResponse;
    }

    public String getResponseIntent() {
        return responseIntent;
    }

    public void setResponseIntent(String responseIntent) {
        this.responseIntent = responseIntent;
    }

    public String getResponseTone() {
        return responseTone;
    }

    public void setResponseTone(String responseTone) {
        this.responseTone = responseTone;
    }

    public String getResponseLength() {
        return responseLength;
    }

    public void setResponseLength(String responseLength) {
        this.responseLength = responseLength;
    }

    public PendingResponseStatus getStatus() {
        return status;
    }

    public void setStatus(PendingResponseStatus status) {
        this.status = status;
    }

    public Long getBaseCount() {
        return baseCount;
    }

    public void setBaseCount(Long baseCount) {
        this.baseCount = baseCount;
    }

    public Integer getRequiredDelta() {
        return requiredDelta;
    }

    public void setRequiredDelta(Integer requiredDelta) {
        this.requiredDelta = requiredDelta;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getEligibleAt() {
        return eligibleAt;
    }

    public void setEligibleAt(Instant eligibleAt) {
        this.eligibleAt = eligibleAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }

    public void setSentAt(Instant sentAt) {
        this.sentAt = sentAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public String getBotInstanceId() {
        return botInstanceId;
    }

    public void setBotInstanceId(String botInstanceId) {
        this.botInstanceId = botInstanceId;
    }

    /**
     * Checks if the response is eligible for sending.
     * Eligible means it was already marked as such previously.
     */
    public boolean isEligible() {
        return status == PendingResponseStatus.ELIGIBLE || status == PendingResponseStatus.SENDING;
    }

    /**
     * Checks if the response has expired.
     */
    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    /**
     * Marks the response as eligible and records the timestamp.
     */
    public void markAsEligible() {
        this.status = PendingResponseStatus.ELIGIBLE;
        this.eligibleAt = Instant.now();
    }

    /**
     * Marks the response as being sent.
     */
    public void markAsSending() {
        this.status = PendingResponseStatus.SENDING;
    }

    /**
     * Marks the response as sent and records the timestamp.
     */
    public void markAsSent() {
        this.status = PendingResponseStatus.SENT;
        this.sentAt = Instant.now();
    }

    /**
     * Marks the response as expired.
     */
    public void markAsExpired() {
        this.status = PendingResponseStatus.EXPIRED;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PendingResponse that = (PendingResponse) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "PendingResponse{" +
                "id=" + id +
                ", chatId=" + chatId +
                ", triggeringMessageId=" + triggeringMessageId +
                ", status=" + status +
                ", baseCount=" + baseCount +
                ", requiredDelta=" + requiredDelta +
                ", createdAt=" + createdAt +
                ", expiresAt=" + expiresAt +
                '}';
    }
}
