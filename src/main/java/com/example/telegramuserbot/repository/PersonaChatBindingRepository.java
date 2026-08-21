package com.example.telegramuserbot.repository;

import com.example.telegramuserbot.domain.PersonaChatBinding;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Flux;

public interface PersonaChatBindingRepository extends R2dbcRepository<PersonaChatBinding, Long> {

    @Query("""
            SELECT bot_id
              FROM bot.persona_chat_bindings
             WHERE chat_id = :chatId
               AND reply_enabled = TRUE
             ORDER BY bot_id
            """)
    Flux<String> findEnabledBotIdsByChatId(@Param("chatId") long chatId);

    @Query("""
            SELECT *
              FROM bot.persona_chat_bindings
             WHERE chat_id = :chatId
               AND reply_enabled = TRUE
             ORDER BY bot_id
            """)
    Flux<PersonaChatBinding> findEnabledBindingsByChatId(@Param("chatId") long chatId);
}
