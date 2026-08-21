package com.example.telegramuserbot.domain;

/**
 * Status of a pending response in the queue.
 *
 * Lifecycle:
 * PENDING -> ELIGIBLE -> SENT
 *         -> EXPIRED (if timeout occurs)
 */
public enum PendingResponseStatus {
    /**
     * Response is queued, waiting for required number of human replies.
     */
    PENDING,

    /**
     * Required number of human replies reached, response is ready to be sent.
     */
    ELIGIBLE,

    /**
     * Response is claimed by a scheduler run and is being sent.
     */
    SENDING,

    /**
     * Response has been sent to Telegram.
     */
    SENT,

    /**
     * Response expired before reaching eligibility or being sent.
     */
    EXPIRED
}
