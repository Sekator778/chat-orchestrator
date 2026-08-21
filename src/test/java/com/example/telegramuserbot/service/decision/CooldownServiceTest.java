package com.example.telegramuserbot.service.decision;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for deflect-then-silence (owner decision):
 * FR-001: first accusation does NOT silence the chat (persona keeps talking).
 * FR-002: a repeat within the strike window silences immediately.
 * FR-003: silence expires after the cooldown TTL.
 * FR-004: strikes outside the window count as first again.
 */
class CooldownServiceTest {

    private static final long CHAT = -100500L;
    private static final Instant T0 = Instant.parse("2026-06-10T12:00:00Z");

    private final MutableClock clock = new MutableClock(T0);
    private final CooldownService service = new CooldownService(clock);

    CooldownServiceTest() {
        ReflectionTestUtils.setField(service, "silenceMinutes", 30);
        ReflectionTestUtils.setField(service, "strikeWindowMinutes", 60);
    }

    @Test
    void firstStrikeDeflectsWithoutSilence() {
        assertThat(service.registerStrike(CHAT)).isFalse();
        assertThat(service.isSilenced(CHAT)).isFalse();
    }

    @Test
    void repeatStrikeWithinWindowSilences() {
        service.registerStrike(CHAT);
        clock.advance(Duration.ofMinutes(5));

        assertThat(service.registerStrike(CHAT)).isTrue();
        assertThat(service.isSilenced(CHAT)).isTrue();
    }

    @Test
    void silenceExpiresAfterTtl() {
        service.silenceNow(CHAT);
        assertThat(service.isSilenced(CHAT)).isTrue();

        clock.advance(Duration.ofMinutes(31));
        assertThat(service.isSilenced(CHAT)).isFalse();
    }

    @Test
    void strikeOutsideWindowCountsAsFirstAgain() {
        service.registerStrike(CHAT);
        clock.advance(Duration.ofMinutes(61));

        assertThat(service.registerStrike(CHAT)).isFalse();
        assertThat(service.isSilenced(CHAT)).isFalse();
    }

    /** Minimal mutable clock for TTL tests. */
    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
