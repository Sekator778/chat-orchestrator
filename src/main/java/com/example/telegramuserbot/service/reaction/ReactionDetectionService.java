package com.example.telegramuserbot.service.reaction;

import reactor.core.publisher.Mono;

/**
 * Service for detecting incoming messages and queuing reactions for eligible personas.
 */
public interface ReactionDetectionService {

    /**
     * Called when a new message arrives in a monitored channel.
     * Checks all eligible persona configs for the channel and schedules reactions.
     *
     * @param chatId    the Telegram chat/channel ID
     * @param messageId the Telegram message ID
     * @return mono of the count of reactions queued
     */
    Mono<Integer> onNewMessage(long chatId, long messageId);

    /**
     * Called when a new message arrives for a specific persona's TDLib client.
     * Only schedules a reaction for the given persona using its own message ID.
     *
     * @param chatId    the Telegram chat/channel ID
     * @param messageId the message ID from this persona's TDLib session
     * @param personaId the persona identifier
     * @return mono of the count of reactions queued (0 or 1)
     */
    Mono<Integer> onNewMessageForPersona(long chatId, long messageId, String personaId);
}
