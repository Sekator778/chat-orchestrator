package com.example.telegramuserbot.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.Objects;

@Table(name = "chat_configs", schema = "bot")
public class ChatConfig {
    @Id
    private Long id;

    @Column("channel_chat_id") // Corrected column name
    private Long channelId;

    @Column("prompt_template")
    private String promptTemplate;

    @Column("language")
    private String language;

    @Column("context_window_size")
    private Integer contextWindowSize = 10;

    @Column("primary_channel_id")
    private Long primaryChannelId;

    @Column("primary_channel_checked_at")
    private Instant primaryChannelCheckedAt;

    @Column("enabled")
    private boolean enabled;

    // --- Optional LLM Params ---
    @Column("max_tokens")
    private Integer maxTokens;

	@Column("temperature")
	private Double temperature;

    // --- Sync Configuration ---
    @Column("default_sync_depth_days")
    private Integer defaultSyncDepthDays;

    @Column("auto_sync_enabled")
    private Boolean autoSyncEnabled = false;

    @Column("sync_enabled")
    private boolean syncEnabled = false;

    @Column("respond_to_forwarded_bot_messages")
    private boolean respondToForwardedBotMessages = false;

	@Column("wait_for_human_replies_count")
	private Integer waitForHumanRepliesCount = -1;

	@Column("multi_stage_enabled")
	private boolean multiStageEnabled = false;

    // --- Channel Processing Pipeline ---
    @Column("processing_phase")
    private ProcessingPhase processingPhase = ProcessingPhase.RAW;

    @Column("last_phase1_at")
    private Instant lastPhase1At;

    @Column("last_phase2_at")
    private Instant lastPhase2At;

    @Column("last_phase3_at")
    private Instant lastPhase3At;

    @Column("last_processing_error")
    private String lastProcessingError;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getChannelId() { return channelId; }
    public void setChannelId(Long channelId) { this.channelId = channelId; }
    public String getPromptTemplate() { return promptTemplate; }
    public void setPromptTemplate(String promptTemplate) { this.promptTemplate = promptTemplate; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public Integer getContextWindowSize() { return contextWindowSize; }
    public void setContextWindowSize(Integer contextWindowSize) { this.contextWindowSize = contextWindowSize; }
    public Long getPrimaryChannelId() { return primaryChannelId; }
    public void setPrimaryChannelId(Long primaryChannelId) { this.primaryChannelId = primaryChannelId; }
    public Instant getPrimaryChannelCheckedAt() { return primaryChannelCheckedAt; }
    public void setPrimaryChannelCheckedAt(Instant primaryChannelCheckedAt) { this.primaryChannelCheckedAt = primaryChannelCheckedAt; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
	public Integer getMaxTokens() { return maxTokens; }
	public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }
	public Double getTemperature() { return temperature; }
	public void setTemperature(Double temperature) { this.temperature = temperature; }

    public Integer getDefaultSyncDepthDays() {
        return defaultSyncDepthDays;
    }

    public void setDefaultSyncDepthDays(Integer defaultSyncDepthDays) {
        this.defaultSyncDepthDays = defaultSyncDepthDays;
    }

    public Boolean getAutoSyncEnabled() {
        return autoSyncEnabled;
    }

    public void setAutoSyncEnabled(Boolean autoSyncEnabled) {
        this.autoSyncEnabled = autoSyncEnabled;
    }

    public boolean isSyncEnabled() {
        return syncEnabled;
    }

    public void setSyncEnabled(boolean syncEnabled) {
        this.syncEnabled = syncEnabled;
    }

    public boolean isRespondToForwardedBotMessages() {
        return respondToForwardedBotMessages;
    }

    public void setRespondToForwardedBotMessages(boolean respondToForwardedBotMessages) {
        this.respondToForwardedBotMessages = respondToForwardedBotMessages;
    }

	public Integer getWaitForHumanRepliesCount() {
		return waitForHumanRepliesCount;
	}

	public void setWaitForHumanRepliesCount(Integer waitForHumanRepliesCount) {
		this.waitForHumanRepliesCount = waitForHumanRepliesCount;
	}

	public boolean isMultiStageEnabled() {
		return multiStageEnabled;
	}

	public void setMultiStageEnabled(boolean multiStageEnabled) {
		this.multiStageEnabled = multiStageEnabled;
	}

    public ProcessingPhase getProcessingPhase() {
        return processingPhase;
    }

    public void setProcessingPhase(ProcessingPhase processingPhase) {
        this.processingPhase = processingPhase;
    }

    public Instant getLastPhase1At() {
        return lastPhase1At;
    }

    public void setLastPhase1At(Instant lastPhase1At) {
        this.lastPhase1At = lastPhase1At;
    }

    public Instant getLastPhase2At() {
        return lastPhase2At;
    }

    public void setLastPhase2At(Instant lastPhase2At) {
        this.lastPhase2At = lastPhase2At;
    }

    public Instant getLastPhase3At() {
        return lastPhase3At;
    }

    public void setLastPhase3At(Instant lastPhase3At) {
        this.lastPhase3At = lastPhase3At;
    }

    public String getLastProcessingError() {
        return lastProcessingError;
    }

    public void setLastProcessingError(String lastProcessingError) {
        this.lastProcessingError = lastProcessingError;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ChatConfig that = (ChatConfig) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
