package com.example.telegramuserbot.service.publishing;

import com.example.telegramuserbot.service.TelegramClientManager;
import com.example.telegramuserbot.service.config.AppSettingsService;
import com.example.telegramuserbot.telegram.TelegramClientFacade;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;

/**
 * Human-like send pacing shared by every outbound path: a length-aware pre-send
 * "read + think" delay, a per-persona stagger that is ALWAYS applied, a minimum
 * gap between two sends of the same persona in the same chat, and a repeating
 * typing indicator for the duration a human would take to type the reply.
 *
 * <p>Real-data evidence this fixes (279-reply scorecard): the same persona sent
 * two different texts in the same second in the same chat (9 pairs); replies
 * from the pending queue and sibling replies showed no "typing…" cue; the
 * pre-send delay ignored the length of the message being answered; stagger was
 * silently dropped whenever the random-delay window was configured off.
 *
 * <p>Every knob lives in {@code bot.app_settings}, read through
 * {@link AppSettingsService} — no {@code application.yml} entries here, so an
 * operator tunes pacing by editing a row, not redeploying.
 */
@Component
public class HumanSendPacer {

    private static final Logger log = LoggerFactory.getLogger(HumanSendPacer.class);

    /** Telegram's typing status expires ~5s server-side; refresh it before that. */
    private static final Duration TYPING_REFRESH_INTERVAL = Duration.ofMillis(4000);
    private static final int SWEEP_THRESHOLD = 2048;
    private static final long SWEEP_MAX_AGE_MS = Duration.ofHours(1).toMillis();

    private final TelegramClientManager telegramClientManager;
    private final AppSettingsService appSettings;
    private final Clock clock;
    private final RandomGenerator random;

    /** lastSentAt (epoch ms) per "botId|chatId", enforcing the minimum same-chat gap. */
    private final ConcurrentHashMap<String, Long> lastSentAtMs = new ConcurrentHashMap<>();

    @Autowired
    public HumanSendPacer(TelegramClientManager telegramClientManager, AppSettingsService appSettings) {
        this(telegramClientManager, appSettings, Clock.systemUTC(), ThreadLocalRandom.current());
    }

    /** Test-only constructor: an injectable clock/random makes the delay math deterministic. */
    HumanSendPacer(TelegramClientManager telegramClientManager, AppSettingsService appSettings,
                   Clock clock, RandomGenerator random) {
        this.telegramClientManager = telegramClientManager;
        this.appSettings = appSettings;
        this.clock = clock;
        this.random = random;
    }

    /**
     * Hints describing the send being paced.
     *
     * @param personaIndex        stagger offset within a fan-out (0-based)
     * @param decidedDelaySeconds nullable floor delay from the decision engine
     * @param triggerTextLength   length of the message being answered (drives read time)
     * @param queued              true for a pending-queue send, which already waited out its
     *                            own eligibility window — no extra random/read time is added,
     *                            only stagger and the minimum-gap floor
     */
    public record PacingHints(int personaIndex, Integer decidedDelaySeconds, int triggerTextLength, boolean queued) {

        public static PacingHints direct(int personaIndex, Integer decidedDelaySeconds, int triggerTextLength) {
            return new PacingHints(personaIndex, decidedDelaySeconds, triggerTextLength, false);
        }

        public static PacingHints queued(int triggerTextLength) {
            return new PacingHints(0, null, triggerTextLength, true);
        }
    }

    // --- Knobs: table-driven via bot.app_settings (AppSettingsService TTL cache) ---
    private long randomDelayMinMs() { return appSettings.getLong("reply_timing.random_delay.min_ms", 0L); }
    private long randomDelayMaxMs() { return appSettings.getLong("reply_timing.random_delay.max_ms", 0L); }
    private long staggerStepMs()    { return appSettings.getLong("reply_timing.stagger.step_ms", 3000L); }
    private int typingMsPerChar()   { return appSettings.getInt("reply_timing.typing.ms_per_char", 60); }
    private int typingCapMs()       { return appSettings.getInt("reply_timing.typing.cap_ms", 8000); }
    private int readMsPerChar()     { return appSettings.getInt("reply_timing.read_ms_per_char", 35); }
    private int readCapMs()         { return appSettings.getInt("reply_timing.read_cap_ms", 12000); }
    private long minGapSameChatMs() { return appSettings.getLong("reply_timing.min_gap_same_chat_ms", 25000L); }
    private long maxPreSendMs()     { return appSettings.getLong("reply_timing.max_pre_send_ms", 180000L); }

