package com.example.telegramuserbot.repository;

import com.example.telegramuserbot.domain.TriggerCondition;
import com.example.telegramuserbot.domain.TriggerType;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface TriggerConditionRepository extends R2dbcRepository<TriggerCondition, Long> {

    @Query("""
        SELECT tc.* FROM bot.trigger_conditions tc
        JOIN bot.chat_configs cc ON tc.chat_config_id = cc.id
        WHERE cc.channel_chat_id = :chatId
        ORDER BY tc.priority DESC
    """)
    Flux<TriggerCondition> findByChatConfigChannelChatIdOrderByPriorityDesc(@Param("chatId") Long chatId);

    @Query("""
        SELECT tc.* FROM bot.trigger_conditions tc
        JOIN bot.chat_configs cc ON tc.chat_config_id = cc.id
        WHERE cc.channel_chat_id = :chatId AND tc.active = :active
        ORDER BY tc.priority DESC
    """)
    Flux<TriggerCondition> findByChatConfigChannelChatIdAndActiveOrderByPriorityDesc(
            @Param("chatId") Long chatId, @Param("active") boolean active);

    @Query("""
        SELECT tc.* FROM bot.trigger_conditions tc
        JOIN bot.chat_configs cc ON tc.chat_config_id = cc.id
        WHERE cc.channel_chat_id = :chatId AND tc.trigger_type = :triggerType AND tc.active = :active
    """)
    Flux<TriggerCondition> findByChatConfigChannelChatIdAndTriggerTypeAndActive(
            @Param("chatId") Long chatId, @Param("triggerType") TriggerType triggerType, @Param("active") boolean active);

    @Query("""
        SELECT COUNT(tc.id) FROM bot.trigger_conditions tc
        JOIN bot.chat_configs cc ON tc.chat_config_id = cc.id
        WHERE cc.channel_chat_id = :chatId AND tc.active = :active
    """)
    Mono<Long> countByChatConfigChannelChatIdAndActive(@Param("chatId") Long chatId, @Param("active") boolean active);

    @Query("""
        SELECT tc.* FROM bot.trigger_conditions tc
        JOIN bot.chat_configs cc ON tc.chat_config_id = cc.id
        WHERE cc.channel_chat_id = :chatId AND tc.mention_required = true AND tc.active = true
        ORDER BY tc.priority DESC
    """)
    Flux<TriggerCondition> findMentionRequiredConditions(@Param("chatId") Long chatId);

    @Query("""
        SELECT tc.* FROM bot.trigger_conditions tc
        JOIN bot.chat_configs cc ON tc.chat_config_id = cc.id
        WHERE cc.channel_chat_id = :chatId AND LOWER(tc.keywords) LIKE LOWER(CONCAT('%', :keyword, '%')) AND tc.active = true
    """)
    Flux<TriggerCondition> findByKeywordContainingIgnoreCase(@Param("chatId") Long chatId,
                                                             @Param("keyword") String keyword);

    @Query("SELECT * FROM bot.trigger_conditions WHERE chat_config_id = :chatConfigId ORDER BY priority DESC")
    Flux<TriggerCondition> findByChatConfigIdOrderByPriorityDesc(@Param("chatConfigId") Long chatConfigId);

    @Query("SELECT * FROM bot.trigger_conditions WHERE chat_config_id = :chatConfigId AND active = :active ORDER BY priority DESC")
    Flux<TriggerCondition> findByChatConfigIdAndActiveOrderByPriorityDesc(@Param("chatConfigId") Long chatConfigId, @Param("active") boolean active);

    @Query("SELECT * FROM bot.trigger_conditions WHERE chat_config_id = :chatConfigId AND condition_name = :conditionName")
    Mono<TriggerCondition> findByChatConfigIdAndConditionName(@Param("chatConfigId") Long chatConfigId, @Param("conditionName") String conditionName);

    @Query("DELETE FROM bot.trigger_conditions WHERE chat_config_id = :chatConfigId")
    Mono<Void> deleteByChatConfigId(@Param("chatConfigId") Long chatConfigId);

    @Modifying
    @Query("UPDATE bot.trigger_conditions SET active = NOT active WHERE id = :conditionId")
    Mono<Integer> toggleActiveStatus(@Param("conditionId") Long conditionId);
}
