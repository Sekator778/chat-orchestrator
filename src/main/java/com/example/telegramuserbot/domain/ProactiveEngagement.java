package com.example.telegramuserbot.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * One proactive message schedule entry per bot persona per chat.
 * The bot sends one organic comment per day to each joined chat
 * when new messages have arrived since the last send.
 */
@Table(schema = "bot", name = "proactive_engagements")
public final class ProactiveEngagement {

    @Id
    private Long id;

    @Column("chat_id")
    private Long chatId;

    @Column("bot_instance_id")
    private String botInstanceId;

    @Column("language")
    private String language;

    @Column("send_hour_utc")
    private Short sendHourUtc;

    @Column("last_anchor_message_id")
    private Long lastAnchorMessageId;

    @Column("last_sent_at")
    private Instant lastSentAt;

    @Column("enabled")
    private Boolean enabled;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;

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

    public String getBotInstanceId() {
        return botInstanceId;
    }

    public void setBotInstanceId(String botInstanceId) {
        this.botInstanceId = botInstanceId;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public Short getSendHourUtc() {
        return sendHourUtc;
    }

    public void setSendHourUtc(Short sendHourUtc) {
        this.sendHourUtc = sendHourUtc;
    }

    public Long getLastAnchorMessageId() {
        return lastAnchorMessageId;
    }

    public void setLastAnchorMessageId(Long lastAnchorMessageId) {
        this.lastAnchorMessageId = lastAnchorMessageId;
    }

    public Instant getLastSentAt() {
        return lastSentAt;
    }

    public void setLastSentAt(Instant lastSentAt) {
        this.lastSentAt = lastSentAt;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
