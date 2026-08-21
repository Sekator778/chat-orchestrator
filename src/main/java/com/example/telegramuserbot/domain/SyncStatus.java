package com.example.telegramuserbot.domain;

/**
 * Enumeration representing the status of a sync job.
 */
public enum SyncStatus {
    /**
     * Job is created but not yet started.
     */
    PENDING("Очікування", "Job is waiting to be processed"),
    
    /**
     * Job is currently being processed.
     */
    IN_PROGRESS("Виконується", "Job is currently running"),
    
    /**
     * Job completed successfully.
     */
    COMPLETED("Завершено", "Job completed successfully"),
    
    /**
     * Job failed due to an error.
     */
    FAILED("Помилка", "Job failed with an error"),
    
    /**
     * Job was cancelled by user or system.
     */
    CANCELLED("Скасовано", "Job was cancelled");

    private final String displayName;
    private final String description;

    SyncStatus(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Checks if the status indicates the job is active/running.
     */
    public boolean isActive() {
        return this == PENDING || this == IN_PROGRESS;
    }

    /**
     * Checks if the status indicates the job is finished (either successfully or with error).
     */
    public boolean isFinished() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}