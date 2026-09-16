package com.example.telegramuserbot.service.orchestration;

import com.example.telegramuserbot.domain.Channel;
import com.example.telegramuserbot.domain.MessageEntity;
import com.example.telegramuserbot.repository.ChannelRepository;
import com.example.telegramuserbot.repository.PersonaChatBindingRepository;
import com.example.telegramuserbot.repository.RateLimitsRepository;
import com.example.telegramuserbot.repository.TelegramAccountRepository;
import com.example.telegramuserbot.service.TelegramClientManager;
import com.example.telegramuserbot.service.config.AppSettingsService;
import com.example.telegramuserbot.telegram.TelegramClientFacade;
import reactor.core.publisher.Flux;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contract test for the owner's product rule: AT MOST ONE persona answers a
 * message when the legacy cap is in force (responders.max_per_message=1), and
 * — under the per-persona model — each persona decides for itself. Dispatch
 * knobs come from bot.app_settings via {@link AppSettingsService}; the test
 * stubs those reads instead of poking private fields. The rule survives any
 * planner redesign — only the selection mechanism is an implementation detail.
 */
@ExtendWith(MockitoExtension.class)
class ChatPersonaDispatchPlannerTest {

    private static final long CHAT_ID = -100123L;
    private static final List<String> CANDIDATES = List.of("bot-a", "bot-b", "bot-c");

    @Mock
    private ChannelRepository channelRepository;
    @Mock
    private TelegramClientManager telegramClientManager;
    @Mock
    private RateLimitsRepository rateLimitsRepository;
    @Mock
    private PersonaChatBindingRepository personaChatBindingRepository;
    @Mock
    private PersonaScheduleService personaScheduleService;
    @Mock
    private TelegramAccountRepository telegramAccountRepository;
    @Mock
    private AppSettingsService appSettings;
    @Mock
    private PersonaAddressResolver personaAddressResolver;

    private ChatPersonaDispatchPlanner planner;

    @BeforeEach
    void setUp() {
        planner = new ChatPersonaDispatchPlanner(channelRepository, telegramClientManager, rateLimitsRepository,
                personaChatBindingRepository, personaScheduleService, telegramAccountRepository, appSettings,
                personaAddressResolver);
        // Defaults mirror the seeded-row fallbacks; individual tests override the knob they exercise.
        lenient().when(appSettings.getBoolean("bindings.enabled", false)).thenReturn(false);
        lenient().when(appSettings.getBoolean("responders.per_persona_decision_enabled", false)).thenReturn(false);
        lenient().when(appSettings.getInt("responders.max_per_message", 1)).thenReturn(1);
        lenient().when(appSettings.getDouble("responders.default_reply_probability", 0.5)).thenReturn(0.5);

        Channel channel = new Channel();
        channel.setBotInstanceIds(CANDIDATES);
        lenient().when(channelRepository.findByChatId(CHAT_ID)).thenReturn(Mono.just(channel));
        lenient().when(telegramClientManager.getClient(anyString())).thenReturn(mock(TelegramClientFacade.class));
        lenient().when(personaScheduleService.isActiveNow(anyString())).thenReturn(Mono.just(true));
        // No candidate is a collector by default — preserves existing selection behavior.
        lenient().when(telegramAccountRepository.isCollector(anyString())).thenReturn(Mono.just(false));
        // Nobody addressed by default — existing (pre-WP4) behavior for every test that doesn't
        // pass a trigger, and for the 2-arg planBotIds(...) overload which always passes null.
        lenient().when(personaAddressResolver.resolveAddressed(org.mockito.ArgumentMatchers.any(), anyList()))
                .thenReturn(Mono.just(Optional.empty()));
    }

    private static MessageEntity triggerWithReplyTo(long replyToMessageId) {
        MessageEntity trigger = new MessageEntity();
        trigger.setChatId(CHAT_ID);
        trigger.setMessageId(999L);
        trigger.setReplyToMessageId(replyToMessageId);
        return trigger;
    }

    private static MessageEntity triggerWithText(String content) {
        MessageEntity trigger = new MessageEntity();
        trigger.setChatId(CHAT_ID);
        trigger.setMessageId(999L);
        trigger.setContent(content);
        return trigger;
    }

