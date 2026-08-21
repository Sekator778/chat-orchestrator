package com.example.telegramuserbot.service.llm;

import com.example.telegramuserbot.domain.ResponseStyle;
import com.example.telegramuserbot.domain.ResponseTone;
import com.example.telegramuserbot.service.llm.client.DeepSeekApiClient;
import com.example.telegramuserbot.service.llm.dto.DeepSeekChatRequest;
import com.example.telegramuserbot.service.llm.dto.DeepSeekChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Minimal LLM client for the orchestrator.
 * All prompt building, context, and personalization logic is handled by upstream services.
 * Delegates actual API calls to DeepSeekApiClient to eliminate code duplication.
 *
 * @author Development Team
 */
@Service
public class EnhancedLlmService {

    private static final Logger log = LoggerFactory.getLogger(EnhancedLlmService.class);

    private final DeepSeekApiClient deepSeekApiClient;

    /**
     * Constructs EnhancedLlmService with the unified DeepSeek API client.
     *
     * @param deepSeekApiClient the unified API client for LLM operations
     */
    public EnhancedLlmService(DeepSeekApiClient deepSeekApiClient) {
        this.deepSeekApiClient = deepSeekApiClient;
    }

    /**
     * Legacy method kept for compatibility.
     * The orchestrator is responsible for building the request.
     *
     * @param chatId the chat identifier
     * @param triggeringMessageId the message that triggered this generation
     * @return empty Mono as this method is deprecated
     * @deprecated Use callDeepSeekApi with a properly constructed request
     */
    @Deprecated(since = "2026-01", forRemoval = true)
    public Mono<EnhancedLlmResponse> generateEnhancedReply(long chatId, long triggeringMessageId) {
        log.warn("[Chat {}] generateEnhancedReply called but is deprecated", chatId);
        return Mono.empty();
    }

    /**
     * Executes a DeepSeek API call and returns the response content.
     * Delegates to DeepSeekApiClient for actual API interaction.
     *
     * @param request the chat request with messages and parameters
     * @param chatId the chat identifier for logging
     * @return Mono containing response content or empty on error
     */
    public Mono<String> callDeepSeekApi(DeepSeekChatRequest request, long chatId) {
        return deepSeekApiClient.chat(request, chatId);
    }

    public Mono<DeepSeekChatResponse> callDeepSeekApiWithResponse(DeepSeekChatRequest request, long chatId) {
        return deepSeekApiClient.chatWithResponse(request, chatId);
    }

    public record EnhancedLlmResponse(String formattedContent, String rawContent, ResponseStyle style,
                                      ResponseTone tone, int contextMessages, int contextCharacters,
                                      ResponseFormat format, com.example.telegramuserbot.domain.ResponseTemplate template) {

        public EnhancedLlmResponse withFormattedContent(String newContent) {
            return new EnhancedLlmResponse(newContent, rawContent, style, tone, contextMessages, contextCharacters, format, template);
        }
    }

    public enum ResponseFormat { TEXT, MARKDOWN, JSON, HTML }
}
