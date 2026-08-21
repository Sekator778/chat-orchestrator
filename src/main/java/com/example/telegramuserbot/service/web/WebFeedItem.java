package com.example.telegramuserbot.service.web;

import java.time.Instant;

/**
 * Lightweight DTO representing a single item parsed from an RSS/Atom feed.
 * Produced by {@link WebFeedClient#fetch(String)} and consumed by the web-harvest
 * pipeline (T3).
 *
 * @param title       item headline; may be {@code null} if the feed omits it
 * @param link        canonical URL of the article; may be {@code null}
 * @param summary     description or content excerpt; may be {@code null}
 * @param publishedAt publication timestamp; falls back to {@link Instant#now()} when
 *                    the feed omits both {@code pubDate} and {@code updated}
 */
public record WebFeedItem(
        String title,
        String link,
        String summary,
        Instant publishedAt
) {
}
