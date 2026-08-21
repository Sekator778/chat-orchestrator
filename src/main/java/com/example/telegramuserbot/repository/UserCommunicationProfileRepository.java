package com.example.telegramuserbot.repository;

import com.example.telegramuserbot.domain.UserCommunicationProfile;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Map;

@Repository
public interface UserCommunicationProfileRepository extends R2dbcRepository<UserCommunicationProfile, Long> {

    Mono<UserCommunicationProfile> findByUserId(Long userId);

    @Query("SELECT ucp.* FROM user_communication_profiles ucp JOIN users u ON ucp.user_id = u.id WHERE u.telegram_user_id = :telegramUserId")
    Mono<UserCommunicationProfile> findByTelegramUserId(@Param("telegramUserId") Long telegramUserId);

    @Query("SELECT * FROM user_communication_profiles WHERE last_updated_at < :cutoffTime AND message_sample_count > 0 ORDER BY last_updated_at ASC")
    Flux<UserCommunicationProfile> findStaleProfiles(@Param("cutoffTime") LocalDateTime cutoffTime);

    @Query("SELECT * FROM user_communication_profiles WHERE message_sample_count >= :minSamples AND confidence_score >= :minConfidence")
    Flux<UserCommunicationProfile> findReliableProfiles(
            @Param("minSamples") Long minSamples,
            @Param("minConfidence") Double minConfidence);

    @Query("SELECT * FROM user_communication_profiles WHERE formality_level BETWEEN :minFormality AND :maxFormality")
    Flux<UserCommunicationProfile> findByFormalityRange(
            @Param("minFormality") Integer minFormality,
            @Param("maxFormality") Integer maxFormality);

    @Query("""
            SELECT * FROM user_communication_profiles
            WHERE (:usesSlang IS NULL OR uses_slang = :usesSlang)
            AND (:usesAbbreviations IS NULL OR uses_abbreviations = :usesAbbreviations)
            AND (:humorAppreciation IS NULL OR humor_appreciation = :humorAppreciation)
            """)
    Flux<UserCommunicationProfile> findByPatterns(
            @Param("usesSlang") Boolean usesSlang,
            @Param("usesAbbreviations") Boolean usesAbbreviations,
            @Param("humorAppreciation") Boolean humorAppreciation);

    @Query("""
            SELECT
                AVG(avg_message_length) as avg_length,
                AVG(formality_level) as avg_formality,
                AVG(emotional_expressiveness) as avg_emotional,
                AVG(confidence_score) as avg_confidence
            FROM user_communication_profiles
            WHERE message_sample_count >= :minSamples
            """)
    Mono<Map<String, Object>> getProfileStatistics(@Param("minSamples") Long minSamples);

    @Query("""
            SELECT
                CASE
                    WHEN confidence_score >= 0.8 THEN 'HIGH'
                    WHEN confidence_score >= 0.5 THEN 'MEDIUM'
                    WHEN confidence_score >= 0.2 THEN 'LOW'
                    ELSE 'MINIMAL'
                END as confidence_level,
                COUNT(*) as count
            FROM user_communication_profiles
            GROUP BY confidence_level
            """)
    Flux<Map<String, Object>> getConfidenceDistribution();
}