    @Test
    void personaOutsideScheduleIsNotACandidate() {
        when(appSettings.getInt("responders.max_per_message", 1)).thenReturn(0);
        when(personaScheduleService.isActiveNow("bot-b")).thenReturn(Mono.just(false));

        StepVerifier.create(planner.planBotIds(CHAT_ID, null))
                .assertNext(selected -> assertThat(selected).containsExactly("bot-a", "bot-c"))
                .verifyComplete();
    }

    @Test
    void atMostOnePersonaAnswersByDefault() {
        when(appSettings.getInt("responders.max_per_message", 1)).thenReturn(1);

        StepVerifier.create(planner.planBotIds(CHAT_ID, null))
                .assertNext(selected -> {
                    assertThat(selected).hasSize(1);
                    assertThat(CANDIDATES).contains(selected.get(0));
                })
                .verifyComplete();
    }

    @Test
    void zeroCapRestoresLegacyAnswerWithAllBehavior() {
        when(appSettings.getInt("responders.max_per_message", 1)).thenReturn(0);

        StepVerifier.create(planner.planBotIds(CHAT_ID, null))
                .assertNext(selected -> assertThat(selected).containsExactlyElementsOf(CANDIDATES))
                .verifyComplete();
    }

    @Test
    void bindingsAreTheCandidateSourceWhenEnabled() {
        when(appSettings.getInt("responders.max_per_message", 1)).thenReturn(0);
        when(appSettings.getBoolean("bindings.enabled", false)).thenReturn(true);
        when(personaChatBindingRepository.findEnabledBotIdsByChatId(CHAT_ID)).thenReturn(Flux.just("bot-x"));

        StepVerifier.create(planner.planBotIds(CHAT_ID, null))
                .assertNext(selected -> assertThat(selected).containsExactly("bot-x"))
                .verifyComplete();
    }

    @Test
    void chatWithoutBindingsFallsBackToLegacyChannelArray() {
        when(appSettings.getInt("responders.max_per_message", 1)).thenReturn(0);
        when(appSettings.getBoolean("bindings.enabled", false)).thenReturn(true);
        when(personaChatBindingRepository.findEnabledBotIdsByChatId(CHAT_ID)).thenReturn(Flux.empty());

        StepVerifier.create(planner.planBotIds(CHAT_ID, null))
                .assertNext(selected -> assertThat(selected).containsExactlyElementsOf(CANDIDATES))
                .verifyComplete();
    }

    @Test
    void perPersonaDecisionIncludesEachPersonaByItsOwnProbability() {
        // Owner model: no cap; each persona decides for itself. bot-a p=1.0 (always),
        // bot-b p=0.0 (never), bot-c p=1.0 (always) → exactly {a, c}, deterministically.
        when(appSettings.getBoolean("responders.per_persona_decision_enabled", false)).thenReturn(true);
        when(personaChatBindingRepository.findEnabledBindingsByChatId(CHAT_ID))
                .thenReturn(Flux.just(binding("bot-a", 1.0), binding("bot-b", 0.0), binding("bot-c", 1.0)));

        StepVerifier.create(planner.planBotIds(CHAT_ID, null))
                .assertNext(selected -> assertThat(selected).containsExactly("bot-a", "bot-c"))
                .verifyComplete();
    }

    @Test
    void rollSucceedsIsDeterministicAtTheBounds() {
        assertThat(planner.rollSucceeds(1.0)).isTrue();
        assertThat(planner.rollSucceeds(0.0)).isFalse();
    }

    @Test
    void replyToAddressedPersonaAnswersAloneWhileOthersStayQuiet() {
        // A human replying to bot-b's message should make bot-b (and only bot-b) answer,
        // even though bot-a and bot-c are also bound to this chat.
        MessageEntity trigger = triggerWithReplyTo(42L);
        when(personaAddressResolver.resolveAddressed(eq(trigger), eq(CANDIDATES)))
                .thenReturn(Mono.just(Optional.of("bot-b")));

        StepVerifier.create(planner.planBotIds(CHAT_ID, null, trigger))
                .assertNext(selected -> assertThat(selected).containsExactly("bot-b"))
                .verifyComplete();
    }

