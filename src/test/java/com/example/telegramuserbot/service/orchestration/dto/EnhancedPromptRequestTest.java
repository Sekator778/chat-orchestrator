package com.example.telegramuserbot.service.orchestration.dto;

import com.example.telegramuserbot.domain.ChatConfig;
import com.example.telegramuserbot.domain.LlmParameters;
import com.example.telegramuserbot.domain.PendingResponse;
import com.example.telegramuserbot.domain.RateLimits;
import com.example.telegramuserbot.domain.ResponseTemplate;
import com.example.telegramuserbot.domain.User;
import com.example.telegramuserbot.service.llm.conversation.LlmSpeakerContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Tests for EnhancedPromptRequest record and its Builder.
 */
class EnhancedPromptRequestTest {

    @Test
    void builderShouldCreateRequestWithAllFields() {
        ResponseTemplate template = new ResponseTemplate();
        ChatConfig chatConfig = new ChatConfig();
        RateLimits rateLimits = new RateLimits();
        LlmParameters llmParameters = new LlmParameters();
        User user = new User();
        LlmSpeakerContext speakerContext = new LlmSpeakerContext("bot-1", 123L, List.of());
        List<PendingResponse> pendingResponses = List.of(new PendingResponse());
        EnhancedPromptRequest request = EnhancedPromptRequest.builder()
                .template(template)
                .chatConfig(chatConfig)
                .rateLimits(rateLimits)
                .llmParameters(llmParameters)
                .fallbackPrompt("Test prompt")
                .fallbackLanguage("en")
                .user(user)
                .speakerContext(speakerContext)
                .pendingResponses(pendingResponses)
                .build();
        assertThat(request.template(), is(sameInstance(template)));
        assertThat(request.chatConfig(), is(sameInstance(chatConfig)));
        assertThat(request.rateLimits(), is(sameInstance(rateLimits)));
        assertThat(request.llmParameters(), is(sameInstance(llmParameters)));
        assertThat(request.fallbackPrompt(), is("Test prompt"));
        assertThat(request.fallbackLanguage(), is("en"));
        assertThat(request.user(), is(sameInstance(user)));
        assertThat(request.speakerContext(), is(sameInstance(speakerContext)));
        assertThat(request.pendingResponses(), is(sameInstance(pendingResponses)));
    }

    @Test
    void builderShouldCreateRequestWithMinimalRequiredFields() {
        EnhancedPromptRequest request = EnhancedPromptRequest.builder()
                .fallbackPrompt("Respond naturally.")
                .fallbackLanguage("auto")
                .build();
        assertThat(request.template(), is(nullValue()));
        assertThat(request.chatConfig(), is(nullValue()));
        assertThat(request.rateLimits(), is(nullValue()));
        assertThat(request.llmParameters(), is(nullValue()));
        assertThat(request.fallbackPrompt(), is("Respond naturally."));
        assertThat(request.fallbackLanguage(), is("auto"));
        assertThat(request.user(), is(nullValue()));
        assertThat(request.speakerContext(), is(nullValue()));
        assertThat(request.pendingResponses(), is(nullValue()));
    }

    @Test
    void builderShouldThrowWhenFallbackPromptIsNull() {
        EnhancedPromptRequest.Builder builder = EnhancedPromptRequest.builder()
                .fallbackLanguage("auto");
        try {
            builder.build();
            assertThat("Expected NullPointerException", false);
        } catch (NullPointerException e) {
            assertThat(e.getMessage(), containsString("fallbackPrompt"));
        }
    }

    @Test
    void builderShouldThrowWhenFallbackLanguageIsNull() {
        EnhancedPromptRequest.Builder builder = EnhancedPromptRequest.builder()
                .fallbackPrompt("Test prompt");
        try {
            builder.build();
            assertThat("Expected NullPointerException", false);
        } catch (NullPointerException e) {
            assertThat(e.getMessage(), containsString("fallbackLanguage"));
        }
    }

    @Test
    void builderShouldAllowFluentChaining() {
        EnhancedPromptRequest.Builder builder = EnhancedPromptRequest.builder();
        EnhancedPromptRequest.Builder result = builder
                .template(null)
                .chatConfig(null)
                .rateLimits(null)
                .llmParameters(null)
                .fallbackPrompt("Test")
                .fallbackLanguage("ru")
                .user(null)
                .speakerContext(null)
                .pendingResponses(null);
        assertThat(result, is(sameInstance(builder)));
    }

