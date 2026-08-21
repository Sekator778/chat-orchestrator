package com.example.telegramuserbot.service.llm.client;

import com.example.telegramuserbot.service.llm.dto.DeepSeekChatRequest;
import com.example.telegramuserbot.service.llm.dto.DeepSeekChatResponse;
import com.example.telegramuserbot.service.llm.dto.ResponseChoice;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Implementation of the DeepSeek API client.
 * Consolidates all LLM API call logic into a single service,
 * eliminating code duplication across multiple services.
 *
 * @author Development Team
 */
@Service
public final class DeepSeekApiClientImpl implements DeepSeekApiClient {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekApiClientImpl.class);
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    private final WebClient deepSeekWebClient;
    private final ObjectMapper objectMapper;
    private final String apiUrl;
    private final String defaultModel;
    private final Integer defaultMaxTokens;
    private final Double defaultTemperature;
    private final boolean payloadLoggingEnabled;

    /**
     * Constructs a new DeepSeekApiClientImpl with the required dependencies.
     *
     * @param deepSeekWebClient the configured WebClient for DeepSeek API
     * @param objectMapper JSON serializer for logging
     * @param apiUrl the DeepSeek API endpoint URL
     * @param defaultModel the default model to use when not specified
     * @param defaultMaxTokens the default max tokens when not specified
     * @param defaultTemperature the default temperature when not specified
     * @param payloadLoggingEnabled whether to log request/response payloads
     */
    public DeepSeekApiClientImpl(
            @Qualifier("deepSeekWebClient") WebClient deepSeekWebClient,
            ObjectMapper objectMapper,
            @Value("${deepseek.apiUrl}") String apiUrl,
            @Value("${deepseek.model}") String defaultModel,
            @Value("${deepseek.default.maxTokens:1000}") Integer defaultMaxTokens,
            @Value("${deepseek.default.temperature:0.7}") Double defaultTemperature,
            @Value("${llm.logging.downstream.payload.enabled:false}") boolean payloadLoggingEnabled) {
        this.deepSeekWebClient = deepSeekWebClient;
        this.objectMapper = objectMapper;
        this.apiUrl = apiUrl;
        this.defaultModel = defaultModel;
        this.defaultMaxTokens = defaultMaxTokens;
        this.defaultTemperature = defaultTemperature;
        this.payloadLoggingEnabled = payloadLoggingEnabled;
    }

    @Override
    public Mono<String> chat(DeepSeekChatRequest request, long chatId) {
        return chat(request, chatId, DEFAULT_TIMEOUT_SECONDS);
    }

    @Override
    public Mono<String> chat(DeepSeekChatRequest request, long chatId, int timeoutSeconds) {
        return executeRequest(request, chatId, timeoutSeconds)
                .mapNotNull(this::extractContent);
    }

    @Override
    public Mono<DeepSeekChatResponse> chatWithResponse(DeepSeekChatRequest request, long chatId) {
        return executeRequest(request, chatId, DEFAULT_TIMEOUT_SECONDS);
    }

    @Override
    public String extractContent(DeepSeekChatResponse response) {
        if (response == null) {
            return null;
        }
        if (response.choices() == null || response.choices().isEmpty()) {
            log.warn("DeepSeek response has no choices");
            return null;
        }
        ResponseChoice choice = response.choices().get(0);
        if (choice == null || choice.message() == null) {
            log.warn("DeepSeek response choice has no message");
            return null;
        }
        String content = choice.message().content();
        if (content == null || content.isBlank()) {
            log.warn("DeepSeek response message content is empty");
            return null;
        }
        return content.trim();
    }

    private Mono<DeepSeekChatResponse> executeRequest(DeepSeekChatRequest request, long chatId, int timeoutSeconds) {
        DeepSeekChatRequest resolved = resolveDefaults(request);
        logRequest(resolved, chatId);
        return deepSeekWebClient.post()
                .uri(apiUrl)
                .bodyValue(resolved)
                .retrieve()
                .bodyToMono(DeepSeekChatResponse.class)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .doOnNext(resp -> logResponse(resp, chatId))
                .onErrorResume(WebClientResponseException.class, error -> handleWebClientError(error, chatId))
                .onErrorResume(error -> handleGenericError(error, chatId));
    }

    private DeepSeekChatRequest resolveDefaults(DeepSeekChatRequest request) {
        String model = request.model() != null ? request.model() : defaultModel;
        Integer maxTokens = request.max_tokens() != null ? request.max_tokens() : defaultMaxTokens;
        Double temperature = request.temperature() != null ? request.temperature() : defaultTemperature;
        return new DeepSeekChatRequest(
                request.messages(),
                model,
                maxTokens,
                temperature,
                request.frequency_penalty(),
                request.presence_penalty(),
                request.top_p(),
                request.stream()
        );
    }

    private void logRequest(DeepSeekChatRequest request, long chatId) {
        if (!payloadLoggingEnabled || !log.isDebugEnabled()) {
            return;
        }
        try {
            String payload = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(request);
            log.debug("[Chat {}] DeepSeek request payload:\n{}", chatId, payload);
        } catch (JsonProcessingException e) {
            log.warn("[Chat {}] Unable to serialize DeepSeek request: {}", chatId, e.getMessage());
        }
    }

    private void logResponse(DeepSeekChatResponse response, long chatId) {
        if (!payloadLoggingEnabled || !log.isDebugEnabled()) {
            return;
        }
        try {
            String payload = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(response);
            log.debug("[Chat {}] DeepSeek response payload:\n{}", chatId, payload);
        } catch (JsonProcessingException e) {
            log.warn("[Chat {}] Unable to serialize DeepSeek response: {}", chatId, e.getMessage());
        }
    }

    private Mono<DeepSeekChatResponse> handleWebClientError(WebClientResponseException error, long chatId) {
        log.error("[Chat {}] DeepSeek API error: status={}, body={}",
                chatId, error.getStatusCode().value(), error.getResponseBodyAsString());
        return Mono.empty();
    }

    private Mono<DeepSeekChatResponse> handleGenericError(Throwable error, long chatId) {
        log.error("[Chat {}] DeepSeek API call failed: {}", chatId, error.getMessage(), error);
        return Mono.empty();
    }
}
