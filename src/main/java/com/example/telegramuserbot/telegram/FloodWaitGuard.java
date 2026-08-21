package com.example.telegramuserbot.telegram;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per-account flood-wait state. When Telegram answers a request with a
 * FLOOD_WAIT (error code 420/429), the guard parks the account for the
 * retry-after window so the next outbound sends short-circuit instead of
 * piling on more rejected requests. The window logic and seconds-parsing live
 * here (unit-tested); {@link FloodWaitTelegramClientFacade} is the thin wiring.
 */
public final class FloodWaitGuard {

    private static final Logger log = LoggerFactory.getLogger(FloodWaitGuard.class);
    private static final Pattern TRAILING_SECONDS = Pattern.compile("(\\d+)\\s*$");
    private static final int DEFAULT_BACKOFF_SECONDS = 30;
    private static final int MAX_BACKOFF_SECONDS = 3600;

    private final String botId;
    private final Clock clock;
    private volatile Instant backoffUntil = Instant.EPOCH;

    public FloodWaitGuard(String botId) {
        this(botId, Clock.systemUTC());
    }

    FloodWaitGuard(String botId, Clock clock) {
        this.botId = botId;
        this.clock = clock;
    }

    public boolean isBackingOff() {
        return clock.instant().isBefore(backoffUntil);
    }

    public static boolean isFloodWait(int code, String message) {
        return code == 420 || code == 429
                || (message != null && message.toUpperCase().contains("FLOOD_WAIT"));
    }

    /** Parks the account for the retry-after window derived from the error. */
    public void recordFloodWait(int code, String message) {
        int seconds = Math.min(MAX_BACKOFF_SECONDS, parseRetrySeconds(message));
        backoffUntil = clock.instant().plus(Duration.ofSeconds(seconds));
        log.warn("FLOOD_WAIT for botId={} (code={}) — backing off {}s", botId, code, seconds);
    }

    /** Extracts the retry-after seconds from a TDLib error message; default when absent. */
    static int parseRetrySeconds(String message) {
        if (message == null || message.isBlank()) {
            return DEFAULT_BACKOFF_SECONDS;
        }
        Matcher matcher = TRAILING_SECONDS.matcher(message.trim());
        if (matcher.find()) {
            try {
                int value = Integer.parseInt(matcher.group(1));
                return value > 0 ? value : DEFAULT_BACKOFF_SECONDS;
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return DEFAULT_BACKOFF_SECONDS;
    }
}
