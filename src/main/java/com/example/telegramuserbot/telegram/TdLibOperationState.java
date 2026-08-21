package com.example.telegramuserbot.telegram;

/**
 * Represents the state of a TDLib operation that modifies internal state.
 * Used by {@link TdLibOperationCoordinator} to track LoadChats operations.
 *
 * <p>TDLib's LoadChats operation modifies internal pagination state machine.
 * Concurrent LoadChats requests can corrupt this state, causing errors like
 * "Last server dialog date didn't increase" when the monotonicity invariant
 * is violated.</p>
 *
 * @see TdLibOperationCoordinator
 */
public enum TdLibOperationState {

    /**
     * No operation is in progress.
     * The coordinator is ready to accept new LoadChats requests.
     */
    IDLE,

    /**
     * A LoadChats operation is currently in progress.
     * No other LoadChats request should be started until transition to another state.
     */
    LOADING,

    /**
     * The last LoadChats operation completed successfully.
     * This is a transient state that quickly transitions to IDLE.
     */
    COMPLETED,

    /**
     * The last LoadChats operation failed with an error.
     * Recovery may be needed before starting new operations.
     */
    ERROR
}
