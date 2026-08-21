package com.example.telegramuserbot.service.orchestration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Простая телеметрия LLM-пайплайна: логируем старт/успех/ошибку.
 * При необходимости здесь можно подключить метрики/трейсинг.
 */
@Component
public class TelemetryRecorder {

    private static final Logger log = LoggerFactory.getLogger(TelemetryRecorder.class);

    public Mono<Void> recordStart(long chatId, String pipeline, String botId) {
        log.debug("[Chat {}] Start pipeline={} botId={}" , chatId, pipeline, botId);
        return Mono.empty();
    }

    public Mono<Void> recordSuccess(long chatId, String pipeline, String botId) {
        log.debug("[Chat {}] Success pipeline={} botId={}", chatId, pipeline, botId);
        return Mono.empty();
    }

    public Mono<Void> recordFailure(long chatId, String pipeline, String reason) {
        log.warn("[Chat {}] Failure pipeline={}, reason={}", chatId, pipeline, reason);
        return Mono.empty();
    }
}
