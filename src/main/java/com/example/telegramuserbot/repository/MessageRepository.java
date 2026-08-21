package com.example.telegramuserbot.repository;

import com.example.telegramuserbot.domain.MessageEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

public interface MessageRepository extends R2dbcRepository<MessageEntity, Long> {
    @Query("SELECT MAX(message_id) FROM messages WHERE chat_id = :chatId")
    Mono<Long> findMaxMessageIdByChatId(@Param("chatId") Long chatId);

    /**
     * Returns the most recent message date recorded for the given chat_id, or an empty
     * Mono when no messages exist for that channel (Spring Data R2DBC maps a NULL
     * aggregate to Mono.empty()).
     *
     * @param chatId the channel's chat ID
     * @return Mono of the latest message row, or empty when the channel has no recorded messages
     */
    // NB: returns the full latest ENTITY, not a scalar date. Two R2DBC pitfalls are avoided:
    //   1) `SELECT max(date)` returns one row with a NULL value for an empty channel, unmappable to Instant;
    //   2) projecting a single column into a bare `Mono<Instant>` makes R2DBC try entity-mapping and fail
    //      ("didn't find a PersistentEntity for java.time.Instant").
    // A full-entity SELECT ... LIMIT 1 maps cleanly (MessageEntity is a registered entity) and yields zero
    // rows -> Mono.empty for an empty channel. The caller extracts `.getDate()`.
    @Query("SELECT * FROM bot.messages WHERE chat_id = :chatId ORDER BY date DESC LIMIT 1")
    Mono<MessageEntity> findLatestMessageByChatId(@Param("chatId") long chatId);

    @Query("SELECT MIN(message_id) FROM bot.messages WHERE chat_id = :chatId")
    Mono<Long> findMinMessageIdByChatId(@Param("chatId") Long chatId);

    @Query("SELECT EXTRACT(EPOCH FROM MIN(date))::bigint FROM bot.messages WHERE chat_id = :chatId")
    Mono<Long> findOldestMessageEpochByChatId(@Param("chatId") Long chatId);

    Mono<Long> countByChatId(Long chatId);

    @Query("""
        SELECT * FROM bot.messages
        WHERE chat_id = :chatId
          AND (message_id = :messageId OR telegram_message_id = :messageId)
        LIMIT 1
    """)
    Mono<MessageEntity> findByChatIdAndMessageId(@Param("chatId") long chatId, @Param("messageId") long messageId);

    Flux<MessageEntity> findByChatIdOrderByIdAsc(long chatId, Pageable pageable);

    @Query("""
        SELECT * FROM bot.messages
        WHERE chat_id = :chatId AND message_id < :beforeMessageId
        ORDER BY message_id DESC
    """)
    Flux<MessageEntity> findLastMessagesBefore(@Param("chatId") long chatId,
                                               @Param("beforeMessageId") long beforeMessageId,
                                               Pageable pageable);

    @Query("""
        SELECT * FROM bot.messages
        WHERE chat_id = :chatId
          AND message_id < :beforeMessageId
          AND date >= :afterTime
        ORDER BY message_id DESC
    """)
    Flux<MessageEntity> findLastMessagesBeforeWithinTimeRange(@Param("chatId") long chatId,
                                                              @Param("beforeMessageId") long beforeMessageId,
                                                              @Param("afterTime") Instant afterTime,
                                                              Pageable pageable);

    /**
     * Same as {@link #findLastMessagesBeforeWithinTimeRange} but additionally filters by
     * {@code received_by_bot_id}. Used for PRIVATE chats ({@code chatId > 0}) to ensure
     * each persona only sees its own DM thread with the human, not another persona's turns.
     */
    @Query("""
        SELECT * FROM bot.messages
        WHERE chat_id = :chatId
          AND message_id < :beforeMessageId
          AND date >= :afterTime
          AND received_by_bot_id = :botId
        ORDER BY message_id DESC
    """)
    Flux<MessageEntity> findLastMessagesBeforeWithinTimeRangeByBot(@Param("chatId") long chatId,
                                                                   @Param("beforeMessageId") long beforeMessageId,
                                                                   @Param("afterTime") Instant afterTime,
                                                                   @Param("botId") String botId,
                                                                   Pageable pageable);

