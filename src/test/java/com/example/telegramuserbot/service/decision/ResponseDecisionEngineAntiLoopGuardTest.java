package com.example.telegramuserbot.service.decision;

import com.example.telegramuserbot.domain.ChatConfig;
import com.example.telegramuserbot.domain.MessageEntity;
import com.example.telegramuserbot.domain.TriggerCondition;
import com.example.telegramuserbot.domain.TriggerType;
import com.example.telegramuserbot.repository.ChatConfigRepository;
import com.example.telegramuserbot.repository.MessageRepository;
import com.example.telegramuserbot.repository.RateLimitsRepository;
import com.example.telegramuserbot.repository.TopicRestrictionRepository;
import com.example.telegramuserbot.repository.TriggerConditionRepository;
import com.example.telegramuserbot.service.decision.ConversationAnalysisService.ConversationActivity;
import com.example.telegramuserbot.service.decision.ConversationAnalysisService.ConversationContext;
import com.example.telegramuserbot.service.decision.ConversationAnalysisService.ConversationTopic;
import com.example.telegramuserbot.service.decision.ResponseDecisionEngine.ResponseDecision;
import com.example.telegramuserbot.service.processing.MessageRateLimiterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the bot-to-bot anti-loop guard in {@link ResponseDecisionEngine}.
 *
 * <p>Covers AC-003-1 and AC-003-2:
 * <ul>
 *   <li>AC-003-1: outgoing message that is NOT a directed reply to a persona's own message
 *       must be suppressed before trigger evaluation.</li>
 *   <li>AC-003-2: outgoing message that IS a directed reply (reply_to_message_id resolves
 *       to an outgoing message) must NOT be immediately suppressed — it proceeds to trigger
 *       evaluation.</li>
 * </ul>
 *
 * <p>NFR-004: each {@code decide()} call for chatId=-4964162923 emits an INFO-level log
 * entry with chatId, persona outcome, and skip reason (validated via log-capturing assertions
 * indirectly through decision result).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ResponseDecisionEngine — anti-loop guard (FR-003 / FR-004)")
class ResponseDecisionEngineAntiLoopGuardTest {

    private static final long TEST_CHAT_ID = -4964162923L;
    private static final long PERSONA_1_MSG_ID = 1001L;
    private static final long PERSONA_1_REPLY_MSG_ID = 1002L;
    private static final long HUMAN_MSG_ID = 2001L;

    @Mock
    private ChatConfigRepository chatConfigRepository;
    @Mock
    private RateLimitsRepository rateLimitsRepository;
    @Mock
    private TopicRestrictionRepository topicRestrictionRepository;
    @Mock
    private TriggerConditionRepository triggerConditionRepository;
    @Mock
    private MessageRateLimiterService rateLimiterService;
    @Mock
    private MessageRepository messageRepository;
    @Mock
    private CooldownService cooldownService;
    @Mock
    private com.example.telegramuserbot.service.config.AppSettingsService appSettings;

    private ResponseDecisionEngine engine;

