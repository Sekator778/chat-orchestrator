package com.example.telegramuserbot.service.llm.dto;

/**
 * @author Sekator
 * @created 27 кві, 2025
 */
public record ResponseChoice(
        // @JsonProperty("finish_reason") String finishReason, // опціонально
        int index,
        ResponseMessage message
        // Logprobs logprobs // опціонально
) {}
