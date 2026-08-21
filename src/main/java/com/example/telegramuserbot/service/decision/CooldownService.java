package com.example.telegramuserbot.service.decision;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Negativity silence with deflect-then-silence semantics (owner decision):
 * the FIRST bot-accusation in a window is laughed off (the persona keeps
 * replying naturally); a REPEAT within the strike window silences the chat
 * for a cooldown period. Explicit in-memory state with real TTLs — replaces
 * the previous @Cacheable null-trick whose TTL was whatever the global
 * Caffeine spec happened to be.
 */
@Service
public class CooldownService {

    private static final Logger log = LoggerFactory.getLogger(CooldownService.class);

    private final Map<Long, Instant> silencedUntil = new ConcurrentHashMap<>();
    private final Map<Long, Instant> lastStrikeAt = new ConcurrentHashMap<>();
    private final Clock clock;

    @Value("${bot.negativity-cooldown.silence-minutes:30}")
    private int silenceMinutes;
    @Value("${bot.negativity-cooldown.strike-window-minutes:60}")
    private int strikeWindowMinutes;

    public CooldownService() {
        this(Clock.systemUTC());
    }

    CooldownService(Clock clock) {
        this.clock = clock;
    }

    public boolean isSilenced(long chatId) {
        Instant until = silencedUntil.get(chatId);
        if (until == null) {
            return false;
        }
        if (clock.instant().isAfter(until)) {
            silencedUntil.remove(chatId);
            return false;
        }
        return true;
    }

    /**
     * Registers a bot-accusation strike.
     *
     * @return true when this is a REPEAT within the strike window — the chat
     *         has just been silenced; false for a first strike (deflect, keep talking)
     */
    public boolean registerStrike(long chatId) {
        Instant now = clock.instant();
        Instant previous = lastStrikeAt.put(chatId, now);
        boolean repeat = previous != null
                && previous.isAfter(now.minus(Duration.ofMinutes(strikeWindowMinutes)));
        if (repeat) {
            silenceNow(chatId);
        }
        return repeat;
    }

    /** Immediate silence (e.g. open aggression toward the persona). */
    public void silenceNow(long chatId) {
        silencedUntil.put(chatId, clock.instant().plus(Duration.ofMinutes(silenceMinutes)));
        log.warn("Chat {} silenced for {} min after negativity", chatId, silenceMinutes);
    }
}
