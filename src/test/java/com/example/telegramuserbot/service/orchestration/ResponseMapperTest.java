package com.example.telegramuserbot.service.orchestration;

import com.example.telegramuserbot.domain.ResponseStyle;
import com.example.telegramuserbot.domain.ResponseTone;
import com.example.telegramuserbot.dto.ResponsePayload;
import com.example.telegramuserbot.service.llm.EnhancedLlmService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ResponseMapper.mapEnhanced is a pure mapper (WP: every handler already ran the text
 * through ResponsePostProcessor with the persona's own language/style, so a second pass
 * here would undo those decisions) — these tests pin the "no post-processing here"
 * contract and the plain field carry-over.
 */
class ResponseMapperTest {

    private final ResponseMapper mapper = new ResponseMapper();

    @Test
    void formattedContentPassesThroughVerbatimIncludingEmojiAndTrailingPeriod() {
        String text = "Согласен на все сто. 👍";
        EnhancedLlmService.EnhancedLlmResponse response = new EnhancedLlmService.EnhancedLlmResponse(
                text, "raw content", ResponseStyle.CONCISE, ResponseTone.CASUAL, 3, 120,
                EnhancedLlmService.ResponseFormat.TEXT, null);

        ResponsePayload payload = mapper.mapEnhanced(response, 3, 120);

        assertThat(payload.content()).isEqualTo(text);
    }

    @Test
    void nullStyleDefaultsToAdaptive() {
        EnhancedLlmService.EnhancedLlmResponse response = new EnhancedLlmService.EnhancedLlmResponse(
                "hello", "raw", null, ResponseTone.NEUTRAL, 1, 10,
                EnhancedLlmService.ResponseFormat.TEXT, null);

        ResponsePayload payload = mapper.mapEnhanced(response, 1, 10);

        assertThat(payload.style()).isEqualTo(ResponseStyle.ADAPTIVE);
    }

    @Test
    void nonNullStyleToneCtxCountsAndFormatNameAreCarriedOverVerbatim() {
        EnhancedLlmService.EnhancedLlmResponse response = new EnhancedLlmService.EnhancedLlmResponse(
                "hi there", "raw", ResponseStyle.DETAILED, ResponseTone.ENTHUSIASTIC, 7, 555,
                EnhancedLlmService.ResponseFormat.MARKDOWN, null);

        ResponsePayload payload = mapper.mapEnhanced(response, 7, 555);

        assertThat(payload.style()).isEqualTo(ResponseStyle.DETAILED);
        assertThat(payload.tone()).isEqualTo(ResponseTone.ENTHUSIASTIC);
        assertThat(payload.contextMessages()).isEqualTo(7);
        assertThat(payload.contextCharacters()).isEqualTo(555);
        assertThat(payload.format()).isEqualTo("MARKDOWN");
    }
}
