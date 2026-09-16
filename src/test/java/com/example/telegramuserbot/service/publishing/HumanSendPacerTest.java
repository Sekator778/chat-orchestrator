package com.example.telegramuserbot.service.publishing;

import com.example.telegramuserbot.service.TelegramClientManager;
import com.example.telegramuserbot.service.config.AppSettingsService;
import com.example.telegramuserbot.telegram.TelegramClientFacade;
import it.tdlight.client.CommandHandler;
import it.tdlight.client.GenericResultHandler;
import it.tdlight.client.GenericUpdateHandler;
import it.tdlight.client.Result;
import it.tdlight.jni.TdApi;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.random.RandomGenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure-formula coverage for {@link HumanSendPacer} plus one virtual-time end-to-end test.
 * Real-data evidence being fixed here: the same persona sent two different texts in the same
 * second in the same chat (9 pairs in a 279-reply scorecard), and stagger was silently dropped
 * whenever the random-delay window was configured off.
 */
class HumanSendPacerTest {

    private static final String BOT_ID = "bot-1";
    private static final long CHAT_ID = -100500L;

    @Test
    void staggerAppliesEvenWhenRandomDelayWindowIsOff() {
        HumanSendPacer pacer = pacerWith(Map.of(
                "reply_timing.random_delay.max_ms", 0L,
                "reply_timing.stagger.step_ms", 3000L
        ), new FixedRandom(0L, 0.0));
        HumanSendPacer.PacingHints hints = HumanSendPacer.PacingHints.direct(2, null, 0);

        long delayMs = pacer.computePreSendDelayMs(hints, 0L);

        assertThat(delayMs)
                .as("stagger (personaIndex * step_ms) must apply even with the random window off")
                .isEqualTo(6000L);
    }

    @Test
    void readTimeIsCappedAtReadCapMs() {
        HumanSendPacer pacer = pacerWith(Map.of(
                "reply_timing.random_delay.max_ms", 0L,
                "reply_timing.stagger.step_ms", 0L,
                "reply_timing.read_ms_per_char", 35,
                "reply_timing.read_cap_ms", 1000L
        ), new FixedRandom(0L, 0.0));
        HumanSendPacer.PacingHints hints = HumanSendPacer.PacingHints.direct(0, null, 10_000);

        long delayMs = pacer.computePreSendDelayMs(hints, 0L);

        assertThat(delayMs)
                .as("triggerTextLength(10000) * 35ms/char would be 350s — must cap at read_cap_ms")
                .isEqualTo(1000L);
    }

    @Test
    void queuedHintsSkipReadAndRandomButKeepStagger() {
        HumanSendPacer pacer = pacerWith(Map.of(
                "reply_timing.random_delay.min_ms", 5000L,
                "reply_timing.random_delay.max_ms", 10000L,
                "reply_timing.stagger.step_ms", 2000L,
                "reply_timing.read_ms_per_char", 1000
        ), new FixedRandom(9999L, 0.0));
        // queued() forces personaIndex=0; use the canonical constructor to also cover stagger.
        HumanSendPacer.PacingHints hints = new HumanSendPacer.PacingHints(3, null, 500, true);

        long delayMs = pacer.computePreSendDelayMs(hints, 0L);

        assertThat(delayMs)
                .as("a pending-queue send already waited its eligibility window: no read/random time, only stagger")
                .isEqualTo(6000L);
    }