    @Query("""
        SELECT * FROM bot.messages
        WHERE chat_id = :chatId
          AND is_outgoing = true
          AND date >= :afterTime
        ORDER BY date DESC
    """)
    Flux<MessageEntity> findRecentOutgoingMessages(@Param("chatId") long chatId,
                                                   @Param("afterTime") Instant afterTime,
                                                   Pageable pageable);

    Mono<MessageEntity> findTopByChatIdAndIsOutgoingTrueOrderByDateDesc(long chatId);

    @Query("""
        SELECT DISTINCT m.chat_id FROM bot.messages m
        WHERE NOT EXISTS (
            SELECT 1 FROM tgscan.channels c WHERE c.id = m.chat_id
        )
    """)
    Flux<Long> findChatIdsNotInChannels();

    /**
     * Checks if a message exists for the given channel and telegram message ID.
     * Used by sync system to avoid duplicates.
     */
    Mono<Boolean> existsByChatIdAndTelegramMessageId(Long chatId, Long telegramMessageId);

    /**
     * Alternative method that works with the existing chatId field.
     */
    @Query("""
        SELECT COUNT(*) > 0 FROM bot.messages
        WHERE chat_id = :chatId
          AND (message_id = :messageId OR telegram_message_id = :messageId)
    """)
    Mono<Boolean> existsByChatIdAndMessageId(@Param("chatId") Long chatId, @Param("messageId") Long messageId);

    /**
     * Find recent messages by chat ID ordered by date (most recent first)
     * Used for conversation history in humanization
     */
    Flux<MessageEntity> findByChatIdOrderByDateDesc(@Param("chatId") Long chatId, Pageable pageable);

    /**
     * Find the most recent message by chatId, content, and senderId
     * Used to locate existing messages for LLM processing
     */
    Mono<MessageEntity> findTopByChatIdAndContentAndSenderIdOrderByDateDesc(Long chatId, String content, Long senderId);

    /**
     * Find the most recent message by chatId and content (fallback when senderId is null)
     * Used to locate existing messages for LLM processing
     */
    Mono<MessageEntity> findTopByChatIdAndContentOrderByDateDesc(Long chatId, String content);

    /**
     * Find bot messages in a specific time range for deletion purposes.
     * Searches for outgoing messages (bot's messages) within the specified timestamp range.
     *
     * @param chatId The chat ID to search in
     * @param botUserId The bot's user ID (senderId)
     * @param fromTime Start of time range (Instant)
     * @param toTime End of time range (Instant)
     * @return List of bot messages in the time range, ordered by date descending
     */
    @Query("""
        SELECT * FROM bot.messages
        WHERE chat_id = :chatId
          AND sender_id = :botUserId
          AND is_outgoing = true
          AND date >= :fromTime
          AND date <= :toTime
        ORDER BY date DESC
    """)
    Flux<MessageEntity> findBotMessagesInTimeRange(@Param("chatId") long chatId,
                                                  @Param("botUserId") long botUserId,
                                                  @Param("fromTime") Instant fromTime,
                                                  @Param("toTime") Instant toTime);

    /**
     * Find messages in a chat after specific time, ordered by date ascending
     * Used for context analysis in human-like response system
     */
    Flux<MessageEntity> findByChatIdAndDateAfterOrderByDateAsc(long chatId, Instant afterTime);