    /**
     * Completes right before the caller sends: waits the computed pre-send delay,
     * then shows (and repeats) the typing indicator for the computed typing window.
     */
    public Mono<Void> pace(String botId, long chatId, String outgoingText, PacingHints hints) {
        Objects.requireNonNull(hints, "hints");
        String gapKey = key(botId, chatId);
        long nowMs = clock.millis();

        long baseDelay = computePreSendDelayMs(hints, nowMs);
        long gappedDelay = applyMinimumGap(gapKey, baseDelay, nowMs);
        long preSendMs = Math.min(gappedDelay, Math.max(0L, maxPreSendMs()));
        long gapAppliedMs = Math.max(0L, preSendMs - baseDelay);
        int typingMs = computeTypingWindowMs(outgoingText);
        // Reserve the planned send slot now, not after the round trip: two paced sends for the
        // same persona+chat that start within milliseconds of each other must see each other,
        // otherwise both clear the gap against the same stale timestamp and land together.
        lastSentAtMs.merge(gapKey, nowMs + preSendMs + typingMs, Math::max);

        log.info("TG TIMING: chat={} persona#{} preSendMs={} typingMs={} gapAppliedMs={}",
                chatId, hints.personaIndex(), preSendMs, typingMs, gapAppliedMs);

        Mono<Void> delay = preSendMs > 0 ? Mono.delay(Duration.ofMillis(preSendMs)).then() : Mono.empty();
        return delay.then(runTypingLoop(botId, chatId, typingMs));
    }

    /** Records a successful send so the NEXT pace() call for this (bot,chat) can enforce the gap. */
    public void markSent(String botId, long chatId) {
        long now = clock.millis();
        lastSentAtMs.merge(key(botId, chatId), now, Math::max);
        // The map is keyed by persona|chat and never needs history: drop stale entries once in
        // a while so a long-lived process does not keep every chat it ever spoke in.
        if (lastSentAtMs.size() > SWEEP_THRESHOLD) {
            long cutoff = now - SWEEP_MAX_AGE_MS;
            lastSentAtMs.entrySet().removeIf(e -> e.getValue() < cutoff);
        }
    }

    private static String key(String botId, long chatId) {
        return botId + "|" + chatId;
    }

    /**
     * Pure pre-send delay formula (no per-chat state): {@code max(decidedMs, randomPart + readTime) + stagger},
     * capped at {@code reply_timing.max_pre_send_ms}. The stagger term is ALWAYS applied, even when the
     * random-delay window is off — a real change from the legacy consumer logic, which dropped it in that case.
     * {@code hints.queued()} skips readTime/randomPart entirely: a pending-queue send already waited out its
     * own eligibility window.
     *
     * <p>{@code nowMs} is accepted for symmetry with the gap-aware pipeline in {@link #pace}, which is the
     * only caller holding a botId+chatId key to look the per-chat gap up — this method itself never touches
     * {@link #lastSentAtMs}, so it stays a pure function of its knobs and arguments.
     */
    long computePreSendDelayMs(PacingHints hints, long nowMs) {
        long decidedMs = hints.decidedDelaySeconds() != null && hints.decidedDelaySeconds() > 0
                ? hints.decidedDelaySeconds() * 1000L
                : 0L;
        long stagger = (long) hints.personaIndex() * Math.max(0L, staggerStepMs());

        long randomAndReadMs = 0L;
        if (!hints.queued()) {
            long randomMax = randomDelayMaxMs();
            long randomPart = 0L;
            if (randomMax > 0) {
                long min = Math.max(0L, randomDelayMinMs());
                long max = Math.max(min + 1L, randomMax);
                randomPart = random.nextLong(min, max);
            }
            long readTime = Math.min((long) Math.max(0, hints.triggerTextLength()) * readMsPerChar(), (long) readCapMs());
            randomAndReadMs = randomPart + readTime;
        }

        long base = Math.max(decidedMs, randomAndReadMs) + stagger;
        return Math.min(base, Math.max(0L, maxPreSendMs()));
    }

