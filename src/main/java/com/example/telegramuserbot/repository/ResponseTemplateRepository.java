package com.example.telegramuserbot.repository;

import com.example.telegramuserbot.domain.ResponseStyle;
import com.example.telegramuserbot.domain.ResponseTemplate;
import com.example.telegramuserbot.domain.ResponseTone;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ResponseTemplateRepository extends R2dbcRepository<ResponseTemplate, Long> {

    @Query("SELECT * FROM bot.response_templates WHERE chat_config_id = :chatConfigId AND active = true")
    Flux<ResponseTemplate> findByChatConfigIdAndActiveTrue(@Param("chatConfigId") Long chatConfigId);

    @Query("SELECT * FROM bot.response_templates WHERE chat_config_id = :chatConfigId")
    Flux<ResponseTemplate> findByChatConfigId(@Param("chatConfigId") Long chatConfigId);

    @Query("SELECT * FROM bot.response_templates WHERE chat_config_id = :chatConfigId AND is_default = true AND active = true")
    Mono<ResponseTemplate> findByChatConfigIdAndIsDefaultTrueAndActiveTrue(@Param("chatConfigId") Long chatConfigId);

    @Query("SELECT * FROM bot.response_templates WHERE chat_config_id = :chatConfigId AND response_style = :responseStyle AND response_tone = :responseTone AND active = true")
    Flux<ResponseTemplate> findByChatConfigIdAndResponseStyleAndResponseToneAndActiveTrue(
            @Param("chatConfigId") Long chatConfigId,
            @Param("responseStyle") ResponseStyle responseStyle,
            @Param("responseTone") ResponseTone responseTone
    );

    @Query("""
            SELECT * FROM bot.response_templates
             WHERE chat_config_id = :chatConfigId
               AND active = true
             ORDER BY is_default DESC, priority DESC, id ASC
            """)
    Flux<ResponseTemplate> findByChatConfigIdAndActiveTrueOrderByPriorityDesc(@Param("chatConfigId") Long chatConfigId);

    @Query("SELECT COUNT(*) FROM bot.response_templates WHERE chat_config_id = :chatConfigId AND active = true")
    Mono<Integer> countByChatConfigIdAndActiveTrue(@Param("chatConfigId") Long chatConfigId);

    @Query("DELETE FROM bot.response_templates WHERE chat_config_id = :chatConfigId")
    Mono<Void> deleteByChatConfigId(@Param("chatConfigId") Long chatConfigId);

    @Modifying
    @Query("UPDATE response_templates SET is_default = false WHERE chat_config_id = :chatConfigId")
    Mono<Integer> resetDefaultTemplates(@Param("chatConfigId") Long chatConfigId);

    @Modifying
    @Query("UPDATE response_templates SET is_default = (id = :templateId) WHERE chat_config_id = :chatConfigId")
    Mono<Integer> setDefaultTemplate(@Param("templateId") Long templateId, @Param("chatConfigId") Long chatConfigId);

    @Modifying
    @Query("UPDATE response_templates SET active = NOT active WHERE id = :templateId")
    Mono<Integer> toggleActiveStatus(@Param("templateId") Long templateId);
}
