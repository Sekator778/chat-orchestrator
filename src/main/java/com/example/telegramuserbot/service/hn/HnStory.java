package com.example.telegramuserbot.service.hn;

import java.time.Instant;

/**
 * Lightweight DTO for a single Hacker News story, parsed from the Firebase API response.
 *
 * <p>Only the fields used by {@link HnNewsCollectorService} are captured here:
 * <ul>
 *   <li>{@code id} — stable HN item id; used directly as the synthetic {@code message_id}.</li>
 *   <li>{@code title} — the story headline; primary content.</li>
 *   <li>{@code url} — optional external URL (null for "Ask HN" / "Show HN" posts without links).</li>
 *   <li>{@code score} — upvotes; mapped to {@code views} in {@code bot.messages}.</li>
 *   <li>{@code descendants} — comment count; mapped to {@code forwards} in {@code bot.messages}.</li>
 *   <li>{@code time} — Unix epoch seconds; converted to {@link Instant} for the {@code date} field.</li>
 *   <li>{@code type} — HN item type; only {@code "story"} is harvested.</li>
 * </ul>
 */
public record HnStory(
        long    id,
        String  title,
        String  url,
        int     score,
        int     descendants,
        Instant time,
        String  type
) {}
