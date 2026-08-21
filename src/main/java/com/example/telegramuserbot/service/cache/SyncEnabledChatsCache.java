package com.example.telegramuserbot.service.cache;

import com.example.telegramuserbot.domain.ChatConfig;
import com.example.telegramuserbot.repository.ChatConfigRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.example.telegramuserbot.service.ProblematicChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Optional;


/**
 * Enterprise-grade cache for chat configuration flags (enabled, sync_enabled).
 *
 * Reduces database load by caching ChatConfig lookups with a 1-hour TTL.
 * Provides reactive API compatible with Spring WebFlux.
 *
 * Cache Strategy:
 * - Positive caching: Stores found ChatConfigs
 * - Negative caching: Stores absence of config (Optional.empty) to avoid repeated DB queries
 * - Auto-refresh: Invalidates entries after 1 hour
 * - Manual invalidation: Provides methods to clear cache when configs change
 *
 * Primary use case: Fast enabled/sync_enabled checks at message ingestion
 */
@Service
public final class SyncEnabledChatsCache {

    private static final Logger log = LoggerFactory.getLogger(SyncEnabledChatsCache.class);
    private static final Duration CACHE_TTL = Duration.ofHours(1);
    private static final int MAXIMUM_CACHE_SIZE = 1000;

    private final Cache<Long, Optional<ChatConfig>> cache;
    private final ChatConfigRepository repository;
    private final ProblematicChatService problematicChatService;

    public SyncEnabledChatsCache(ChatConfigRepository repository,
                                 ProblematicChatService problematicChatService) {
        this.repository = repository;
        this.problematicChatService = problematicChatService;
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(CACHE_TTL)
                .maximumSize(MAXIMUM_CACHE_SIZE)
                .recordStats()
                .build();

        log.info("SyncEnabledChatsCache initialized with TTL={}, maxSize={}", CACHE_TTL, MAXIMUM_CACHE_SIZE);
    }

    /**
     * Retrieves ChatConfig for a channel, using cache if available.
     *
     * @param chatId Telegram chat ID (original TDLib format)
     * @return Mono of ChatConfig if sync is enabled, empty Mono if sync disabled or config not found
     */
    public Mono<ChatConfig> find(Long chatId) {
        // Using original TDLib chat ID directly - no normalization needed
        // Try cache first
        Optional<ChatConfig> cached = cache.getIfPresent(chatId);

        if (cached != null) {
            log.debug("Cache HIT for chat {}: {}", chatId, cached.isPresent() ? "config found" : "no config");
            return cached.map(Mono::just).orElse(Mono.empty());
        }

        log.trace("Cache MISS for chat {}, querying database", chatId);

        return problematicChatService.shouldProcess(chatId)
                .flatMap(shouldProcess -> {
                    if (!shouldProcess) {
                        cache.put(chatId, Optional.empty());
                        log.debug("Chat {} marked problematic. Skipping cache population (sync-enabled check).", chatId);
                        return Mono.empty();
                    }
                    return queryAndCache(chatId);
                })
                .switchIfEmpty(Mono.fromRunnable(() -> {
                    cache.put(chatId, Optional.empty());
                    log.debug("Chat config not found for {}. Cached miss.", chatId);
                }).then(Mono.empty()));
    }

    /**
     * Checks if sync is enabled for a channel without returning the full config.
     * More efficient when you only need the boolean flag.
     *
     * @param chatId Telegram chat ID
     * @return Mono<Boolean> - true if sync enabled, false otherwise
     */
    public Mono<Boolean> syncEnabled(Long chatId) {
        return findAny(chatId)
                .map(config -> {
                    boolean discussionLinked = config.getPrimaryChannelId() != null;
                    if (discussionLinked && !config.isSyncEnabled()) {
                        log.trace("Chat {} treated as sync-enabled because it is a discussion linked to {}", chatId, config.getPrimaryChannelId());
                    }
                    return config.isSyncEnabled() || discussionLinked;
                })
                .defaultIfEmpty(false);
    }

    /**
     * Checks if chat is enabled for bot responses (chat_configs.enabled flag).
     * This is the PRIMARY gate for message processing - if false, skip all processing.
     *
     * @param chatId Telegram chat ID
     * @return Mono<Boolean> - true if chat is enabled for bot responses, false otherwise
     */
    public Mono<Boolean> isEnabled(Long chatId) {
        return findAny(chatId)
                .map(ChatConfig::isEnabled)
                .defaultIfEmpty(false);
    }

