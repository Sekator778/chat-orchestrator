package com.example.telegramuserbot.service.proactive;

import com.example.telegramuserbot.domain.ChatConfig;
import com.example.telegramuserbot.domain.LlmParameters;
import com.example.telegramuserbot.domain.MessageEntity;
import com.example.telegramuserbot.domain.ProactiveEngagement;
import com.example.telegramuserbot.domain.RateLimits;
import com.example.telegramuserbot.domain.ResponseTemplate;
import com.example.telegramuserbot.repository.MessageRepository;
import com.example.telegramuserbot.repository.ProactiveEngagementRepository;
import com.example.telegramuserbot.service.config.AppSettingsService;
import com.example.telegramuserbot.service.orchestration.BotContextResolver;
import com.example.telegramuserbot.service.orchestration.LlmCallService;
import com.example.telegramuserbot.service.orchestration.PersonaScheduleService;
import com.example.telegramuserbot.service.orchestration.PromptBuilder;
import com.example.telegramuserbot.service.orchestration.ResponsePostProcessor;
import com.example.telegramuserbot.service.orchestration.dto.EnhancedPromptRequest;
import com.example.telegramuserbot.service.publishing.HumanSendPacer;
import com.example.telegramuserbot.service.publishing.TelegramMessageSender;
import it.tdlight.jni.TdApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ProactiveEngagementService is only reachable through {@link ProactiveEngagementService#processDueEngagements()}
 * (the entry point {@code ProactiveEngagementScheduler} calls once per UTC hour) — the actual
 * generate/send pipeline lives in private methods, so every test below drives the whole flow
 * through that one public entry point.
 */
@ExtendWith(MockitoExtension.class)
class ProactiveEngagementServiceTest {

    private static final long CHAT_ID = 555L;
    private static final String BOT_ID = "bot-nova";
    private static final long ENGAGEMENT_ID = 7L;
    private static final long ANCHOR_ID = 10L;

    @Mock
    private ProactiveEngagementRepository engagementRepository;
    @Mock
    private MessageRepository messageRepository;
    @Mock
    private TelegramMessageSender messageSender;
    @Mock
    private BotContextResolver botContextResolver;
    @Mock
    private PromptBuilder promptBuilder;
    @Mock
    private LlmCallService llmCallService;
    @Mock
    private PersonaScheduleService personaScheduleService;
    @Mock
    private ResponsePostProcessor responsePostProcessor;
    @Mock
    private AppSettingsService appSettings;

    private ProactiveEngagementService service;

    @BeforeEach
    void setUp() {
        service = new ProactiveEngagementService(engagementRepository, messageRepository, messageSender,
                botContextResolver, promptBuilder, llmCallService, personaScheduleService, responsePostProcessor,
                appSettings);
        lenient().when(appSettings.getInt(eq("proactive.engagement.jitter_max_minutes"), anyInt())).thenReturn(0);
        lenient().when(messageRepository.findMaxMessageIdByChatId(CHAT_ID)).thenReturn(Mono.just(ANCHOR_ID));
        lenient().when(engagementRepository.findDueEngagements(anyShort())).thenReturn(Flux.just(dueEngagement()));
    }

    private static ProactiveEngagement dueEngagement() {
        ProactiveEngagement engagement = new ProactiveEngagement();
        engagement.setId(ENGAGEMENT_ID);
        engagement.setChatId(CHAT_ID);
        engagement.setBotInstanceId(BOT_ID);
        engagement.setLanguage("ru");
        engagement.setEnabled(true);
        // No prior anchor: the anchor-guard in processOne() never skips this engagement.
        engagement.setLastAnchorMessageId(null);
        return engagement;
    }

    private static MessageEntity inbound(long messageId, String content) {
        MessageEntity m = new MessageEntity();
        m.setChatId(CHAT_ID);
        m.setMessageId(messageId);
        m.setContent(content);
        m.setOutgoing(false);
        return m;
    }

    // --- (a) outside activity window: no LLM call, no send ---

    @Test
    void personaOutsideActivityWindowNeverCallsLlmOrSends() {
        when(personaScheduleService.isActiveNow(BOT_ID)).thenReturn(Mono.just(false));

        StepVerifier.create(service.processDueEngagements()).verifyComplete();

        verify(personaScheduleService, times(1)).isActiveNow(BOT_ID);
        verifyNoInteractions(llmCallService);
        verifyNoInteractions(messageSender);
        verify(engagementRepository, never()).markSent(any(), any(), any());
    }

    // --- (b) window closes during the jitter re-check: no send ---

    @Test
    void activityWindowClosingDuringJitterSkipsTheSend() {
        // jitter_max_minutes is stubbed to 0 in setUp(), so the jitter delay itself is zero —
        // but the post-jitter re-check of isActiveNow still runs unconditionally, which is
        // exactly the guard this test pins: active at the first check, asleep by the second.
        when(personaScheduleService.isActiveNow(BOT_ID)).thenReturn(Mono.just(true), Mono.just(false));

        StepVerifier.create(service.processDueEngagements()).verifyComplete();

        verify(personaScheduleService, times(2)).isActiveNow(BOT_ID);
        verifyNoInteractions(llmCallService);
        verifyNoInteractions(messageSender);
        verify(engagementRepository, never()).markSent(any(), any(), any());
    }

    // --- (c)/(d) happy path ---

    private ChatConfig ruChatConfig() {
        ChatConfig cfg = new ChatConfig();
        cfg.setLanguage("ru");
        return cfg;
    }

    private void stubHappyPathUpTo(ChatConfig chatConfig, ResponseTemplate template, String rawLlmText,
                                    String postProcessedText, MessageEntity latestInbound) {
        when(personaScheduleService.isActiveNow(BOT_ID)).thenReturn(Mono.just(true));
        when(messageRepository.findByChatIdOrderByDateDesc(eq(CHAT_ID), eq(PageRequest.of(0, 10))))
                .thenReturn(Flux.just(latestInbound, inbound(90L, "older message")));

        LlmParameters llmParameters = new LlmParameters();
        BotContextResolver.ResolvedConfig resolved = new BotContextResolver.ResolvedConfig(
                chatConfig, template, new RateLimits(), llmParameters, BOT_ID);
        when(botContextResolver.resolveForBot(CHAT_ID, BOT_ID)).thenReturn(Mono.just(resolved));

        when(promptBuilder.buildEnhancedPrompt(any(EnhancedPromptRequest.class))).thenReturn("system prompt");
        when(llmCallService.call(eq(CHAT_ID), eq(latestInbound.getMessageId()), eq("PROACTIVE"), anyList(),
                eq(chatConfig), eq(llmParameters)))
                .thenReturn(Mono.just(rawLlmText));
        when(responsePostProcessor.postProcess(eq(rawLlmText), eq(template))).thenReturn(postProcessedText);
    }

    @Test
    void happyPathSendsPacedMessageWithTriggerLengthFromLatestInboundAndMarksSent() {
        ChatConfig chatConfig = ruChatConfig();
        ResponseTemplate template = new ResponseTemplate();
        MessageEntity latestInbound = inbound(101L, "Привет, как у вас дела сегодня?");
        stubHappyPathUpTo(chatConfig, template, "raw llm text", "clean reply text", latestInbound);

        ArgumentCaptor<HumanSendPacer.PacingHints> hintsCaptor = ArgumentCaptor.forClass(HumanSendPacer.PacingHints.class);
        when(messageSender.sendPaced(eq(BOT_ID), eq(CHAT_ID), isNull(), eq("clean reply text"), hintsCaptor.capture()))
                .thenReturn(Mono.just(new TdApi.Message()));
        when(engagementRepository.markSent(eq(ENGAGEMENT_ID), any(Instant.class), eq(ANCHOR_ID)))
                .thenReturn(Mono.just(1));

        StepVerifier.create(service.processDueEngagements()).verifyComplete();

        verify(messageSender).sendPaced(eq(BOT_ID), eq(CHAT_ID), isNull(), eq("clean reply text"), any());
        assertThat(hintsCaptor.getValue().triggerTextLength()).isEqualTo(latestInbound.getContent().length());
        verify(engagementRepository).markSent(eq(ENGAGEMENT_ID), any(Instant.class), eq(ANCHOR_ID));
    }

    @Test
    void promptRequestSpeakerContextCarriesThePostingPersonaBotId() {
        ChatConfig chatConfig = ruChatConfig();
        ResponseTemplate template = new ResponseTemplate();
        MessageEntity latestInbound = inbound(101L, "Что нового?");
        stubHappyPathUpTo(chatConfig, template, "raw llm text", "clean reply text", latestInbound);
        when(messageSender.sendPaced(eq(BOT_ID), eq(CHAT_ID), isNull(), eq("clean reply text"), any()))
                .thenReturn(Mono.just(new TdApi.Message()));
        when(engagementRepository.markSent(eq(ENGAGEMENT_ID), any(Instant.class), eq(ANCHOR_ID)))
                .thenReturn(Mono.just(1));

        ArgumentCaptor<EnhancedPromptRequest> requestCaptor = ArgumentCaptor.forClass(EnhancedPromptRequest.class);

        StepVerifier.create(service.processDueEngagements()).verifyComplete();

        verify(promptBuilder).buildEnhancedPrompt(requestCaptor.capture());
        assertThat(requestCaptor.getValue().speakerContext()).isNotNull();
        assertThat(requestCaptor.getValue().speakerContext().botInstanceId()).isEqualTo(BOT_ID);
    }

    // --- (e) post-processed blank text: no send ---

    @Test
    void blankPostProcessedTextNeverSends() {
        ChatConfig chatConfig = ruChatConfig();
        ResponseTemplate template = new ResponseTemplate();
        MessageEntity latestInbound = inbound(101L, "Привет!");
        stubHappyPathUpTo(chatConfig, template, "raw llm text", "   ", latestInbound);

        StepVerifier.create(service.processDueEngagements()).verifyComplete();

        verifyNoInteractions(messageSender);
        verify(engagementRepository, never()).markSent(any(), any(), any());
    }
}
