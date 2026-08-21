package com.example.telegramuserbot.service.orchestration;

import com.example.telegramuserbot.domain.TelegramAccount;
import com.example.telegramuserbot.repository.TelegramAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Answers "is this persona awake right now?" from the activity window on its
 * account row (owner decision: schedule-based activity). NULL window = always
 * active, so behavior is unchanged until hours are actually configured.
 * Fail-open: an unknown account or a lookup error never silences a persona.
 */
@Service
public class PersonaScheduleService {

    private static final Logger log = LoggerFactory.getLogger(PersonaScheduleService.class);

    private final TelegramAccountRepository accountRepository;
    private final Clock clock;

    @Autowired
    public PersonaScheduleService(TelegramAccountRepository accountRepository) {
        this(accountRepository, Clock.systemDefaultZone());
    }

    PersonaScheduleService(TelegramAccountRepository accountRepository, Clock clock) {
        this.accountRepository = accountRepository;
        this.clock = clock;
    }

    public Mono<Boolean> isActiveNow(String botId) {
        if (botId == null || botId.isBlank()) {
            return Mono.just(true);
        }
        return accountRepository.findByBotId(botId)
                .map(this::isWithinWindow)
                .defaultIfEmpty(true)
                .onErrorResume(e -> {
                    log.warn("Schedule lookup failed for botId={} (treating as active): {}", botId, e.getMessage());
                    return Mono.just(true);
                });
    }

    private boolean isWithinWindow(TelegramAccount account) {
        LocalTime from = account.getActiveFrom();
        LocalTime until = account.getActiveUntil();
        if (from == null || until == null || from.equals(until)) {
            return true;
        }
        ZoneId zone = resolveZone(account);
        LocalTime now = ZonedDateTime.now(clock.withZone(zone)).toLocalTime();
        boolean active = from.isBefore(until)
                ? !now.isBefore(from) && now.isBefore(until)
                : !now.isBefore(from) || now.isBefore(until); // window wraps past midnight
        if (!active) {
            log.info("Persona botId={} is outside its activity window {}–{} ({}), staying silent",
                    account.getBotId(), from, until, zone);
        }
        return active;
    }

    private ZoneId resolveZone(TelegramAccount account) {
        String timezone = account.getTimezone();
        if (timezone == null || timezone.isBlank()) {
            return clock.getZone();
        }
        try {
            return ZoneId.of(timezone);
        } catch (Exception e) {
            log.warn("Invalid timezone '{}' for botId={}, using system default", timezone, account.getBotId());
            return clock.getZone();
        }
    }
}