    @Test
    void builderShouldBeReusableForMultipleRequests() {
        EnhancedPromptRequest.Builder builder = EnhancedPromptRequest.builder()
                .fallbackPrompt("Common prompt")
                .fallbackLanguage("en");
        EnhancedPromptRequest request1 = builder.user(new User()).build();
        EnhancedPromptRequest request2 = builder.user(null).build();
        assertThat(request1.user(), is(notNullValue()));
        assertThat(request2.user(), is(nullValue()));
        assertThat(request1.fallbackPrompt(), is(request2.fallbackPrompt()));
    }

    @Test
    void recordShouldImplementEqualsAndHashCode() {
        EnhancedPromptRequest request1 = EnhancedPromptRequest.builder()
                .fallbackPrompt("Test")
                .fallbackLanguage("en")
                .build();
        EnhancedPromptRequest request2 = EnhancedPromptRequest.builder()
                .fallbackPrompt("Test")
                .fallbackLanguage("en")
                .build();
        assertThat(request1, is(equalTo(request2)));
        assertThat(request1.hashCode(), is(request2.hashCode()));
    }

    @Test
    void recordShouldNotBeEqualWithDifferentFields() {
        EnhancedPromptRequest request1 = EnhancedPromptRequest.builder()
                .fallbackPrompt("Test1")
                .fallbackLanguage("en")
                .build();
        EnhancedPromptRequest request2 = EnhancedPromptRequest.builder()
                .fallbackPrompt("Test2")
                .fallbackLanguage("en")
                .build();
        assertThat(request1, is(not(equalTo(request2))));
    }

    @Test
    void recordShouldProvideToString() {
        EnhancedPromptRequest request = EnhancedPromptRequest.builder()
                .fallbackPrompt("Test")
                .fallbackLanguage("en")
                .build();
        String toString = request.toString();
        assertThat(toString, containsString("EnhancedPromptRequest"));
        assertThat(toString, containsString("Test"));
        assertThat(toString, containsString("en"));
    }

    @Test
    void builderShouldHandleEmptyStringFields() {
        EnhancedPromptRequest request = EnhancedPromptRequest.builder()
                .fallbackPrompt("")
                .fallbackLanguage("")
                .build();
        assertThat(request.fallbackPrompt(), is(""));
        assertThat(request.fallbackLanguage(), is(""));
    }

    @Test
    void builderShouldHandleUnicodeContent() {
        EnhancedPromptRequest request = EnhancedPromptRequest.builder()
                .fallbackPrompt("Привет мир 你好世界 🌍")
                .fallbackLanguage("ru")
                .build();
        assertThat(request.fallbackPrompt(), is("Привет мир 你好世界 🌍"));
    }

    @Test
    void builderShouldHandleEmptyPendingResponsesList() {
        EnhancedPromptRequest request = EnhancedPromptRequest.builder()
                .fallbackPrompt("Test")
                .fallbackLanguage("en")
                .pendingResponses(List.of())
                .build();
        assertThat(request.pendingResponses(), is(empty()));
    }

    @Test
    void builderShouldAcceptNullableOptionalFields() {
        EnhancedPromptRequest request = EnhancedPromptRequest.builder()
                .template(null)
                .chatConfig(null)
                .rateLimits(null)
                .llmParameters(null)
                .fallbackPrompt("Prompt")
                .fallbackLanguage("auto")
                .user(null)
                .speakerContext(null)
                .pendingResponses(null)
                .build();
        assertThat(request.template(), is(nullValue()));
        assertThat(request.chatConfig(), is(nullValue()));
        assertThat(request.rateLimits(), is(nullValue()));
        assertThat(request.llmParameters(), is(nullValue()));
        assertThat(request.user(), is(nullValue()));
        assertThat(request.speakerContext(), is(nullValue()));
        assertThat(request.pendingResponses(), is(nullValue()));
    }

    @Test
    void staticBuilderMethodShouldReturnNewInstance() {
        EnhancedPromptRequest.Builder builder1 = EnhancedPromptRequest.builder();
        EnhancedPromptRequest.Builder builder2 = EnhancedPromptRequest.builder();
        assertThat(builder1, is(not(sameInstance(builder2))));
    }
}