    @Test
    void gapRaisesTheDelayToRespectTheMinimumSameChatGap() {
        long lastSentAtMs = 1_000_000L;
        FixedInstantClock clock = new FixedInstantClock(Instant.ofEpochMilli(lastSentAtMs));
        // jitter fixed at 50% of the 20% band via FixedRandom's nextDouble()
        HumanSendPacer pacer = new HumanSendPacer(mock(TelegramClientManager.class),
                settingsWith(Map.of("reply_timing.min_gap_same_chat_ms", 25000L)),
                clock, new FixedRandom(0L, 0.5));
        String key = BOT_ID + "|" + CHAT_ID;
        pacer.markSent(BOT_ID, CHAT_ID); // records lastSentAtMs under "botId|chatId" via the fixed clock

        long nowMs = lastSentAtMs; // no time has passed since the last send
        long baseDelayMs = 1000L; // would otherwise land well inside the forbidden window
        long gappedMs = pacer.applyMinimumGap(key, baseDelayMs, nowMs);

        // earliestAllowed = 1_000_000 + 25_000 = 1_025_000; jitter = 0.5 * 25_000 * 0.20 = 2_500
        // expected relative delay = (1_025_000 + 2_500) - 1_000_000 = 27_500
        assertThat(gappedMs)
                .as("the gap must raise (never shorten) the delay so the send lands past lastSentAt+minGap")
                .isEqualTo(27_500L);
        assertThat(gappedMs).isGreaterThan(baseDelayMs);
    }

    @Test
    void gapDoesNothingWhenNoPriorSendIsRecorded() {
        HumanSendPacer pacer = pacerWith(Map.of("reply_timing.min_gap_same_chat_ms", 25000L), new FixedRandom(0L, 0.5));

        long gappedMs = pacer.applyMinimumGap(BOT_ID + "|" + CHAT_ID, 1234L, 0L);

        assertThat(gappedMs).isEqualTo(1234L);
    }

    @Test
    void typingWindowIsCappedAtTypingCapMs() {
        HumanSendPacer pacer = pacerWith(Map.of(
                "reply_timing.typing.ms_per_char", 60,
                "reply_timing.typing.cap_ms", 8000
        ), new FixedRandom(0L, 0.0));

        assertThat(pacer.computeTypingWindowMs("x".repeat(1000)))
                .as("1000 chars * 60ms/char = 60s — must cap at typing.cap_ms")
                .isEqualTo(8000);
        assertThat(pacer.computeTypingWindowMs("hi"))
                .as("under the cap, the window is length * ms_per_char")
                .isEqualTo(120);
        assertThat(pacer.computeTypingWindowMs(""))
                .as("an empty reply still shows a minimum one-char typing window")
                .isEqualTo(60);
    }

    @Test
    void preSendDelayIsCappedAtMaxPreSendMs() {
        HumanSendPacer pacer = pacerWith(Map.of(
                "reply_timing.max_pre_send_ms", 50_000L
        ), new FixedRandom(0L, 0.0));
        // A huge engine-decided delay would otherwise dominate the max(...) formula.
        HumanSendPacer.PacingHints hints = HumanSendPacer.PacingHints.direct(0, 1_000_000, 0);

        long delayMs = pacer.computePreSendDelayMs(hints, 0L);

        assertThat(delayMs).isEqualTo(50_000L);
    }

