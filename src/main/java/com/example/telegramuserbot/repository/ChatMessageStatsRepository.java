package com.example.telegramuserbot.repository;

import com.example.telegramuserbot.domain.ChatMessageStats;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Mono;

public interface ChatMessageStatsRepository extends R2dbcRepository<ChatMessageStats, Long> {

    @Query("""
            SELECT human_message_count
              FROM bot.chat_message_stats
             WHERE chat_id = :chatId
            """)
    Mono<Long> findCountByChatId(@Param("chatId") Long chatId);

    @Query("""
            INSERT INTO bot.chat_message_stats (chat_id, human_message_count)
            VALUES (:chatId, 1)
            ON CONFLICT (chat_id)
            DO UPDATE SET human_message_count = bot.chat_message_stats.human_message_count + 1
            RETURNING human_message_count
            """)
    Mono<Long> incrementHumanCount(@Param("chatId") Long chatId);
}
