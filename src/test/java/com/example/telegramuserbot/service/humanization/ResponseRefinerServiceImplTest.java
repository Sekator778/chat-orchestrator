package com.example.telegramuserbot.service.humanization;

import com.example.telegramuserbot.service.llm.client.DeepSeekApiClient;
import com.example.telegramuserbot.service.llm.dto.DeepSeekChatRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for ResponseRefinerServiceImpl.
 * Verifies that service correctly identifies AI indicators and delegates refinement to DeepSeekApiClient.
 *
 * @author Development Team
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ResponseRefinerServiceImpl should")
class ResponseRefinerServiceImplTest {

    @Mock
    private DeepSeekApiClient deepSeekApiClient;

    @Mock
    private PersonaService personaService;

    private ResponseRefinerServiceImpl refinerService;

    @BeforeEach
    void createService() {
        refinerService = new ResponseRefinerServiceImpl(deepSeekApiClient, personaService);
    }

    @Test
    @DisplayName("return original response when no refinement is needed")
    void returnOriginalWhenNoRefinementNeeded() {
        String safeResponse = "Це просто звичайна відповідь без проблем";
        StepVerifier.create(refinerService.refineResponse(safeResponse, "Як справи?", 12345L))
                .expectNext(safeResponse)
                .verifyComplete();
        verify(deepSeekApiClient, never()).chat(any(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("detect AI indicator pattern with я бот")
    void detectAiIndicatorWithBotMention() {
        String aiResponse = "Так, я бот і я тут щоб допомогти";
        assertThat(refinerService.needsRefinement(aiResponse), is(true));
    }

    @Test
    @DisplayName("detect AI indicator pattern with штучний інтелект")
    void detectAiIndicatorWithAiMention() {
        String aiResponse = "Я штучний інтелект створений для допомоги";
        assertThat(refinerService.needsRefinement(aiResponse), is(true));
    }

    @Test
    @DisplayName("detect AI indicator pattern with AI assistant")
    void detectAiIndicatorWithEnglishMention() {
        String aiResponse = "As an AI assistant I can help you";
        assertThat(refinerService.needsRefinement(aiResponse), is(true));
    }

    @Test
    @DisplayName("return false for safe response without AI indicators")
    void returnFalseForSafeResponse() {
        String safeResponse = "Привіт! Як справи?";
        assertThat(refinerService.needsRefinement(safeResponse), is(false));
    }

    @Test
    @DisplayName("return false for null response")
    void returnFalseForNullResponse() {
        assertThat(refinerService.needsRefinement(null), is(false));
    }

    @Test
    @DisplayName("return false for blank response")
    void returnFalseForBlankResponse() {
        assertThat(refinerService.needsRefinement("   "), is(false));
    }

    @Test
    @DisplayName("call DeepSeekApiClient when refinement is needed")
    void callClientWhenRefinementNeeded() {
        String aiResponse = "Так, я бот і тут щоб допомогти";
        String refinedResponse = "Ну так, завжди радий допомогти 😊";
        when(personaService.getBotIdentity()).thenReturn("Андрій");
        when(personaService.getBotName()).thenReturn("Андрій");
        when(deepSeekApiClient.chat(any(DeepSeekChatRequest.class), eq(-2L), eq(15)))
                .thenReturn(Mono.just(refinedResponse));
        StepVerifier.create(refinerService.refineResponse(aiResponse, "Ти бот?", 12345L))
                .expectNext(refinedResponse)
                .verifyComplete();
        verify(deepSeekApiClient).chat(any(DeepSeekChatRequest.class), eq(-2L), eq(15));
    }

    @Test
    @DisplayName("use fallback when refined response still contains AI indicators")
    void useFallbackWhenRefinedStillHasIndicators() {
        String aiResponse = "Так, я бот";
        String stillBadResponse = "Я розумний помічник";
        when(personaService.getBotIdentity()).thenReturn("Сашко");
        when(personaService.getBotName()).thenReturn("Сашко");
        when(personaService.getPersonaResponse("Ти бот?")).thenReturn(null);
        when(deepSeekApiClient.chat(any(DeepSeekChatRequest.class), eq(-2L), eq(15)))
                .thenReturn(Mono.just(stillBadResponse));
        StepVerifier.create(refinerService.refineResponse(aiResponse, "Ти бот?", 12345L))
                .expectNextMatches(response -> response.contains("чому ти запитуєш"))
                .verifyComplete();
    }

    @Test
    @DisplayName("use fallback when client returns error")
    void useFallbackOnClientError() {
        String aiResponse = "Я штучний інтелект";
        lenient().when(personaService.getBotIdentity()).thenReturn("Тест");
        lenient().when(personaService.getBotName()).thenReturn("Тест");
        lenient().when(deepSeekApiClient.chat(any(DeepSeekChatRequest.class), eq(-2L), eq(15)))
                .thenReturn(Mono.error(new RuntimeException("API timeout")));
        StepVerifier.create(refinerService.refineResponse(aiResponse, "Розкажи про себе", 99L))
                .expectNextMatches(response -> response != null && !response.isBlank())
                .verifyComplete();
    }

    @Test
    @DisplayName("use alternative response when original is empty")
    void useAlternativeWhenOriginalEmpty() {
        when(personaService.getPersonaResponse("Привіт")).thenReturn("Агов!");
        StepVerifier.create(refinerService.refineResponse("", "Привіт", 12345L))
                .expectNext("Агов!")
                .verifyComplete();
        verify(deepSeekApiClient, never()).chat(any(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("use alternative response when original is null")
    void useAlternativeWhenOriginalNull() {
        when(personaService.getPersonaResponse("Як справи?")).thenReturn("Все норм!");
        StepVerifier.create(refinerService.refineResponse(null, "Як справи?", 12345L))
                .expectNext("Все норм!")
                .verifyComplete();
    }

    @Test
    @DisplayName("generate photo deflection for photo-related questions")
    void generatePhotoDeflection() {
        when(personaService.getPersonaResponse("Покажи фото")).thenReturn(null);
        String response = refinerService.generateAlternativeResponse("Покажи фото", 12345L);
        assertThat(response, is(notNullValue()));
    }

    @Test
    @DisplayName("generate bot deflection for bot-related questions")
    void generateBotDeflection() {
        when(personaService.getPersonaResponse("Ти бот?")).thenReturn(null);
        String response = refinerService.generateAlternativeResponse("Ти бот?", 12345L);
        assertThat(response, containsString("чому ти запитуєш"));
    }

    @Test
    @DisplayName("use persona response when available")
    void usePersonaResponseWhenAvailable() {
        when(personaService.getPersonaResponse("Хто ти?")).thenReturn("Я Андрій, звичайний хлопець");
        String response = refinerService.generateAlternativeResponse("Хто ти?", 12345L);
        assertThat(response, is("Я Андрій, звичайний хлопець"));
    }

    @Test
    @DisplayName("build correct refinement prompt with persona")
    void buildCorrectRefinementPrompt() {
        String aiResponse = "Так, я бот";
        when(personaService.getBotIdentity()).thenReturn("молодий програміст Олег");
        when(personaService.getBotName()).thenReturn("Олег");
        ArgumentCaptor<DeepSeekChatRequest> requestCaptor = ArgumentCaptor.forClass(DeepSeekChatRequest.class);
        when(deepSeekApiClient.chat(requestCaptor.capture(), eq(-2L), eq(15)))
                .thenReturn(Mono.just("Нормальна відповідь"));
        StepVerifier.create(refinerService.refineResponse(aiResponse, "Ти людина?", 555L))
                .expectNext("Нормальна відповідь")
                .verifyComplete();
        DeepSeekChatRequest capturedRequest = requestCaptor.getValue();
        assertThat(capturedRequest.messages().size(), is(1));
        String promptContent = capturedRequest.messages().get(0).content();
        assertThat(promptContent, containsString("молодий програміст Олег"));
        assertThat(promptContent, containsString("Ти людина?"));
        assertThat(promptContent, containsString("Так, я бот"));
        assertThat(promptContent, containsString("Олег"));
    }

    @Test
    @DisplayName("use correct timeout of 15 seconds for refinement")
    void useCorrectTimeout() {
        String aiResponse = "Так, я бот і допоможу";
        String safeResponse = "Ну звичайно допоможу!";
        when(personaService.getBotIdentity()).thenReturn("Тест");
        when(personaService.getBotName()).thenReturn("Тест");
        when(deepSeekApiClient.chat(any(DeepSeekChatRequest.class), eq(-2L), eq(15)))
                .thenReturn(Mono.just(safeResponse));
        StepVerifier.create(refinerService.refineResponse(aiResponse, "Хто ти?", 123L))
                .expectNext(safeResponse)
                .verifyComplete();
        verify(deepSeekApiClient).chat(any(DeepSeekChatRequest.class), eq(-2L), eq(15));
    }

    @Test
    @DisplayName("handle cyrillic and special characters in responses")
    void handleCyrillicAndSpecialCharacters() {
        String response = "Привіт! Як справи? 👋 Гарного дня! «цитата» — дефіс";
        assertThat(refinerService.needsRefinement(response), is(false));
    }
}
