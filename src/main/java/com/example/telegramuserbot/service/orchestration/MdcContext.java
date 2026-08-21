package com.example.telegramuserbot.service.orchestration;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Утилита для безопасной работы с MDC в реактивных цепочках.
 * Создаёт traceId и прокидывает chatId/messageId/pipeline, чистит MDC после завершения.
 */
@Component
public class MdcContext {

    public <T> Mono<T> withTrace(long chatId, long messageId, String pipeline, Mono<T> flow) {
        return Mono.deferContextual(ctxView -> {
                    String traceId = ctxView.getOrDefault("traceId", UUID.randomUUID().toString());
                    Map<String, String> mdcMap = new HashMap<>();
                    mdcMap.put("traceId", traceId);
                    mdcMap.put("chatId", String.valueOf(chatId));
                    mdcMap.put("messageId", String.valueOf(messageId));
                    mdcMap.put("pipeline", pipeline);

                    mdcMap.forEach(MDC::put);
                    return flow.doFinally(signal -> mdcMap.keySet().forEach(MDC::remove));
                })
                .contextWrite(ctx -> ctx.put("traceId", ctx.getOrDefault("traceId", UUID.randomUUID().toString())))
                .doOnError(e -> MDC.clear());
    }
}
