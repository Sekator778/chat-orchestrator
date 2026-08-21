package com.example.telegramuserbot.service.ratelimit;

import com.example.telegramuserbot.domain.ChatConfig;
import com.example.telegramuserbot.domain.RateLimits;
import com.example.telegramuserbot.repository.RateLimitsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single gate for response rate limiting.
 *
 * Chat-scoped (chatId), with in-memory "blockedUntil" caching to avoid repeated DB work.
 */
@Service
public class ResponseRateLimitGate {

    private static final Logger log = LoggerFactory.getLogger(ResponseRateLimitGate.class);

    private final RateLimitsRepository rateLimitsRepository;
    private final ZoneId resetZoneId;

    private final ConcurrentHashMap<Long, BlockedState> blockedUntilCache = new ConcurrentHashMap<>();

    @Value("${rate-limits.daily.block-cache.max-minutes:1440}")
    private long maxBlockCacheMinutes;

    public ResponseRateLimitGate(
            RateLimitsRepository rateLimitsRepository,
            @Value("${rate-limits.daily-reset.zone:Europe/Berlin}") String zoneId
    ) {
        this.rateLimitsRepository = rateLimitsRepository;
        this.resetZoneId = ZoneId.of(zoneId);
    }

    public boolean isChatFullyBlocked(long chatId) {
        Instant now = Instant.now();
        BlockedState state = blockedUntilCache.get(chatId);
        return state != null && state.blockedUntil().isAfter(now);
    }

    public void registerKnownBots(long chatId, List<String> botInstanceIds) {
        // No-op: rate limit is chat-scoped (shared across bot personas).
    }

    /**
     * Attempts to reserve permission to generate a response (increments DB counter when applicable).
     * If limit is reached, caches "blockedUntil" to short-circuit future attempts without DB calls.
     */
    public Mono<GateDecision> tryAcquire(ChatConfig cfg, RateLimits limits) {
        if (cfg == null) {
            return Mono.just(GateDecision.allowed("no config"));
        }

        Long chatId = cfg.getChannelId();
        Long configId = cfg.getId();
        Integer maxDaily = limits != null ? limits.getMaxMessagesPerDay() : null;

        if (maxDaily == null || maxDaily <= 0) {
            return Mono.just(GateDecision.allowed("no daily limit"));
        }
        if (configId == null) {
            log.warn("RateLimitGate: missing chatConfigId for chatId={} (allowing)", chatId);
            return Mono.just(GateDecision.allowed("missing chatConfigId"));
        }

        Instant now = Instant.now();
        long key = chatId != null ? chatId : 0L;

        BlockedState cached = blockedUntilCache.get(key);
        if (cached != null && cached.blockedUntil().isAfter(now)) {
            return Mono.just(GateDecision.denied(cached.blockedUntil(), "daily limit reached (cached)", false));
        }

        return rateLimitsRepository.incrementDailyIfAllowed(configId)
                .map(updated -> {
                    if (updated != null && updated > 0) {
                        return GateDecision.allowed("incremented");
                    }

                    Instant blockedUntil = computeNextResetAt(now);
                    blockedUntil = capBlockedUntil(now, blockedUntil);

                    BlockedState state = new BlockedState(blockedUntil, now, maxDaily);
                    blockedUntilCache.put(key, state);
                    log.warn(
                            "RateLimitGate: BLOCKED chatId={} chatConfigId={} maxDaily={} blockedUntil={} zone={}",
                            chatId, configId, maxDaily, blockedUntil, resetZoneId
                    );
                    return GateDecision.denied(blockedUntil, "daily limit reached", true);
                })
                .onErrorResume(e -> {
                    log.error(
                            "RateLimitGate: error while acquiring chatId={} chatConfigId={}: {} (fail-open)",
                            chatId, configId, e.getMessage(), e
                    );
                    return Mono.just(GateDecision.allowed("db error (fail-open)"));
                });
    }

    public void clearAllCaches() {
        int blocked = blockedUntilCache.size();
        blockedUntilCache.clear();
        log.info("RateLimitGate: caches cleared (blockedEntries={})", blocked);
    }

    public void invalidateChat(long chatId) {
        blockedUntilCache.remove(chatId);
        log.info("RateLimitGate: invalidated chat caches chatId={}", chatId);
    }

    private Instant computeNextResetAt(Instant now) {
        ZonedDateTime zonedNow = ZonedDateTime.ofInstant(now, resetZoneId);
        ZonedDateTime next = zonedNow.withHour(2).withMinute(0).withSecond(0).withNano(0);
        if (!next.isAfter(zonedNow)) {
            next = next.plusDays(1);
        }
        return next.toInstant();
    }

    private Instant capBlockedUntil(Instant now, Instant blockedUntil) {
        if (maxBlockCacheMinutes <= 0) {
            return blockedUntil;
        }
        Instant cap = now.plus(Duration.ofMinutes(maxBlockCacheMinutes));
        return blockedUntil.isAfter(cap) ? cap : blockedUntil;
    }

    private record BlockedState(Instant blockedUntil, Instant createdAt, int maxDaily) {
        private BlockedState {
            Objects.requireNonNull(blockedUntil, "blockedUntil");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    public record GateDecision(boolean allowed, Instant blockedUntil, String reason, boolean newlyBlocked) {
        public static GateDecision allowed(String reason) {
            return new GateDecision(true, null, reason, false);
        }

        public static GateDecision denied(Instant blockedUntil, String reason, boolean newlyBlocked) {
            return new GateDecision(false, blockedUntil, reason, newlyBlocked);
        }
    }
}
