package com.example.telegramuserbot.service.llm;

import com.example.telegramuserbot.service.llm.client.DeepSeekApiClient;
import com.example.telegramuserbot.service.llm.dto.ApiMessage;
import com.example.telegramuserbot.service.llm.dto.DeepSeekChatRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for EnhancedLlmService.
 * Verifies that service correctly delegates to DeepSeekApiClient.
 *
 * @author Development Team
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EnhancedLlmService should")
class EnhancedLlmServiceTest {

    @Mock
    private DeepSeekApiClient deepSeekApiClient;

    private EnhancedLlmService enhancedLlmService;

    @BeforeEach
    void createService() {
        enhancedLlmService = new EnhancedLlmService(deepSeekApiClient);
    }

    @Test
    @DisplayName("delegate callDeepSeekApi to DeepSeekApiClient")
    void delegateCallDeepSeekApiToClient() {
        long chatId = 12345L;
        String expectedResponse = "Test response from LLM";
        DeepSeekChatRequest request = createTestRequest();
        when(deepSeekApiClient.chat(any(DeepSeekChatRequest.class), eq(chatId)))
                .thenReturn(Mono.just(expectedResponse));
        StepVerifier.create(enhancedLlmService.callDeepSeekApi(request, chatId))
                .expectNext(expectedResponse)
                .verifyComplete();
        verify(deepSeekApiClient).chat(request, chatId);
    }

    @Test
    @DisplayName("return empty Mono when client returns empty")
    void returnEmptyWhenClientReturnsEmpty() {
        long chatId = 67890L;
        DeepSeekChatRequest request = createTestRequest();
        when(deepSeekApiClient.chat(any(DeepSeekChatRequest.class), eq(chatId)))
                .thenReturn(Mono.empty());
        StepVerifier.create(enhancedLlmService.callDeepSeekApi(request, chatId))
                .verifyComplete();
    }

    @Test
    @DisplayName("propagate errors from client as empty Mono")
    void propagateErrorsAsEmptyMono() {
        long chatId = 99999L;
        DeepSeekChatRequest request = createTestRequest();
        when(deepSeekApiClient.chat(any(DeepSeekChatRequest.class), eq(chatId)))
                .thenReturn(Mono.error(new RuntimeException("API error")));
        StepVerifier.create(enhancedLlmService.callDeepSeekApi(request, chatId))
                .expectError(RuntimeException.class)
                .verify();
    }

    @Test
    @DisplayName("return empty Mono for deprecated generateEnhancedReply method")
    void returnEmptyForDeprecatedMethod() {
        StepVerifier.create(enhancedLlmService.generateEnhancedReply(12345L, 67890L))
                .verifyComplete();
    }

    @Test
    @DisplayName("handle requests with unicode content correctly")
    void handleUnicodeContent() {
        long chatId = 11111L;
        String unicodeContent = "Ответ на русском языке с emoji 🎉 и спецсимволами «»";
        List<ApiMessage> messages = List.of(
                new ApiMessage("user", "Привіт! Як справи? 你好")
        );
        DeepSeekChatRequest request = new DeepSeekChatRequest(
                messages, null, null, null, null, null, null, null
        );
        when(deepSeekApiClient.chat(any(DeepSeekChatRequest.class), eq(chatId)))
                .thenReturn(Mono.just(unicodeContent));
        StepVerifier.create(enhancedLlmService.callDeepSeekApi(request, chatId))
                .expectNext(unicodeContent)
                .verifyComplete();
    }

    @Test
    @DisplayName("create EnhancedLlmResponse record with all fields")
    void createEnhancedLlmResponseRecord() {
        EnhancedLlmService.EnhancedLlmResponse response = new EnhancedLlmService.EnhancedLlmResponse(
                "formatted content",
                "raw content",
                null,
                null,
                5,
                1000,
                EnhancedLlmService.ResponseFormat.TEXT,
                null
        );
        assertThat(response.formattedContent(), is("formatted content"));
        assertThat(response.rawContent(), is("raw content"));
        assertThat(response.contextMessages(), is(5));
        assertThat(response.contextCharacters(), is(1000));
        assertThat(response.format(), is(EnhancedLlmService.ResponseFormat.TEXT));
    }

    @Test
    @DisplayName("create new response with modified formatted content using withFormattedContent")
    void createResponseWithModifiedContent() {
        EnhancedLlmService.EnhancedLlmResponse original = new EnhancedLlmService.EnhancedLlmResponse(
                "original formatted",
                "raw content",
                null,
                null,
                3,
                500,
                EnhancedLlmService.ResponseFormat.MARKDOWN,
                null
        );
        EnhancedLlmService.EnhancedLlmResponse modified = original.withFormattedContent("new formatted");
        assertThat(modified.formattedContent(), is("new formatted"));
        assertThat(modified.rawContent(), is("raw content"));
        assertThat(modified.contextMessages(), is(3));
    }

    @Test
    @DisplayName("handle ResponseFormat enum values")
    void handleResponseFormatValues() {
        assertThat(EnhancedLlmService.ResponseFormat.TEXT, is(notNullValue()));
        assertThat(EnhancedLlmService.ResponseFormat.MARKDOWN, is(notNullValue()));
        assertThat(EnhancedLlmService.ResponseFormat.JSON, is(notNullValue()));
        assertThat(EnhancedLlmService.ResponseFormat.HTML, is(notNullValue()));
        assertThat(EnhancedLlmService.ResponseFormat.values().length, is(4));
    }

    private DeepSeekChatRequest createTestRequest() {
        List<ApiMessage> messages = List.of(
                new ApiMessage("system", "You are a helpful assistant"),
                new ApiMessage("user", "Hello, how are you?")
        );
        return new DeepSeekChatRequest(
                messages,
                "deepseek-chat",
                1000,
                0.7,
                null,
                null,
                null,
                false
        );
    }
}
