package com.example.telegramuserbot.repository;

import com.example.telegramuserbot.domain.RestrictionType;
import com.example.telegramuserbot.domain.TopicRestriction;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface TopicRestrictionRepository extends R2dbcRepository<TopicRestriction, Long> {

    @Query("""
        SELECT tr.* FROM bot.topic_restrictions tr
        INNER JOIN bot.chat_configs cc ON tr.chat_config_id = cc.id
        WHERE cc.channel_chat_id = :chatId AND tr.active = :active
    """)
    Flux<TopicRestriction> findByChatConfigChannelChatIdAndActive(@Param("chatId") Long chatId, @Param("active") boolean active);

    Flux<TopicRestriction> findByChatConfigIdOrderByIdDesc(Long chatConfigId);

    Flux<TopicRestriction> findByChatConfigIdAndActiveOrderByIdDesc(Long chatConfigId, boolean active);

    Mono<TopicRestriction> findByChatConfigIdAndRestrictionName(Long chatConfigId, String restrictionName);

    @Query("""
        SELECT tr.* FROM bot.topic_restrictions tr
        INNER JOIN bot.chat_configs cc ON tr.chat_config_id = cc.id
        WHERE cc.channel_chat_id = :chatId
          AND tr.restriction_type = :restrictionType
          AND tr.active = :active
    """)
    Flux<TopicRestriction> findByChatConfigChannelChatIdAndRestrictionTypeAndActive(
            @Param("chatId") Long chatId, @Param("restrictionType") RestrictionType restrictionType, @Param("active") boolean active);

    @Query("""
        SELECT tr.* FROM bot.topic_restrictions tr
        INNER JOIN bot.chat_configs cc ON tr.chat_config_id = cc.id
        WHERE cc.channel_chat_id = :chatId
          AND tr.active = true
          AND LOWER(tr.keywords) LIKE LOWER(CONCAT('%', :keyword, '%'))
    """)
    Flux<TopicRestriction> findByKeywordContainingIgnoreCase(@Param("chatId") Long chatId,
                                                           @Param("keyword") String keyword);

    @Query("""
        SELECT COUNT(tr.id) FROM bot.topic_restrictions tr
        INNER JOIN bot.chat_configs cc ON tr.chat_config_id = cc.id
        WHERE cc.channel_chat_id = :chatId AND tr.active = :active
    """)
    Mono<Long> countByChatConfigChannelChatIdAndActive(@Param("chatId") Long chatId, @Param("active") boolean active);

    Mono<Void> deleteByChatConfigId(Long chatConfigId);

    Flux<TopicRestriction> findByChatConfigId(Long chatConfigId);

    @Modifying
    @Query("UPDATE bot.topic_restrictions SET active = NOT active WHERE id = :restrictionId")
    Mono<Integer> toggleActiveStatus(@Param("restrictionId") Long restrictionId);
}
