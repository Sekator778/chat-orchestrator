package com.example.telegramuserbot.repository;

import com.example.telegramuserbot.domain.NewsPost;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * R2DBC repository for {@link NewsPost}.
 * Supports duplicate detection (unique index on message+persona+chat) and
 * daily-cap counting.
 */
public interface NewsPostRepository extends R2dbcRepository<NewsPost, Long> {

    @Query("""
        SELECT COUNT(*) > 0
        FROM bot.news_posts
        WHERE message_id         = :messageId
          AND persona_bot_id     = :personaBotId
          AND target_chat_id     = :targetChatId
    """)
    Mono<Boolean> existsByMessageIdAndPersonaBotIdAndTargetChatId(
            @Param("messageId")     Long messageId,
            @Param("personaBotId")  String personaBotId,
            @Param("targetChatId")  Long targetChatId
    );

    @Query("""
        SELECT COUNT(*)
        FROM bot.news_posts
        WHERE persona_bot_id = :personaBotId
          AND target_chat_id = :targetChatId
          AND posted_at      >= :after
          AND status         = 'SENT'
    """)
    Mono<Long> countByPersonaBotIdAndTargetChatIdAndPostedAtAfter(
            @Param("personaBotId") String personaBotId,
            @Param("targetChatId") Long targetChatId,
            @Param("after")        Instant after
    );
}