    @Query("""
        SELECT * FROM bot.messages
        WHERE chat_id = :chatId
          AND reply_to_message_id IN (:messageIds)
          AND date >= :afterTime
          AND date <= :beforeTime
        ORDER BY date ASC
    """)
    Flux<MessageEntity> findRepliesToMessagesInRange(@Param("chatId") long chatId,
                                                     @Param("messageIds") Iterable<Long> messageIds,
                                                     @Param("afterTime") Instant afterTime,
                                                     @Param("beforeTime") Instant beforeTime);

    @Query("""
        SELECT * FROM bot.messages
        WHERE chat_id = :chatId
          AND date >= :afterTime
          AND date <= :beforeTime
        ORDER BY date ASC
        LIMIT :limit
    """)
    Flux<MessageEntity> findByChatIdAndDateBetweenOrderByDateAsc(@Param("chatId") long chatId,
                                                                 @Param("afterTime") Instant afterTime,
                                                                 @Param("beforeTime") Instant beforeTime,
                                                                 @Param("limit") int limit);

    /**
     * Counts human replies to a specific message in a chat since a given timestamp.
     * Human reply criteria:
     * - not outgoing (bot flag is false)
     * - sender_id is positive (real user)
     * - reply_to_message_id matches triggering message
     * - sent after the provided timestamp
     */
    @Query("""
        SELECT COUNT(*)
          FROM bot.messages
         WHERE chat_id = :chatId
           AND reply_to_message_id = :triggeringMessageId
           AND is_outgoing = false
           AND sender_id IS NOT NULL
           AND sender_id > 0
           AND date >= :since
    """)
    Mono<Long> countHumanRepliesSince(@Param("chatId") long chatId,
                                      @Param("triggeringMessageId") long triggeringMessageId,
                                      @Param("since") Instant since);

    @Query("""
        SELECT * FROM bot.messages
        WHERE cluster_id IS NULL
          AND content_simhash IS NOT NULL
          AND content_simhash != '0000000000000000'
          AND date >= :since
        ORDER BY date DESC
        LIMIT :limit
    """)
    Flux<MessageEntity> findUnclusteredMessages(@Param("since") Instant since, @Param("limit") int limit);

    @Query("""
        SELECT * FROM bot.messages
        WHERE content_simhash IS NOT NULL
          AND id != :excludeId
          AND date >= :since
        ORDER BY date DESC
        LIMIT :limit
    """)
    Flux<MessageEntity> findCandidatesForClustering(
            @Param("excludeId") Long excludeId,
            @Param("since") Instant since,
            @Param("limit") int limit
    );

    @Query("SELECT * FROM bot.messages WHERE cluster_id = :clusterId ORDER BY importance DESC NULLS LAST")
    Flux<MessageEntity> findByClusterId(@Param("clusterId") String clusterId);

    @Query("UPDATE bot.messages SET cluster_id = :clusterId, is_primary_in_cluster = :isPrimary WHERE id = :id")
    Mono<Integer> updateClusterAssignment(@Param("id") Long id, @Param("clusterId") String clusterId, @Param("isPrimary") Boolean isPrimary);

    @Query("UPDATE bot.messages SET is_primary_in_cluster = false WHERE cluster_id = :clusterId")
    Mono<Integer> resetClusterPrimary(@Param("clusterId") String clusterId);

    /**
     * Returns cluster-primary messages from news channels only (tgscan.channels.is_channel = true),
     * excluding the requesting chat so a chat's own content never echoes back into its reply prompt.
     *
     * @param since      look-back window start
     * @param excludeId  the requesting chat_id to exclude (prevents self-echo and cross-chat leakage)
     * @param limit      max rows to return
     */
    @Query("""
        SELECT m.* FROM bot.messages m
        JOIN tgscan.channels c ON c.id = m.chat_id
        WHERE m.is_primary_in_cluster = true
          AND m.date >= :since
          AND c.is_channel = true
          AND m.chat_id != :excludeId
        ORDER BY m.importance DESC NULLS LAST
        LIMIT :limit
    """)
    Flux<MessageEntity> findPrimaryMessagesForDigest(@Param("since") Instant since,
                                                      @Param("excludeId") long excludeId,
                                                      @Param("limit") int limit);

