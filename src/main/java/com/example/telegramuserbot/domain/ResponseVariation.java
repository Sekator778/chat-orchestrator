package com.example.telegramuserbot.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Represents a response variation template for humanizing bot responses.
 * Multiple variations for the same intent prevent repetitive AI patterns.
 */
@Table("response_variations")
public class ResponseVariation {

    @Id
    private Long id;

    @Column("intent_type")
    private ResponseIntent intentType;

    @Column("communication_style")
    private CommunicationStyle communicationStyle;

    @Column("template_text")
    private String templateText;

    @Column("emotional_tone")
    private String emotionalTone;

    @Column("formality_level")
    private Integer formalityLevel; // 1-5 scale

    @Column("response_length")
    private ResponseLength responseLength;

    @Column("usage_count")
    private Long usageCount = 0L;

    @Column("last_used_at")
    private LocalDateTime lastUsedAt;

    @Column("enabled")
    private Boolean enabled = true;

    @Column("weight")
    private Integer weight = 10; // Higher weight = more likely to be selected

    @Column("requires_context")
    private Boolean requiresContext = false;

    @Column("created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    // Constructors
    public ResponseVariation() {}

    public ResponseVariation(ResponseIntent intentType, String templateText, CommunicationStyle style) {
        this.intentType = intentType;
        this.templateText = templateText;
        this.communicationStyle = style;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public ResponseIntent getIntentType() { return intentType; }
    public void setIntentType(ResponseIntent intentType) { this.intentType = intentType; }

    public CommunicationStyle getCommunicationStyle() { return communicationStyle; }
    public void setCommunicationStyle(CommunicationStyle communicationStyle) { this.communicationStyle = communicationStyle; }

    public String getTemplateText() { return templateText; }
    public void setTemplateText(String templateText) { this.templateText = templateText; }

    public String getEmotionalTone() { return emotionalTone; }
    public void setEmotionalTone(String emotionalTone) { this.emotionalTone = emotionalTone; }

    public Integer getFormalityLevel() { return formalityLevel; }
    public void setFormalityLevel(Integer formalityLevel) { this.formalityLevel = formalityLevel; }

    public ResponseLength getResponseLength() { return responseLength; }
    public void setResponseLength(ResponseLength responseLength) { this.responseLength = responseLength; }

    public Long getUsageCount() { return usageCount; }
    public void setUsageCount(Long usageCount) { this.usageCount = usageCount; }

    public LocalDateTime getLastUsedAt() { return lastUsedAt; }
    public void setLastUsedAt(LocalDateTime lastUsedAt) { this.lastUsedAt = lastUsedAt; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public Integer getWeight() { return weight; }
    public void setWeight(Integer weight) { this.weight = weight; }

    public Boolean getRequiresContext() { return requiresContext; }
    public void setRequiresContext(Boolean requiresContext) { this.requiresContext = requiresContext; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    /**
     * Increments usage count and updates last used timestamp
     */
    public void recordUsage() {
        this.usageCount++;
        this.lastUsedAt = LocalDateTime.now();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ResponseVariation that = (ResponseVariation) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "ResponseVariation{" +
                "id=" + id +
                ", intentType=" + intentType +
                ", communicationStyle=" + communicationStyle +
                ", formalityLevel=" + formalityLevel +
                ", usageCount=" + usageCount +
                '}';
    }
}