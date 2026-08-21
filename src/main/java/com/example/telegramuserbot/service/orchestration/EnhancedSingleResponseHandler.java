package com.example.telegramuserbot.service.orchestration;

import com.example.telegramuserbot.domain.ResponseStyle;
import com.example.telegramuserbot.domain.ResponseTemplate;
import com.example.telegramuserbot.domain.ResponseTone;
import com.example.telegramuserbot.domain.LlmQueryPhase;
import com.example.telegramuserbot.domain.LlmQueryStatus;
import com.example.telegramuserbot.dto.ResponsePayload;
import com.example.telegramuserbot.service.llm.EnhancedLlmService;
import com.example.telegramuserbot.service.llm.dto.ApiMessage;
import com.example.telegramuserbot.service.orchestration.dto.ResponseDirectives;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class EnhancedSingleResponseHandler {

    private static final Logger log = LoggerFactory.getLogger(EnhancedSingleResponseHandler.class);

    private final ContextCollector contextCollector;
    private final LlmMessageBuilder llmMessageBuilder;
    private final LlmCallService llmCallService;
    private final SearchAugmentor searchAugmentor;
    private final ResponsePostProcessor responsePostProcessor;
    private final ResponseMapper responseMapper;
    private final PendingResponseCoordinator pendingResponseCoordinator;
    private final LlmTrackingFacade trackingFacade;

    public EnhancedSingleResponseHandler(ContextCollector contextCollector,
                                        LlmMessageBuilder llmMessageBuilder,
                                        LlmCallService llmCallService,
                                        SearchAugmentor searchAugmentor,
                                        ResponsePostProcessor responsePostProcessor,
                                        ResponseMapper responseMapper,
                                        PendingResponseCoordinator pendingResponseCoordinator,
                                        LlmTrackingFacade trackingFacade) {
        this.contextCollector = contextCollector;
        this.llmMessageBuilder = llmMessageBuilder;
        this.llmCallService = llmCallService;
        this.searchAugmentor = searchAugmentor;
        this.responsePostProcessor = responsePostProcessor;
        this.responseMapper = responseMapper;
        this.pendingResponseCoordinator = pendingResponseCoordinator;
        this.trackingFacade = trackingFacade;
    }

    /**
     * Handle with no directives (byte-identical current behavior; delegates).
     */
    public Mono<ResponsePayload> handle(long chatId, long triggeringMessageId, String rawText, BotContextResolver.ResolvedConfig cfg) {
        return handle(chatId, triggeringMessageId, rawText, cfg, null);
    }

    /**
     * Handle with optional shaping directives from the decision engine.
     * When directives is null the output is byte-identical to the 4-arg overload.
     */
    public Mono<ResponsePayload> handle(long chatId, long triggeringMessageId, String rawText, BotContextResolver.ResolvedConfig cfg, ResponseDirectives directives) {
        ResponseTemplate template = cfg.template();
        return contextCollector.collectForBot(chatId, triggeringMessageId, cfg.botInstanceId())
                .flatMap(context -> trackingFacade.start(chatId, triggeringMessageId, rawText, "ENHANCED", cfg)
                        .map(Optional::of)
                        .defaultIfEmpty(Optional.empty())
                        .flatMap(tracker -> generate(chatId, triggeringMessageId, rawText, cfg, template, context, tracker.orElse(null), directives)));
    }

    private Mono<ResponsePayload> generate(long chatId,
                                          long triggeringMessageId,
                                          String rawText,
                                          BotContextResolver.ResolvedConfig cfg,
                                          ResponseTemplate template,
                                          ContextCollector.ConversationContext context,
                                          com.example.telegramuserbot.service.tracking.LlmQueryTracker tracker,
                                          ResponseDirectives directives) {
        return llmMessageBuilder.buildApiMessagesWithSystem(chatId, context, cfg, "ENHANCED", directives)
                .flatMap(apiMessages -> {
                    // Net behind the caption fix: if no user turn survived (a truly text-less
                    // media post), stay silent — a human who has nothing to say says nothing,
                    // never a one-word "Ок." echoed from prior assistant fillers.
                    if (apiMessages.stream().noneMatch(m -> "user".equals(m.role()))) {
                        return skipSilently(chatId, tracker, "нет реплики пользователя (медиа без текста) — молчим, не filler");
                    }
                    return callEnhanced(chatId, triggeringMessageId, rawText, cfg, template, context, apiMessages, tracker);
                });
    }

    private Mono<ResponsePayload> callEnhanced(long chatId,
                                              long triggeringMessageId,
                                              String rawText,
                                              BotContextResolver.ResolvedConfig cfg,
                                              ResponseTemplate template,
                                              ContextCollector.ConversationContext context,
                                              List<ApiMessage> apiMessages,
                                              com.example.telegramuserbot.service.tracking.LlmQueryTracker tracker) {
        return llmCallService.call(chatId, triggeringMessageId, "ENHANCED", apiMessages, cfg.config(), cfg.llmParameters(),
                        tracker, LlmQueryPhase.SINGLE_STAGE_GENERATION, 1, Map.of("stage", "single"))
                .flatMap(raw -> searchAugmentor.augmentIfNeeded(raw, rawText != null ? rawText : "", chatId))
                .map(content -> {
                    String processed = responsePostProcessor.postProcess(content, template);
                    llmCallService.logNormalizedIfChanged(chatId, "ENHANCED", content, processed);
                    return responseMapper.mapEnhanced(
                            new EnhancedLlmService.EnhancedLlmResponse(
                                    processed,
                                    content,
                                    Optional.ofNullable(template).map(ResponseTemplate::getResponseStyle).orElse(ResponseStyle.ADAPTIVE),
                                    Optional.ofNullable(template).map(ResponseTemplate::getResponseTone).orElse(ResponseTone.NEUTRAL),
                                    context.totalMessages(),
                                    context.totalCharacters(),
                                    EnhancedLlmService.ResponseFormat.TEXT,
                                    template
                            ),
                            context.totalMessages(),
                            context.totalCharacters());
                })
                .doOnSubscribe(sub -> log.debug("[Chat {}] Используем расширенный пайплайн (ENHANCED)", chatId))
                .switchIfEmpty(Mono.defer(() -> skipSilently(chatId, tracker, "ENHANCED цепочка вернула empty")))
                .flatMap(resp -> {
                    if (resp == null || resp.content() == null || resp.content().isBlank()) {
                        return skipSilently(chatId, tracker, "ENHANCED вернул пустой ответ");
                    }
                    return Mono.just(resp);
                })
                .flatMap(payload -> pendingResponseCoordinator.maybeQueuePending(chatId, triggeringMessageId, cfg, payload.content(), payload.tone(), "ENHANCED")
                        .flatMap(queued -> {
                            Mono<Void> tracked = trackingFacade.markCompletedOrSkip(tracker, payload.content(), queued);
                            if (queued) {
                                return tracked.then(Mono.empty());
                            }
                            return tracked.thenReturn(payload);
                        }));
    }

    /**
     * Человек, которому нечего сказать, молчит — никакой шаблонной фразы в чат.
     * Запрос помечается SKIPPED, сообщение не отправляется.
     */
    private Mono<ResponsePayload> skipSilently(long chatId,
                                              com.example.telegramuserbot.service.tracking.LlmQueryTracker tracker,
                                              String reason) {
        log.warn("[Chat {}] {} — пропускаем отправку без fallback-фразы", chatId, reason);
        if (tracker == null) {
            return Mono.empty();
        }
        return tracker.markCompleted(LlmQueryStatus.SKIPPED, null, reason, null, false)
                .onErrorResume(e -> Mono.empty())
                .then(Mono.empty());
    }
}