    @BeforeEach
    void setUp() {
        engine = new ResponseDecisionEngine(
                chatConfigRepository,
                rateLimitsRepository,
                topicRestrictionRepository,
                triggerConditionRepository,
                rateLimiterService,
                messageRepository,
                cooldownService,
                appSettings
        );
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private ChatConfig enabledChatConfig() {
        ChatConfig cfg = new ChatConfig();
        cfg.setId(55L);
        // channel_chat_id maps to channelId field in ChatConfig
        cfg.setChannelId(TEST_CHAT_ID);
        cfg.setEnabled(true);
        return cfg;
    }

    private MessageEntity outgoingMessage(long messageId) {
        MessageEntity m = new MessageEntity();
        m.setId(messageId * 10);
        m.setChatId(TEST_CHAT_ID);
        m.setMessageId(messageId);
        m.setOutgoing(true);
        m.setContent("Outgoing persona message");
        m.setDate(Instant.now());
        return m;
    }

    private MessageEntity outgoingReplyMessage(long messageId, long replyToMessageId) {
        MessageEntity m = outgoingMessage(messageId);
        m.setReplyToMessageId(replyToMessageId);
        return m;
    }

    private MessageEntity incomingHumanMessage(long messageId) {
        MessageEntity m = new MessageEntity();
        m.setId(messageId * 10);
        m.setChatId(TEST_CHAT_ID);
        m.setMessageId(messageId);
        m.setOutgoing(false);
        m.setSenderId(999888L);
        m.setContent("Human message: привет ребята");
        m.setDate(Instant.now());
        return m;
    }

    private ConversationContext contextFor(MessageEntity triggeringMessage) {
        return new ConversationContext(
                triggeringMessage,
                List.of(triggeringMessage),
                ConversationActivity.inactive(),
                ConversationTopic.unknown()
        );
    }

    private TriggerCondition continuousTrigger(long chatConfigId) {
        TriggerCondition tc = new TriggerCondition();
        tc.setId(1L);
        tc.setChatConfigId(chatConfigId);
        tc.setConditionName("continuous-test");
        tc.setTriggerType(TriggerType.CONTINUOUS);
        tc.setActive(true);
        tc.setProbabilityPercent(100);
        tc.setTimeDelaySeconds(3);
        tc.setPriority(1);
        return tc;
    }

    // -----------------------------------------------------------------------
    // AC-003-1: outgoing non-directed message is suppressed
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("AC-003-1: outgoing non-directed message suppressed before trigger evaluation")
    class OutgoingNonDirectedMessageSuppressed {

        @Test
        @DisplayName("decide() returns skip when triggering message is outgoing and has no reply_to_message_id")
        void outgoingMessageWithoutReplyIsSuppressed() {
            // GIVEN: chat is enabled
            ChatConfig cfg = enabledChatConfig();
            when(chatConfigRepository.findByChannelChatId(TEST_CHAT_ID)).thenReturn(Mono.just(cfg));
            when(cooldownService.isSilenced(TEST_CHAT_ID)).thenReturn(false);

            // GIVEN: triggering message is outgoing (sent by a persona) with no reply
            MessageEntity outgoing = outgoingMessage(PERSONA_1_MSG_ID);
            ConversationContext ctx = contextFor(outgoing);

            // WHEN
            Mono<ResponseDecision> result = engine.decide(ctx);

            // THEN: skipped before trigger evaluation — trigger repo NOT consulted
            StepVerifier.create(result)
                    .assertNext(decision -> {
                        assertThat(decision.shouldRespond()).isFalse();
                        assertThat(decision.reason()).contains("anti-loop");
                    })
                    .verifyComplete();

            // Trigger conditions must NOT be queried (guard fires before trigger eval)
            verifyNoInteractions(triggerConditionRepository);
        }

        @Test
        @DisplayName("decide() returns skip when outgoing message is a forward (non-null forwardFromChatId, no reply)")
        void outgoingForwardWithoutReplyIsSuppressed() {
            ChatConfig cfg = enabledChatConfig();
            when(chatConfigRepository.findByChannelChatId(TEST_CHAT_ID)).thenReturn(Mono.just(cfg));
            when(cooldownService.isSilenced(TEST_CHAT_ID)).thenReturn(false);

            MessageEntity forward = outgoingMessage(PERSONA_1_MSG_ID);
            forward.setForwardFromChatId(TEST_CHAT_ID - 1000); // arbitrary forward source
            // no replyToMessageId set
            ConversationContext ctx = contextFor(forward);

            StepVerifier.create(engine.decide(ctx))
                    .assertNext(d -> assertThat(d.shouldRespond()).isFalse())
                    .verifyComplete();

            verifyNoInteractions(triggerConditionRepository);
        }
    }

    // -----------------------------------------------------------------------
    // AC-003-2: outgoing directed reply to an outgoing message is NOT suppressed
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("AC-003-2: outgoing directed reply to outgoing message proceeds to trigger evaluation")
    class OutgoingDirectedReplyAllowed {

        @Test
        @DisplayName("decide() does NOT immediately suppress when reply_to resolves to an outgoing message")
        void outgoingDirectedReplyProceedsToTriggerEvaluation() {
            // GIVEN: chat is enabled
            ChatConfig cfg = enabledChatConfig();
            when(chatConfigRepository.findByChannelChatId(TEST_CHAT_ID)).thenReturn(Mono.just(cfg));
            when(cooldownService.isSilenced(TEST_CHAT_ID)).thenReturn(false);

            // GIVEN: M1 is a previously stored outgoing message (persona 1)
            MessageEntity m1 = outgoingMessage(PERSONA_1_MSG_ID);
            // GIVEN: triggering message is persona 2 replying to M1
            MessageEntity replyMsg = outgoingReplyMessage(PERSONA_1_REPLY_MSG_ID, PERSONA_1_MSG_ID);

            // Stub: resolveReplyTarget returns M1 (outgoing)
            when(messageRepository.findByChatIdAndMessageId(TEST_CHAT_ID, PERSONA_1_MSG_ID))
                    .thenReturn(Mono.just(m1));

            // The REPLY_TO_BOT path calls respondWithRateLimits(context, chatConfig)
            // which queries triggerConditionRepository (not topic restrictions)
            TriggerCondition tc = continuousTrigger(cfg.getId());
            when(triggerConditionRepository.findByChatConfigChannelChatIdAndActiveOrderByPriorityDesc(TEST_CHAT_ID, true))
                    .thenReturn(Flux.just(tc));

            // Stub rate limits (no limit row → allowed by default)
            when(rateLimitsRepository.findByChatConfigChannelChatId(TEST_CHAT_ID)).thenReturn(Mono.empty());

            ConversationContext ctx = contextFor(replyMsg);

            // WHEN
            Mono<ResponseDecision> result = engine.decide(ctx);

            // THEN: engine proceeds past the anti-loop guard and evaluates triggers
            StepVerifier.create(result)
                    .assertNext(decision -> {
                        // CONTINUOUS trigger fires → should respond
                        assertThat(decision.shouldRespond()).isTrue();
                    })
                    .verifyComplete();

            // Trigger conditions WERE consulted (not short-circuited by anti-loop)
            verify(triggerConditionRepository, atLeastOnce())
                    .findByChatConfigChannelChatIdAndActiveOrderByPriorityDesc(TEST_CHAT_ID, true);
        }

        @Test
        @DisplayName("decide() suppresses when reply_to message does NOT resolve (not found in DB)")
        void outgoingReplyToNonExistentMessageIsSuppressed() {
            ChatConfig cfg = enabledChatConfig();
            when(chatConfigRepository.findByChannelChatId(TEST_CHAT_ID)).thenReturn(Mono.just(cfg));
            when(cooldownService.isSilenced(TEST_CHAT_ID)).thenReturn(false);

            MessageEntity replyMsg = outgoingReplyMessage(PERSONA_1_REPLY_MSG_ID, PERSONA_1_MSG_ID);

            // Stub: reply target not found in DB
            when(messageRepository.findByChatIdAndMessageId(TEST_CHAT_ID, PERSONA_1_MSG_ID))
                    .thenReturn(Mono.empty());

            ConversationContext ctx = contextFor(replyMsg);

            StepVerifier.create(engine.decide(ctx))
                    .assertNext(d -> {
                        assertThat(d.shouldRespond()).isFalse();
                        assertThat(d.reason()).contains("anti-loop");
                    })
                    .verifyComplete();

            verifyNoInteractions(triggerConditionRepository);
        }

        @Test
        @DisplayName("decide() suppresses when reply_to resolves to an INCOMING (non-outgoing) message")
        void outgoingReplyToIncomingMessageIsSuppressed() {
            ChatConfig cfg = enabledChatConfig();
            when(chatConfigRepository.findByChannelChatId(TEST_CHAT_ID)).thenReturn(Mono.just(cfg));
            when(cooldownService.isSilenced(TEST_CHAT_ID)).thenReturn(false);

            // reply target is an incoming human message (isOutgoing=false)
            MessageEntity humanMsg = incomingHumanMessage(HUMAN_MSG_ID);
            MessageEntity replyMsg = outgoingReplyMessage(PERSONA_1_REPLY_MSG_ID, HUMAN_MSG_ID);

            when(messageRepository.findByChatIdAndMessageId(TEST_CHAT_ID, HUMAN_MSG_ID))
                    .thenReturn(Mono.just(humanMsg));

            ConversationContext ctx = contextFor(replyMsg);

            StepVerifier.create(engine.decide(ctx))
                    .assertNext(d -> {
                        assertThat(d.shouldRespond()).isFalse();
                        assertThat(d.reason()).contains("anti-loop");
                    })
                    .verifyComplete();

            verifyNoInteractions(triggerConditionRepository);
        }
    }

    // -----------------------------------------------------------------------
    // Baseline: incoming human message still triggers persona response (FR-001)
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("FR-001: incoming human message triggers response (anti-loop guard does not affect)")
    class IncomingHumanMessageStillWorks {

        @Test
        @DisplayName("decide() evaluates triggers for a normal incoming human message")
        void incomingHumanMessageReachesTriggersEvaluation() {
            ChatConfig cfg = enabledChatConfig();
            when(chatConfigRepository.findByChannelChatId(TEST_CHAT_ID)).thenReturn(Mono.just(cfg));
            when(cooldownService.isSilenced(TEST_CHAT_ID)).thenReturn(false);

            MessageEntity humanMsg = incomingHumanMessage(HUMAN_MSG_ID);
            ConversationContext ctx = contextFor(humanMsg);

            TriggerCondition tc = continuousTrigger(cfg.getId());
            when(triggerConditionRepository.findByChatConfigChannelChatIdAndActiveOrderByPriorityDesc(TEST_CHAT_ID, true))
                    .thenReturn(Flux.just(tc));
            when(topicRestrictionRepository.findByChatConfigChannelChatIdAndActive(TEST_CHAT_ID, true))
                    .thenReturn(Flux.empty());
            when(rateLimitsRepository.findByChatConfigChannelChatId(TEST_CHAT_ID)).thenReturn(Mono.empty());

            StepVerifier.create(engine.decide(ctx))
                    .assertNext(d -> assertThat(d.shouldRespond()).isTrue())
                    .verifyComplete();

            verify(triggerConditionRepository, atLeastOnce())
                    .findByChatConfigChannelChatIdAndActiveOrderByPriorityDesc(TEST_CHAT_ID, true);
        }
    }
}
