package com.example.telegramuserbot.service.processing;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    // Используем Guava Cache для автоматического удаления старых записей (TTL)
    private final Cache<String, Boolean> processedMessages = CacheBuilder.newBuilder()
            .maximumSize(100_000)                   // Ограничиваем размер кеша при N-персонах
            .expireAfterWrite(15, TimeUnit.MINUTES) // Храним ключ 15 минут
            .build();

    /**
     * Проверяет, обрабатывается ли уже сообщение с таким ключом.
     * Если нет, "ставит замок" и возвращает true.
     * Если да, возвращает false.
     *
     * @param idempotencyKey Уникальный ключ операции (например, "chatId:messageId")
     * @return true, если обработку можно начинать, false - если это дубликат.
     */
    public boolean checkAndSet(String idempotencyKey) {
        // Atomic claim: putIfAbsent on the backing map closes the check-then-act
        // race when two persona clients ingest the same physical message on
        // different threads within milliseconds.
        Boolean previous = processedMessages.asMap().putIfAbsent(idempotencyKey, Boolean.TRUE);
        if (previous != null) {
            log.debug("Idempotency check failed: key '{}' is already being processed.", idempotencyKey);
            return false; // Это дубликат
        }
        log.debug("Idempotency check passed: key '{}' is new.", idempotencyKey);
        return true;
    }
}