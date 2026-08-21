package com.example.telegramuserbot.dto;

/**
 * Per-channel activity + engagement signal for the collector observability report.
 *
 * <ul>
 *   <li>{@code chatId}              — numeric Telegram channel id (tgscan.channels.id)</li>
 *   <li>{@code channelTitle}        — display title; null when not yet populated</li>
 *   <li>{@code subscribers}         — known subscriber count; null when not yet enriched</li>
 *   <li>{@code messageCount}        — posts in the requested window (0 = silent)</li>
 *   <li>{@code postFrequencyPerDay} — {@code messageCount / days}; 0 for silent channels</li>
 *   <li>{@code avgViews}            — average message.views in the window; null when no views data</li>
 *   <li>{@code engagementPerSub}    — {@code avgViews / subscribers}; null when subscribers is null
 *       or zero; a ratio useful for spotting high-reach channels with low real audience</li>
 * </ul>
 */
public record ChannelEngagementEntry(
        long chatId,
        String channelTitle,
        Long subscribers,
        long messageCount,
        double postFrequencyPerDay,
        Double avgViews,
        Double engagementPerSub
) {
}
