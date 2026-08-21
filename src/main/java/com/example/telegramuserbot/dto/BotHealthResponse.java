package com.example.telegramuserbot.dto;

import java.util.List;

/**
 * Aggregate health snapshot for all configured bot instances.
 *
 * @param status           overall state: {@code "UP"} (all bots initialized),
 *                         {@code "DEGRADED"} (some initialized) or
 *                         {@code "DOWN"} (none initialized)
 * @param configuredCount  number of bots declared in {@code bot.persona-ids}
 * @param initializedCount number of bots with an initialized TDLib client
 * @param pendingSecondary whether secondary clients are still initializing
 * @param bots             per-bot status, one entry per configured bot
 */
public record BotHealthResponse(
        String status,
        int configuredCount,
        int initializedCount,
        boolean pendingSecondary,
        List<BotStatus> bots
) {
}
