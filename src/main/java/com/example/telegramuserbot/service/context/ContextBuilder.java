package com.example.telegramuserbot.service.context;

import com.example.telegramuserbot.domain.ContextSettings;
import reactor.core.publisher.Mono;

public interface ContextBuilder {
    Mono<ContextWindow> build(long chatId, long triggeringMessageId, ContextSettings settings);

    /**
     * Variant that carries the responding persona's bot ID. For private chats
     * ({@code chatId > 0}) the implementation MUST filter history to rows where
     * {@code received_by_bot_id = botId} so each persona sees only its own DM thread.
     * For group chats ({@code chatId < 0}) the {@code botId} is ignored and behaviour
     * is identical to {@link #build(long, long, ContextSettings)}.
     *
     * @param botId the persona's client instance ID (may be {@code null}; falls back
     *              to unfiltered behaviour)
     */
    default Mono<ContextWindow> buildForBot(long chatId, long triggeringMessageId, ContextSettings settings, String botId) {
        return build(chatId, triggeringMessageId, settings);
    }
}
