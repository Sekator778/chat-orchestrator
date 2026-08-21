package com.example.telegramuserbot.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.Objects;

@Table("rate_limits")
public class RateLimits {
    @Id
    private Long id;

    @Column("chat_config_id")
    private Long chatConfigId;

    @Column("max_messages_per_minute")
    private Integer maxMessagesPerMinute;

    @Column("max_messages_per_hour")
    private Integer maxMessagesPerHour = 20;

    @Column("max_messages_per_day")
    private Integer maxMessagesPerDay = 100;

    @Column("current_daily_messages")
    private Integer currentDailyMessages = 0;

    @Column("max_tokens_per_day")
    private Integer maxTokensPerDay = 50000;

    @Column("pending_response_delay_seconds")
    private Integer pendingResponseDelaySeconds = 0;

    @Column("cooldown_after_limit_minutes")
    private Integer cooldownAfterLimitMinutes = 60;

    @Column("burst_limit")
    private Integer burstLimit = 3;

    @Column("burst_window_seconds")
    private Integer burstWindowSeconds = 60;

    @Column("user_specific_limits")
    private boolean userSpecificLimits = false;

    // Constructors
    public RateLimits() {}

    public RateLimits(Long chatConfigId) {
        this.chatConfigId = chatConfigId;
    }

    // Getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getChatConfigId() { return chatConfigId; }
    public void setChatConfigId(Long chatConfigId) { this.chatConfigId = chatConfigId; }

    public Integer getMaxMessagesPerMinute() { return maxMessagesPerMinute; }
    public void setMaxMessagesPerMinute(Integer maxMessagesPerMinute) { this.maxMessagesPerMinute = maxMessagesPerMinute; }

    public Integer getMaxMessagesPerHour() { return maxMessagesPerHour; }
    public void setMaxMessagesPerHour(Integer maxMessagesPerHour) { this.maxMessagesPerHour = maxMessagesPerHour; }

    public Integer getMaxMessagesPerDay() { return maxMessagesPerDay; }
    public void setMaxMessagesPerDay(Integer maxMessagesPerDay) { this.maxMessagesPerDay = maxMessagesPerDay; }

    public Integer getCurrentDailyMessages() { return currentDailyMessages; }
    public void setCurrentDailyMessages(Integer currentDailyMessages) { this.currentDailyMessages = currentDailyMessages; }

    public Integer getMaxTokensPerDay() { return maxTokensPerDay; }
    public void setMaxTokensPerDay(Integer maxTokensPerDay) { this.maxTokensPerDay = maxTokensPerDay; }

    public Integer getPendingResponseDelaySeconds() { return pendingResponseDelaySeconds; }
    public void setPendingResponseDelaySeconds(Integer pendingResponseDelaySeconds) { this.pendingResponseDelaySeconds = pendingResponseDelaySeconds; }

    public Integer getCooldownAfterLimitMinutes() { return cooldownAfterLimitMinutes; }
    public void setCooldownAfterLimitMinutes(Integer cooldownAfterLimitMinutes) { this.cooldownAfterLimitMinutes = cooldownAfterLimitMinutes; }

    public Integer getBurstLimit() { return burstLimit; }
    public void setBurstLimit(Integer burstLimit) { this.burstLimit = burstLimit; }

    public Integer getBurstWindowSeconds() { return burstWindowSeconds; }
    public void setBurstWindowSeconds(Integer burstWindowSeconds) { this.burstWindowSeconds = burstWindowSeconds; }

    public boolean isUserSpecificLimits() { return userSpecificLimits; }
    public void setUserSpecificLimits(boolean userSpecificLimits) { this.userSpecificLimits = userSpecificLimits; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RateLimits that = (RateLimits) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "RateLimits{" +
                "id=" + id +
                ", maxMessagesPerHour=" + maxMessagesPerHour +
                ", maxMessagesPerDay=" + maxMessagesPerDay +
                ", currentDailyMessages=" + currentDailyMessages +
                ", maxTokensPerDay=" + maxTokensPerDay +
                ", pendingResponseDelaySeconds=" + pendingResponseDelaySeconds +
                '}';
    }
}