    @Query("""
        SELECT m.* FROM bot.messages m
        JOIN tgscan.channels tc ON tc.id = m.chat_id
        WHERE m.date >= :since
          AND m.content IS NOT NULL AND m.content != ''
          AND length(m.content) > 40
          AND tc.subscribers >= :minSubscribers
          AND tc.is_channel = true
          AND m.chat_id NOT IN (SELECT DISTINCT dp.target_channel_id FROM bot.digest_personas dp WHERE dp.target_channel_id IS NOT NULL)
        ORDER BY (m.importance * ln(greatest(tc.subscribers, 2))) DESC NULLS LAST
        LIMIT :limit
    """)
    Flux<MessageEntity> findQualityMessagesForDigest(
            @Param("since") Instant since,
            @Param("minSubscribers") int minSubscribers,
            @Param("limit") int limit);

    @Query("SELECT DISTINCT cluster_id FROM bot.messages WHERE cluster_id IS NOT NULL AND date >= :since")
    Flux<String> findDistinctClusterIds(@Param("since") Instant since);

    /**
     * Returns cluster ids that have NO primary member ("headless" clusters) — every row has
     * {@code is_primary_in_cluster=false}. Window-independent on purpose: a cluster that ages
     * out of the recalc window before a primary is designated would otherwise stay headless
     * forever, and its rows are dropped from BOTH posting and embedding eligibility (which key
     * on {@code is_primary_in_cluster=true OR cluster_id IS NULL}). The hourly heal pass uses
     * this to designate a primary regardless of cluster age.
     */
    @Query("""
        SELECT cluster_id FROM bot.messages
        WHERE cluster_id IS NOT NULL
        GROUP BY cluster_id
        HAVING bool_or(is_primary_in_cluster) = false
    """)
    Flux<String> findHeadlessClusterIds();
    @Query("DELETE FROM bot.messages WHERE chat_id = :chatId")
    Mono<Integer> purgeByChatId(@Param("chatId") long chatId);

    /**
     * Deletes all messages whose {@code date} column is strictly before the given cutoff.
     * Used by the retention scheduler to implement the configurable N-day rolling window.
     *
     * @param cutoff messages older than this instant are deleted
     * @return count of deleted rows
     */
    @Query("DELETE FROM bot.messages WHERE date < :cutoff")
    Mono<Long> deleteOlderThan(@Param("cutoff") Instant cutoff);

    /**
     * Finds recent messages in a chat that contain the given hashtag, before the specified message ID.
     * Used to enrich LLM context with previous posts on the same topic.
     *
     * @param chatId          target chat
     * @param hashtag         hashtag string, e.g. "#клещи_для_рынка_недвижимости"
     * @param beforeMessageId only look at messages with message_id less than this
     * @param limit           max results
     */
    @Query("""
        SELECT * FROM bot.messages
        WHERE chat_id = :chatId
          AND content ILIKE CONCAT('%', :hashtag, '%')
          AND message_id < :beforeMessageId
          AND content IS NOT NULL
          AND content != ''
          AND is_outgoing = false
        ORDER BY message_id DESC
        LIMIT :limit
    """)
    Flux<MessageEntity> findRecentByHashtagBefore(@Param("chatId") long chatId,
                                                   @Param("hashtag") String hashtag,
                                                   @Param("beforeMessageId") long beforeMessageId,
                                                   @Param("limit") int limit);

