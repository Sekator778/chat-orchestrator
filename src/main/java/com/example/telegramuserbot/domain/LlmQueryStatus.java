package com.example.telegramuserbot.domain;

/**
 * Lifecycle state for an orchestrated LLM query.
 */
public enum LlmQueryStatus {
    IN_PROGRESS,
    COMPLETED,
    SKIPPED,
    FAILED
}
