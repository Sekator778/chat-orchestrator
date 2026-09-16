package com.example.telegramuserbot.service.orchestration;

import com.example.telegramuserbot.domain.MessageEntity;
import com.example.telegramuserbot.repository.MessageRepository;
import com.example.telegramuserbot.service.humanization.PersonaService;
import com.example.telegramuserbot.service.telegram.TelegramSelfUserIdResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Owner rule (2026-06-10): a directly addressed persona always replies. These
 * tests pin down the address resolution itself — reply-to, @mention, first-name
 * whole-word mention, ambiguity, and fail-open on lookup errors.
 */
@ExtendWith(MockitoExtension.class)
class PersonaAddressResolverTest {

    private static final long CHAT_ID = -100123L;
    private static final List<String> CANDIDATES = List.of("bot-a", "bot-b");

    @Mock
    private MessageRepository messageRepository;
    @Mock
    private TelegramSelfUserIdResolver selfUserIdResolver;
    @Mock
    private PersonaService personaService;

    private PersonaAddressResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new PersonaAddressResolver(messageRepository, selfUserIdResolver, personaService);
        // No candidate has a resolvable username/first name unless a test says otherwise.
        lenient().when(selfUserIdResolver.resolveSelfUsername(anyString())).thenReturn(Mono.empty());
        lenient().when(selfUserIdResolver.resolveSelfFirstName(anyString())).thenReturn(Mono.empty());
        lenient().when(personaService.getBotName(anyString())).thenReturn(null);
    }

    private static MessageEntity replyTrigger(long replyToId) {
        MessageEntity trigger = new MessageEntity();
        trigger.setChatId(CHAT_ID);
        trigger.setMessageId(999L);
        trigger.setReplyToMessageId(replyToId);
        return trigger;
    }

    private static MessageEntity textTrigger(String content) {
        MessageEntity trigger = new MessageEntity();
        trigger.setChatId(CHAT_ID);
        trigger.setMessageId(999L);
        trigger.setContent(content);
        return trigger;
    }

    private static MessageEntity outgoingRow(String receivedByBotId) {
        MessageEntity row = new MessageEntity();
        row.setOutgoing(true);
        row.setReceivedByBotId(receivedByBotId);
        return row;
    }

    @Test
    void replyToOutgoingRowFromCandidateResolvesThatPersona() {
        MessageEntity trigger = replyTrigger(42L);
        when(messageRepository.findByChatIdAndMessageId(CHAT_ID, 42L)).thenReturn(Mono.just(outgoingRow("bot-b")));

        StepVerifier.create(resolver.resolveAddressed(trigger, CANDIDATES))
                .assertNext(addressed -> org.assertj.core.api.Assertions.assertThat(addressed).contains("bot-b"))
                .verifyComplete();
    }

    @Test
    void replyToMissFallsThroughToUsernameMention() {
        MessageEntity trigger = replyTrigger(42L);
        trigger.setContent("@bob_bot yo, what's up?");
        // Replied-to row is NOT outgoing (a human's own message) — no reply-to match.
        MessageEntity row = new MessageEntity();
        row.setOutgoing(false);
        when(messageRepository.findByChatIdAndMessageId(CHAT_ID, 42L)).thenReturn(Mono.just(row));
        when(selfUserIdResolver.resolveSelfUsername("bot-a")).thenReturn(Mono.empty());
        when(selfUserIdResolver.resolveSelfUsername("bot-b")).thenReturn(Mono.just("bob_bot"));

        StepVerifier.create(resolver.resolveAddressed(trigger, CANDIDATES))
                .assertNext(addressed -> org.assertj.core.api.Assertions.assertThat(addressed).contains("bot-b"))
                .verifyComplete();
    }

    @Test
    void firstNameWholeWordMentionResolvesThatPersona() {
        MessageEntity trigger = textTrigger("Максим, как сам?");
        when(selfUserIdResolver.resolveSelfFirstName("bot-a")).thenReturn(Mono.just("Максим"));

        StepVerifier.create(resolver.resolveAddressed(trigger, CANDIDATES))
                .assertNext(addressed -> org.assertj.core.api.Assertions.assertThat(addressed).contains("bot-a"))
                .verifyComplete();
    }

    @Test
    void firstNameSubstringInsideLongerWordDoesNotMatch() {
        // "Максим" must not match inside "Максимум" — whole-word boundary only.
        MessageEntity trigger = textTrigger("тут у нас Максимум скидка");
        when(selfUserIdResolver.resolveSelfFirstName("bot-a")).thenReturn(Mono.just("Максим"));

        StepVerifier.create(resolver.resolveAddressed(trigger, CANDIDATES))
                .assertNext(addressed -> org.assertj.core.api.Assertions.assertThat(addressed).isEmpty())
                .verifyComplete();
    }

    @Test
    void ambiguousUsernameMentionResolvesToNobody() {
        MessageEntity trigger = textTrigger("@alex @maria что скажете?");
        when(selfUserIdResolver.resolveSelfUsername("bot-a")).thenReturn(Mono.just("alex"));
        when(selfUserIdResolver.resolveSelfUsername("bot-b")).thenReturn(Mono.just("maria"));

        StepVerifier.create(resolver.resolveAddressed(trigger, CANDIDATES))
                .assertNext(addressed -> org.assertj.core.api.Assertions.assertThat(addressed).isEmpty())
                .verifyComplete();
    }

    @Test
    void ambiguousFirstNameMentionResolvesToNobody() {
        // Two candidates share the resolved first name "Максим" — ambiguity resolves to
        // "nobody addressed" rather than picking either one.
        MessageEntity trigger = textTrigger("Максим, как сам?");
        when(selfUserIdResolver.resolveSelfFirstName("bot-a")).thenReturn(Mono.just("Максим"));
        when(selfUserIdResolver.resolveSelfFirstName("bot-b")).thenReturn(Mono.just("Максим"));

        StepVerifier.create(resolver.resolveAddressed(trigger, CANDIDATES))
                .assertNext(addressed -> org.assertj.core.api.Assertions.assertThat(addressed).isEmpty())
                .verifyComplete();
    }

    @Test
    void replyToOutgoingRowFromNonCandidateFallsThroughToMentionMatching() {
        // The replied-to message was sent by some OTHER bot, not one of the current
        // dispatch candidates — reply-to must not claim the match, and since the text
        // carries no mention either, resolution falls all the way through to empty.
        MessageEntity trigger = replyTrigger(42L);
        trigger.setContent("just chatting, no mention here");
        when(messageRepository.findByChatIdAndMessageId(CHAT_ID, 42L))
                .thenReturn(Mono.just(outgoingRow("bot-c")));

        StepVerifier.create(resolver.resolveAddressed(trigger, CANDIDATES))
                .assertNext(addressed -> org.assertj.core.api.Assertions.assertThat(addressed).isEmpty())
                .verifyComplete();
    }

    @Test
    void replyToTakesPrecedenceOverASimultaneousMentionOfAnotherCandidate() {
        // Replying to bot-a's message while also @mentioning bot-b in the same text:
        // reply-to wins outright, mention matching is never even reached.
        MessageEntity trigger = replyTrigger(42L);
        trigger.setContent("@bob_bot взгляни, что думаешь?");
        when(messageRepository.findByChatIdAndMessageId(CHAT_ID, 42L))
                .thenReturn(Mono.just(outgoingRow("bot-a")));
        lenient().when(selfUserIdResolver.resolveSelfUsername("bot-b")).thenReturn(Mono.just("bob_bot"));

        StepVerifier.create(resolver.resolveAddressed(trigger, CANDIDATES))
                .assertNext(addressed -> org.assertj.core.api.Assertions.assertThat(addressed).contains("bot-a"))
                .verifyComplete();
    }

    @Test
    void wholeHandleRuleRejectsMentionInsideALongerHandle() {
        // "@alex" must not claim a message that only mentions "@alex_dev".
        MessageEntity trigger = textTrigger("Привет, @alex_dev, как сам?");
        when(selfUserIdResolver.resolveSelfUsername("bot-a")).thenReturn(Mono.just("alex"));

        StepVerifier.create(resolver.resolveAddressed(trigger, CANDIDATES))
                .assertNext(addressed -> org.assertj.core.api.Assertions.assertThat(addressed).isEmpty())
                .verifyComplete();
    }

    @Test
    void wholeHandleRuleAcceptsMentionFollowedByComma() {
        MessageEntity trigger = textTrigger("До связи, @alex, как сам?");
        when(selfUserIdResolver.resolveSelfUsername("bot-a")).thenReturn(Mono.just("alex"));

        StepVerifier.create(resolver.resolveAddressed(trigger, CANDIDATES))
                .assertNext(addressed -> org.assertj.core.api.Assertions.assertThat(addressed).contains("bot-a"))
                .verifyComplete();
    }

    @Test
    void wholeHandleRuleAcceptsMentionAtEndOfText() {
        MessageEntity trigger = textTrigger("Слушай, зайди в чат @alex");
        when(selfUserIdResolver.resolveSelfUsername("bot-a")).thenReturn(Mono.just("alex"));

        StepVerifier.create(resolver.resolveAddressed(trigger, CANDIDATES))
                .assertNext(addressed -> org.assertj.core.api.Assertions.assertThat(addressed).contains("bot-a"))
                .verifyComplete();
    }

    @Test
    void repositoryErrorFailsOpenToNobodyAddressed() {
        MessageEntity trigger = replyTrigger(42L);
        when(messageRepository.findByChatIdAndMessageId(CHAT_ID, 42L))
                .thenReturn(Mono.error(new RuntimeException("db unavailable")));

        StepVerifier.create(resolver.resolveAddressed(trigger, CANDIDATES))
                .assertNext(addressed -> org.assertj.core.api.Assertions.assertThat(addressed).isEmpty())
                .verifyComplete();
    }

    @Test
    void nullTriggerResolvesToNobodyAddressed() {
        StepVerifier.create(resolver.resolveAddressed(null, CANDIDATES))
                .assertNext(addressed -> org.assertj.core.api.Assertions.assertThat(addressed).isEmpty())
                .verifyComplete();
    }

    @Test
    void emptyCandidateListResolvesToNobodyAddressed() {
        StepVerifier.create(resolver.resolveAddressed(replyTrigger(42L), List.of()))
                .assertNext(addressed -> org.assertj.core.api.Assertions.assertThat(addressed).isEmpty())
                .verifyComplete();
    }
}