    /**
     * Counts outgoing bot messages sent since the last inbound (non-outgoing) message before the given message_id.
     * Used to enforce a per-post bot conversation chain length limit and prevent echo-chamber loops.
     *
     * @param chatId           target chat
     * @param currentMessageId the triggering message's message_id (exclusive upper bound)
     */
    @Query("""
        SELECT COUNT(*) FROM bot.messages
        WHERE chat_id = :chatId
          AND is_outgoing = true
          AND message_id > (
              SELECT COALESCE(MAX(m2.message_id), 0) FROM bot.messages m2
              WHERE m2.chat_id = :chatId
                AND m2.is_outgoing = false
                AND m2.message_id < :currentMessageId
          )
          AND message_id < :currentMessageId
    """)
    Mono<Long> countOutgoingMessagesSinceLastInbound(@Param("chatId") long chatId,
                                                     @Param("currentMessageId") long currentMessageId);

    // -------------------------------------------------------------------------
    // F3 keyword-backfill helpers
    // -------------------------------------------------------------------------

    /**
     * Returns a forward-paged batch of message rows whose {@code matched_keywords} has never
     * been populated ({@code IS NULL}) and that have non-empty text (content or caption).
     * Used by {@code KeywordBackfillRunner} to retroactively fill the column.
     *
     * <p>Paging is by ascending {@code id} strictly greater than {@code afterId}; the runner
     * advances {@code afterId} to the max id seen in each batch. This guarantees the scan
     * walks the table forward exactly once and always terminates. The predicate is
     * {@code matched_keywords IS NULL} (not {@code cardinality = 0}) so a row that matches
     * <em>no</em> keywords — set to an empty array {@code '{}'} — counts as processed and is
     * never re-scanned (avoids an infinite loop on no-match rows).
     *
     * @param afterId exclusive lower bound on {@code id} (pass 0 to start from the beginning)
     * @param limit   max rows to return per batch
     * @return flux of candidate messages ordered by id
     */
    @Query("""
        SELECT * FROM bot.messages
        WHERE id > :afterId
          AND matched_keywords IS NULL
          AND (
              (content  IS NOT NULL AND content  != '')
           OR (caption  IS NOT NULL AND caption  != '')
          )
        ORDER BY id
        LIMIT :limit
    """)
    Flux<MessageEntity> findUnmatchedKeywordsBatch(@Param("afterId") long afterId, @Param("limit") int limit);

    /**
     * Updates the {@code matched_keywords} column for a single message row by primary key.
     * Safe to call repeatedly (idempotent on the same keywords array).
     *
     * @param id              primary key of the row to update
     * @param matchedKeywords array of matched keyword strings (may be empty, not null)
     * @return number of rows updated (0 or 1)
     */
    @Modifying
    @Query("UPDATE bot.messages SET matched_keywords = :matchedKeywords WHERE id = :id")
    Mono<Integer> updateMatchedKeywords(@Param("id") Long id,
                                        @Param("matchedKeywords") String[] matchedKeywords);

    // -------------------------------------------------------------------------
    // Geo backfill
    // -------------------------------------------------------------------------

    /**
     * Returns a forward-paged batch of messages that have no geo classification yet
     * and have at least some text content to classify.
     *
     * @param afterId exclusive lower bound on id (pass 0 to start from the beginning)
     * @param limit   max rows to return per batch
     * @return flux of candidate messages ordered by id
     */
    @Query("""
        SELECT * FROM bot.messages
        WHERE id > :afterId
          AND geo IS NULL
          AND (
              (content IS NOT NULL AND content != '')
           OR (caption IS NOT NULL AND caption != '')
          )
        ORDER BY id
        LIMIT :limit
    """)
    Flux<MessageEntity> findGeoBackfillBatch(@Param("afterId") long afterId, @Param("limit") int limit);

    /**
     * Updates the {@code geo} column for a single message row by primary key.
     *
     * @param id  primary key of the row to update
     * @param geo geo scope string (e.g. "RU", "GLOBAL")
     * @return number of rows updated (0 or 1)
     */
    @Modifying
    @Query("UPDATE bot.messages SET geo = :geo WHERE id = :id")
    Mono<Integer> updateGeo(@Param("id") Long id, @Param("geo") String geo);

