package com.example.telegramuserbot.service.orchestration;

import com.example.telegramuserbot.domain.ResponseStyle;
import com.example.telegramuserbot.dto.ResponsePayload;
import com.example.telegramuserbot.service.llm.EnhancedLlmService;
import org.springframework.stereotype.Component;

/**
 * Маппер EnhancedLlmResponse -> ResponsePayload.
 *
 * <p>Pure mapping: every handler has already run the text through
 * {@link ResponsePostProcessor} with the persona's own language and style, so a
 * second pass here (which could only use "auto" + default style) would undo the
 * persona-specific decisions — emoji kept for an OFTEN persona, a final period
 * kept for one that never drops it.
 */
@Component
public class ResponseMapper {

    public ResponsePayload mapEnhanced(EnhancedLlmService.EnhancedLlmResponse response, int ctxMessages, int ctxChars) {
        return ResponsePayload.ofEnhanced(
                response.formattedContent(),
                response.style() != null ? response.style() : ResponseStyle.ADAPTIVE,
                response.tone(),
                ctxMessages,
                ctxChars,
                response.format().name()
        );
    }
}
