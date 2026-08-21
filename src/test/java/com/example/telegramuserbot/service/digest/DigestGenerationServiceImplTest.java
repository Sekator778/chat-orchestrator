package com.example.telegramuserbot.service.digest;

import com.example.telegramuserbot.domain.DigestHistory;
import com.example.telegramuserbot.domain.DigestPersona;
import com.example.telegramuserbot.domain.MessageEntity;
import com.example.telegramuserbot.repository.DigestHistoryRepository;
import com.example.telegramuserbot.repository.DigestPersonaRepository;
import com.example.telegramuserbot.repository.MessageRepository;
import com.example.telegramuserbot.repository.SourceTrustRepository;
import com.example.telegramuserbot.service.llm.client.DeepSeekApiClient;
import com.example.telegramuserbot.service.llm.dto.DeepSeekChatRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.lang.reflect.Field;
import java.time.Instant;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for DigestGenerationServiceImpl.
 * Verifies LLM integration via DeepSeekApiClient for digest generation.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
final class DigestGenerationServiceImplTest {

    @Mock
    private DigestPersonaRepository personaRepository;

    @Mock
    private DigestHistoryRepository historyRepository;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private SourceTrustRepository sourceTrustRepository;

    @Mock
    private DeepSeekApiClient deepSeekApiClient;

    @Mock
    private com.example.telegramuserbot.service.humanization.PersonaService personaService;

    @Mock
    private com.example.telegramuserbot.service.humanization.AntiDetectionService antiDetectionService;

    @Mock
    private com.example.telegramuserbot.service.humanization.ResponseRefinerService responseRefinerService;

