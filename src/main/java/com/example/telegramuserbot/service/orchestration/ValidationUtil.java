package com.example.telegramuserbot.service.orchestration;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Лёгкие валидации входных данных для оркестратора. Реактивный API для консистентности.
 */
@Component
public class ValidationUtil {

    public Mono<Long> requireChatId(Long chatId) {
        if (chatId == null) {
            return Mono.error(new IllegalArgumentException("chatId is required"));
        }
        return Mono.just(chatId);
    }

    public Mono<Long> requireMessageId(Long messageId) {
        if (messageId == null) {
            return Mono.error(new IllegalArgumentException("triggeringMessageId is required"));
        }
        return Mono.just(messageId);
    }
}

