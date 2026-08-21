package com.example.telegramuserbot.dto;

/**
 * Health status of a single configured bot instance.
 *
 * @param botId       the bot instance identifier (a {@code bot.persona-ids} entry)
 * @param primary     whether this is the primary bot instance
 * @param initialized whether a TDLib client is initialized (authorized and ready)
 * @param status      {@code "UP"} when initialized, otherwise {@code "DOWN"}
 */
public record BotStatus(
        String botId,
        boolean primary,
        boolean initialized,
        String status
) {
}
