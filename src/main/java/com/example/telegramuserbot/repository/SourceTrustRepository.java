package com.example.telegramuserbot.repository;

import com.example.telegramuserbot.domain.SourceTrust;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Repository for managing source trust scores.
 */
public interface SourceTrustRepository extends R2dbcRepository<SourceTrust, Long> {

    /**
     * Finds trust score for a specific channel.
     */
    @Query("SELECT * FROM tgscan.source_trust WHERE channel_id = :channelId")
    Mono<SourceTrust> findByChannelId(@Param("channelId") Long channelId);

    /**
     * Gets trust score for a channel, returning default value if not found.
     */
    @Query("""
            SELECT COALESCE(
                (SELECT trust_score FROM tgscan.source_trust WHERE channel_id = :channelId),
                0.5
            )
            """)
    Mono<Double> getTrustScoreOrDefault(@Param("channelId") Long channelId);

    /**
     * Finds all official sources.
     */
    @Query("SELECT * FROM tgscan.source_trust WHERE is_official = true ORDER BY trust_score DESC")
    Flux<SourceTrust> findAllOfficial();

    /**
     * Finds sources by category.
     */
    @Query("SELECT * FROM tgscan.source_trust WHERE category = :category ORDER BY trust_score DESC")
    Flux<SourceTrust> findByCategory(@Param("category") String category);

    /**
     * Finds high-trust sources above a threshold.
     */
    @Query("SELECT * FROM tgscan.source_trust WHERE trust_score >= :minScore ORDER BY trust_score DESC")
    Flux<SourceTrust> findHighTrustSources(@Param("minScore") Double minScore);

    /**
     * Updates trust score for a channel.
     */
    @Modifying
    @Query("""
            UPDATE tgscan.source_trust
            SET trust_score = :score, last_updated = NOW()
            WHERE channel_id = :channelId
            """)
    Mono<Integer> updateTrustScore(@Param("channelId") Long channelId, @Param("score") Double score);

    /**
     * Upserts trust score for a channel.
     */
    @Modifying
    @Query("""
            INSERT INTO tgscan.source_trust (channel_id, trust_score, category, created_at, last_updated)
            VALUES (:channelId, :score, :category, NOW(), NOW())
            ON CONFLICT (channel_id) DO UPDATE
            SET trust_score = EXCLUDED.trust_score,
                category = COALESCE(EXCLUDED.category, source_trust.category),
                last_updated = NOW()
            WHERE NOT source_trust.manual_override
            """)
    Mono<Integer> upsertTrustScore(
            @Param("channelId") Long channelId,
            @Param("score") Double score,
            @Param("category") String category
    );
}
