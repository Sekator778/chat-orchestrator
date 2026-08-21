package com.example.telegramuserbot.service.orchestration;

import com.example.telegramuserbot.service.llm.EnhancedLlmService;
import com.example.telegramuserbot.service.llm.client.DeepSeekApiClient;
import com.example.telegramuserbot.service.llm.dto.DeepSeekChatRequest;
import com.example.telegramuserbot.service.llm.dto.DeepSeekChatResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Упрощённый адаптер к LLM-сервису. Пустой результат пробрасывается как есть:
 * решение «молчать или нет» принимает вызывающий, скрытых повторных вызовов нет.
 */
@Component
public class LLMCaller {

    private final EnhancedLlmService enhancedLlmService;
    private final DeepSeekApiClient deepSeekApiClient;

    public LLMCaller(EnhancedLlmService enhancedLlmService, DeepSeekApiClient deepSeekApiClient) {
        this.enhancedLlmService = enhancedLlmService;
        this.deepSeekApiClient = deepSeekApiClient;
    }

    public Mono<String> callEnhanced(long chatId, long triggeringMessageId, DeepSeekChatRequest request) {
        return enhancedLlmService.callDeepSeekApi(request, chatId);
    }

    public Mono<DeepSeekChatResponse> callEnhancedWithResponse(long chatId, long triggeringMessageId, DeepSeekChatRequest request) {
        return enhancedLlmService.callDeepSeekApiWithResponse(request, chatId);
    }

    public String extractContent(DeepSeekChatResponse response) {
        return deepSeekApiClient.extractContent(response);
    }
}
