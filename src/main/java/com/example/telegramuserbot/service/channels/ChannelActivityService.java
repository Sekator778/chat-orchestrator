package com.example.telegramuserbot.service.channels;

import com.example.telegramuserbot.dto.ChannelActivityEntry;
import com.example.telegramuserbot.dto.ChannelEngagementEntry;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Instant;

/**
 * Provides reactive per-channel activity and engagement aggregates over a
 * configurable time window.
 *
 * <p>Activity reports: all channels LEFT JOIN message-count sub-query on
 * {@code bot.messages}.  Silent channels appear with {@code messageCount = 0}.
 *
 * <p>Engagement reports: joined-only channels (join_status = 'joined') with
 * post-frequency (posts/day) and engagement-per-subscriber (avg views / subscribers)
 * — computed from existing columns, no schema changes.
 *
 * <p>The query leverages {@code idx_messages_chat_date_activity}
 * (added by Liquibase changeset 054) for the window range-scan to stay well
 * within the NFR-001 500 ms p95 budget at 5 M rows / 500 channels.
 *
 * <p>No {@code .block()} call is made anywhere in this class (D1 — reactive stack).
 */
@Service
public class ChannelActivityService {

    /**
     * Cross-schema LEFT JOIN aggregate (all channels, activity window).
     *
     * <ul>
     *   <li>Anchored on {@code tgscan.channels} so silent channels are included (FR-006, D-7).</li>
     *   <li>Window: half-open {@code [NOW() - days * INTERVAL '1 day', NOW())} (FR-005).</li>
     *   <li>Ordering: {@code messageCount DESC, lastActivityAt DESC NULLS LAST} (FR-008).</li>
     *   <li>{@code :days} is an integer bound via R2DBC parameter binding — never
     *       string-concatenated into SQL (OWASP V5 / injection prevention).</li>
     * </ul>
     */
    private static final String ACTIVITY_SQL = """
            SELECT c.id                  AS chat_id,
                   c.title               AS channel_title,
                   COALESCE(agg.msg_count, 0)  AS message_count,
                   agg.last_date         AS last_activity_at
              FROM tgscan.channels c
              LEFT JOIN (
                   SELECT chat_id,
                          COUNT(*)   AS msg_count,
                          MAX(date)  AS last_date
                     FROM bot.messages
                    WHERE date >= NOW() - :days * INTERVAL '1 day'
                      AND date <  NOW()
                    GROUP BY chat_id
              ) agg ON agg.chat_id = c.id
             ORDER BY message_count DESC,
                      last_activity_at DESC NULLS LAST
            """;

    /**
     * Engagement aggregate — joined channels only.
     *
     * <p>Computes for each joined channel within the window:
     * <ul>
     *   <li>{@code message_count} — number of posts harvested</li>
     *   <li>{@code post_frequency_per_day} — message_count / :days</li>
     *   <li>{@code avg_views} — average of bot.messages.views (null when no views data)</li>
     *   <li>{@code engagement_per_sub} — avg_views / subscribers (null when subscribers is
     *       null or zero)</li>
     * </ul>
     * Silent channels (no messages in window) appear with message_count = 0 and
     * null avg_views / engagement_per_sub — they are candidates for leaving.
     */
    /**
     * Note: computed ratio columns are CAST to {@code double precision} so that
     * r2dbc-postgresql maps them to {@code Double} (its default for NUMERIC is
     * {@code BigDecimal}, which has no matching codec for {@code Double.class}).
     */
    private static final String ENGAGEMENT_SQL = """
            SELECT c.id                          AS chat_id,
                   c.title                       AS channel_title,
                   c.subscribers                 AS subscribers,
                   COALESCE(agg.msg_count, 0)    AS message_count,
                   (COALESCE(agg.msg_count, 0)::DOUBLE PRECISION / GREATEST(:days, 1)) AS post_frequency_per_day,
                   agg.avg_views                 AS avg_views,
                   CASE
                     WHEN c.subscribers IS NOT NULL AND c.subscribers > 0 AND agg.avg_views IS NOT NULL
                     THEN (agg.avg_views / c.subscribers::DOUBLE PRECISION)
                     ELSE NULL
                   END                           AS engagement_per_sub
              FROM tgscan.channels c
              LEFT JOIN (
                   SELECT chat_id,
                          COUNT(*)                          AS msg_count,
                          AVG(views::DOUBLE PRECISION)      AS avg_views
                     FROM bot.messages
                    WHERE date >= NOW() - :days * INTERVAL '1 day'
                      AND date <  NOW()
                    GROUP BY chat_id
              ) agg ON agg.chat_id = c.id
             WHERE c.join_status = 'joined'
             ORDER BY post_frequency_per_day DESC,
                      message_count DESC,
                      engagement_per_sub DESC NULLS LAST
            """;

    private final DatabaseClient databaseClient;

    public ChannelActivityService(DatabaseClient databaseClient) {
        this.databaseClient = databaseClient;
    }

    /**
     * Returns a {@link Flux} of {@link ChannelActivityEntry} records ranked by
     * activity (most active first, silent channels last).
     *
     * @param days lookback window in days; must be in {@code [1, 365]} — the caller
     *             (controller) is responsible for validating this constraint before
     *             invoking this method.
     * @return reactive stream of per-channel activity entries; never null, may be empty.
     */
    public Flux<ChannelActivityEntry> reportActivity(int days) {
        return databaseClient.sql(ACTIVITY_SQL)
                .bind("days", days)
                .map((row, metadata) -> new ChannelActivityEntry(
                        row.get("chat_id", Long.class),
                        row.get("channel_title", String.class),
                        toLong(row.get("message_count", Long.class)),
                        row.get("last_activity_at", Instant.class)
                ))
                .all();
    }

    /**
     * Returns a {@link Flux} of {@link ChannelEngagementEntry} for all <em>joined</em>
     * channels, ranked by post frequency descending (busiest first, silent last).
     * Includes engagement-per-subscriber where subscriber data is available.
     *
     * @param days lookback window in days; must be in {@code [1, 365]} — the caller
     *             is responsible for validating this constraint.
     * @return reactive stream of per-channel engagement entries; never null, may be empty.
     */
    public Flux<ChannelEngagementEntry> reportEngagement(int days) {
        return databaseClient.sql(ENGAGEMENT_SQL)
                .bind("days", days)
                .map((row, metadata) -> new ChannelEngagementEntry(
                        row.get("chat_id", Long.class),
                        row.get("channel_title", String.class),
                        row.get("subscribers", Long.class),
                        toLong(row.get("message_count", Long.class)),
                        toDouble(row.get("post_frequency_per_day", Double.class)),
                        row.get("avg_views", Double.class),
                        row.get("engagement_per_sub", Double.class)
                ))
                .all();
    }

    /**
     * Null-safe conversion for the {@code COALESCE}-guarded count column.
     * COALESCE guarantees non-null, but the R2DBC driver may still return null
     * in edge cases (e.g. empty result before projection); treat null as 0.
     */
    private static long toLong(Long value) {
        return value != null ? value : 0L;
    }

    /** Null-safe conversion for numeric ratio columns. */
    private static double toDouble(Double value) {
        return value != null ? value : 0.0;
    }
}
