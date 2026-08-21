package com.example.telegramuserbot.domain;

/**
 * Phase markers for multi-step LLM response generation.
 */
public enum LlmQueryPhase {
    CONTEXT_ANALYSIS,
    RESPONSE_PLANNING,
    DRAFT_GENERATION,
    SINGLE_STAGE_GENERATION,
    POST_PROCESSING,
    AI_DETECTION,
    FINAL_DELIVERY
}
