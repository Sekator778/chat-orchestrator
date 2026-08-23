package com.example.telegramuserbot.service.orchestration;

import com.example.telegramuserbot.domain.ChatConfig;
import com.example.telegramuserbot.domain.LlmParameters;
import com.example.telegramuserbot.domain.PendingResponse;
import com.example.telegramuserbot.domain.PendingResponseStatus;
import com.example.telegramuserbot.domain.RateLimits;
import com.example.telegramuserbot.domain.ResponseFormat;
import com.example.telegramuserbot.domain.ResponseStyle;
import com.example.telegramuserbot.domain.ResponseTemplate;
import com.example.telegramuserbot.domain.ResponseTone;
import com.example.telegramuserbot.domain.User;
import com.example.telegramuserbot.service.UserService;
import com.example.telegramuserbot.service.common.TextOperationsImpl;
import com.example.telegramuserbot.service.humanization.PersonaService;
import com.example.telegramuserbot.service.llm.conversation.LlmSpeakerContext;
import com.example.telegramuserbot.service.orchestration.dto.EnhancedPromptRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

class PromptBuilderTest {

    private PersonaService personaService;
    private UserService userService;
    private PromptJsonSerializer serializer;
    private PromptBuilder promptBuilder;

    @BeforeEach
    void setUp() {
        personaService = Mockito.mock(PersonaService.class);
        userService = Mockito.mock(UserService.class);
        serializer = new PromptJsonSerializer(new TextOperationsImpl());
        Mockito.when(personaService.buildPersonaSystemPrompt(any(), anyString(), anyString())).thenReturn("persona");
        Mockito.when(userService.buildPersonalizedPrompt(any(User.class), anyString())).thenReturn("user-prefs");
        promptBuilder = new PromptBuilder(personaService, userService, serializer);
    }

    @Test
    void shouldBuildPromptWithRequestBuilder() {
        ResponseTemplate template = new ResponseTemplate();
        template.setResponseTone(ResponseTone.FRIENDLY);
        template.setMaxResponseLength(200);
        User user = new User();
        EnhancedPromptRequest request = EnhancedPromptRequest.builder()
                .template(template)
                .fallbackPrompt("base prompt")
                .fallbackLanguage("en")
                .user(user)
                .build();
        String prompt = promptBuilder.buildEnhancedPrompt(request);
        assertThat(prompt).contains("persona");
        assertThat(prompt).contains("user-prefs");
        assertThat(prompt).contains("\"prompt_template\": \"base prompt\"");
        assertThat(prompt).contains("\"language\": \"en\"");
    }

    @Test
    void shouldBuildPromptWithChatConfigOverrides() {
        ChatConfig chatConfig = new ChatConfig();
        chatConfig.setPromptTemplate("chat config prompt");
        chatConfig.setLanguage("ru");
        chatConfig.setEnabled(true);
        EnhancedPromptRequest request = EnhancedPromptRequest.builder()
                .chatConfig(chatConfig)
                .fallbackPrompt("fallback")
                .fallbackLanguage("en")
                .build();
        String prompt = promptBuilder.buildEnhancedPrompt(request);
        assertThat(prompt).contains("\"prompt_template\": \"chat config prompt\"");
        assertThat(prompt).contains("\"language\": \"ru\"");
    }

    @Test
    void shouldBuildPromptWithLlmParametersUsingBuilder() {
        LlmParameters llmParameters = new LlmParameters(1L);
        llmParameters.setModelName("gpt-4");
        llmParameters.setTemperature(0.7);
        llmParameters.setMaxTokens(2000);
        llmParameters.setResponseFormat(ResponseFormat.TEXT);
        EnhancedPromptRequest request = EnhancedPromptRequest.builder()
                .llmParameters(llmParameters)
                .fallbackPrompt("test")
                .fallbackLanguage("auto")
                .build();
        String prompt = promptBuilder.buildEnhancedPrompt(request);
        assertThat(prompt).contains("\"llm_parameters\"");
        assertThat(prompt).contains("\"model_name\": \"gpt-4\"");
        assertThat(prompt).contains("\"temperature\": 0.7");
        assertThat(prompt).contains("\"max_tokens\": 2000");
    }

