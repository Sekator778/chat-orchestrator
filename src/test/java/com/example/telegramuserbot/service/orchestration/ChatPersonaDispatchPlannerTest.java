package com.example.telegramuserbot.service.orchestration;

import com.example.telegramuserbot.domain.Channel;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
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

    private ChatPersonaDispatchPlanner planner;

    @BeforeEach
    void setUp() {
        planner = new ChatPersonaDispatchPlanner(channelRepository, telegramClientManager, rateLimitsRepository,
                personaChatBindingRepository, personaScheduleService, telegramAccountRepository, appSettings);
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

    private static com.example.telegramuserbot.domain.PersonaChatBinding binding(String botId, double probability) {
        com.example.telegramuserbot.domain.PersonaChatBinding b = new com.example.telegramuserbot.domain.PersonaChatBinding();
        b.setBotId(botId);
        b.setReplyProbability(probability);
        return b;
    }
}
