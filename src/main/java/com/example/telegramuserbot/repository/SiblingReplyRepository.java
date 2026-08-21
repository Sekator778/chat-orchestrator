package com.example.telegramuserbot.repository;

import com.example.telegramuserbot.domain.SiblingReply;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * R2DBC repository for {@link SiblingReply}.
 * Supports idempotency (unique constraint on persona_bot_id + in_reply_to_message_id)
 * and daily-cap counting.
 */
public interface SiblingReplyRepository extends R2dbcRepository<SiblingReply, Long> {

    /**
     * Count sibling replies sent by this persona today (since midnight UTC via {@code after}).
     */
    @Query("""
        SELECT COUNT(*)
          FROM bot.sibling_replies
         WHERE persona_bot_id = :personaBotId
           AND posted_at      >= :after
    """)
    Mono<Long> countByPersonaBotIdAndPostedAtAfter(
            @Param("personaBotId") String personaBotId,
            @Param("after") Instant after
    );
}
