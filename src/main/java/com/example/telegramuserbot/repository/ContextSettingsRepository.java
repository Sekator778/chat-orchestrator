package com.example.telegramuserbot.repository;

import com.example.telegramuserbot.domain.ContextSettings;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Mono;

public interface ContextSettingsRepository extends R2dbcRepository<ContextSettings, Long> {

    @Query("""
        SELECT cs.* FROM bot.context_settings cs
        INNER JOIN bot.chat_configs cc ON cs.chat_config_id = cc.id
        WHERE cc.channel_chat_id = :chatId
    """)
    Mono<ContextSettings> findByChatConfigChannelChatId(@Param("chatId") Long chatId);

    @Query("SELECT * FROM bot.context_settings WHERE chat_config_id = :chatConfigId")
    Mono<ContextSettings> findByChatConfigId(@Param("chatConfigId") Long chatConfigId);

    @Query("DELETE FROM bot.context_settings WHERE chat_config_id = :chatConfigId")
    Mono<Void> deleteByChatConfigId(@Param("chatConfigId") Long chatConfigId);

    @Query("""
        SELECT COUNT(cs.id) > 0 FROM bot.context_settings cs
        INNER JOIN bot.chat_configs cc ON cs.chat_config_id = cc.id
        WHERE cc.channel_chat_id = :chatId
    """)
    Mono<Boolean> existsByChatConfigChannelChatId(@Param("chatId") Long chatId);
}