    /**
     * Retrieves ChatConfig for any channel (regardless of sync_enabled flag).
     * Used for enabled check.
     *
     * @param chatId Telegram chat ID
     * @return Mono of ChatConfig if found, empty Mono otherwise
     */
    private Mono<ChatConfig> findAny(Long chatId) {
        // Try cache first
        Optional<ChatConfig> cached = cache.getIfPresent(chatId);

        if (cached != null) {
            log.trace("Cache HIT for chat {}: {}", chatId, cached.isPresent() ? "config found" : "no config");
            return cached.map(Mono::just).orElse(Mono.empty());
        }

        log.trace("Cache MISS for chat {}, querying database", chatId);

        return problematicChatService.shouldProcess(chatId)
                .flatMap(shouldProcess -> {
                    if (!shouldProcess) {
                        cache.put(chatId, Optional.empty());
                        log.debug("Chat {} marked problematic. Skipping cache population.", chatId);
                        return Mono.empty();
                    }
                    return repository.findByChannelChatId(chatId)
                            .doOnNext(config -> cache.put(chatId, Optional.of(config)))
                            .switchIfEmpty(Mono.fromRunnable(() -> {
                                cache.put(chatId, Optional.empty());
                                log.trace("Chat config not found for {}. Cached miss.", chatId);
                            }));
                });
    }

    /**
     * Exposes raw chat configuration for cases where we need to examine additional flags.
     *
     * @param chatId Telegram chat ID
     * @return Mono with ChatConfig if present
     */
    public Mono<ChatConfig> getConfig(Long chatId) {
        return findAny(chatId);
    }

    /**
     * Invalidates cache entry for a specific chat.
     * Call this when ChatConfig is created, updated, or deleted.
     *
     * @param chatId Telegram chat ID (original TDLib format)
     */
    public void invalidate(Long chatId) {
        // Using original TDLib chat ID directly
        cache.invalidate(chatId);
        log.trace("Invalidated cache for chat {}", chatId);
    }

    /**
     * Clears entire cache.
     * Use sparingly, primarily for administrative operations or testing.
     */
    public void invalidateAll() {
        cache.invalidateAll();
        log.info("Entire SyncEnabledChatsCache cleared");
    }

    /**
     * Pre-loads cache with sync-enabled chats.
     * Useful during application startup to warm the cache.
     *
     * @return Mono<Long> count of preloaded entries
     */
    public Mono<Long> preload() {
        log.info("Preloading sync-enabled chats cache...");

        return problematicChatService.listProblematicChatIds()
                .defaultIfEmpty(java.util.Set.of())
                .flatMapMany(blocked -> repository.findAllForInstance()
                        .filter(ChatConfig::isSyncEnabled)
                        .filter(cfg -> !blocked.contains(cfg.getChannelId()))
                        .doOnNext(config -> cache.put(config.getChannelId(), Optional.of(config))))
                .count()
                .doOnSuccess(count -> log.info("Preloaded {} sync-enabled chat configs into cache (problematic chats excluded)", count))
                .doOnError(error -> log.error("Failed to preload cache", error));
    }

    private Mono<ChatConfig> queryAndCache(Long chatId) {
        return repository.findByChannelChatId(chatId)
                .doOnNext(config -> {
                    Optional<ChatConfig> value = config.isSyncEnabled()
                            ? Optional.of(config)
                            : Optional.empty();
                    cache.put(chatId, value);
                });
    }

    /**
     * Returns cache statistics for monitoring and debugging.
     *
     * @return CacheStats record with hit/miss metrics
     */
    public CacheStats stats() {
        var stats = cache.stats();
        return new CacheStats(
                cache.estimatedSize(),
                stats.hitCount(),
                stats.missCount(),
                stats.hitRate(),
                stats.evictionCount()
        );
    }

    /**
     * Cache statistics for monitoring.
     *
     * @param size Current number of entries
     * @param hits Number of cache hits
     * @param misses Number of cache misses
     * @param hitRate Hit rate percentage (0.0 to 1.0)
     * @param evictions Number of evicted entries
     */
    public record CacheStats(
            long size,
            long hits,
            long misses,
            double hitRate,
            long evictions
    ) {}
}
