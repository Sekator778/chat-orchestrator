package com.example.telegramuserbot.service.orchestration;

import com.example.telegramuserbot.domain.LlmQueryPhase;
import com.example.telegramuserbot.domain.LlmQueryStatus;
import com.example.telegramuserbot.service.tracking.LlmQueryTracker;
import com.example.telegramuserbot.service.tracking.LlmQueryTrackingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Component
public class LlmTrackingFacade {

    private final LlmQueryTrackingService trackingService;

    @Value("${llm.tracking.enabled:true}")
    private boolean trackingEnabled;

    public LlmTrackingFacade(LlmQueryTrackingService trackingService) {
        this.trackingService = trackingService;
    }

    public Mono<LlmQueryTracker> start(long chatId,
                                       long triggeringMessageId,
                                       String triggerExcerpt,
                                       String pipeline,
                                       BotContextResolver.ResolvedConfig cfg) {
        if (!trackingEnabled) {
            return Mono.empty();
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("pipeline", pipeline);
        metadata.put("botInstanceId", cfg != null ? cfg.botInstanceId() : null);
        metadata.put("chatConfigId", cfg != null && cfg.config() != null ? cfg.config().getId() : null);
        metadata.put("templateId", cfg != null && cfg.template() != null ? cfg.template().getId() : null);
        metadata.put("responseStyle", cfg != null && cfg.template() != null && cfg.template().getResponseStyle() != null ? cfg.template().getResponseStyle().name() : null);
        metadata.put("responseTone", cfg != null && cfg.template() != null && cfg.template().getResponseTone() != null ? cfg.template().getResponseTone().name() : null);
        metadata.put("multiStageEnabled", cfg != null && cfg.config() != null && cfg.config().isMultiStageEnabled());
        metadata.put("modelName", cfg != null && cfg.llmParameters() != null ? cfg.llmParameters().getModelName() : null);

        String excerpt = Optional.ofNullable(triggerExcerpt)
                .map(String::strip)
                .filter(s -> !s.isBlank())
                .map(s -> s.length() > 300 ? s.substring(0, 300) : s)
                .orElse(null);

        return trackingService.startTracking(new LlmQueryTrackingService.LlmQueryStartParams(
                        chatId,
                        triggeringMessageId,
                        Instant.now(),
                        null,
                        null,
                        null,
                        excerpt,
                        metadata
                ))
                .flatMap(tracker -> tracker.recordDecision(true, "reply", pipeline, null, null).thenReturn(tracker))
                .onErrorResume(e -> Mono.empty());
    }

    public Mono<Void> markCompletedOrSkip(LlmQueryTracker tracker, String finalResponse, boolean queued) {
        if (tracker == null) {
            return Mono.empty();
        }
        if (queued) {
            return tracker.recordPhase(LlmQueryPhase.FINAL_DELIVERY, 1, java.util.List.of(), finalResponse, Map.of("direction", "pending"))
                    .then(tracker.markCompleted(LlmQueryStatus.COMPLETED, finalResponse, null, null, true))
                    .onErrorResume(e -> Mono.empty());
        }
        return tracker.recordFinalDelivery(finalResponse)
                .then(tracker.markCompleted(LlmQueryStatus.COMPLETED, finalResponse, null, null, true))
                .onErrorResume(e -> Mono.empty());
    }
}
