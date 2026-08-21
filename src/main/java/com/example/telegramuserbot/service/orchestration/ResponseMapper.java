package com.example.telegramuserbot.service.orchestration;

import com.example.telegramuserbot.domain.ResponseStyle;
import com.example.telegramuserbot.dto.ResponsePayload;
import com.example.telegramuserbot.service.llm.EnhancedLlmService;
import org.springframework.stereotype.Component;

/**
 * Маппер EnhancedLlmResponse -> ResponsePayload с лёгкой постобработкой.
 */
@Component
public class ResponseMapper {

    private final ResponsePostProcessor responsePostProcessor;

    public ResponseMapper(ResponsePostProcessor responsePostProcessor) {
        this.responsePostProcessor = responsePostProcessor;
    }

    public ResponsePayload mapEnhanced(EnhancedLlmService.EnhancedLlmResponse response, int ctxMessages, int ctxChars) {
        String processed = responsePostProcessor.postProcess(response.formattedContent(), response.template());
        return ResponsePayload.ofEnhanced(
                processed,
                response.style() != null ? response.style() : ResponseStyle.ADAPTIVE,
                response.tone(),
                ctxMessages,
                ctxChars,
                response.format().name()
        );
    }
}

