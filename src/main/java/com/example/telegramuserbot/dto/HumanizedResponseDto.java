package com.example.telegramuserbot.dto;

import java.util.List;

/**
 * DTO for humanized response with timing and processing metadata
 */
public record HumanizedResponseDto(
        String humanizedText,
        int recommendedDelay,
        int typingDuration,
        boolean requiresSplitting,
        List<String> splitMessages,
        String emotionalTone,
        boolean wasAntiDetectionApplied,
        String adaptationSource,
        double humanizationConfidence
) {
    
    /**
     * Create a simple humanized response
     */
    public static HumanizedResponseDto simple(String text, int delay) {
        return new HumanizedResponseDto(
                text,
                delay,
                text.length() * 50, // ~50ms per character for typing
                false,
                List.of(),
                "neutral",
                false,
                "none",
                0.5
        );
    }
    
    /**
     * Create a response with anti-detection measures applied
     */
    public static HumanizedResponseDto withAntiDetection(
            String text, 
            int delay,
            int typingDuration,
            String emotionalTone,
            String adaptationSource,
            double confidence) {
        return new HumanizedResponseDto(
                text,
                delay,
                typingDuration,
                false,
                List.of(),
                emotionalTone,
                true,
                adaptationSource,
                confidence
        );
    }
    
    /**
     * Create a response that needs to be split into multiple messages
     */
    public static HumanizedResponseDto withSplitting(
            List<String> splitMessages,
            int baseDelay,
            String emotionalTone,
            String adaptationSource) {
        String fullText = String.join(" ", splitMessages);
        return new HumanizedResponseDto(
                fullText,
                baseDelay,
                splitMessages.get(0).length() * 50,
                true,
                splitMessages,
                emotionalTone,
                true,
                adaptationSource,
                0.8
        );
    }
    
    /**
     * Get the primary message to send first
     */
    public String getPrimaryMessage() {
        if (requiresSplitting && !splitMessages.isEmpty()) {
            return splitMessages.get(0);
        }
        return humanizedText;
    }
    
    /**
     * Get follow-up messages if splitting is required
     */
    public List<String> getFollowUpMessages() {
        if (requiresSplitting && splitMessages.size() > 1) {
            return splitMessages.subList(1, splitMessages.size());
        }
        return List.of();
    }
    
    /**
     * Check if this response was significantly humanized
     */
    public boolean isSignificantlyHumanized() {
        return wasAntiDetectionApplied && humanizationConfidence > 0.6;
    }
}