    @Test
    void paceCompletesAfterTheComputedDelayAndShowsTyping() {
        AtomicInteger typingCalls = new AtomicInteger();
        TelegramClientManager clientManager = mock(TelegramClientManager.class);
        when(clientManager.getClient(BOT_ID)).thenReturn(fakeClient(typingCalls));

        HumanSendPacer pacer = new HumanSendPacer(
                clientManager,
                settingsWith(Map.of(
                        "reply_timing.random_delay.max_ms", 0L,
                        "reply_timing.stagger.step_ms", 0L,
                        "reply_timing.typing.ms_per_char", 60,
                        "reply_timing.typing.cap_ms", 8000,
                        "reply_timing.min_gap_same_chat_ms", 0L
                )),
                Clock.systemUTC(),
                new FixedRandom(0L, 0.0));

        String replyText = "x".repeat(100); // typingWindowMs = 100 * 60 = 6000
        HumanSendPacer.PacingHints hints = HumanSendPacer.PacingHints.direct(0, null, 0);

        StepVerifier.withVirtualTime(() -> pacer.pace(BOT_ID, CHAT_ID, replyText, hints))
                .expectSubscription()
                .thenAwait(Duration.ofMillis(6000))
                .expectComplete()
                .verify(Duration.ofSeconds(5));

        assertThat(typingCalls.get())
                .as("at least the first typing indicator must have been sent")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void secondPaceCallForSamePersonaChatRespectsTheFirstOnesReservedSendSlot() {
        long nowMs = 1_000_000L;
        FixedInstantClock clock = new FixedInstantClock(Instant.ofEpochMilli(nowMs));
        HumanSendPacer pacer = new HumanSendPacer(mock(TelegramClientManager.class),
                settingsWith(Map.of(
                        "reply_timing.random_delay.max_ms", 0L,
                        "reply_timing.stagger.step_ms", 0L,
                        "reply_timing.typing.ms_per_char", 60,
                        "reply_timing.typing.cap_ms", 8000,
                        "reply_timing.min_gap_same_chat_ms", 25_000L
                )), clock, new FixedRandom(0L, 0.0));
        String key = BOT_ID + "|" + CHAT_ID;
        HumanSendPacer.PacingHints hints = HumanSendPacer.PacingHints.direct(0, null, 0);

        // pace() reserves its planned send slot (now + preSendMs + typingMs) the moment it is
        // CALLED, before the delay/typing even runs and long before any markSent() — this is
        // what makes two back-to-back pace() calls for the same key see each other. Text is
        // 50 chars -> typingMs = 50*60 = 3000, so the reserved slot is 1_000_000+0+3000=1_003_000.
        Mono<Void> ignored = pacer.pace(BOT_ID, CHAT_ID, "x".repeat(50), hints);

        // Second call, same instant, no markSent in between: the raw formula alone would give
        // baseDelay=0 again, but the gap must be computed against the slot the FIRST call just
        // reserved, not against an empty map.
        long secondBaseDelay = pacer.computePreSendDelayMs(hints, nowMs);
        long secondGappedDelay = pacer.applyMinimumGap(key, secondBaseDelay, nowMs);

        // earliestAllowed = reservedSlot(1_003_000) + minGap(25_000) = 1_028_000; jitter=0 (FixedRandom).
        assertThat(secondGappedDelay)
                .as("the second pace() call must respect the first call's reserved slot, not a stale/empty map")
                .isEqualTo(28_000L);
    }

    @Test
    void markSentNeverMovesTheRecordedTimestampBackwards() {
        MutableInstantClock clock = new MutableInstantClock(Instant.ofEpochMilli(2_000_000L));
        HumanSendPacer pacer = new HumanSendPacer(mock(TelegramClientManager.class),
                settingsWith(Map.of("reply_timing.min_gap_same_chat_ms", 25_000L)),
                clock, new FixedRandom(0L, 0.0));
        String key = BOT_ID + "|" + CHAT_ID;

        pacer.markSent(BOT_ID, CHAT_ID); // records 2_000_000
        clock.set(Instant.ofEpochMilli(1_000_000L));
        pacer.markSent(BOT_ID, CHAT_ID); // an EARLIER send must not overwrite the later recorded time

        long nowMs = 1_000_000L;
        long gappedMs = pacer.applyMinimumGap(key, 0L, nowMs);

        // If the later timestamp (2_000_000) survived: earliestAllowed=2_025_000, gappedMs=1_025_000.
        // A regression letting the earlier markSent overwrite it would instead give gappedMs=25_000.
        assertThat(gappedMs)
                .as("markSent must merge with max — an earlier call must never move lastSentAt backwards")
                .isEqualTo(1_025_000L);
    }

    @Test
    void typingRefreshLoopStopsWhenPaceIsCancelled() {
        AtomicInteger typingCalls = new AtomicInteger();
        TelegramClientManager clientManager = mock(TelegramClientManager.class);
        when(clientManager.getClient(BOT_ID)).thenReturn(fakeClient(typingCalls));

        HumanSendPacer pacer = new HumanSendPacer(
                clientManager,
                settingsWith(Map.of(
                        "reply_timing.random_delay.max_ms", 0L,
                        "reply_timing.stagger.step_ms", 0L,
                        "reply_timing.typing.ms_per_char", 60,
                        "reply_timing.typing.cap_ms", 60_000,
                        "reply_timing.min_gap_same_chat_ms", 0L
                )),
                Clock.systemUTC(),
                new FixedRandom(0L, 0.0));

        String replyText = "x".repeat(1000); // typingWindowMs = min(1000*60, 60000) = 60000
        HumanSendPacer.PacingHints hints = HumanSendPacer.PacingHints.direct(0, null, 0);

        VirtualTimeScheduler vts = VirtualTimeScheduler.getOrSet();
        try {
            Disposable subscription = pacer.pace(BOT_ID, CHAT_ID, replyText, hints).subscribe();
            vts.advanceTimeBy(Duration.ofMillis(500)); // let the first (immediate) typing tick fire
            int callsAtCancel = typingCalls.get();
            assertThat(callsAtCancel).isGreaterThanOrEqualTo(1);

            subscription.dispose(); // cancel the outer send: the typing refresh loop must die with it

            vts.advanceTimeBy(Duration.ofSeconds(30)); // would fire ~7 more refreshes (every 4s) if still running
            assertThat(typingCalls.get())
                    .as("cancelling pace() must dispose the typing refresh loop — no SendChatAction after cancel")
                    .isEqualTo(callsAtCancel);
        } finally {
            VirtualTimeScheduler.reset();
        }
    }

    // --- test helpers -----------------------------------------------------------------------

    private static HumanSendPacer pacerWith(Map<String, Object> overrides, RandomGenerator random) {
        return new HumanSendPacer(mock(TelegramClientManager.class), settingsWith(overrides), Clock.systemUTC(), random);
    }

    private static AppSettingsService settingsWith(Map<String, Object> overrides) {
        return new AppSettingsService(null) {
            @Override
            public long getLong(String name, long fallback) {
                Object v = overrides.get(name);
                return v != null ? ((Number) v).longValue() : fallback;
            }

            @Override
            public int getInt(String name, int fallback) {
                Object v = overrides.get(name);
                return v != null ? ((Number) v).intValue() : fallback;
            }
        };
    }

    private static TelegramClientFacade fakeClient(AtomicInteger typingCalls) {
        return new TelegramClientFacade() {
            @Override
            public <T extends TdApi.Object> CompletableFuture<T> send(TdApi.Function<T> function) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T extends TdApi.Object> void send(TdApi.Function<T> function, GenericResultHandler<T> handler) {
                if (function instanceof TdApi.SendChatAction) {
                    typingCalls.incrementAndGet();
                }
                T dummy = (T) new TdApi.Ok();
                handler.onResult(Result.of(dummy));
            }

            @Override
            public <T extends TdApi.Update> void addUpdateHandler(Class<T> type, GenericUpdateHandler<? super T> handler) {
                // no-op
            }

            @Override
            public void addUpdatesHandler(GenericUpdateHandler<TdApi.Update> handler) {
                // no-op
            }

            @Override
            public void addUpdateExceptionHandler(it.tdlight.ExceptionHandler handler) {
                // no-op
            }

            @Override
            public void addDefaultExceptionHandler(it.tdlight.ExceptionHandler handler) {
                // no-op
            }

            @Override
            public void addCommandHandler(String command, CommandHandler handler) {
                // no-op
            }
        };
    }

    /** Deterministic RandomGenerator: fixed range-pick and fixed [0,1) draw. */
    private static final class FixedRandom implements RandomGenerator {
        private final long fixedRangeValue;
        private final double fixedDouble;

        FixedRandom(long fixedRangeValue, double fixedDouble) {
            this.fixedRangeValue = fixedRangeValue;
            this.fixedDouble = fixedDouble;
        }

        @Override
        public long nextLong() {
            return fixedRangeValue;
        }

        @Override
        public long nextLong(long origin, long bound) {
            return fixedRangeValue;
        }

        @Override
        public double nextDouble() {
            return fixedDouble;
        }
    }

    /** A Clock pinned to one instant, for tests that need an exact lastSentAt. */
    private static final class FixedInstantClock extends Clock {
        private final Instant instant;

        FixedInstantClock(Instant instant) {
            this.instant = instant;
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
            return instant;
        }
    }

    /** A Clock whose instant can be advanced between calls, for markSent ordering tests. */
    private static final class MutableInstantClock extends Clock {
        private Instant instant;

        MutableInstantClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant instant) {
            this.instant = instant;
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
            return instant;
        }
    }
}
