package com.example.telegramuserbot.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.time.LocalTime;

/**
 * Registry row for a real Telegram account the application runs (one TDLight
 * session per row, stored in bot.telegram_accounts). Identity/state only —
 * secrets (api_id/api_hash) stay in configuration.
 */
@Table(name = "telegram_accounts", schema = "bot")
public class TelegramAccount {
    @Id
    private Long id;
    @Column("bot_id")
    private String botId;
    @Column("name")
    private String name;
    @Column("phone_number")
    private String phoneNumber;
    @Column("telegram_user_id")
    private Long telegramUserId;
    @Column("is_collector")
    private boolean collector;
    @Column("status")
    private String status;
    @Column("sessions_directory")
    private String sessionsDirectory;
    @Column("active_from")
    private LocalTime activeFrom;
    @Column("active_until")
    private LocalTime activeUntil;
    @Column("timezone")
    private String timezone;
    @Column("created_at")
    private Instant createdAt;
    @Column("updated_at")
    private Instant updatedAt;

    // Sibling-reply per-persona knobs (changeset 073)
    @Column("sibling_reply_probability")
    private double siblingReplyProbability = 0.0;

    @Column("sibling_reply_min_delay_sec")
    private int siblingReplyMinDelaySec = 90;

    @Column("sibling_reply_max_delay_sec")
    private int siblingReplyMaxDelaySec = 600;

    @Column("sibling_reply_max_per_day")
    private int siblingReplyMaxPerDay = 3;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getBotId() {
        return botId;
    }

    public void setBotId(String botId) {
        this.botId = botId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPhoneNumber() {
        return phoneNumber;
    }

    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    public Long getTelegramUserId() {
        return telegramUserId;
    }

    public void setTelegramUserId(Long telegramUserId) {
        this.telegramUserId = telegramUserId;
    }

    public boolean isCollector() {
        return collector;
    }

    public void setCollector(boolean collector) {
        this.collector = collector;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSessionsDirectory() {
        return sessionsDirectory;
    }

    public void setSessionsDirectory(String sessionsDirectory) {
        this.sessionsDirectory = sessionsDirectory;
    }

    public LocalTime getActiveFrom() {
        return activeFrom;
    }

    public void setActiveFrom(LocalTime activeFrom) {
        this.activeFrom = activeFrom;
    }

    public LocalTime getActiveUntil() {
        return activeUntil;
    }

    public void setActiveUntil(LocalTime activeUntil) {
        this.activeUntil = activeUntil;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
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

    public double getSiblingReplyProbability() { return siblingReplyProbability; }
    public void setSiblingReplyProbability(double siblingReplyProbability) { this.siblingReplyProbability = siblingReplyProbability; }

    public int getSiblingReplyMinDelaySec() { return siblingReplyMinDelaySec; }
    public void setSiblingReplyMinDelaySec(int siblingReplyMinDelaySec) { this.siblingReplyMinDelaySec = siblingReplyMinDelaySec; }

    public int getSiblingReplyMaxDelaySec() { return siblingReplyMaxDelaySec; }
    public void setSiblingReplyMaxDelaySec(int siblingReplyMaxDelaySec) { this.siblingReplyMaxDelaySec = siblingReplyMaxDelaySec; }

    public int getSiblingReplyMaxPerDay() { return siblingReplyMaxPerDay; }
    public void setSiblingReplyMaxPerDay(int siblingReplyMaxPerDay) { this.siblingReplyMaxPerDay = siblingReplyMaxPerDay; }
}
