package com.example.telegramuserbot.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalTime;
import java.util.Objects;

@Table("trigger_conditions")
public class TriggerCondition {
    @Id
    private Long id;

    @Column("chat_config_id")
    private Long chatConfigId;

    @Column("condition_name")
    private String conditionName;

    @Column("trigger_type")
    private TriggerType triggerType;

    @Column("keywords")
    private String keywords; // Comma-separated or JSON

    @Column("mention_required")
    private boolean mentionRequired = false;

    @Column("time_delay_seconds")
    private Integer timeDelaySeconds = 0;

    @Column("probability_percent")
    private Integer probabilityPercent = 100;

    @Column("active_hours_start")
    private LocalTime activeHoursStart;

    @Column("active_hours_end")
    private LocalTime activeHoursEnd;

    @Column("active_days_of_week")
    private String activeDaysOfWeek = "1,2,3,4,5,6,7"; // Monday=1, Sunday=7

    @Column("minimum_gap_minutes")
    private Integer minimumGapMinutes = 0;

    @Column("priority")
    private Integer priority = 1;

    @Column("active")
    private boolean active = true;

    @Column("response_length")
    private ResponseLength responseLength = ResponseLength.MEDIUM;

    // Constructors
    public TriggerCondition() {}

    public TriggerCondition(Long chatConfigId, String conditionName, TriggerType triggerType) {
        this.chatConfigId = chatConfigId;
        this.conditionName = conditionName;
        this.triggerType = triggerType;
    }

    // Getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getChatConfigId() { return chatConfigId; }
    public void setChatConfigId(Long chatConfigId) { this.chatConfigId = chatConfigId; }

    public String getConditionName() { return conditionName; }
    public void setConditionName(String conditionName) { this.conditionName = conditionName; }

    public TriggerType getTriggerType() { return triggerType; }
    public void setTriggerType(TriggerType triggerType) { this.triggerType = triggerType; }

    public String getKeywords() { return keywords; }
    public void setKeywords(String keywords) { this.keywords = keywords; }

    public boolean isMentionRequired() { return mentionRequired; }
    public void setMentionRequired(boolean mentionRequired) { this.mentionRequired = mentionRequired; }

    public Integer getTimeDelaySeconds() { return timeDelaySeconds; }
    public void setTimeDelaySeconds(Integer timeDelaySeconds) { this.timeDelaySeconds = timeDelaySeconds; }

    public Integer getProbabilityPercent() { return probabilityPercent; }
    public void setProbabilityPercent(Integer probabilityPercent) { this.probabilityPercent = probabilityPercent; }

    public LocalTime getActiveHoursStart() { return activeHoursStart; }
    public void setActiveHoursStart(LocalTime activeHoursStart) { this.activeHoursStart = activeHoursStart; }

    public LocalTime getActiveHoursEnd() { return activeHoursEnd; }
    public void setActiveHoursEnd(LocalTime activeHoursEnd) { this.activeHoursEnd = activeHoursEnd; }

    public String getActiveDaysOfWeek() { return activeDaysOfWeek; }
    public void setActiveDaysOfWeek(String activeDaysOfWeek) { this.activeDaysOfWeek = activeDaysOfWeek; }

    public Integer getMinimumGapMinutes() { return minimumGapMinutes; }
    public void setMinimumGapMinutes(Integer minimumGapMinutes) { this.minimumGapMinutes = minimumGapMinutes; }

    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public ResponseLength getResponseLength() { return responseLength; }
    public void setResponseLength(ResponseLength responseLength) { this.responseLength = responseLength; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TriggerCondition that = (TriggerCondition) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "TriggerCondition{" +
                "id=" + id +
                ", conditionName='" + conditionName + '\'' +
                ", triggerType=" + triggerType +
                ", active=" + active +
                '}';
    }
}