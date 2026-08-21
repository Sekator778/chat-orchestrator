package com.example.telegramuserbot.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Represents a chat history synchronization job.
 * Tracks the status, progress, and configuration of sync operations.
 */
@Table("sync_jobs")
public class SyncJob {

    @Id
    private Long id;

    @Column("channel_id")
    private Long channelId;

    @Column("status")
    private SyncStatus status = SyncStatus.PENDING;

    @Column("sync_depth_days")
    private Integer syncDepthDays;

    @Column("sync_from_date")
    private LocalDateTime syncFromDate;

    @Column("sync_to_date")
    private LocalDateTime syncToDate;

    @Column("messages_processed")
    private Long messagesProcessed = 0L;

    @Column("messages_total")
    private Long messagesTotal;

    @Column("error_message")
    private String errorMessage;

    @Column("created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column("started_at")
    private LocalDateTime startedAt;

    @Column("completed_at")
    private LocalDateTime completedAt;

    @Column("created_by_user_id")
    private Long createdByUserId;

    @Column("bot_instance_id")
    private String botInstanceId;

    // Constructors
    public SyncJob() {}

    public SyncJob(Long channelId, Integer syncDepthDays, Long createdByUserId) {
        this.channelId = channelId;
        this.syncDepthDays = syncDepthDays;
        this.createdByUserId = createdByUserId;
        this.syncToDate = LocalDateTime.now();
        // null syncDepthDays means "full history" — syncFromDate stays null so no date filter is applied
        this.syncFromDate = syncDepthDays != null ? syncToDate.minusDays(syncDepthDays) : null;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getChannelId() { return channelId; }
    public void setChannelId(Long channelId) { this.channelId = channelId; }

    public SyncStatus getStatus() { return status; }
    public void setStatus(SyncStatus status) { this.status = status; }

    public Integer getSyncDepthDays() { return syncDepthDays; }
    public void setSyncDepthDays(Integer syncDepthDays) { this.syncDepthDays = syncDepthDays; }

    public LocalDateTime getSyncFromDate() { return syncFromDate; }
    public void setSyncFromDate(LocalDateTime syncFromDate) { this.syncFromDate = syncFromDate; }

    public LocalDateTime getSyncToDate() { return syncToDate; }
    public void setSyncToDate(LocalDateTime syncToDate) { this.syncToDate = syncToDate; }

    public Long getMessagesProcessed() { return messagesProcessed; }
    public void setMessagesProcessed(Long messagesProcessed) { this.messagesProcessed = messagesProcessed; }

    public Long getMessagesTotal() { return messagesTotal; }
    public void setMessagesTotal(Long messagesTotal) { this.messagesTotal = messagesTotal; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }

    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }

    public Long getCreatedByUserId() { return createdByUserId; }
    public void setCreatedByUserId(Long createdByUserId) { this.createdByUserId = createdByUserId; }

    public String getBotInstanceId() { return botInstanceId; }
    public void setBotInstanceId(String botInstanceId) { this.botInstanceId = botInstanceId; }

    /**
     * Calculates the completion percentage for this sync job.
     * @return percentage (0-100) or null if total is not set
     */
    public Double getCompletionPercentage() {
        if (messagesTotal == null || messagesTotal == 0) {
            return null;
        }
        return (messagesProcessed.doubleValue() / messagesTotal.doubleValue()) * 100.0;
    }

    /**
     * Marks the job as started and sets the started timestamp.
     */
    public void markAsStarted() {
        this.status = SyncStatus.IN_PROGRESS;
        this.startedAt = LocalDateTime.now();
    }

    /**
     * Marks the job as completed successfully.
     */
    public void markAsCompleted() {
        this.status = SyncStatus.COMPLETED;
        this.completedAt = LocalDateTime.now();
    }

    /**
     * Marks the job as failed with an error message.
     */
    public void markAsFailed(String errorMessage) {
        this.status = SyncStatus.FAILED;
        this.errorMessage = errorMessage;
        this.completedAt = LocalDateTime.now();
    }

    /**
     * Increments the processed message count.
     */
    public void incrementProcessedMessages() {
        this.messagesProcessed++;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SyncJob syncJob = (SyncJob) o;
        return Objects.equals(id, syncJob.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "SyncJob{" +
                "id=" + id +
                ", channelId=" + channelId +
                ", status=" + status +
                ", syncDepthDays=" + syncDepthDays +
                ", messagesProcessed=" + messagesProcessed +
                ", messagesTotal=" + messagesTotal +
                '}';
    }
}
