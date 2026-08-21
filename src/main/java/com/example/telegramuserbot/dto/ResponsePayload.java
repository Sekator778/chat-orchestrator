package com.example.telegramuserbot.dto;

import com.example.telegramuserbot.domain.ResponseStyle;
import com.example.telegramuserbot.domain.ResponseTone;

/**
 * Unified response DTO returned by the orchestration layer.
 * Captures the final text plus minimal metadata about how it was generated.
 */
public record ResponsePayload(
        String content,
        ResponseStyle style,
        ResponseTone tone,
        int contextMessages,
        int contextCharacters,
        String format,
        String pipeline
) {

    public static ResponsePayload ofConcise(String content, ResponseTone tone) {
        return new ResponsePayload(content, ResponseStyle.CONCISE, tone, 0, content != null ? content.length() : 0, "TEXT", "concise");
    }

    public static ResponsePayload ofEnhanced(String content,
                                             ResponseStyle style,
                                             ResponseTone tone,
                                             int contextMessages,
                                             int contextCharacters,
                                             String format) {
        return new ResponsePayload(content, style, tone, contextMessages, contextCharacters, format, "enhanced");
    }
}
