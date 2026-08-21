package com.example.telegramuserbot.repository;

import com.example.telegramuserbot.domain.CommunicationStyle;
import com.example.telegramuserbot.domain.ResponseIntent;
import com.example.telegramuserbot.domain.ResponseLength;
import com.example.telegramuserbot.domain.ResponseVariation;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Repository
public interface ResponseVariationRepository extends R2dbcRepository<ResponseVariation, Long> {

    Flux<ResponseVariation> findByIntentTypeAndEnabledTrue(ResponseIntent intentType);

    Flux<ResponseVariation> findByIntentTypeAndCommunicationStyleAndEnabledTrue(
            ResponseIntent intentType, CommunicationStyle communicationStyle);

    Flux<ResponseVariation> findByIntentTypeAndCommunicationStyleAndResponseLengthAndEnabledTrue(
            ResponseIntent intentType, CommunicationStyle communicationStyle, ResponseLength responseLength);

    @Query("""
            SELECT * FROM response_variations
            WHERE intent_type = :intentType
            AND enabled = true
            AND (communication_style = :style OR communication_style IS NULL)
            AND (response_length = :length OR response_length IS NULL)
            AND (formality_level IS NULL OR formality_level = :formalityLevel)
            ORDER BY weight DESC, usage_count ASC, RANDOM()
            """)
    Flux<ResponseVariation> findSuitableVariations(
            @Param("intentType") ResponseIntent intentType,
            @Param("style") CommunicationStyle style,
            @Param("length") ResponseLength length,
            @Param("formalityLevel") Integer formalityLevel);

    @Query("SELECT * FROM response_variations WHERE intent_type = :intentType AND last_used_at > :cutoffTime ORDER BY last_used_at DESC")
    Flux<ResponseVariation> findRecentlyUsedVariations(
            @Param("intentType") ResponseIntent intentType,
            @Param("cutoffTime") LocalDateTime cutoffTime);

    @Query("SELECT * FROM response_variations WHERE intent_type = :intentType AND enabled = true ORDER BY usage_count ASC, last_used_at ASC NULLS FIRST")
    Flux<ResponseVariation> findLeastUsedVariations(@Param("intentType") ResponseIntent intentType);

    Flux<ResponseVariation> findByEmotionalToneAndEnabledTrue(String emotionalTone);

    Mono<Long> countByIntentTypeAndEnabledTrue(ResponseIntent intentType);

    Flux<ResponseVariation> findByRequiresContextTrueAndEnabledTrue();

    @Query("SELECT * FROM response_variations WHERE intent_type = :intentType AND enabled = true AND weight >= :minWeight ORDER BY weight DESC")
    Flux<ResponseVariation> findHighWeightVariations(
            @Param("intentType") ResponseIntent intentType,
            @Param("minWeight") Integer minWeight);
}