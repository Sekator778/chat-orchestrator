package com.example.telegramuserbot.service.orchestration;

import com.example.telegramuserbot.domain.ResponseTemplate;
import com.example.telegramuserbot.domain.ResponseTone;
import com.example.telegramuserbot.domain.LlmQueryPhase;
import com.example.telegramuserbot.dto.ResponsePayload;
import com.example.telegramuserbot.service.llm.dto.ApiMessage;
import com.example.telegramuserbot.service.llm.conversation.LlmSpeakerContext;
import com.example.telegramuserbot.service.orchestration.dto.EnhancedPromptRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class ConciseResponseHandler {

    private static final Logger log = LoggerFactory.getLogger(ConciseResponseHandler.class);

    private final PromptBuilder promptBuilder;
    private final ResponsePostProcessor responsePostProcessor;
    private final LlmCallService llmCallService;
    private final PendingResponseCoordinator pendingResponseCoordinator;
    private final LlmTrackingFacade trackingFacade;

    public ConciseResponseHandler(PromptBuilder promptBuilder,
                                  ResponsePostProcessor responsePostProcessor,
                                  LlmCallService llmCallService,
                                  PendingResponseCoordinator pendingResponseCoordinator,
                                  LlmTrackingFacade trackingFacade) {
        this.promptBuilder = promptBuilder;
        this.responsePostProcessor = responsePostProcessor;
        this.llmCallService = llmCallService;
        this.pendingResponseCoordinator = pendingResponseCoordinator;
        this.trackingFacade = trackingFacade;
    }

    public Mono<ResponsePayload> handle(long chatId, long triggeringMessageId, String rawText, BotContextResolver.ResolvedConfig cfg) {
        ResponseTemplate template = cfg.template();
        ResponseTone tone = Optional.ofNullable(template).map(ResponseTemplate::getResponseTone).orElse(ResponseTone.NEUTRAL);

        LlmSpeakerContext speakers = new LlmSpeakerContext(
                cfg != null ? cfg.botInstanceId() : null,
                null,
                List.of()
        );

        EnhancedPromptRequest promptRequest = EnhancedPromptRequest.builder()
                .template(template)
                .chatConfig(cfg.config())
                .rateLimits(cfg.rateLimits())
                .llmParameters(cfg.llmParameters())
                .fallbackPrompt("Respond concisely and naturally.")
                .fallbackLanguage(cfg.config() != null ? cfg.config().getLanguage() : "auto")
                .speakerContext(speakers)
                .build();

        List<ApiMessage> messages = new ArrayList<>();
        messages.add(new ApiMessage("system", promptBuilder.buildEnhancedPrompt(promptRequest)));

        if (rawText != null && !rawText.isBlank()) {
            messages.add(new ApiMessage("user", rawText));
        }

        // No user turn (text-less trigger) → stay silent instead of letting the LLM emit a
        // bare "Ок." from the system prompt alone. Normal text replies are unaffected.
        if (messages.stream().noneMatch(m -> "user".equals(m.role()))) {
            log.warn("[Chat {}] CONCISE: нет реплики пользователя (медиа без текста) — молчим, не filler", chatId);
            return Mono.empty();
        }

        return trackingFacade.start(chatId, triggeringMessageId, rawText, "CONCISE", cfg)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(tracker -> generate(chatId, triggeringMessageId, rawText, cfg, template, tone, messages, tracker.orElse(null)))
                .doOnSubscribe(sub -> log.debug("[Chat {}] Используем краткий пайплайн (CONCISE)", chatId));
    }

    private Mono<ResponsePayload> generate(long chatId,
                                          long triggeringMessageId,
                                          String rawText,
                                          BotContextResolver.ResolvedConfig cfg,
                                          ResponseTemplate template,
                                          ResponseTone tone,
                                          List<ApiMessage> messages,
                                          com.example.telegramuserbot.service.tracking.LlmQueryTracker tracker) {
        return llmCallService.call(
                        chatId,
                        triggeringMessageId,
                        "CONCISE",
                        messages,
                        cfg.config(),
                        cfg.llmParameters(),
                        tracker,
                        LlmQueryPhase.SINGLE_STAGE_GENERATION,
                        1,
                        Map.of("stage", "single")
                )
                .flatMap(content -> {
                    String processed = responsePostProcessor.postProcess(content, template);
                    llmCallService.logNormalizedIfChanged(chatId, "CONCISE", content, processed);
                    return pendingResponseCoordinator.maybeQueuePending(chatId, triggeringMessageId, cfg, processed, tone, "CONCISE")
                            .flatMap(queued -> {
                                Mono<Void> tracked = trackingFacade.markCompletedOrSkip(tracker, processed, queued);
                                if (queued) {
                                    return tracked.then(Mono.empty());
                                }
                                return tracked.thenReturn(ResponsePayload.ofConcise(processed, tone));
                            });
                });
    }
}