    @Test
    void shouldBuildPromptWithRateLimits() {
        RateLimits rateLimits = new RateLimits(1L);
        rateLimits.setPendingResponseDelaySeconds(30);
        EnhancedPromptRequest request = EnhancedPromptRequest.builder()
                .rateLimits(rateLimits)
                .fallbackPrompt("test")
                .fallbackLanguage("auto")
                .build();
        String prompt = promptBuilder.buildEnhancedPrompt(request);
        assertThat(prompt).contains("\"pending_response_delay_seconds\": 30");
    }

    @Test
    void shouldBuildPromptWithSpeakerContext() {
        LlmSpeakerContext speakerContext = new LlmSpeakerContext(
                "bot-instance-123",
                12345L,
                List.of(
                        new LlmSpeakerContext.Participant("P1", 100L, "user1", "John", "Doe", "John Doe"),
                        new LlmSpeakerContext.Participant("P2", 200L, "user2", "Jane", null, "Jane")
                )
        );
        EnhancedPromptRequest request = EnhancedPromptRequest.builder()
                .speakerContext(speakerContext)
                .fallbackPrompt("test")
                .fallbackLanguage("auto")
                .build();
        String prompt = promptBuilder.buildEnhancedPrompt(request);
        assertThat(prompt).contains("\"conversation_speakers\"");
        assertThat(prompt).contains("\"bot_instance_id\": \"bot-instance-123\"");
        assertThat(prompt).contains("\"telegram_user_id\": 12345");
        assertThat(prompt).contains("\"label\": \"P1\"");
        assertThat(prompt).contains("\"label\": \"P2\"");
    }

    @Test
    void shouldBuildPromptWithPendingResponses() {
        PendingResponse pending = new PendingResponse();
        pending.setId(1L);
        pending.setTriggeringMessageId(100L);
        pending.setStatus(PendingResponseStatus.PENDING);
        pending.setPreparedResponse("Draft response text");
        pending.setCreatedAt(Instant.parse("2026-01-17T10:00:00Z"));
        EnhancedPromptRequest request = EnhancedPromptRequest.builder()
                .pendingResponses(List.of(pending))
                .fallbackPrompt("test")
                .fallbackLanguage("auto")
                .build();
        String prompt = promptBuilder.buildEnhancedPrompt(request);
        assertThat(prompt).contains("\"pending_responses\"");
        assertThat(prompt).contains("\"id\": 1");
        assertThat(prompt).contains("\"triggering_message_id\": 100");
        assertThat(prompt).contains("Draft response text");
    }

    @Test
    void shouldBuildPromptWithAllParameters() {
        ResponseTemplate template = new ResponseTemplate();
        template.setResponseTone(ResponseTone.FORMAL);
        template.setResponseStyle(ResponseStyle.CONCISE);
        ChatConfig chatConfig = new ChatConfig();
        chatConfig.setId(1L);
        chatConfig.setEnabled(true);
        RateLimits rateLimits = new RateLimits(1L);
        LlmParameters llmParameters = new LlmParameters(1L);
        llmParameters.setModelName("deepseek-chat");
        User user = new User();
        LlmSpeakerContext speakerContext = new LlmSpeakerContext("bot-1", 999L, List.of());
        PendingResponse pending = new PendingResponse();
        pending.setId(2L);
        pending.setTriggeringMessageId(50L);
        pending.setStatus(PendingResponseStatus.ELIGIBLE);
        pending.setPreparedResponse("Test draft");
        EnhancedPromptRequest request = EnhancedPromptRequest.builder()
                .template(template)
                .chatConfig(chatConfig)
                .rateLimits(rateLimits)
                .llmParameters(llmParameters)
                .fallbackPrompt("fallback prompt")
                .fallbackLanguage("auto")
                .user(user)
                .speakerContext(speakerContext)
                .pendingResponses(List.of(pending))
                .build();
        String prompt = promptBuilder.buildEnhancedPrompt(request);
        assertThat(prompt).contains("\"persona\"");
        assertThat(prompt).contains("\"chat_config\"");
        assertThat(prompt).contains("\"llm_parameters\"");
        assertThat(prompt).contains("\"response_template\"");
        assertThat(prompt).contains("\"response_rules\"");
        assertThat(prompt).contains("\"conversation_speakers\"");
        assertThat(prompt).contains("\"pending_responses\"");
        assertThat(prompt).contains("\"user_personalization\"");
    }

