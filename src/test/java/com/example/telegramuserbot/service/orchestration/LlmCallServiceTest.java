package com.example.telegramuserbot.service.orchestration;

import com.example.telegramuserbot.domain.ChatConfig;
import com.example.telegramuserbot.domain.LlmParameters;
import com.example.telegramuserbot.service.llm.dto.ApiMessage;
import com.example.telegramuserbot.service.llm.dto.DeepSeekChatRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmCallServiceTest {

    @Test
    void shouldMergeChatConfigOverridesWithLlmParameters() {
        LLMCaller llmCaller = mock(LLMCaller.class);
        when(llmCaller.callEnhanced(anyLong(), anyLong(), any(DeepSeekChatRequest.class))).thenReturn(Mono.just("ok"));

        LlmCallService service = new LlmCallService(llmCaller);

        ChatConfig chatConfig = new ChatConfig();
        chatConfig.setMaxTokens(123);
        chatConfig.setTemperature(0.9);

        LlmParameters llmParameters = new LlmParameters(1L);
        llmParameters.setModelName("deepseek-chat");
        llmParameters.setMaxTokens(999);
        llmParameters.setTemperature(0.1);
        llmParameters.setTopP(0.42);
        llmParameters.setFrequencyPenalty(0.3);
        llmParameters.setPresencePenalty(0.2);

        List<ApiMessage> messages = List.of(
                new ApiMessage("system", "sys"),
                new ApiMessage("user", "hi")
        );

        service.call(1L, 2L, "TEST", messages, chatConfig, llmParameters).block();

        ArgumentCaptor<DeepSeekChatRequest> captor = ArgumentCaptor.forClass(DeepSeekChatRequest.class);
        verify(llmCaller).callEnhanced(anyLong(), anyLong(), captor.capture());

        DeepSeekChatRequest request = captor.getValue();
        assertThat(request).isNotNull();
        assertThat(request.model()).isEqualTo("deepseek-chat");
        assertThat(request.max_tokens()).isEqualTo(123);
        assertThat(request.temperature()).isEqualTo(0.9);
        assertThat(request.top_p()).isEqualTo(0.42);
        assertThat(request.frequency_penalty()).isEqualTo(0.3);
        assertThat(request.presence_penalty()).isEqualTo(0.2);
    }
}
