package com.example.telegramuserbot.service.llm.client;

import com.example.telegramuserbot.service.llm.dto.DeepSeekChatRequest;
import com.example.telegramuserbot.service.llm.dto.DeepSeekChatResponse;
import reactor.core.publisher.Mono;

/**
 * Unified client for interacting with the DeepSeek LLM API.
 * Provides a single point of access for all LLM operations,
 * handling request building, response extraction, logging, and error handling.
 *
 * @author Development Team
 */
public interface DeepSeekApiClient {

    /**
     * Executes a chat completion request and returns the extracted content.
     * Applies default values for missing parameters (model, maxTokens, temperature).
     *
     * @param request the chat request containing messages and optional parameters
     * @param chatId the chat identifier for logging and tracking purposes
     * @return Mono containing the response content, or empty Mono on error
     */
    Mono<String> chat(DeepSeekChatRequest request, long chatId);

    /**
     * Executes a chat completion request and returns the full response.
     * Applies default values for missing parameters (model, maxTokens, temperature).
     *
     * @param request the chat request containing messages and optional parameters
     * @param chatId the chat identifier for logging and tracking purposes
     * @return Mono containing the full response, or empty Mono on error
     */
    Mono<DeepSeekChatResponse> chatWithResponse(DeepSeekChatRequest request, long chatId);

    /**
     * Executes a chat completion request with a custom timeout.
     * Applies default values for missing parameters.
     *
     * @param request the chat request containing messages and optional parameters
     * @param chatId the chat identifier for logging and tracking purposes
     * @param timeoutSeconds custom timeout in seconds for this request
     * @return Mono containing the response content, or empty Mono on error
     */
    Mono<String> chat(DeepSeekChatRequest request, long chatId, int timeoutSeconds);

    /**
     * Extracts the text content from a DeepSeek API response.
     *
     * @param response the API response to extract content from
     * @return the content string, or null if response is invalid
     */
    String extractContent(DeepSeekChatResponse response);
}
