package com.example.telegramuserbot.repository;

import com.example.telegramuserbot.domain.LlmParameters;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Mono;

public interface LlmParametersRepository extends R2dbcRepository<LlmParameters, Long> {

    /**
     * Find LLM parameters by chat configuration ID
     */
    @Query("SELECT * FROM bot.llm_parameters WHERE chat_config_id = :chatConfigId")
    Mono<LlmParameters> findByChatConfigId(@Param("chatConfigId") Long chatConfigId);

    /**
     * Check if LLM parameters exist for a chat configuration
     */
    @Query("SELECT COUNT(*) > 0 FROM bot.llm_parameters WHERE chat_config_id = :chatConfigId")
    Mono<Boolean> existsByChatConfigId(@Param("chatConfigId") Long chatConfigId);

    /**
     * Delete LLM parameters by chat configuration ID
     */
    @Query("DELETE FROM bot.llm_parameters WHERE chat_config_id = :chatConfigId")
    Mono<Void> deleteByChatConfigId(@Param("chatConfigId") Long chatConfigId);
}