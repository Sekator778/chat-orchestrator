package com.example.telegramuserbot.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table(name = "problematic_chats", schema = "bot")
public class ProblematicChat implements Persistable<Long> {

    @Id
    @Column("channel_chat_id")
    private Long channelChatId;
    private String reason;
    private String details;
    @Column("failure_count")
    private Integer failureCount;
    @Column("first_detected_at")
    private Instant firstDetectedAt;
    @Column("last_detected_at")
    private Instant lastDetectedAt;
    @Column("last_attempted_at")
    private Instant lastAttemptedAt;
    private String notes;

    @Transient
    private boolean newEntity;

    public ProblematicChat markNew() {
        this.newEntity = true;
        return this;
    }

    public ProblematicChat markPersisted() {
        this.newEntity = false;
        return this;
    }

    @Override
    public Long getId() {
        return channelChatId;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    public Long getChannelChatId() {
        return channelChatId;
    }

    public void setChannelChatId(Long channelChatId) {
        this.channelChatId = channelChatId;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }

    public Integer getFailureCount() {
        return failureCount;
    }

    public void setFailureCount(Integer failureCount) {
        this.failureCount = failureCount;
    }

    public Instant getFirstDetectedAt() {
        return firstDetectedAt;
    }

    public void setFirstDetectedAt(Instant firstDetectedAt) {
        this.firstDetectedAt = firstDetectedAt;
    }

    public Instant getLastDetectedAt() {
        return lastDetectedAt;
    }

    public void setLastDetectedAt(Instant lastDetectedAt) {
        this.lastDetectedAt = lastDetectedAt;
    }

    public Instant getLastAttemptedAt() {
        return lastAttemptedAt;
    }

    public void setLastAttemptedAt(Instant lastAttemptedAt) {
        this.lastAttemptedAt = lastAttemptedAt;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}
