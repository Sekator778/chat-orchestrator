package com.example.telegramuserbot.service.orchestration;

import com.example.telegramuserbot.domain.PendingResponse;
import com.example.telegramuserbot.domain.ResponseTemplate;
import com.example.telegramuserbot.domain.ResponseTone;
import com.example.telegramuserbot.service.queue.PendingResponseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class PendingResponseCoordinator {

    private static final Logger log = LoggerFactory.getLogger(PendingResponseCoordinator.class);

    private final PendingResponseService pendingResponseService;

    @Value("${llm.pending-context.max-items:3}")
    private int maxPendingContextItems;

    public PendingResponseCoordinator(PendingResponseService pendingResponseService) {
        this.pendingResponseService = pendingResponseService;
    }

    public Mono<List<PendingResponse>> loadPendingContext(long chatId, Long beforeMessageId, String botInstanceId) {
        return pendingResponseService.findActiveForChatBeforeMessage(
                chatId,
                beforeMessageId,
                Math.max(0, maxPendingContextItems),
                botInstanceId
        ).defaultIfEmpty(List.of());
    }

    public Mono<Boolean> maybeQueuePending(long chatId,
                                          long triggeringMessageId,
                                          BotContextResolver.ResolvedConfig cfg,
                                          String preparedContent,
                                          ResponseTone tone,
                                          String responseIntent) {
        int requiredDelta = cfg.config() != null && cfg.config().getWaitForHumanRepliesCount() != null
                ? cfg.config().getWaitForHumanRepliesCount()
                : -1;
        int delaySeconds = cfg.rateLimits() != null && cfg.rateLimits().getPendingResponseDelaySeconds() != null
                ? cfg.rateLimits().getPendingResponseDelaySeconds()
                : 0;

        if (requiredDelta < 0) {
            return Mono.just(false);
        }

        delaySeconds = Math.max(0, delaySeconds);
        boolean shouldQueue = requiredDelta > 0 || delaySeconds > 0;
        if (!shouldQueue) {
            return Mono.just(false);
        }

        String botInstanceId = cfg != null ? cfg.botInstanceId() : null;
        if (botInstanceId == null || botInstanceId.isBlank()) {
            log.warn("[Chat {}] Очередь: отсутствует botInstanceId, пропускаем enqueue pending", chatId);
            return Mono.just(false);
        }

        Instant eligibleAt;
        if (delaySeconds > 0) {
            long jitter = ThreadLocalRandom.current().nextLong(-(long)(delaySeconds * 0.50), (long)(delaySeconds * 0.50));
            long botOffset = Math.abs(botInstanceId.hashCode() % (delaySeconds / 2));
            eligibleAt = Instant.now().plusSeconds(delaySeconds + jitter + botOffset);
        } else {
            eligibleAt = Instant.now();
        }

        String responseLength = Optional.ofNullable(cfg.template())
                .map(ResponseTemplate::getResponseStyle)
                .map(Enum::name)
                .orElse(null);
        String toneName = tone != null ? tone.name() : null;

        return pendingResponseService.enqueue(
                        chatId,
                        triggeringMessageId,
                        botInstanceId,
                        preparedContent,
                        responseIntent,
                        toneName,
                        responseLength,
                        requiredDelta,
                        eligibleAt)
                .doOnSuccess(p -> log.info("[Chat {}] Очередь: откладываем ответ (pending id={}, requiredDelta={}, eligibleAt={})",
                        chatId, p.getId(), requiredDelta, eligibleAt))
                .doOnError(err -> log.error("[Chat {}] Очередь: не удалось сохранить отложенный ответ: {}", chatId, err.getMessage(), err))
                .map(p -> true)
                .onErrorReturn(false);
    }
}
