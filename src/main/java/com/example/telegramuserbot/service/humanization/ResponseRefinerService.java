package com.example.telegramuserbot.service.humanization;

import reactor.core.publisher.Mono;

/**
 * Service for refining responses through secondary LLM processing to ensure human-like output
 */
public interface ResponseRefinerService {
    
    /**
     * Refine a response to make it more human-like and remove any AI indicators
     * @param originalResponse The original response from LLM
     * @param userQuestion The user's original question for context
     * @param userId User ID for personalization (can be null)
     * @return Refined human-like response
     */
    Mono<String> refineResponse(String originalResponse, String userQuestion, Long userId);
    
    /**
     * Check if response needs refinement (contains AI indicators)
     * @param response The response to check
     * @return true if refinement is needed
     */
    boolean needsRefinement(String response);
    
    /**
     * Generate alternative human response if original is too AI-like
     * @param userQuestion The user's question
     * @param userId User ID for context
     * @return Alternative human-like response
     */
    String generateAlternativeResponse(String userQuestion, Long userId);
}
