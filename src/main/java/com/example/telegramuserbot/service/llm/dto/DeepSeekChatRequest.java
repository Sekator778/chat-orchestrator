package com.example.telegramuserbot.service.llm.dto;

import java.util.List;

/**
 * Enhanced DeepSeek Chat Request with dynamic parameters support
 * @author Sekator
 * @created 27 кві, 2025
 */
public record DeepSeekChatRequest(
        List<ApiMessage> messages,
        String model,
        Integer max_tokens,          // Dynamic from chat config
        Double temperature,          // Dynamic from chat config
        Double frequency_penalty,    // Optional parameter
        Double presence_penalty,     // Optional parameter
        Double top_p,               // Optional parameter
        Boolean stream              // Always false for our use case
) {
    // Constructor with defaults for backward compatibility
    public DeepSeekChatRequest(List<ApiMessage> messages, String model) {
        this(messages, model, null, null, null, null, null, false);
    }
    
    // Constructor with chat config parameters
    public DeepSeekChatRequest(List<ApiMessage> messages, String model, Integer maxTokens, Double temperature) {
        this(messages, model, maxTokens, temperature, null, null, null, false);
    }
}
