package com.example.telegramuserbot.service.orchestration;

import com.example.telegramuserbot.domain.TelegramAccount;
import com.example.telegramuserbot.repository.TelegramAccountRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;

import static org.mockito.Mockito.when;

/**
 * Contract tests for schedule-based persona activity (owner decision):
 * FR-001: persona outside its window is NOT active.
 * FR-002: NULL window = always active (no behavior change until configured).
 * FR-003: a window wrapping past midnight works on both sides.
 * FR-004: unknown account fails open (active).
 */
@ExtendWith(MockitoExtension.class)
class PersonaScheduleServiceTest {

    private static final String BOT = "bot-a";
    // Fixed "now": 2026-06-10T12:00:00Z
    private static final Clock NOON_UTC = Clock.fixed(Instant.parse("2026-06-10T12:00:00Z"), ZoneOffset.UTC);

    @Mock
    private TelegramAccountRepository accountRepository;

    private PersonaScheduleService serviceAtNoon() {
        return new PersonaScheduleService(accountRepository, NOON_UTC);
    }

    private static TelegramAccount account(LocalTime from, LocalTime until) {
        TelegramAccount account = new TelegramAccount();
        account.setBotId(BOT);
        account.setActiveFrom(from);
        account.setActiveUntil(until);
        return account;
    }

    @Test
    void personaOutsideWindowIsSilent() {
        when(accountRepository.findByBotId(BOT))
                .thenReturn(Mono.just(account(LocalTime.of(18, 0), LocalTime.of(23, 0))));

        StepVerifier.create(serviceAtNoon().isActiveNow(BOT)).expectNext(false).verifyComplete();
    }

    @Test
    void personaInsideWindowIsActive() {
        when(accountRepository.findByBotId(BOT))
                .thenReturn(Mono.just(account(LocalTime.of(9, 0), LocalTime.of(18, 0))));

        StepVerifier.create(serviceAtNoon().isActiveNow(BOT)).expectNext(true).verifyComplete();
    }

    @Test
    void nullWindowMeansAlwaysActive() {
        when(accountRepository.findByBotId(BOT)).thenReturn(Mono.just(account(null, null)));

        StepVerifier.create(serviceAtNoon().isActiveNow(BOT)).expectNext(true).verifyComplete();
    }

    @Test
    void overnightWindowCoversBothSidesOfMidnight() {
        when(accountRepository.findByBotId(BOT))
                .thenReturn(Mono.just(account(LocalTime.of(22, 0), LocalTime.of(6, 0))));
        // noon is outside 22:00–06:00
        StepVerifier.create(serviceAtNoon().isActiveNow(BOT)).expectNext(false).verifyComplete();

        Clock twoAm = Clock.fixed(Instant.parse("2026-06-10T02:00:00Z"), ZoneOffset.UTC);
        when(accountRepository.findByBotId(BOT))
                .thenReturn(Mono.just(account(LocalTime.of(22, 0), LocalTime.of(6, 0))));
        StepVerifier.create(new PersonaScheduleService(accountRepository, twoAm).isActiveNow(BOT))
                .expectNext(true).verifyComplete();
    }

    @Test
    void unknownAccountFailsOpen() {
        when(accountRepository.findByBotId(BOT)).thenReturn(Mono.empty());

        StepVerifier.create(serviceAtNoon().isActiveNow(BOT)).expectNext(true).verifyComplete();
    }
}