    @Test
    void shouldUseFallbackWhenChatConfigPromptIsBlank() {
        ChatConfig chatConfig = new ChatConfig();
        chatConfig.setPromptTemplate("   ");
        chatConfig.setLanguage("");
        EnhancedPromptRequest request = EnhancedPromptRequest.builder()
                .chatConfig(chatConfig)
                .fallbackPrompt("fallback prompt value")
                .fallbackLanguage("uk")
                .build();
        String prompt = promptBuilder.buildEnhancedPrompt(request);
        assertThat(prompt).contains("\"prompt_template\": \"fallback prompt value\"");
        assertThat(prompt).contains("\"language\": \"uk\"");
    }

    @Test
    void shouldHandleNullUserGracefully() {
        EnhancedPromptRequest request = EnhancedPromptRequest.builder()
                .fallbackPrompt("test")
                .fallbackLanguage("auto")
                .user(null)
                .build();
        String prompt = promptBuilder.buildEnhancedPrompt(request);
        assertThat(prompt).contains("\"user_personalization\": \"\"");
    }

    @Test
    void shouldReturnValidJsonStructure() {
        EnhancedPromptRequest request = EnhancedPromptRequest.builder()
                .fallbackPrompt("test")
                .fallbackLanguage("auto")
                .build();
        String prompt = promptBuilder.buildEnhancedPrompt(request);
        assertThat(prompt).startsWith("{");
        assertThat(prompt).endsWith("}");
        assertThat(prompt).contains("\"persona\"");
        assertThat(prompt).contains("\"chat_config\"");
        assertThat(prompt).contains("\"response_template\"");
        assertThat(prompt).contains("\"response_rules\"");
    }

    @Test
    void shouldEscapeSpecialCharactersInPrompt() {
        ChatConfig chatConfig = new ChatConfig();
        chatConfig.setPromptTemplate("Prompt with \"quotes\" and\nnewlines");
        EnhancedPromptRequest request = EnhancedPromptRequest.builder()
                .chatConfig(chatConfig)
                .fallbackPrompt("fallback")
                .fallbackLanguage("auto")
                .build();
        String prompt = promptBuilder.buildEnhancedPrompt(request);
        assertThat(prompt).contains("\\\"quotes\\\"");
        assertThat(prompt).contains("\\n");
    }

    @Test
    void shouldUseResponseFormatFromLlmParameters() {
        LlmParameters llmParameters = new LlmParameters(1L);
        llmParameters.setResponseFormat(ResponseFormat.JSON);
        EnhancedPromptRequest request = EnhancedPromptRequest.builder()
                .llmParameters(llmParameters)
                .fallbackPrompt("test")
                .fallbackLanguage("auto")
                .build();
        String prompt = promptBuilder.buildEnhancedPrompt(request);
        assertThat(prompt).contains("Response format: JSON");
    }

    @Test
    void shouldDefaultToTextFormatWhenLlmParametersNull() {
        EnhancedPromptRequest request = EnhancedPromptRequest.builder()
                .llmParameters(null)
                .fallbackPrompt("test")
                .fallbackLanguage("auto")
                .build();
        String prompt = promptBuilder.buildEnhancedPrompt(request);
        assertThat(prompt).contains("Response format: TEXT");
    }
}
