package com.example.telegramuserbot.service.telegram;

import com.example.telegramuserbot.config.BotInstanceProvider;
import com.example.telegramuserbot.service.TelegramClientManager;
import com.example.telegramuserbot.telegram.TelegramClientFacade;
import com.example.telegramuserbot.telegram.TelegramClientLifecycleListener;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public final class TelegramSelfUserIdResolver implements TelegramClientLifecycleListener {

    private static final Logger log = LoggerFactory.getLogger(TelegramSelfUserIdResolver.class);

    private final TelegramClientManager telegramClientManager;
    private final BotInstanceProvider botInstanceProvider;
    // Single cache of the raw GetMe result per botId; resolveSelfUserId/Username/FirstName
    // all derive from this one cached Mono<TdApi.User> instead of each issuing its own
    // TDLib round trip (id, username and first name used to be three separate calls).
    private final Map<String, Mono<TdApi.User>> cache = new ConcurrentHashMap<>();

    public TelegramSelfUserIdResolver(TelegramClientManager telegramClientManager,
                                     BotInstanceProvider botInstanceProvider) {
        this.telegramClientManager = telegramClientManager;
        this.botInstanceProvider = botInstanceProvider;
    }

    public Mono<Long> resolveSelfUserId(String botInstanceId) {
        return resolveSelfUser(botInstanceId).map(user -> user.id);
    }

    /** Self @username (active username first, editable username as fallback); empty when the account has none. */
    public Mono<String> resolveSelfUsername(String botInstanceId) {
        return resolveSelfUser(botInstanceId)
                .mapNotNull(this::extractUsername)
                .filter(username -> !username.isBlank());
    }

    /** Self first name as set on the Telegram account; empty when blank. */
    public Mono<String> resolveSelfFirstName(String botInstanceId) {
        return resolveSelfUser(botInstanceId)
                .mapNotNull(user -> user.firstName)
                .filter(firstName -> !firstName.isBlank());
    }

    private String extractUsername(TdApi.User user) {
        if (user.usernames == null) {
            return null;
        }
        if (user.usernames.activeUsernames != null && user.usernames.activeUsernames.length > 0) {
            return user.usernames.activeUsernames[0];
        }
        return user.usernames.editableUsername;
    }

    private Mono<TdApi.User> resolveSelfUser(String botInstanceId) {
        String resolvedBotId = normalizeBotInstanceId(botInstanceId);
        if (resolvedBotId == null || resolvedBotId.isBlank()) {
            return Mono.empty();
        }
        return cache.computeIfAbsent(resolvedBotId, this::fetchSelfUserCached);
    }

    /**
     * Clears cached entry for a specific bot instance.
     * Called after secondary clients are initialized to clear any failed lookups.
     */
    public void clearCacheForClient(String botInstanceId) {
        cache.remove(botInstanceId);
        log.debug("SelfUserIdResolver: cleared cache for botInstanceId={}", botInstanceId);
    }

    @Override
    public void onClientReady(String botId, TelegramClientFacade client) {
        log.info("SelfUserIdResolver: client ready for botId={}, clearing stale cache", botId);
        clearCacheForClient(botId);
    }

    private Mono<TdApi.User> fetchSelfUserCached(String botInstanceId) {
        return fetchSelfUser(botInstanceId).cache();
    }

    private Mono<TdApi.User> fetchSelfUser(String botInstanceId) {
        TelegramClientFacade client = telegramClientManager.getClient(botInstanceId);
        if (client == null) {
            log.warn("Cannot resolve self Telegram user id: no Telegram client for botInstanceId={}", botInstanceId);
            return Mono.empty();
        }

        return Mono.fromFuture(() -> client.send(new TdApi.GetMe()))
                .cast(TdApi.User.class)
                .doOnNext(user -> log.info("Resolved self Telegram user id for botInstanceId={}: {}", botInstanceId, user.id))
                .onErrorResume(error -> {
                    log.warn("Failed to resolve self Telegram user id for botInstanceId={}: {}",
                            botInstanceId, error.getMessage());
                    return Mono.empty();
                });
    }

    private String normalizeBotInstanceId(String raw) {
        if (raw == null || raw.isBlank() || "default-bot".equalsIgnoreCase(raw)) {
            return botInstanceProvider.getInstanceId();
        }
        return raw;
    }
}

