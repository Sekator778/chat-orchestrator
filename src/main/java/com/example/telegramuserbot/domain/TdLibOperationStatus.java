package com.example.telegramuserbot.domain;

/**
 * Status values for TDLib operations.
 * Tracks the lifecycle of operations from start to completion.
 */
public enum TdLibOperationStatus {

    /**
     * Operation is currently running.
     * Only one operation of a given type can be IN_PROGRESS per bot instance.
     */
    IN_PROGRESS,

    /**
     * Operation completed successfully.
     */
    COMPLETED,

    /**
     * Operation failed with an error.
     * The error message is stored in the operation record.
     */
    FAILED,

    /**
     * Operation exceeded its timeout threshold.
     * This typically indicates a stuck operation that was forcefully terminated.
     */
    TIMEOUT
}