    private DigestGenerationServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        org.mockito.Mockito.when(personaService.buildPersonaSystemPrompt(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> inv.getArgument(0));
        org.mockito.Mockito.when(antiDetectionService.analyzeAndAdjustResponse(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> reactor.core.publisher.Mono.just(inv.getArgument(0)));
        org.mockito.Mockito.when(antiDetectionService.hasAiPatterns(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(false);
        org.mockito.Mockito.when(antiDetectionService.addStrategicImperfections(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyDouble()))
                .thenAnswer(inv -> inv.getArgument(0));
        // The production generateDigest path always records the run via
        // updateLastRunAt(...).timeout(...) — even when no messages are found —
        // so stub it for every test (LENIENT strictness swallows the unused case).
        when(personaRepository.updateLastRunAt(anyLong(), any(Instant.class)))
                .thenReturn(Mono.just(1));
        service = new DigestGenerationServiceImpl(
                personaRepository,
                historyRepository,
                messageRepository,
                sourceTrustRepository,
                deepSeekApiClient,
                personaService,
                antiDetectionService,
                responseRefinerService
        );
        Field defaultModelField = DigestGenerationServiceImpl.class.getDeclaredField("defaultModel");
        defaultModelField.setAccessible(true);
        defaultModelField.set(service, "deepseek-chat");
    }

    @Test
    void generateDigestReturnsLlmResponseForValidMessages() {
        DigestPersona persona = createPersona(1L, "TestPersona", "PROFESSIONAL");
        MessageEntity message = createMessage(100L, 1001L, "Breaking news content");
        when(personaRepository.findById(1L)).thenReturn(Mono.just(persona));
        when(messageRepository.findQualityMessagesForDigest(any(Instant.class), anyInt(), anyInt()))
                .thenReturn(Flux.just(message));
        when(deepSeekApiClient.chat(any(DeepSeekChatRequest.class), anyLong(), anyInt()))
                .thenReturn(Mono.just("• Breaking news summary"));
        when(historyRepository.save(any(DigestHistory.class))).thenReturn(Mono.just(new DigestHistory(1L, "d-test", "content")));
        StepVerifier.create(service.generateDigest(1L))
                .assertNext(dto -> {
                    assertThat(dto.content(), is("• Breaking news summary"));
                    assertThat(dto.personaId(), is(1L));
                    assertThat(dto.messagesIncluded(), is(1));
                })
                .verifyComplete();
        verify(deepSeekApiClient).chat(any(DeepSeekChatRequest.class), eq(-1L), eq(60));
    }

    @Test
    void generateDigestReturnsEmptyDtoWhenNoMessagesFound() {
        DigestPersona persona = createPersona(1L, "EmptyPersona", "PROFESSIONAL");
        when(personaRepository.findById(1L)).thenReturn(Mono.just(persona));
        when(messageRepository.findQualityMessagesForDigest(any(Instant.class), anyInt(), anyInt()))
                .thenReturn(Flux.empty());
        StepVerifier.create(service.generateDigest(1L))
                .assertNext(dto -> {
                    assertThat(dto.content(), is("No significant news in this period."));
                    assertThat(dto.messagesIncluded(), is(0));
                })
                .verifyComplete();
        verifyNoInteractions(deepSeekApiClient);
    }

    @Test
    void generateDigestReturnsErrorWhenPersonaNotFound() {
        when(personaRepository.findById(999L)).thenReturn(Mono.empty());
        StepVerifier.create(service.generateDigest(999L))
                .expectErrorMatches(e -> e instanceof IllegalArgumentException &&
                        e.getMessage().contains("Persona not found: 999"))
                .verify();
    }

    @Test
    void generateTestDigestDoesNotPersistHistory() {
        DigestPersona persona = createPersona(2L, "TestPreview", "IRONIC");
        MessageEntity message = createMessage(101L, 1002L, "Test news for preview");
        when(personaRepository.findById(2L)).thenReturn(Mono.just(persona));
        when(messageRepository.findQualityMessagesForDigest(any(Instant.class), anyInt(), anyInt()))
                .thenReturn(Flux.just(message));
        when(deepSeekApiClient.chat(any(DeepSeekChatRequest.class), anyLong(), anyInt()))
                .thenReturn(Mono.just("• Test preview content"));
        StepVerifier.create(service.generateTestDigest(2L))
                .assertNext(dto -> {
                    assertThat(dto.digestId(), startsWith("test-"));
                    assertThat(dto.content(), is("• Test preview content"));
                })
                .verifyComplete();
        verifyNoInteractions(historyRepository);
    }

    @Test
    void buildSystemPromptContainsBaseRules() {
        DigestPersona persona = createPersona(1L, "ProEN", "PROFESSIONAL");
        persona.setLanguage("en");
        String prompt = service.buildSystemPrompt(persona);
        assertThat(prompt, containsString("STRICT RULES"));
    }

    @Test
    void buildSystemPromptContainsBaseRulesRussian() {
        DigestPersona persona = createPersona(1L, "ProRU", "PROFESSIONAL");
        persona.setLanguage("ru");
        String prompt = service.buildSystemPrompt(persona);
        assertThat(prompt, containsString("STRICT RULES"));
    }

    @Test
    void buildSystemPromptContainsBaseRulesForIronic() {
        DigestPersona persona = createPersona(1L, "IronicEN", "IRONIC");
        persona.setLanguage("en");
        String prompt = service.buildSystemPrompt(persona);
        assertThat(prompt, containsString("STRICT RULES"));
    }

    @Test
    void buildSystemPromptContainsBaseRulesForBreaking() {
        DigestPersona persona = createPersona(1L, "BreakingEN", "BREAKING_NEWS");
        persona.setLanguage("en");
        String prompt = service.buildSystemPrompt(persona);
        assertThat(prompt, containsString("STRICT RULES"));
    }

    @Test
    void buildSystemPromptContainsBaseRulesForTechnical() {
        DigestPersona persona = createPersona(1L, "TechEN", "TECHNICAL");
        persona.setLanguage("en");
        String prompt = service.buildSystemPrompt(persona);
        assertThat(prompt, containsString("STRICT RULES"));
    }

    @Test
    void buildSystemPromptReturnsCustomPromptWhenProvided() {
        DigestPersona persona = createPersona(1L, "Custom", "CUSTOM");
        persona.setCustomSystemPrompt("You are a custom news bot");
        String prompt = service.buildSystemPrompt(persona);
        assertThat(prompt, containsString("You are a custom news bot"));
        assertThat(prompt, containsString("Today is"));
    }

    @Test
    void generateDigestUsesCustomLookbackHours() {
        DigestPersona persona = createPersona(3L, "CustomLookback", "PROFESSIONAL");
        persona.setLookbackHours(12);
        when(personaRepository.findById(3L)).thenReturn(Mono.just(persona));
        when(messageRepository.findQualityMessagesForDigest(any(Instant.class), anyInt(), anyInt()))
                .thenReturn(Flux.empty());
        StepVerifier.create(service.generateDigest(3L, 48))
                .assertNext(dto -> assertThat(dto.messagesIncluded(), is(0)))
                .verifyComplete();
    }

    @Test
    void generateDigestHandlesLlmError() {
        DigestPersona persona = createPersona(4L, "ErrorTest", "PROFESSIONAL");
        MessageEntity message = createMessage(102L, 1003L, "Error test content");
        when(personaRepository.findById(4L)).thenReturn(Mono.just(persona));
        when(messageRepository.findQualityMessagesForDigest(any(Instant.class), anyInt(), anyInt()))
                .thenReturn(Flux.just(message));
        when(deepSeekApiClient.chat(any(DeepSeekChatRequest.class), anyLong(), anyInt()))
                .thenReturn(Mono.error(new RuntimeException("API timeout")));
        when(historyRepository.save(any(DigestHistory.class))).thenReturn(Mono.just(new DigestHistory(4L, "d-error", "error")));
        when(personaRepository.updateLastRunAt(anyLong(), any(Instant.class))).thenReturn(Mono.just(1));
        StepVerifier.create(service.generateDigest(4L))
                .assertNext(dto -> {
                    assertThat(dto.content(), containsString("Digest generation failed"));
                    assertThat(dto.content(), containsString("API timeout"));
                })
                .verifyComplete();
    }

    @Test
    void generateDigestHandlesEmptyLlmResponse() {
        DigestPersona persona = createPersona(5L, "EmptyResponse", "PROFESSIONAL");
        MessageEntity message = createMessage(103L, 1004L, "Empty response test");
        when(personaRepository.findById(5L)).thenReturn(Mono.just(persona));
        when(messageRepository.findQualityMessagesForDigest(any(Instant.class), anyInt(), anyInt()))
                .thenReturn(Flux.just(message));
        when(deepSeekApiClient.chat(any(DeepSeekChatRequest.class), anyLong(), anyInt()))
                .thenReturn(Mono.empty());
        when(historyRepository.save(any(DigestHistory.class))).thenReturn(Mono.just(new DigestHistory(5L, "d-test", "content")));
        when(personaRepository.updateLastRunAt(anyLong(), any(Instant.class))).thenReturn(Mono.just(1));
        StepVerifier.create(service.generateDigest(5L))
                .assertNext(dto -> assertThat(dto.content(), is("No content generated")))
                .verifyComplete();
    }

    @Test
    void generateDigestUsesPersonaModelSettings() {
        DigestPersona persona = createPersona(6L, "CustomModel", "PROFESSIONAL");
        persona.setModelName("custom-model");
        persona.setMaxTokens(2000);
        persona.setTemperature(0.5);
        MessageEntity message = createMessage(104L, 1005L, "Model settings test");
        when(personaRepository.findById(6L)).thenReturn(Mono.just(persona));
        when(messageRepository.findQualityMessagesForDigest(any(Instant.class), anyInt(), anyInt()))
                .thenReturn(Flux.just(message));
        when(deepSeekApiClient.chat(any(DeepSeekChatRequest.class), anyLong(), anyInt()))
                .thenReturn(Mono.just("Custom model response"));
        when(historyRepository.save(any(DigestHistory.class))).thenReturn(Mono.just(new DigestHistory(6L, "d-test", "content")));
        when(personaRepository.updateLastRunAt(anyLong(), any(Instant.class))).thenReturn(Mono.just(1));
        ArgumentCaptor<DeepSeekChatRequest> requestCaptor = ArgumentCaptor.forClass(DeepSeekChatRequest.class);
        StepVerifier.create(service.generateDigest(6L))
                .assertNext(dto -> assertThat(dto.content(), is("Custom model response")))
                .verifyComplete();
        verify(deepSeekApiClient).chat(requestCaptor.capture(), eq(-1L), eq(60));
        DeepSeekChatRequest captured = requestCaptor.getValue();
        assertThat(captured.model(), is("custom-model"));
        assertThat(captured.max_tokens(), is(2000));
        assertThat(captured.temperature(), is(0.5));
    }

    @Test
    void generateDigestFiltersMessagesByImportance() {
        DigestPersona persona = createPersona(7L, "ImportanceFilter", "PROFESSIONAL");
        persona.setMinImportanceScore(0.5);
        MessageEntity highImportance = createMessage(105L, 1006L, "High importance news");
        highImportance.setImportance(0.8);
        MessageEntity lowImportance = createMessage(106L, 1007L, "Low importance news");
        lowImportance.setImportance(0.2);
        when(personaRepository.findById(7L)).thenReturn(Mono.just(persona));
        when(messageRepository.findQualityMessagesForDigest(any(Instant.class), anyInt(), anyInt()))
                .thenReturn(Flux.just(highImportance, lowImportance));
        when(deepSeekApiClient.chat(any(DeepSeekChatRequest.class), anyLong(), anyInt()))
                .thenReturn(Mono.just("Filtered by importance"));
        when(historyRepository.save(any(DigestHistory.class))).thenReturn(Mono.just(new DigestHistory(7L, "d-test", "content")));
        when(personaRepository.updateLastRunAt(anyLong(), any(Instant.class))).thenReturn(Mono.just(1));
        StepVerifier.create(service.generateDigest(7L))
                .assertNext(dto -> assertThat(dto.messagesIncluded(), is(1)))
                .verifyComplete();
    }

    @Test
    void generateDigestFiltersMessagesByKeywords() {
        DigestPersona persona = createPersona(8L, "KeywordFilter", "PROFESSIONAL");
        persona.setTopicKeywords(new String[]{"crypto", "bitcoin"});
        MessageEntity matchingMessage = createMessage(107L, 1008L, "Bitcoin price surges");
        MessageEntity nonMatchingMessage = createMessage(108L, 1009L, "Weather forecast");
        when(personaRepository.findById(8L)).thenReturn(Mono.just(persona));
        when(messageRepository.findQualityMessagesForDigest(any(Instant.class), anyInt(), anyInt()))
                .thenReturn(Flux.just(matchingMessage, nonMatchingMessage));
        when(deepSeekApiClient.chat(any(DeepSeekChatRequest.class), anyLong(), anyInt()))
                .thenReturn(Mono.just("Keyword filtered result"));
        when(historyRepository.save(any(DigestHistory.class))).thenReturn(Mono.just(new DigestHistory(8L, "d-test", "content")));
        when(personaRepository.updateLastRunAt(anyLong(), any(Instant.class))).thenReturn(Mono.just(1));
        StepVerifier.create(service.generateDigest(8L))
                .assertNext(dto -> assertThat(dto.messagesIncluded(), is(1)))
                .verifyComplete();
    }

    @Test
    void generateDigestFiltersMessagesBySourceTrust() {
        DigestPersona persona = createPersona(9L, "TrustFilter", "PROFESSIONAL");
        persona.setSourceTrustThreshold(0.6);
        MessageEntity trustedMessage = createMessage(109L, 1010L, "Trusted source news");
        MessageEntity untrustedMessage = createMessage(110L, 1011L, "Untrusted source news");
        when(personaRepository.findById(9L)).thenReturn(Mono.just(persona));
        when(messageRepository.findQualityMessagesForDigest(any(Instant.class), anyInt(), anyInt()))
                .thenReturn(Flux.just(trustedMessage, untrustedMessage));
        when(sourceTrustRepository.getTrustScoreOrDefault(109L)).thenReturn(Mono.just(0.9));
        when(sourceTrustRepository.getTrustScoreOrDefault(110L)).thenReturn(Mono.just(0.3));
        when(deepSeekApiClient.chat(any(DeepSeekChatRequest.class), anyLong(), anyInt()))
                .thenReturn(Mono.just("Trust filtered result"));
        when(historyRepository.save(any(DigestHistory.class))).thenReturn(Mono.just(new DigestHistory(9L, "d-test", "content")));
        when(personaRepository.updateLastRunAt(anyLong(), any(Instant.class))).thenReturn(Mono.just(1));
        StepVerifier.create(service.generateDigest(9L))
                .assertNext(dto -> assertThat(dto.messagesIncluded(), is(1)))
                .verifyComplete();
    }

    private DigestPersona createPersona(Long id, String name, String style) {
        DigestPersona persona = new DigestPersona();
        persona.setId(id);
        persona.setName(name);
        persona.setPersonaStyle(style);
        persona.setLookbackHours(24);
        persona.setMaxMessages(10);
        persona.setLanguage("en");
        return persona;
    }

    private MessageEntity createMessage(Long chatId, Long messageId, String content) {
        MessageEntity message = new MessageEntity();
        message.setChatId(chatId);
        message.setMessageId(messageId);
        message.setContent(content);
        message.setDate(Instant.now());
        return message;
    }
}
