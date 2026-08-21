package com.example.telegramuserbot.repository;

import com.example.telegramuserbot.domain.Channel;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

public interface ChannelRepository extends R2dbcRepository<Channel, Long> {
    @Query("""
            SELECT *
              FROM tgscan.channels
             WHERE id = :chatId
            """)
    Mono<Channel> findByChatId(@Param("chatId") Long chatId);

    @Query("""
            SELECT *
              FROM tgscan.channels
             WHERE LOWER(title) = LOWER(:title)
            """)
    Mono<Channel> findByTitle(@Param("title") String title);

    @Query("""
            SELECT *
              FROM tgscan.channels
            """)
    Flux<Channel> findAllForInstance();

    @Query("""
            SELECT *
              FROM tgscan.channels
             ORDER BY title NULLS LAST, id
            """)
    Flux<Channel> findAllOrderedForInstance();

    @Query("""
            SELECT COUNT(*)
              FROM tgscan.channels
            """)
    Mono<Long> countForInstance();

    /**
     * Counts channels whose {@code join_status} is {@code 'joined'}.
     * Used by the membership-cap observability scheduler to compute headroom
     * relative to Telegram's 500-channel limit.
     */
    @Query("""
            SELECT COUNT(*)
              FROM tgscan.channels
             WHERE join_status = 'joined'
            """)
    Mono<Long> countJoined();

    /**
     * Finds high-quality channels from tgscan.channels that are joined
     * but do not have a corresponding configuration in bot.chat_configs.
     * <p>
     * Uses multi-factor weighted scoring to prioritize channels:
     * - 40% channel_score (overall quality assessment, with fallback to raw_keyword_score)
     * - 30% weight (reliability based on message consensus/novelty)
     * - 20% subscribers (audience size, normalized)
     * - 10% recency (activity within last 7 days)
     * <p>
     * IMPORTANT: If channel_score is NULL/0 (not yet calculated), falls back to raw_keyword_score.
     * This allows channel selection to work even before fn_recalc_channel_score() has been run.
     *
     * @param minScore Minimum score threshold for channels
     * @return Flux of unconfigured high-quality channels, sorted by composite quality metric
     */
    @Query("""
            SELECT c.*
            FROM tgscan.channels c
            LEFT JOIN bot.chat_configs cc
              ON c.id = cc.channel_chat_id
            WHERE COALESCE(c.channel_score * 100, c.raw_keyword_score, 0) > :minScore
              AND c.join_status = 'joined'
              AND cc.id IS NULL
            ORDER BY
              -- Multi-factor weighted quality score (0-100 range)
              (
                -- Use channel_score (0-1 range) if available, else fallback to raw_keyword_score (0-100)
                COALESCE(c.channel_score * 100, c.raw_keyword_score, 0) * 0.4 +
                COALESCE(c.weight, 0) * 100 * 0.3 +
                LEAST(100, COALESCE(c.subscribers, 0)::NUMERIC / 1000) * 0.2 +
                CASE WHEN c.last_seen > NOW() - INTERVAL '7 days' THEN 100 ELSE 0 END * 0.1
              ) DESC,
              COALESCE(c.channel_score, c.raw_keyword_score, 0) DESC
            """)
    Flux<Channel> findUnconfiguredHighScoringChannels(@Param("minScore") double minScore);

    /**
     * retrieve channel where count of subscribers more than
     */
    @Query(
            """
                    SELECT c.*
                     FROM tgscan.channels c
                     LEFT JOIN bot.chat_configs cc
                       ON c.id = cc.channel_chat_id
                     where cc.id is null
                       and c.subscribers > :subscribers
                       and c.join_status = 'joined'
                    """)
    Flux<Channel> findUnconfiguredChannelsWithSubscribers(@Param("subscribers") int subscribers);

    /**
     * Finds channels from tgscan.channels that have been discovered by the Python scanner
     * but not yet processed by the Java application (join_status is NULL/empty).
     * These channels need to be checked if the bot is already a member.
     *
     * @return Flux of channels without join_status set
     */
    @Query("""
            SELECT c.*
            FROM tgscan.channels c
            WHERE c.join_status IS NULL OR c.join_status = ''
            LIMIT 1000
            """)
    Flux<Channel> findChannelsWithoutJoinStatus();

