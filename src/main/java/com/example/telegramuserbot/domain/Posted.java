package com.example.telegramuserbot.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * Audit trail of published events.
 * Tracks what was sent where for analytics and idempotency.
 */
@Table(schema = "tgscan", name = "posted")
public final class Posted {

    @Id
    private Long id;

    @Column("event_id")
    private Long eventId;

    @Column("subscription_id")
    private Long subscriptionId;

    @Column("chat_id")
    private Long chatId;

    @Column("message_id")
    private Long messageId;

    @Column("template_code")
    private String templateCode;

    @Column("status")
    private String status;

    @Column("error_message")
    private String errorMessage;

    @Column("posted_at")
    private LocalDateTime postedAt;

    /**
     * Default constructor for R2DBC mapping.
     */
    public Posted() {
    }

    // Getters

    public Long id() {
        return id;
    }

    public Long eventId() {
        return eventId;
    }

    public Long subscriptionId() {
        return subscriptionId;
    }

    public Long chatId() {
        return chatId;
    }

    public Long messageId() {
        return messageId;
    }

    public String templateCode() {
        return templateCode;
    }

    public String status() {
        return status;
    }

    public String errorMessage() {
        return errorMessage;
    }

    public LocalDateTime postedAt() {
        return postedAt;
    }

    // Setters

    public void setId(Long id) {
        this.id = id;
    }

    public void setEventId(Long eventId) {
        this.eventId = eventId;
    }

    public void setSubscriptionId(Long subscriptionId) {
        this.subscriptionId = subscriptionId;
    }

    public void setChatId(Long chatId) {
        this.chatId = chatId;
    }

    public void setMessageId(Long messageId) {
        this.messageId = messageId;
    }

    public void setTemplateCode(String templateCode) {
        this.templateCode = templateCode;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public void setPostedAt(LocalDateTime postedAt) {
        this.postedAt = postedAt;
    }

    @Override
    public String toString() {
        return String.format(
            "Posted[id=%d, eventId=%d, chatId=%d, status=%s, messageId=%s]",
            id,
            eventId,
            chatId,
            status,
            messageId
        );
    }
}
