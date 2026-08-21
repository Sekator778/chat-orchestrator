package com.example.telegramuserbot.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.List;
import java.util.Objects;

@Table("topic_restrictions")
public class TopicRestriction {
    @Id
    private Long id;

    @Column("chat_config_id")
    private Long chatConfigId;

    @Column("restriction_name")
    private String restrictionName;

    @Column("restriction_type")
    private RestrictionType restrictionType;

    @Column("keywords")
    private String keywords; // Comma-separated or JSON

    @Column("categories")
    private String categories; // Comma-separated categories

    @Column("action_type")
    private ActionType actionType = ActionType.IGNORE;

    @Column("custom_response")
    private String customResponse;

    @Column("active")
    private boolean active = true;

    // Constructors
    public TopicRestriction() {}

    public TopicRestriction(Long chatConfigId, String restrictionName, RestrictionType restrictionType) {
        this.chatConfigId = chatConfigId;
        this.restrictionName = restrictionName;
        this.restrictionType = restrictionType;
    }

    // Getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getChatConfigId() { return chatConfigId; }
    public void setChatConfigId(Long chatConfigId) { this.chatConfigId = chatConfigId; }

    public String getRestrictionName() { return restrictionName; }
    public void setRestrictionName(String restrictionName) { this.restrictionName = restrictionName; }

    public RestrictionType getRestrictionType() { return restrictionType; }
    public void setRestrictionType(RestrictionType restrictionType) { this.restrictionType = restrictionType; }

    public String getKeywords() { return keywords; }
    public void setKeywords(String keywords) { this.keywords = keywords; }

    public String getCategories() { return categories; }
    public void setCategories(String categories) { this.categories = categories; }

    public ActionType getActionType() { return actionType; }
    public void setActionType(ActionType actionType) { this.actionType = actionType; }

    public String getCustomResponse() { return customResponse; }
    public void setCustomResponse(String customResponse) { this.customResponse = customResponse; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    // Compatibility methods for service layer
    public List<String> getRestrictedKeywords() {
        if (keywords == null || keywords.trim().isEmpty()) {
            return new java.util.ArrayList<>();
        }
        return java.util.Arrays.asList(keywords.split(","))
                .stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toList());
    }

    public void setRestrictedKeywords(List<String> restrictedKeywords) {
        if (restrictedKeywords == null || restrictedKeywords.isEmpty()) {
            this.keywords = null;
        } else {
            this.keywords = String.join(",", restrictedKeywords);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TopicRestriction that = (TopicRestriction) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "TopicRestriction{" +
                "id=" + id +
                ", restrictionName='" + restrictionName + '\'' +
                ", restrictionType=" + restrictionType +
                ", actionType=" + actionType +
                ", active=" + active +
                '}';
    }
}