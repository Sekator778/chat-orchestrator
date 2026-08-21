package com.example.telegramuserbot.dto;

import java.time.Instant;

/**
 * Projection record for a single channel's activity within a time window.
 *
 * <ul>
 *   <li>{@code chatId} — numeric Telegram channel identifier (matches {@code tgscan.channels.id})</li>
 *   <li>{@code channelTitle} — human-readable title from {@code tgscan.channels.title}; null when not set</li>
 *   <li>{@code messageCount} — number of messages in the requested window (0 for silent channels)</li>
 *   <li>{@code lastActivityAt} — UTC timestamp of the most recent message in the window;
 *       null when {@code messageCount == 0}</li>
 * </ul>
 */
public record ChannelActivityEntry(
        long chatId,
        String channelTitle,
        long messageCount,
        Instant lastActivityAt
) {
}
