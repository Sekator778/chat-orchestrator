package com.example.telegramuserbot.service.orchestration;

import com.example.telegramuserbot.domain.ChatConfig;
import com.example.telegramuserbot.domain.LlmParameters;
import com.example.telegramuserbot.domain.LlmQueryPhase;
import com.example.telegramuserbot.service.llm.dto.ApiMessage;
import com.example.telegramuserbot.service.llm.dto.DeepSeekChatRequest;
import com.example.telegramuserbot.service.llm.dto.DeepSeekChatResponse;
import com.example.telegramuserbot.service.llm.dto.UsageInfo;
import com.example.telegramuserbot.service.tracking.LlmQueryTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Component
public class LlmCallService {

    private static final Logger llmPayloadLog = LoggerFactory.getLogger("llm.payload");

    private final LLMCaller llmCaller;

    @Value("${llm.logging.payload.enabled:true}")
    private boolean payloadLoggingEnabled;

    @Value("${llm.logging.payload.max-chars:60000}")
    private int payloadMaxChars;

    public LlmCallService(LLMCaller llmCaller) {
        this.llmCaller = llmCaller;
    }

    public Mono<String> call(long chatId,
                             long triggeringMessageId,
                             String pipeline,
                             List<ApiMessage> messages,
                             ChatConfig chatConfig) {
        return call(chatId, triggeringMessageId, pipeline, messages, chatConfig, null);
    }

    public Mono<String> call(long chatId,
                             long triggeringMessageId,
                             String pipeline,
                             List<ApiMessage> messages,
                             ChatConfig chatConfig,
                             LlmParameters llmParameters) {
        return call(chatId, triggeringMessageId, pipeline, messages, chatConfig, llmParameters, null, LlmQueryPhase.SINGLE_STAGE_GENERATION, 1, Map.of());
    }

    public Mono<String> call(long chatId,
                             long triggeringMessageId,
                             String pipeline,
                             List<ApiMessage> messages,
                             ChatConfig chatConfig,
                             LlmParameters llmParameters,
                             LlmQueryTracker tracker,
                             LlmQueryPhase phase,
                             int attempt,
                             Map<String, Object> metadata) {
        String model = llmParameters != null ? llmParameters.getModelName() : null;

        Integer maxTokens = chatConfig != null && chatConfig.getMaxTokens() != null
                ? chatConfig.getMaxTokens()
                : (llmParameters != null ? llmParameters.getMaxTokens() : null);
        Double temperature = chatConfig != null && chatConfig.getTemperature() != null
                ? chatConfig.getTemperature()
                : (llmParameters != null ? llmParameters.getTemperature() : null);

        Double topP = llmParameters != null ? llmParameters.getTopP() : null;
        Double frequencyPenalty = llmParameters != null ? llmParameters.getFrequencyPenalty() : null;
        Double presencePenalty = llmParameters != null ? llmParameters.getPresencePenalty() : null;

        DeepSeekChatRequest request = new DeepSeekChatRequest(
                messages,
                model,
                maxTokens,
                temperature,
                frequencyPenalty,
                presencePenalty,
                topP,
                false
        );
        logLlmRequestBody(chatId, pipeline, request);

        if (tracker == null) {
            return llmCaller.callEnhanced(chatId, triggeringMessageId, request)
                    .doOnNext(resp -> logLlmResponseBody(chatId, pipeline, resp))
                    .doOnSuccess(resp -> {
                        if (resp == null || resp.isBlank()) {
                            llmPayloadLog.debug("[LLM RESPONSE BODY] chatId={} pipeline={} empty response", chatId, pipeline);
                        }
                    });
        }

        int resolvedAttempt = Math.max(1, attempt);
        Map<String, Object> safeMetadata = metadata != null ? metadata : Map.of();
        return tracker.registerAttempt(resolvedAttempt)
                .onErrorResume(e -> Mono.empty())
                .then(llmCaller.callEnhancedWithResponse(chatId, triggeringMessageId, request))
                .flatMap(fullResponse -> {
                    String resp = llmCaller.extractContent(fullResponse);
                    logLlmResponseBody(chatId, pipeline, resp);
                    if (resp == null || resp.isBlank()) {
                        llmPayloadLog.debug("[LLM RESPONSE BODY] chatId={} pipeline={} empty response", chatId, pipeline);
                    }
                    UsageInfo usage = fullResponse != null ? fullResponse.usage() : null;
                    Mono<Void> usageMono = (usage != null)
                            ? tracker.recordUsage(usage.prompt_tokens(), usage.completion_tokens(), usage.total_tokens())
                                    .onErrorResume(e -> Mono.empty())
                            : Mono.empty();
                    String safeResp = resp != null ? resp : "";
                    return usageMono
                            .then(tracker.recordPhase(phase, resolvedAttempt, request.messages(), safeResp, safeMetadata)
                                    .onErrorResume(e -> Mono.empty()))
                            .thenReturn(safeResp);
                });
    }

    public void logNormalizedIfChanged(long chatId, String pipeline, String raw, String normalized) {
        if (!payloadLoggingEnabled || !llmPayloadLog.isDebugEnabled()) {
            return;
        }
        if (raw == null || normalized == null) {
            return;
        }
        if (raw.equals(normalized)) {
            return;
        }
        llmPayloadLog.debug("[LLM RESPONSE NORMALIZED] chatId={} pipeline={} body:\n{}", chatId, pipeline, truncate(normalized));
    }

    private void logLlmRequestBody(long chatId, String pipeline, DeepSeekChatRequest request) {
        if (!payloadLoggingEnabled || !llmPayloadLog.isDebugEnabled() || request == null) {
            return;
        }

        List<ApiMessage> messages = request.messages();
        if (messages == null || messages.isEmpty()) {
            llmPayloadLog.debug("[LLM REQUEST BODY] chatId={} pipeline={} body: empty messages", chatId, pipeline);
            return;
        }

        ApiMessage system = messages.get(0);
        String systemContent = system != null ? system.content() : null;

        StringBuilder sb = new StringBuilder();
        sb.append("[LLM REQUEST BODY] chatId=").append(chatId)
                .append(" pipeline=").append(pipeline)
                .append(" max_tokens=").append(request.max_tokens())
                .append(" temperature=").append(request.temperature())
                .append(" messages=").append(messages.size())
                .append("\n----- SYSTEM (system prompt) -----\n")
                .append(truncate(systemContent))
                .append("\n----- CONVERSATION (prepared messages) -----");
        for (int i = 1; i < messages.size(); i++) {
            ApiMessage msg = messages.get(i);
            if (msg == null || msg.content() == null || msg.content().isBlank()) {
                continue;
            }
            sb.append("\n").append(truncate(msg.content()));
        }
        sb.append("\n----- END -----");
        llmPayloadLog.debug(sb.toString());
    }

    private void logLlmResponseBody(long chatId, String pipeline, String response) {
        if (!payloadLoggingEnabled || !llmPayloadLog.isDebugEnabled()) {
            return;
        }
        if (response == null) {
            llmPayloadLog.debug("[LLM RESPONSE BODY] chatId={} pipeline={} body: null", chatId, pipeline);
            return;
        }
        llmPayloadLog.debug("[LLM RESPONSE BODY] chatId={} pipeline={} body:\n{}", chatId, pipeline, truncate(response));
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        int limit = payloadMaxChars > 0 ? payloadMaxChars : 60000;
        if (text.length() <= limit) {
            return text;
        }
        return text.substring(0, limit) + "\n...[truncated " + (text.length() - limit) + " chars]";
    }
}
