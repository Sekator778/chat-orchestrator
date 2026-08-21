package com.example.telegramuserbot.telegram;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract tests for flood-wait backoff:
 * FR-001: retry-after seconds parsed from the TDLib message (default when absent).
 * FR-002: 420/429/FLOOD_WAIT recognized; other errors are not flood.
 * FR-003: account is parked for the window and frees up after it elapses.
 */
class FloodWaitGuardTest {

    private static final Instant T0 = Instant.parse("2026-06-10T12:00:00Z");

    private final MutableClock clock = new MutableClock(T0);
    private final FloodWaitGuard guard = new FloodWaitGuard("bot-a", clock);

    @Test
    void parsesRetrySecondsFromMessage() {
        assertThat(FloodWaitGuard.parseRetrySeconds("Too Many Requests: retry after 42")).isEqualTo(42);
        assertThat(FloodWaitGuard.parseRetrySeconds("FLOOD_WAIT_17")).isEqualTo(17);
    }

    @Test
    void defaultsWhenNoSecondsPresent() {
        assertThat(FloodWaitGuard.parseRetrySeconds("FLOOD_WAIT")).isEqualTo(30);
        assertThat(FloodWaitGuard.parseRetrySeconds(null)).isEqualTo(30);
    }

    @Test
    void recognizesFloodCodes() {
        assertThat(FloodWaitGuard.isFloodWait(429, "Too Many Requests")).isTrue();
        assertThat(FloodWaitGuard.isFloodWait(420, "")).isTrue();
        assertThat(FloodWaitGuard.isFloodWait(400, "FLOOD_WAIT_5")).isTrue();
        assertThat(FloodWaitGuard.isFloodWait(400, "Bad Request")).isFalse();
    }

    @Test
    void parksForTheWindowThenFreesUp() {
        assertThat(guard.isBackingOff()).isFalse();

        guard.recordFloodWait(429, "retry after 60");
        assertThat(guard.isBackingOff()).isTrue();

        clock.advance(Duration.ofSeconds(59));
        assertThat(guard.isBackingOff()).isTrue();

        clock.advance(Duration.ofSeconds(2));
        assertThat(guard.isBackingOff()).isFalse();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
