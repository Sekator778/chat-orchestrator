package com.example.telegramuserbot.repository;

import com.example.telegramuserbot.domain.User;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.data.repository.query.Param;
import reactor.core.publisher.Mono;

import java.time.Instant;

public interface UserRepository extends R2dbcRepository<User, Long> {
    
    Mono<User> findByTelegramUserId(Long telegramUserId);
    
    @Modifying
    @Query("UPDATE users SET last_interaction_at = :interactionTime WHERE telegram_user_id = :telegramUserId")
    Mono<Integer> updateLastInteractionTime(@Param("telegramUserId") Long telegramUserId, 
                                  @Param("interactionTime") Instant interactionTime);
    
    @Query("SELECT COUNT(u) > 0 FROM users u WHERE u.telegram_user_id = :telegramUserId AND u.ai_enabled = true")
    Mono<Boolean> isAiEnabledForUser(@Param("telegramUserId") Long telegramUserId);
}