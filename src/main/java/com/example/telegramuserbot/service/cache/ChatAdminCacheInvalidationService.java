package com.example.telegramuserbot.service.cache;

import com.example.telegramuserbot.service.orchestration.BotContextResolver;
import com.example.telegramuserbot.service.ratelimit.ResponseRateLimitGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Central place for invalidating in-memory caches that depend on chat configuration.
 *
 * Keep this narrow and safe: invalidate only per-chat entries to avoid global cache thrash.
 */
@Service
public final class ChatAdminCacheInvalidationService {

    private static final Logger uiLog = LoggerFactory.getLogger("frontend.ui");

    private final BotContextResolver botContextResolver;
    private final SyncEnabledChatsCache syncEnabledChatsCache;
    private final ResponseRateLimitGate responseRateLimitGate;

    public ChatAdminCacheInvalidationService(
            BotContextResolver botContextResolver,
            SyncEnabledChatsCache syncEnabledChatsCache,
            ResponseRateLimitGate responseRateLimitGate
    ) {
        this.botContextResolver = botContextResolver;
        this.syncEnabledChatsCache = syncEnabledChatsCache;
        this.responseRateLimitGate = responseRateLimitGate;
    }

    public void invalidateChat(long chatId, String reason) {
        botContextResolver.invalidate(chatId);
        syncEnabledChatsCache.invalidate(chatId);
        responseRateLimitGate.invalidateChat(chatId);
        uiLog.info("UI:invalidateChatCaches chatId={} reason={}", chatId, reason);
    }

    public void invalidateBotContext(long chatId, String reason) {
        botContextResolver.invalidate(chatId);
        uiLog.info("UI:invalidateBotContextCache chatId={} reason={}", chatId, reason);
    }
}
