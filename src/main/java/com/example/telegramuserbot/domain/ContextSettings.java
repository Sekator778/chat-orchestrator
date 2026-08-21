package com.example.telegramuserbot.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.Objects;

@Table("context_settings")
public class ContextSettings {
    @Id
    private Long id;

    @Column("chat_config_id")
    private Long chatConfigId;

    @Column("history_message_count")
    private Integer historyMessageCount = 10;

    @Column("history_time_window_hours")
    private Integer historyTimeWindowHours = 24;

    @Column("include_user_context")
    private boolean includeUserContext = true;

    @Column("include_media_descriptions")
    private boolean includeMediaDescriptions = true;

    @Column("context_compression_enabled")
    private boolean contextCompressionEnabled = false;

    @Column("max_context_tokens")
    private Integer maxContextTokens = 2000;

    @Column("preserve_important_messages")
    private boolean preserveImportantMessages = true;

    // Constructors
    public ContextSettings() {}

    public ContextSettings(Long chatConfigId) {
        this.chatConfigId = chatConfigId;
    }

    // Getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getChatConfigId() { return chatConfigId; }
    public void setChatConfigId(Long chatConfigId) { this.chatConfigId = chatConfigId; }

    public Integer getHistoryMessageCount() { return historyMessageCount; }
    public void setHistoryMessageCount(Integer historyMessageCount) { this.historyMessageCount = historyMessageCount; }

    public Integer getHistoryTimeWindowHours() { return historyTimeWindowHours; }
    public void setHistoryTimeWindowHours(Integer historyTimeWindowHours) { this.historyTimeWindowHours = historyTimeWindowHours; }

    public boolean isIncludeUserContext() { return includeUserContext; }
    public void setIncludeUserContext(boolean includeUserContext) { this.includeUserContext = includeUserContext; }

    public boolean isIncludeMediaDescriptions() { return includeMediaDescriptions; }
    public void setIncludeMediaDescriptions(boolean includeMediaDescriptions) { this.includeMediaDescriptions = includeMediaDescriptions; }

    public boolean isContextCompressionEnabled() { return contextCompressionEnabled; }
    public void setContextCompressionEnabled(boolean contextCompressionEnabled) { this.contextCompressionEnabled = contextCompressionEnabled; }

    public Integer getMaxContextTokens() { return maxContextTokens; }
    public void setMaxContextTokens(Integer maxContextTokens) { this.maxContextTokens = maxContextTokens; }

    public boolean isPreserveImportantMessages() { return preserveImportantMessages; }
    public void setPreserveImportantMessages(boolean preserveImportantMessages) { this.preserveImportantMessages = preserveImportantMessages; }

    // Compatibility methods for service layer
    public Integer getMaxContextMessages() { return historyMessageCount; }
    public void setMaxContextMessages(Integer maxContextMessages) { this.historyMessageCount = maxContextMessages; }

    public Integer getMaxContextHours() { return historyTimeWindowHours; }
    public void setMaxContextHours(Integer maxContextHours) { this.historyTimeWindowHours = maxContextHours; }

    public Integer getMaxContextLength() { return maxContextTokens; }
    public void setMaxContextLength(Integer maxContextLength) { this.maxContextTokens = maxContextLength; }

    public boolean isIncludeUserInfo() { return includeUserContext; }
    public void setIncludeUserInfo(boolean includeUserInfo) { this.includeUserContext = includeUserInfo; }

    public boolean isIncludeTimeStamps() { return includeMediaDescriptions; }
    public void setIncludeTimeStamps(boolean includeTimeStamps) { this.includeMediaDescriptions = includeTimeStamps; }

    public boolean isContextSummaryEnabled() { return contextCompressionEnabled; }
    public void setContextSummaryEnabled(boolean contextSummaryEnabled) { this.contextCompressionEnabled = contextSummaryEnabled; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ContextSettings that = (ContextSettings) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "ContextSettings{" +
                "id=" + id +
                ", historyMessageCount=" + historyMessageCount +
                ", historyTimeWindowHours=" + historyTimeWindowHours +
                ", includeUserContext=" + includeUserContext +
                '}';
    }
}