    // -------------------------------------------------------------------------
    // P1 v2 — proactive post opener anti-repetition
    // -------------------------------------------------------------------------

    /**
     * Returns the N most recent outgoing posts sent by this persona in the target chat,
     * newest first. Used to extract opening fragments and instruct the LLM to start its
     * next post differently.
     *
     * <p>Attribution: outgoing messages are persisted with {@code received_by_bot_id = botId}
     * (set in {@link com.example.telegramuserbot.service.persistence.MessageEntityHydrator#create})
     * and {@code is_outgoing = true}. Both predicates together uniquely identify a persona's
     * own posts in a given chat.
     *
     * @param chatId target chat (the persona's posting channel or discussion group)
     * @param botId  string form of the persona's Telegram user id (matches received_by_bot_id)
     * @param limit  max rows to return
     * @return flux of outgoing messages, newest first
     */
    @Query("""
        SELECT * FROM bot.messages
        WHERE chat_id = :chatId
          AND received_by_bot_id = :botId
          AND is_outgoing = true
          AND (content IS NOT NULL AND content != '')
        ORDER BY date DESC
        LIMIT :limit
    """)
    Flux<MessageEntity> findRecentOutgoingByPersona(@Param("chatId") long chatId,
                                                    @Param("botId") String botId,
                                                    @Param("limit") int limit);

    // -------------------------------------------------------------------------
    // P1 v1 — proactive news posting
    // -------------------------------------------------------------------------

    /**
     * Selects the single best unposted news message for a persona's proactive post.
     *
     * <p>Ranks messages from subscribed channels (is_channel=true) by
     * {@code importance * ln(greatest(subscribers, 2))} descending, subject to:
     * <ul>
     *   <li>Recency window: {@code m.date >= :since}</li>
     *   <li>Min subscriber count: {@code tc.subscribers >= :minSubscribers}</li>
     *   <li>Min computed value: {@code importance * ln(greatest(subscribers,2)) >= :minValue}</li>
     *   <li>Cluster-primary (or unclustered): {@code COALESCE(m.is_primary_in_cluster, true)}</li>
     *   <li>Excludes the persona's own target channel to avoid echo-posting</li>
     *   <li>Excludes messages already posted by this persona to this chat</li>
     * </ul>
     *
     * <p>Topic keyword pre-filter: when the persona has topic keywords ({@code :hasKeywords = true}),
     * the SQL keeps only rows whose {@code m.content} matches at least one {@code %keyword%} ILIKE
     * pattern from the {@code :keywordPatterns} array. This ensures the {@code LIMIT :scanLimit}
     * applies to keyword-RELEVANT rows, preventing topic-specialized personas (e.g. crypto) from
     * being starved by the global value-ranking window. The Java-side {@code passesKeywordFilter}
     * (whole-word) remains in place as the final precision narrower.
     *
     * <p>When {@code :hasKeywords = false} (persona has no topic keywords) the pattern condition is
     * skipped entirely, preserving the original behavior.
     *
     * @param since           look-back window start (UTC)
     * @param minSubscribers  minimum channel subscriber count
     * @param minValue        minimum computed value score
     * @param personaBotId    string form of the persona's bot_id (for already-posted exclusion)
     * @param targetChatId    the target chat — excluded from source channels
     * @param scanLimit       how many candidates to fetch (keyword-relevant when hasKeywords=true)
     * @param audienceGeo     geo filter value (e.g. "GLOBAL", "RU")
     * @param hasKeywords     {@code true} when keywordPatterns is non-empty; gates the ILIKE filter
     * @param keywordPatterns {@code text[]} of {@code %keyword%} patterns for ILIKE ANY pre-filter
     * @return flux of candidate messages, ordered by value descending
     */
    // ⚠️ COLUMN ORDER IS LOAD-BEARING — do NOT move `m.*` before the computed alias.
    // bot.messages has a real (always-NULL) value_score column (#94/cs071). With `m.*` first, the
    // result has two columns named value_score and R2DBC binds MessageEntity.valueScore to the
    // FIRST/lowest-index one (the NULL base column), so getValueScore() returns NULL and the
    // importance×ln(subscribers) ranking is lost in Java (broke the web-enrich value gate + the
    // recorded news_posts.value_score). Putting the computed alias FIRST makes it win that binding.
    /**
     * Returns news-eligible rows (channel messages with meaningful content) whose
     * vector has not yet been upserted into Qdrant ({@code embedded_at IS NULL}).
     *
     * <p>Eligibility mirrors the proactive-posting candidate query:
     * TG channel ({@code tc.is_channel = true}), {@code subscribers >= 1000},
     * content present and {@code length > 40}, cluster-primary (or unclustered).
     *
     * <p>Ordered by {@code content_simhash} so that cluster siblings land in adjacent
     * batch positions — maximising in-run simhash cache hits (siblings reuse one vector).
     *
     * @param limit max rows per batch tick
     */
    @Query("""
        SELECT m.*
        FROM bot.messages m
        JOIN tgscan.channels tc ON tc.id = m.chat_id
        WHERE m.embedded_at IS NULL
          AND m.chat_id < 0
          AND tc.is_channel = true
          AND tc.subscribers >= 1000
          AND m.content IS NOT NULL
          AND length(m.content) > 40
          AND (COALESCE(m.is_primary_in_cluster, true) = true OR m.cluster_id IS NULL)
        ORDER BY m.content_simhash NULLS LAST, m.id
        LIMIT :limit
    """)
    Flux<MessageEntity> findNewsEligibleWithoutEmbedding(@Param("limit") int limit);

