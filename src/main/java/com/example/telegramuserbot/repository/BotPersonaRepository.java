package com.example.telegramuserbot.repository;

import com.example.telegramuserbot.domain.BotPersona;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface BotPersonaRepository extends R2dbcRepository<BotPersona, Long> {

    @Query("""
            SELECT *
              FROM bot.bot_personas
             WHERE bot_id = :botId
               AND language = :language
            """)
    Mono<BotPersona> findByBotIdAndLanguage(@Param("botId") String botId, @Param("language") String language);

    @Query("""
            SELECT *
              FROM bot.bot_personas
             WHERE bot_id = :botId
            """)
    Flux<BotPersona> findByBotId(@Param("botId") String botId);

    @Query("""
            SELECT DISTINCT bot_id
              FROM bot.bot_personas
             ORDER BY bot_id
            """)
    Flux<String> findDistinctBotIds();

    /**
     * Whether the persona answers private (direct) messages. A persona may have
     * several language rows; ANY row with reply_to_direct=true enables DM replies.
     * Emits false when the persona has no rows at all.
     */
    @Query("""
            SELECT COALESCE(bool_or(reply_to_direct), false)
              FROM bot.bot_personas
             WHERE bot_id = :botId
            """)
    Mono<Boolean> replyToDirectEnabled(@Param("botId") String botId);
}
