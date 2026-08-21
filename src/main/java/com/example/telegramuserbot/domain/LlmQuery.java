package com.example.telegramuserbot.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

@Table("llm_queries")
public class LlmQuery {

    @Id
    private Long id;

    @Column("chat_id")
    private Long chatId;

    @Column("triggering_message_id")
    private Long triggeringMessageId;

    @Column("sender_id")
    private Long senderId;

    @Column("sender_username")
    private String senderUsername;

    @Column("sender_name")
    private String senderName;

    @Column("trigger_excerpt")
    private String triggerExcerpt;

    @Column("triggered_at")
    private Instant triggeredAt;

    @Column("completed_at")
    private Instant completedAt;

    @Column("status")
    private LlmQueryStatus status = LlmQueryStatus.IN_PROGRESS;

    @Column("should_respond")
    private Boolean shouldRespond;

    @Column("decision_intent")
    private String decisionIntent;

    @Column("decision_tone")
    private String decisionTone;

    @Column("decision_confidence")
    private Double decisionConfidence;

    @Column("attempt_count")
    private Integer attemptCount = 0;

    @Column("skip_reason")
    private String skipReason;

    @Column("final_response")
    private String finalResponse;

    @Column("metadata")
    private String metadata;

    @Column("prompt_tokens")
    private Integer promptTokens;

    @Column("completion_tokens")
    private Integer completionTokens;

    @Column("total_tokens")
    private Integer totalTokens;

    @Column("created_at")
    private Instant createdAt;

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

    public Long getTriggeringMessageId() {
        return triggeringMessageId;
    }

    public void setTriggeringMessageId(Long triggeringMessageId) {
        this.triggeringMessageId = triggeringMessageId;
    }

    public Long getSenderId() {
        return senderId;
    }

    public void setSenderId(Long senderId) {
        this.senderId = senderId;
    }

    public String getSenderUsername() {
        return senderUsername;
    }

    public void setSenderUsername(String senderUsername) {
        this.senderUsername = senderUsername;
    }

    public String getSenderName() {
        return senderName;
    }

    public void setSenderName(String senderName) {
        this.senderName = senderName;
    }

    public String getTriggerExcerpt() {
        return triggerExcerpt;
    }

    public void setTriggerExcerpt(String triggerExcerpt) {
        this.triggerExcerpt = triggerExcerpt;
    }

    public Instant getTriggeredAt() {
        return triggeredAt;
    }

    public void setTriggeredAt(Instant triggeredAt) {
        this.triggeredAt = triggeredAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public LlmQueryStatus getStatus() {
        return status;
    }

    public void setStatus(LlmQueryStatus status) {
        this.status = status;
    }

    public Boolean getShouldRespond() {
        return shouldRespond;
    }

    public void setShouldRespond(Boolean shouldRespond) {
        this.shouldRespond = shouldRespond;
    }

    public String getDecisionIntent() {
        return decisionIntent;
    }

    public void setDecisionIntent(String decisionIntent) {
        this.decisionIntent = decisionIntent;
    }

    public String getDecisionTone() {
        return decisionTone;
    }

    public void setDecisionTone(String decisionTone) {
        this.decisionTone = decisionTone;
    }

    public Double getDecisionConfidence() {
        return decisionConfidence;
    }

    public void setDecisionConfidence(Double decisionConfidence) {
        this.decisionConfidence = decisionConfidence;
    }

    public Integer getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(Integer attemptCount) {
        this.attemptCount = attemptCount;
    }

    public String getSkipReason() {
        return skipReason;
    }

    public void setSkipReason(String skipReason) {
        this.skipReason = skipReason;
    }

    public String getFinalResponse() {
        return finalResponse;
    }

    public void setFinalResponse(String finalResponse) {
        this.finalResponse = finalResponse;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }

    public Integer getPromptTokens() {
        return promptTokens;
    }

    public void setPromptTokens(Integer promptTokens) {
        this.promptTokens = promptTokens;
    }

    public Integer getCompletionTokens() {
        return completionTokens;
    }

    public void setCompletionTokens(Integer completionTokens) {
        this.completionTokens = completionTokens;
    }

    public Integer getTotalTokens() {
        return totalTokens;
    }

    public void setTotalTokens(Integer totalTokens) {
        this.totalTokens = totalTokens;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