    /**
     * Marks a message as embedded by setting embedded_at to the current timestamp.
     * Called only AFTER a successful Qdrant upsert.
     */
    @Modifying
    @Query("UPDATE bot.messages SET embedded_at = NOW() WHERE id = :id")
    Mono<Integer> markEmbedded(@Param("id") Long id);

    @Query("""
        SELECT (m.importance * ln(greatest(tc.subscribers, 2))) AS value_score, m.*
        FROM bot.messages m
        JOIN tgscan.channels tc ON tc.id = m.chat_id
        WHERE m.date >= :since
          AND tc.subscribers >= :minSubscribers
          AND tc.is_channel = true
          AND m.content IS NOT NULL AND m.content != ''
          AND length(m.content) > 40
          AND (COALESCE(m.is_primary_in_cluster, true) = true OR m.cluster_id IS NULL)
          AND m.chat_id != :targetChatId
          AND (m.importance * ln(greatest(tc.subscribers, 2))) >= :minValue
          AND (m.geo IS NULL OR m.geo = 'GLOBAL' OR m.geo = :audienceGeo)
          AND (:hasKeywords = false OR m.content ILIKE ANY(:keywordPatterns))
          AND m.id NOT IN (
              SELECT message_id FROM bot.news_posts
              WHERE target_chat_id = :targetChatId
                AND status = 'SENT'
          )
        ORDER BY PERCENT_RANK() OVER (
                     PARTITION BY (tc.outlet_trust IS NOT NULL)
                     ORDER BY (m.importance * ln(greatest(tc.subscribers, 2)))
                 ) DESC,
                 (m.importance * ln(greatest(tc.subscribers, 2))) DESC NULLS LAST
        LIMIT :scanLimit
    """)
    Flux<MessageEntity> findUnpostedNewsCandidatesForPersona(
            @Param("since")           Instant since,
            @Param("minSubscribers")  int minSubscribers,
            @Param("minValue")        double minValue,
            @Param("targetChatId")    long targetChatId,
            @Param("scanLimit")       int scanLimit,
            @Param("audienceGeo")     String audienceGeo,
            @Param("hasKeywords")     boolean hasKeywords,
            @Param("keywordPatterns") String[] keywordPatterns
    );

}
