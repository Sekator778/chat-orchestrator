package com.example.telegramuserbot.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * Defines who receives event notifications and in what format.
 * Subscriptions use regex pattern matching for topics and filter by event type/severity.
 */
@Table(schema = "tgscan", name = "post_subscriptions")
public final class PostSubscription {

    @Id
    private Long id;

    @Column("chat_id")
    private Long chatId;

    @Column("enabled")
    private Boolean enabled;

    @Column("topic_pattern")
    private String topicPattern;

    @Column("event_types")
    private String[] eventTypes;

    @Column("min_severity")
    private String minSeverity;

    @Column("template_code")
    private String templateCode;

    @Column("dedupe_ttl_sec")
    private Integer dedupeTtlSec;

    @Column("created_at")
    private LocalDateTime createdAt;

    @Column("updated_at")
    private LocalDateTime updatedAt;

    /**
     * Default constructor for R2DBC mapping.
     */
    public PostSubscription() {
    }

    // Getters

    public Long id() {
        return id;
    }

    public Long chatId() {
        return chatId;
    }

    public Boolean enabled() {
        return enabled;
    }

    public String topicPattern() {
        return topicPattern;
    }

    public String[] eventTypes() {
        return eventTypes;
    }

    public String minSeverity() {
        return minSeverity;
    }

    public String templateCode() {
        return templateCode;
    }

    public Integer dedupeTtlSec() {
        return dedupeTtlSec;
    }

    public LocalDateTime createdAt() {
        return createdAt;
    }

    public LocalDateTime updatedAt() {
        return updatedAt;
    }

    // Setters

    public void setId(Long id) {
        this.id = id;
    }

    public void setChatId(Long chatId) {
        this.chatId = chatId;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public void setTopicPattern(String topicPattern) {
        this.topicPattern = topicPattern;
    }

    public void setEventTypes(String[] eventTypes) {
        this.eventTypes = eventTypes;
    }

    public void setMinSeverity(String minSeverity) {
        this.minSeverity = minSeverity;
    }

    public void setTemplateCode(String templateCode) {
        this.templateCode = templateCode;
    }

    public void setDedupeTtlSec(Integer dedupeTtlSec) {
        this.dedupeTtlSec = dedupeTtlSec;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public String toString() {
        return String.format(
            "PostSubscription[id=%d, chatId=%d, pattern=%s, types=%s, severity>=%s, template=%s]",
            id,
            chatId,
            topicPattern,
            eventTypes != null ? String.join(",", eventTypes) : "[]",
            minSeverity,
            templateCode
        );
    }
}