    @Test
    void mentionedPersonaAnswersEvenWithZeroReplyProbability() {
        // Owner rule: addressing bypasses selectFinalResponders (the roll/cap step) entirely —
        // no per-persona probability or binding is even consulted for an addressed persona.
        MessageEntity trigger = triggerWithText("@alex_persona привет, что думаешь?");
        when(personaAddressResolver.resolveAddressed(eq(trigger), eq(CANDIDATES)))
                .thenReturn(Mono.just(Optional.of("bot-a")));

        StepVerifier.create(planner.planBotIds(CHAT_ID, null, trigger))
                .assertNext(selected -> assertThat(selected).containsExactly("bot-a"))
                .verifyComplete();
    }

    @Test
    void addressedPersonaOutsideActivityWindowAnswersForNobody() {
        MessageEntity trigger = triggerWithReplyTo(42L);
        when(personaAddressResolver.resolveAddressed(eq(trigger), eq(CANDIDATES)))
                .thenReturn(Mono.just(Optional.of("bot-a")));
        when(personaScheduleService.isActiveNow("bot-a")).thenReturn(Mono.just(false));

        StepVerifier.create(planner.planBotIds(CHAT_ID, null, trigger))
                .assertNext(selected -> assertThat(selected).isEmpty())
                .verifyComplete();
    }

    @Test
    void noAddressPreservesExistingCapBehavior() {
        // Default stub resolves to Optional.empty() — same legacy-cap behavior as before WP4.
        when(appSettings.getInt("responders.max_per_message", 1)).thenReturn(1);

        StepVerifier.create(planner.planBotIds(CHAT_ID, null, triggerWithText("just chatting, no mention here")))
                .assertNext(selected -> {
                    assertThat(selected).hasSize(1);
                    assertThat(CANDIDATES).contains(selected.get(0));
                })
                .verifyComplete();
    }

    @Test
    void addressedPersonaRepliesEvenWithExhaustedDailyQuotaAndNeverConsultsTheQuota() {
        long chatConfigId = 900L;
        MessageEntity trigger = triggerWithReplyTo(42L);
        when(personaAddressResolver.resolveAddressed(eq(trigger), eq(CANDIDATES)))
                .thenReturn(Mono.just(Optional.of("bot-b")));
        // rateLimitsRepository is deliberately left unstubbed: the addressed-persona path
        // must return without ever consulting the daily quota at all (see verify below),
        // not merely receive a favorable quota answer.

        StepVerifier.create(planner.planBotIds(CHAT_ID, chatConfigId, trigger))
                .assertNext(selected -> assertThat(selected).containsExactly("bot-b"))
                .verifyComplete();

        verify(rateLimitsRepository, never()).reserveDailySlotsIfAllowed(anyLong(), anyInt());
        verify(rateLimitsRepository, never()).findByChatConfigId(anyLong());
    }

    @Test
    void noAddressPathStillGoesThroughTheExhaustedDailyQuotaAndReturnsEmpty() {
        // Same exhausted-quota chat, but nobody is directly addressed this time — the
        // legacy cap/quota pipeline still applies and blocks everyone (existing behavior,
        // unlike the addressed-persona bypass pinned above).
        long chatConfigId = 901L;
        when(appSettings.getInt("responders.max_per_message", 1)).thenReturn(1);
        com.example.telegramuserbot.domain.RateLimits exhausted = new com.example.telegramuserbot.domain.RateLimits(chatConfigId);
        exhausted.setMaxMessagesPerDay(5);
        exhausted.setCurrentDailyMessages(5);
        when(rateLimitsRepository.findByChatConfigId(chatConfigId)).thenReturn(Mono.just(exhausted));
        // ensureRateLimits(...) always builds the switchIfEmpty(save(...)) fallback Mono eagerly
        // (it is only ever SUBSCRIBED when findByChatConfigId is empty, which it is not here) —
        // a raw Mockito mock still needs this stubbed or the eager .save(...) call returns null.
        lenient().when(rateLimitsRepository.save(any(com.example.telegramuserbot.domain.RateLimits.class)))
                .thenReturn(Mono.just(exhausted));

        StepVerifier.create(planner.planBotIds(CHAT_ID, chatConfigId, triggerWithText("just chatting, no mention here")))
                .assertNext(selected -> assertThat(selected).isEmpty())
                .verifyComplete();
    }

    private static com.example.telegramuserbot.domain.PersonaChatBinding binding(String botId, double probability) {
        com.example.telegramuserbot.domain.PersonaChatBinding b = new com.example.telegramuserbot.domain.PersonaChatBinding();
        b.setBotId(botId);
        b.setReplyProbability(probability);
        return b;
    }
}
