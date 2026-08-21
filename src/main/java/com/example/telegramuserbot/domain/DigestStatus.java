package com.example.telegramuserbot.domain;

/**
 * Status of a digest in its lifecycle.
 */
public enum DigestStatus {
    /**
     * Digest has been generated but not yet published.
     */
    GENERATED,
    /**
     * Digest has been successfully published to Telegram.
     */
    PUBLISHED,
    /**
     * Digest generation or publishing failed.
     */
    FAILED
}
