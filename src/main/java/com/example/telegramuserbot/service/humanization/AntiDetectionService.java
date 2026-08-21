package com.example.telegramuserbot.service.humanization;

import com.example.telegramuserbot.dto.MessageContextDto;
import reactor.core.publisher.Mono;

/**
 * Service for advanced anti-detection and pattern breaking.
 * Implements sophisticated techniques to prevent AI detection.
 */
public interface AntiDetectionService {
    
    /**
     * Analyze response for AI-like patterns and adjust if needed
     */
    Mono<String> analyzeAndAdjustResponse(String response, Long userId, MessageContextDto context);
    
    /**
     * Check if response exhibits typical AI patterns
     */
    boolean hasAiPatterns(String response);
    
    /**
     * Break repetitive patterns in user's response history
     */
    String breakRepetitivePatterns(String response, Long userId);
    
    /**
     * Add strategic imperfections to make response more human-like
     */
    String addStrategicImperfections(String response, Double confidenceLevel);
    
    /**
     * Monitor and adjust conversation flow to prevent suspicion
     */
    Mono<String> adjustConversationFlow(String response, MessageContextDto context, Long userId);
    
    /**
     * Calculate AI detection risk score for a response
     */
    double calculateDetectionRisk(String response, MessageContextDto context);
    
    /**
     * Apply emergency anti-detection measures for high-risk responses
     */
    Mono<String> applyEmergencyMeasures(String response, MessageContextDto context, Long userId);
    
    /**
     * Generate alternative response when original is too AI-like
     */
    Mono<String> generateAlternativeResponse(String originalResponse, MessageContextDto context, Long userId);
    
    /**
     * Check conversation for suspicious patterns over time
     */
    boolean detectSuspiciousConversationPattern(Long userId, MessageContextDto context);
    
    /**
     * Reset conversation state to break detection patterns
     */
    void resetConversationState(Long userId);
}