    /**
     * Raises {@code baseDelayMs} (never lowers it) so the send lands no earlier than
     * {@code lastSentAt + min_gap + jitter(0..20% of min_gap)} for this (bot,chat) key.
     * Reads {@link #lastSentAtMs}, so unlike {@link #computePreSendDelayMs} this is a
     * stateful step — it is the one place same-second duplicate sends are ruled out.
     */
    long applyMinimumGap(String key, long baseDelayMs, long nowMs) {
        long minGap = Math.max(0L, minGapSameChatMs());
        if (minGap <= 0 || key == null) {
            return baseDelayMs;
        }
        Long lastSent = lastSentAtMs.get(key);
        if (lastSent == null) {
            return baseDelayMs;
        }
        long earliestAllowed = lastSent + minGap;
        long plannedSendAt = nowMs + baseDelayMs;
        if (plannedSendAt >= earliestAllowed) {
            return baseDelayMs;
        }
        long jitter = (long) (random.nextDouble() * minGap * 0.20);
        return (earliestAllowed + jitter) - nowMs;
    }

    /** Typing-indicator window: {@code min(max(1,len) * ms_per_char, cap_ms)}. */
    int computeTypingWindowMs(String text) {
        int length = text == null ? 0 : text.length();
        long windowMs = Math.min((long) Math.max(1, length) * typingMsPerChar(), (long) typingCapMs());
        return (int) windowMs;
    }

    /**
     * Shows the typing indicator immediately, repeating every {@link #TYPING_REFRESH_INTERVAL}
     * while the window lasts (Telegram's typing status expires after ~5s), then completes once
     * the full window has elapsed.
     *
     * <p>The refresh loop runs as a detached subscription rather than being joined into the
     * returned Mono: joining it (e.g. via {@code Mono.when}) would make completion wait for the
     * interval's own {@code takeWhile} to observe and reject its first out-of-window tick, which
     * lands up to one refresh interval late. Pacing must complete at exactly {@code typingWindowMs},
     * so that single trailing delay is the only thing the caller awaits; the loop self-terminates
     * within one refresh interval regardless.
     */
    private Mono<Void> runTypingLoop(String botId, long chatId, int typingWindowMs) {
        if (typingWindowMs <= 0) {
            return Mono.empty();
        }
        long refreshMs = TYPING_REFRESH_INTERVAL.toMillis();
        // The refresh ticks run beside the window delay and die with it: a cancelled or timed-out
        // send must not keep showing "typing…" for a message that will never arrive.
        return Mono.using(
                () -> Flux.interval(Duration.ZERO, TYPING_REFRESH_INTERVAL)
                        .takeWhile(tick -> tick * refreshMs < typingWindowMs)
                        .concatMap(tick -> sendTypingAction(botId, chatId))
                        .subscribe(v -> { }, e -> log.debug("HumanSendPacer: typing loop error for chat={}: {}", chatId, e.getMessage())),
                (Disposable ticks) -> Mono.delay(Duration.ofMillis(typingWindowMs)).then(),
                Disposable::dispose);
    }

    private Mono<Void> sendTypingAction(String botId, long chatId) {
        return Mono.<Void>create(sink -> {
            TelegramClientFacade client = telegramClientManager.getClient(botId);
            if (client == null) {
                log.warn("HumanSendPacer: no telegram client for botId={} — skipping typing indicator", botId);
                sink.success();
                return;
            }
            try {
                TdApi.SendChatAction chatAction = new TdApi.SendChatAction(chatId, 0, new TdApi.ChatActionTyping());
                client.send(chatAction, result -> {
                    if (result.isError()) {
                        log.debug("HumanSendPacer: typing indicator failed for chat={}: {}", chatId, result.getError());
                    }
                    sink.success();
                });
            } catch (Exception e) {
                log.debug("HumanSendPacer: typing indicator threw for chat={}: {}", chatId, e.getMessage());
                sink.success();
            }
        });
    }
}
