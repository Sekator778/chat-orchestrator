package com.example.telegramuserbot.service.llm.client;

import com.example.telegramuserbot.service.llm.dto.ApiMessage;
import com.example.telegramuserbot.service.llm.dto.DeepSeekChatRequest;
import com.example.telegramuserbot.service.llm.dto.DeepSeekChatResponse;
import com.example.telegramuserbot.service.llm.dto.ResponseChoice;
import com.example.telegramuserbot.service.llm.dto.ResponseMessage;
import com.example.telegramuserbot.service.llm.dto.UsageInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Unit tests for DeepSeekApiClientImpl.
 * Uses MockWebServer to simulate DeepSeek API responses.
 */
class DeepSeekApiClientImplTest {

    private MockWebServer mockWebServer;
    private DeepSeekApiClient client;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();
        objectMapper = new ObjectMapper();
        String baseUrl = mockWebServer.url("/v1/chat/completions").toString();
        WebClient webClient = WebClient.builder()
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        client = new DeepSeekApiClientImpl(
                webClient,
                objectMapper,
                baseUrl,
                "deepseek-chat",
                1000,
                0.7,
                false
        );
    }

    @AfterEach
    void tearDown() throws IOException {
        mockWebServer.shutdown();
    }

    @Test
    void chatReturnsContentFromSuccessfulResponse() throws Exception {
        String expectedContent = "Hello, I am DeepSeek assistant";
        DeepSeekChatResponse response = createResponse(expectedContent);
        mockWebServer.enqueue(new MockResponse()
                .setBody(objectMapper.writeValueAsString(response))
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE));
        DeepSeekChatRequest request = createRequest("Hi there");
        StepVerifier.create(client.chat(request, 12345L))
                .expectNext(expectedContent)
                .verifyComplete();
    }

    @Test
    void chatReturnsEmptyMonoWhenResponseHasNoChoices() throws Exception {
        DeepSeekChatResponse response = new DeepSeekChatResponse(
                "chatcmpl-123",
                List.of(),
                System.currentTimeMillis(),
                "deepseek-chat",
                "chat.completion",
                new UsageInfo(null, null, null)
        );
        mockWebServer.enqueue(new MockResponse()
                .setBody(objectMapper.writeValueAsString(response))
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE));
        DeepSeekChatRequest request = createRequest("Test message");
        StepVerifier.create(client.chat(request, 12345L))
                .verifyComplete();
    }

    @Test
    void chatReturnsEmptyMonoWhenApiReturnsServerError() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(500)
                .setBody("{\"error\": \"Internal server error\"}")
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE));
        DeepSeekChatRequest request = createRequest("Test message");
        StepVerifier.create(client.chat(request, 12345L))
                .verifyComplete();
    }

    @Test
    void chatReturnsEmptyMonoWhenApiReturnsClientError() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(400)
                .setBody("{\"error\": \"Bad request\"}")
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE));
        DeepSeekChatRequest request = createRequest("Test message");
        StepVerifier.create(client.chat(request, 12345L))
                .verifyComplete();
    }

    @Test
    void chatAppliesDefaultModelWhenNotSpecified() throws Exception {
        String expectedContent = "Response text";
        mockWebServer.enqueue(new MockResponse()
                .setBody(objectMapper.writeValueAsString(createResponse(expectedContent)))
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE));
        DeepSeekChatRequest request = new DeepSeekChatRequest(
                List.of(new ApiMessage("user", "Hello")),
                null
        );
        StepVerifier.create(client.chat(request, 12345L))
                .expectNext(expectedContent)
                .verifyComplete();
        RecordedRequest recorded = mockWebServer.takeRequest(5, TimeUnit.SECONDS);
        assertThat(recorded, is(notNullValue()));
        String body = recorded.getBody().readUtf8();
        assertThat(body, containsString("\"model\":\"deepseek-chat\""));
    }

    @Test
    void chatAppliesDefaultMaxTokensWhenNotSpecified() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setBody(objectMapper.writeValueAsString(createResponse("Response")))
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE));
        DeepSeekChatRequest request = new DeepSeekChatRequest(
                List.of(new ApiMessage("user", "Hello")),
                "deepseek-chat"
        );
        StepVerifier.create(client.chat(request, 12345L))
                .expectNext("Response")
                .verifyComplete();
        RecordedRequest recorded = mockWebServer.takeRequest(5, TimeUnit.SECONDS);
        assertThat(recorded, is(notNullValue()));
        String body = recorded.getBody().readUtf8();
        assertThat(body, containsString("\"max_tokens\":1000"));
    }

    @Test
    void chatAppliesDefaultTemperatureWhenNotSpecified() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setBody(objectMapper.writeValueAsString(createResponse("Response")))
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE));
        DeepSeekChatRequest request = new DeepSeekChatRequest(
                List.of(new ApiMessage("user", "Hello")),
                "deepseek-chat"
        );
        StepVerifier.create(client.chat(request, 12345L))
                .expectNext("Response")
                .verifyComplete();
        RecordedRequest recorded = mockWebServer.takeRequest(5, TimeUnit.SECONDS);
        assertThat(recorded, is(notNullValue()));
        String body = recorded.getBody().readUtf8();
        assertThat(body, containsString("\"temperature\":0.7"));
    }

    @Test
    void chatPreservesCustomParametersWhenProvided() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setBody(objectMapper.writeValueAsString(createResponse("Response")))
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE));
        DeepSeekChatRequest request = new DeepSeekChatRequest(
                List.of(new ApiMessage("user", "Hello")),
                "custom-model",
                2000,
                0.9,
                0.5,
                0.3,
                0.95,
                false
        );
        StepVerifier.create(client.chat(request, 12345L))
                .expectNext("Response")
                .verifyComplete();
        RecordedRequest recorded = mockWebServer.takeRequest(5, TimeUnit.SECONDS);
        assertThat(recorded, is(notNullValue()));
        String body = recorded.getBody().readUtf8();
        assertThat(body, containsString("\"model\":\"custom-model\""));
        assertThat(body, containsString("\"max_tokens\":2000"));
        assertThat(body, containsString("\"temperature\":0.9"));
    }

    @Test
    void chatWithResponseReturnsFullResponseObject() throws Exception {
        String expectedContent = "Full response content";
        DeepSeekChatResponse expectedResponse = createResponse(expectedContent);
        mockWebServer.enqueue(new MockResponse()
                .setBody(objectMapper.writeValueAsString(expectedResponse))
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE));
        DeepSeekChatRequest request = createRequest("Test");
        StepVerifier.create(client.chatWithResponse(request, 12345L))
                .assertNext(response -> {
                    assertThat(response.choices(), hasSize(1));
                    assertThat(response.choices().get(0).message().content(), is(expectedContent));
                })
                .verifyComplete();
    }

    @Test
    void extractContentReturnsNullForNullResponse() {
        String result = client.extractContent(null);
        assertThat(result, is(nullValue()));
    }

    @Test
    void extractContentReturnsNullForEmptyChoices() {
        DeepSeekChatResponse response = new DeepSeekChatResponse(
                "id", List.of(), 0L, "model", "object", new UsageInfo(null, null, null)
        );
        String result = client.extractContent(response);
        assertThat(result, is(nullValue()));
    }

    @Test
    void extractContentReturnsNullForNullChoicesList() {
        DeepSeekChatResponse response = new DeepSeekChatResponse(
                "id", null, 0L, "model", "object", new UsageInfo(null, null, null)
        );
        String result = client.extractContent(response);
        assertThat(result, is(nullValue()));
    }

    @Test
    void extractContentReturnsNullForBlankContent() {
        DeepSeekChatResponse response = new DeepSeekChatResponse(
                "id",
                List.of(new ResponseChoice(0, new ResponseMessage("assistant", "   "))),
                0L, "model", "object", new UsageInfo(null, null, null)
        );
        String result = client.extractContent(response);
        assertThat(result, is(nullValue()));
    }

    @Test
    void extractContentTrimsWhitespaceFromContent() {
        DeepSeekChatResponse response = new DeepSeekChatResponse(
                "id",
                List.of(new ResponseChoice(0, new ResponseMessage("assistant", "  trimmed content  "))),
                0L, "model", "object", new UsageInfo(null, null, null)
        );
        String result = client.extractContent(response);
        assertThat(result, is("trimmed content"));
    }

    @Test
    void chatHandlesUnicodeContentCorrectly() throws Exception {
        String unicodeContent = "Привіт! 你好 مرحبا 🎉";
        mockWebServer.enqueue(new MockResponse()
                .setBody(objectMapper.writeValueAsString(createResponse(unicodeContent)))
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE));
        DeepSeekChatRequest request = createRequest("Unicode test");
        StepVerifier.create(client.chat(request, 12345L))
                .expectNext(unicodeContent)
                .verifyComplete();
    }

    @Test
    void chatWithCustomTimeoutCompletesBeforeTimeout() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setBody(objectMapper.writeValueAsString(createResponse("Quick response")))
                .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE));
        DeepSeekChatRequest request = createRequest("Test");
        StepVerifier.create(client.chat(request, 12345L, 60))
                .expectNext("Quick response")
                .verifyComplete();
    }

    private DeepSeekChatRequest createRequest(String content) {
        return new DeepSeekChatRequest(
                List.of(new ApiMessage("user", content)),
                "deepseek-chat",
                1000,
                0.7
        );
    }

    private DeepSeekChatResponse createResponse(String content) {
        return new DeepSeekChatResponse(
                "chatcmpl-123",
                List.of(new ResponseChoice(0, new ResponseMessage("assistant", content))),
                System.currentTimeMillis(),
                "deepseek-chat",
                "chat.completion",
                new UsageInfo(null, null, null)
        );
    }
}
