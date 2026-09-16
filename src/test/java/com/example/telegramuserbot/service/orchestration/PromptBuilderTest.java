package com.example.telegramuserbot.service.orchestration;

import com.example.telegramuserbot.domain.ChatConfig;
import com.example.telegramuserbot.domain.ResponseTemplate;
import com.example.telegramuserbot.domain.ResponseTone;
import com.example.telegramuserbot.service.humanization.PersonaService;
import com.example.telegramuserbot.service.humanization.PersonaStyle;
import com.example.telegramuserbot.service.llm.conversation.LlmSpeakerContext;
import com.example.telegramuserbot.service.orchestration.dto.EnhancedPromptRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;

/**
 * PromptBuilder no longer serializes anything to JSON — it resolves the language
 * hint and bot id, asks PersonaService for the identity block and style, and
 * hands everything to PersonaPromptComposer. These tests verify the resolution
 * and delegation; PersonaPromptComposer's own tests cover the composed text.
 */
class PromptBuilderTest {

    private PersonaService personaService;
    private PersonaPromptComposer composer;
    private PromptBuilder promptBuilder;

    @BeforeEach
    void setUp() {
        personaService = Mockito.mock(PersonaService.class);
        composer = Mockito.mock(PersonaPromptComposer.class);
        Mockito.when(personaService.buildPersonaSystemPrompt(any(), anyString(), any())).thenReturn("identity block");
        Mockito.when(personaService.resolveStyle(any(), anyString())).thenReturn(PersonaStyle.defaults());
        Mockito.when(composer.compose(any(), anyString(), any())).thenReturn("composed prompt");
        promptBuilder = new PromptBuilder(personaService, composer);
    }

    @Test
    void shouldReturnWhatTheComposerReturns() {
        EnhancedPromptRequest request = EnhancedPromptRequest.builder()
                .fallbackPrompt("base prompt")
                .fallbackLanguage("en")
                .build();

        String prompt = promptBuilder.buildEnhancedPrompt(request);

        assertThat(prompt).isEqualTo("composed prompt");
    }

    @Test
    void shouldUseChatConfigLanguageOverFallback() {
        ChatConfig chatConfig = new ChatConfig();
        chatConfig.setLanguage("ru");
        EnhancedPromptRequest request = EnhancedPromptRequest.builder()
                .chatConfig(chatConfig)
                .fallbackPrompt("fallback")
                .fallbackLanguage("en")
                .build();

        promptBuilder.buildEnhancedPrompt(request);

        Mockito.verify(personaService).buildPersonaSystemPrompt(isNull(), eq("ru"), any());
        Mockito.verify(personaService).resolveStyle(any(), eq("ru"));
    }

    @Test
    void shouldFallBackToFallbackLanguageWhenChatConfigLanguageIsBlank() {
        ChatConfig chatConfig = new ChatConfig();
        chatConfig.setLanguage("");
        EnhancedPromptRequest request = EnhancedPromptRequest.builder()
                .chatConfig(chatConfig)
                .fallbackPrompt("fallback")
                .fallbackLanguage("uk")
                .build();

        promptBuilder.buildEnhancedPrompt(request);

        Mockito.verify(personaService).buildPersonaSystemPrompt(isNull(), eq("uk"), any());
    }

    @Test
    void shouldResolveBotIdFromSpeakerContext() {
        LlmSpeakerContext speakerContext = new LlmSpeakerContext("bot-instance-123", 1L, List.of());
        EnhancedPromptRequest request = EnhancedPromptRequest.builder()
                .speakerContext(speakerContext)
                .fallbackPrompt("test")
                .fallbackLanguage("auto")
                .build();

        promptBuilder.buildEnhancedPrompt(request);

        Mockito.verify(personaService).buildPersonaSystemPrompt(isNull(), anyString(), eq("bot-instance-123"));
        Mockito.verify(personaService).resolveStyle(eq("bot-instance-123"), anyString());
    }

    @Test
    void shouldPassNullBotIdWhenSpeakerContextAbsent() {
        EnhancedPromptRequest request = EnhancedPromptRequest.builder()
                .fallbackPrompt("test")
                .fallbackLanguage("auto")
                .build();

        promptBuilder.buildEnhancedPrompt(request);

        Mockito.verify(personaService).buildPersonaSystemPrompt(isNull(), anyString(), isNull());
    }

    @Test
    void shouldPassIdentityBlockStyleAndRequestToComposer() {
        ResponseTemplate template = new ResponseTemplate();
        template.setResponseTone(ResponseTone.FRIENDLY);
        PersonaStyle style = new PersonaStyle(3, PersonaStyle.EmojiUsage.OFTEN, true, true, 0.1, List.of(), List.of(), null);
        Mockito.when(personaService.resolveStyle(any(), anyString())).thenReturn(style);
        Mockito.when(personaService.buildPersonaSystemPrompt(any(), anyString(), any())).thenReturn("Nova identity");

        EnhancedPromptRequest request = EnhancedPromptRequest.builder()
                .template(template)
                .fallbackPrompt("test")
                .fallbackLanguage("auto")
                .build();

        promptBuilder.buildEnhancedPrompt(request);

        ArgumentCaptor<EnhancedPromptRequest> requestCaptor = ArgumentCaptor.forClass(EnhancedPromptRequest.class);
        Mockito.verify(composer).compose(requestCaptor.capture(), eq("Nova identity"), eq(style));
        assertThat(requestCaptor.getValue()).isSameAs(request);
    }

    @Test
    void shouldNotThrowWhenComposerCalledOnMinimalRequest() {
        EnhancedPromptRequest request = EnhancedPromptRequest.builder()
                .fallbackPrompt("test")
                .fallbackLanguage("auto")
                .build();

        assertThat(promptBuilder.buildEnhancedPrompt(request)).isNotNull();
    }
}
