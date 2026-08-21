package com.example.telegramuserbot.service.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Warms up the sync-enabled chats cache on application startup.
 *
 * Preloading the cache reduces initial database load and improves
 * startup synchronization performance.
 */
@Component
public final class SyncCacheWarmer {

    private static final Logger log = LoggerFactory.getLogger(SyncCacheWarmer.class);

    private final SyncEnabledChatsCache cache;

    public SyncCacheWarmer(SyncEnabledChatsCache cache) {
        this.cache = cache;
    }

    /**
     * Preloads sync-enabled chats into cache after application is ready.
     * Runs asynchronously to not block application startup.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warmCache() {
        log.info("Starting sync-enabled chats cache preload...");

        cache.preload()
                .subscribe(
                        count -> log.info("Successfully preloaded {} sync-enabled chats into cache", count),
                        error -> log.error("Failed to preload sync-enabled chats cache", error)
                );
    }
}