    /**
     * Finds channels needing Phase 1 ingestion processing.
     * Selects channels that either:
     * 1. Have never been attempted (last_ingestion_attempt_at IS NULL), OR
     * 2. Last attempt was more than specified days ago
     * <p>
     * Orders by score DESC to prioritize high-quality channels.
     *
     * @param minDaysSinceLastAttempt Minimum days since last ingestion attempt
     * @param limit Maximum number of channels to return
     * @return Flux of channels needing ingestion, ordered by quality score
     */
    @Query("""
            SELECT c.*
            FROM tgscan.channels c
            WHERE (c.last_ingestion_attempt_at IS NULL
                   OR (COALESCE(c.join_status, '') != 'joined'
                       AND c.last_ingestion_attempt_at < NOW() - INTERVAL '1 day')
                   OR (c.join_status = 'joined'
                       AND c.last_ingestion_attempt_at < NOW() - (:minDays * INTERVAL '1 day')))
            ORDER BY
              CASE WHEN COALESCE(c.join_status, '') != 'joined' THEN 0 ELSE 1 END,
              COALESCE(c.subscribers, 0) DESC,
              COALESCE(c.channel_score, c.raw_keyword_score, 0) DESC
            LIMIT :limit
            """)
    Flux<Channel> findChannelsNeedingIngestion(
            @Param("minDays") int minDaysSinceLastAttempt,
            @Param("limit") int limit
    );

    /**
     * Phase 1 ingestion with a subscriber gate on the JOIN decision (owner: "join by subscriber
     * count + activity, skip noise"). Already-joined channels are re-ingested on the slow cadence
     * with NO subscriber gate (we are already a member). NOT-yet-joined channels are only attempted
     * when they have a KNOWN subscriber count at or above {@code minSubscribers} — NULL (unenriched)
     * and known-small channels are skipped/deferred so we never join noise in the first place.
     *
     * @param minDays Minimum days since last attempt before re-ingesting an already-joined channel
     * @param minSubscribers Minimum known subscriber count required to attempt a NEW join
     * @param limit Maximum number of channels to return
     */
    @Query("""
            SELECT c.*
            FROM tgscan.channels c
            WHERE (
                    (c.join_status = 'joined'
                        AND (c.last_ingestion_attempt_at IS NULL
                             OR c.last_ingestion_attempt_at < NOW() - (:minDays * INTERVAL '1 day')))
                 OR (COALESCE(c.join_status, '') != 'joined'
                        AND (c.last_ingestion_attempt_at IS NULL
                             OR c.last_ingestion_attempt_at < NOW() - INTERVAL '1 day')
                        AND c.subscribers IS NOT NULL
                        AND c.subscribers >= :minSubscribers)
                  )
            ORDER BY
              CASE WHEN COALESCE(c.join_status, '') != 'joined' THEN 0 ELSE 1 END,
              COALESCE(c.subscribers, 0) DESC,
              COALESCE(c.channel_score, c.raw_keyword_score, 0) DESC
            LIMIT :limit
            """)
    Flux<Channel> findChannelsNeedingIngestionGated(
            @Param("minDays") int minDaysSinceLastAttempt,
            @Param("minSubscribers") int minSubscribers,
            @Param("limit") int limit
    );

    /**
     * Counts channels joined within the rolling window starting at {@code since}.
     * Backs the Phase 1 daily-join cap ("join N per 24h, defer the rest as candidates").
     */
    @Query("""
            SELECT count(*)
              FROM tgscan.channels
             WHERE joined_at > :since
            """)
    Mono<Long> countJoinedSince(@Param("since") Instant since);

    /**
     * Low-value broadcast channels to leave during cleanup: joined broadcast channels with a
     * KNOWN subscriber count below the threshold (never NULL — unknown subs are not "low",
     * they're unenriched; never groups — those are the reply plane). Recently-joined ≥threshold
     * channels are intentionally excluded: their lack of harvested history is a join-recency
     * artifact, not a quality signal. Test-plane / protected ids are filtered in the service.
     * Ordered smallest-first so the dry-run reads worst-to-best.
     */
    @Query("""
            SELECT * FROM tgscan.channels
             WHERE is_channel = true
               AND join_status = 'joined'
               AND subscribers IS NOT NULL
               AND subscribers < :minSubscribers
             ORDER BY subscribers ASC
             LIMIT :limit
            """)
    Flux<Channel> findLowValueBroadcastToLeave(@Param("minSubscribers") int minSubscribers,
                                               @Param("limit") int limit);

    @Modifying
    @Query("UPDATE tgscan.channels SET join_status = 'left' WHERE id = :chatId")
    Mono<Long> markChannelLeft(@Param("chatId") long chatId);

    @Query("""
            SELECT *
              FROM tgscan.channels
             WHERE id = :channelPk
            """)
    Mono<Channel> findByInternalId(@Param("channelPk") Long channelPk);

    default Mono<Channel> findByIdForInstance(Long channelPk) {
        if (channelPk == null) {
            return Mono.empty();
        }
        return findByInternalId(channelPk);
    }

    default Flux<Channel> findAllByIdForInstance(Iterable<Long> ids) {
        return Flux.fromIterable(ids)
                .concatMap(this::findByIdForInstance);
    }

    @Modifying
    @Query("""
            UPDATE tgscan.channels
               SET bot_instance_id = array_remove(bot_instance_id, :botInstanceId)
             WHERE id = :chatId
            """)
    Mono<Integer> removeBotInstanceId(@Param("chatId") Long chatId, @Param("botInstanceId") String botInstanceId);

