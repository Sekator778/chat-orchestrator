package com.example.telegramuserbot.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * Represents a TDLib operation that requires coordination.
 * Used for distributed locking across multiple bot instances
 * to prevent concurrent state-modifying operations.
 */
@Table(schema = "bot", name = "tdlib_operations")
public class TdLibOperation {

    @Id
    private Long id;

    @Column("operation_type")
    private TdLibOperationType operationType;

    @Column("bot_instance_id")
    private String botInstanceId;

    @Column("resource_id")
    private String resourceId;

    @Column("status")
    private TdLibOperationStatus status = TdLibOperationStatus.IN_PROGRESS;

    @Column("started_at")
    private OffsetDateTime startedAt = OffsetDateTime.now();

    @Column("completed_at")
    private OffsetDateTime completedAt;

    @Column("timeout_at")
    private OffsetDateTime timeoutAt;

    @Column("error_message")
    private String errorMessage;

    @Column("details")
    private String details;

    @Column("heartbeat_at")
    private OffsetDateTime heartbeatAt;

    /**
     * Default constructor required by R2DBC.
     */
    public TdLibOperation() {
    }

    /**
     * Creates a new operation with the specified type and bot instance.
     *
     * @param operationType the type of operation
     * @param botInstanceId the bot instance that owns this operation
     */
    public TdLibOperation(TdLibOperationType operationType, String botInstanceId) {
        this.operationType = operationType;
        this.botInstanceId = botInstanceId;
        this.startedAt = OffsetDateTime.now();
        this.heartbeatAt = this.startedAt;
    }

    /**
     * Creates a new operation with an associated resource.
     *
     * @param operationType the type of operation
     * @param botInstanceId the bot instance that owns this operation
     * @param resourceId optional resource identifier
     */
    public TdLibOperation(TdLibOperationType operationType, String botInstanceId, String resourceId) {
        this(operationType, botInstanceId);
        this.resourceId = resourceId;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public TdLibOperationType getOperationType() {
        return operationType;
    }

    public void setOperationType(TdLibOperationType operationType) {
        this.operationType = operationType;
    }

    public String getBotInstanceId() {
        return botInstanceId;
    }

    public void setBotInstanceId(String botInstanceId) {
        this.botInstanceId = botInstanceId;
    }

    public String getResourceId() {
        return resourceId;
    }

    public void setResourceId(String resourceId) {
        this.resourceId = resourceId;
    }

    public TdLibOperationStatus getStatus() {
        return status;
    }

    public void setStatus(TdLibOperationStatus status) {
        this.status = status;
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(OffsetDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public OffsetDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(OffsetDateTime completedAt) {
        this.completedAt = completedAt;
    }

    public OffsetDateTime getTimeoutAt() {
        return timeoutAt;
    }

    public void setTimeoutAt(OffsetDateTime timeoutAt) {
        this.timeoutAt = timeoutAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }

    public OffsetDateTime getHeartbeatAt() {
        return heartbeatAt;
    }

    public void setHeartbeatAt(OffsetDateTime heartbeatAt) {
        this.heartbeatAt = heartbeatAt;
    }

    /**
     * Sets the timeout based on a duration from now.
     *
     * @param duration the timeout duration
     */
    public void setTimeoutDuration(Duration duration) {
        this.timeoutAt = OffsetDateTime.now().plus(duration);
    }

    /**
     * Marks this operation as completed successfully.
     */
    public void markCompleted() {
        this.status = TdLibOperationStatus.COMPLETED;
        this.completedAt = OffsetDateTime.now();
    }

    /**
     * Marks this operation as failed with an error message.
     *
     * @param error the error message
     */
    public void markFailed(String error) {
        this.status = TdLibOperationStatus.FAILED;
        this.errorMessage = error;
        this.completedAt = OffsetDateTime.now();
    }

    /**
     * Marks this operation as timed out.
     */
    public void markTimeout() {
        this.status = TdLibOperationStatus.TIMEOUT;
        this.errorMessage = "Operation exceeded timeout threshold";
        this.completedAt = OffsetDateTime.now();
    }

    /**
     * Updates the heartbeat timestamp to indicate the operation is still running.
     */
    public void updateHeartbeat() {
        this.heartbeatAt = OffsetDateTime.now();
    }

    /**
     * Checks if this operation is considered stale based on timeout.
     *
     * @return true if the operation has exceeded its timeout
     */
    public boolean isStale() {
        if (status != TdLibOperationStatus.IN_PROGRESS) {
            return false;
        }
        if (timeoutAt == null) {
            return false;
        }
        return OffsetDateTime.now().isAfter(timeoutAt);
    }

    /**
     * Calculates the duration of this operation.
     *
     * @return the duration, or null if still running
     */
    public Duration getDuration() {
        OffsetDateTime end = completedAt != null ? completedAt : OffsetDateTime.now();
        return Duration.between(startedAt, end);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TdLibOperation that = (TdLibOperation) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "TdLibOperation{" +
                "id=" + id +
                ", operationType=" + operationType +
                ", botInstanceId='" + botInstanceId + '\'' +
                ", resourceId='" + resourceId + '\'' +
                ", status=" + status +
                ", startedAt=" + startedAt +
                ", completedAt=" + completedAt +
                '}';
    }
}