    /**
     * Finds joined channels where the given persona is NOT in the bot_instance_id array.
     * Used by reconciliation service to detect channels missing specific personas.
     */
    @Query("""
            SELECT c.*
            FROM tgscan.channels c
            WHERE c.join_status = 'joined'
              AND NOT (:personaId = ANY(c.bot_instance_id))
            ORDER BY c.joined_at ASC
            LIMIT :limit
            """)
    Flux<Channel> findJoinedChannelsMissingPersona(
            @Param("personaId") String personaId,
            @Param("limit") int limit
    );

    /**
     * Finds the oldest not-yet-backfilled news channel (is_channel = true, backfilled_at IS NULL)
     * that the collector has joined. Returns at most one channel per call so the scheduler
     * processes exactly one channel per scheduled run (conservative pacing).
     *
     * @return Mono of the next channel to backfill, or empty if all joined news channels
     *         have already been backfilled.
     */
    @Query("""
            SELECT *
              FROM tgscan.channels
             WHERE is_channel = true
               AND join_status = 'joined'
               AND backfilled_at IS NULL
             ORDER BY COALESCE(subscribers, 0) DESC, id
             LIMIT 1
            """)
    Mono<Channel> findNextNewsChannelForBackfill();

    /**
     * Returns all joined broadcast (news) channels regardless of backfill state.
     * Used by the startup gap-fill to catch up messages posted while the app was down.
     *
     * @return Flux of all joined broadcast channels, ordered by subscriber count desc then id
     */
    @Query("""
            SELECT *
              FROM tgscan.channels
             WHERE is_channel = true
               AND join_status = 'joined'
             ORDER BY COALESCE(subscribers, 0) DESC, id
            """)
    Flux<Channel> findJoinedBroadcastChannels();

    /**
     * Marks a news channel as backfilled by setting backfilled_at to now().
     *
     * @param chatId the channel's chat ID (primary key in tgscan.channels)
     * @return Mono of the number of rows updated
     */
    @Modifying
    @Query("""
            UPDATE tgscan.channels
               SET backfilled_at = NOW()
             WHERE id = :chatId
            """)
    Mono<Integer> markBackfilled(@Param("chatId") Long chatId);

    /**
     * Variant for non-collector (reply-plane) personas: same as above but
     * excludes broadcast/news channels (is_channel = true).
     * Non-collectors must not be force-joined into broadcast channels.
     */
    @Query("""
            SELECT c.*
            FROM tgscan.channels c
            WHERE c.join_status = 'joined'
              AND NOT (:personaId = ANY(c.bot_instance_id))
              AND c.is_channel IS NOT TRUE
            ORDER BY c.joined_at ASC
            LIMIT :limit
            """)
    Flux<Channel> findJoinedNonBroadcastChannelsMissingPersona(
            @Param("personaId") String personaId,
            @Param("limit") int limit
    );

    /**
     * Idempotent upsert for a broadcast (news) channel discovered via the collector account's
     * live message stream. Inserts a new row if none exists; on conflict updates only the
     * title (preserving all scoring/weight columns set by the Python scanner or later jobs).
     * {@code is_channel} is always forced to {@code true} — the caller guarantees this path
     * is only reached for TDLib {@code ChatTypeSupergroup.isChannel == true} chats.
     *
     * @param chatId  TDLib chat id (negative supergroup id, e.g. -1001234567890)
     * @param title   Channel display name from the most-recent TDLib update
     * @return        Mono emitting 1 (row inserted or updated), completing on success
     */
    @Modifying
    @Query("""
            INSERT INTO tgscan.channels (id, title, is_channel)
            VALUES (:chatId, :title, true)
            ON CONFLICT (id) DO UPDATE
               SET title      = EXCLUDED.title,
                   is_channel = true
            """)
    Mono<Integer> upsertBroadcastChannel(
            @Param("chatId") Long chatId,
            @Param("title") String title
    );

    /**
     * Finds broadcast channels whose subscriber count has not yet been populated.
     * Used by {@code ChannelSourceTrustScheduler} to drive the source-value sweep.
     *
     * @return Flux of channels with {@code is_channel = true} and {@code subscribers IS NULL}
     */
    @Query("""
            SELECT *
              FROM tgscan.channels
             WHERE is_channel = true
               AND subscribers IS NULL
            ORDER BY id
            """)
    Flux<Channel> findBroadcastChannelsWithoutSubscribers();

    /**
     * Stores the fetched member count for a broadcast channel.
     * Idempotent — safe to call repeatedly; only writes when the value actually differs.
     *
     * @param chatId      TDLib chat id (primary key of tgscan.channels)
     * @param subscribers member count returned by TDLib
     * @return Mono emitting the number of rows updated (1 on success, 0 if row not found)
     */
    @Modifying
    @Query("""
            UPDATE tgscan.channels
               SET subscribers = :subscribers
             WHERE id = :chatId
            """)
    Mono<Integer> updateSubscribers(
            @Param("chatId") Long chatId,
            @Param("subscribers") Long subscribers
    );
}
