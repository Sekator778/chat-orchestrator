package com.example.telegramuserbot.service.orchestration;

import com.example.telegramuserbot.domain.*;
import com.example.telegramuserbot.dto.ResponsePayload;
import com.example.telegramuserbot.service.decision.ResponseDecisionEngine;
import com.example.telegramuserbot.service.llm.EnhancedLlmService;
import com.example.telegramuserbot.service.orchestration.dto.ResponseDirectives;
import com.example.telegramuserbot.service.llm.conversation.ConversationFormatter;
import com.example.telegramuserbot.service.llm.conversation.LlmSpeakerContext;
import com.example.telegramuserbot.service.llm.dto.ApiMessage;
import com.example.telegramuserbot.service.llm.dto.DeepSeekChatRequest;
import com.example.telegramuserbot.service.orchestration.dto.EnhancedPromptRequest;
import com.example.telegramuserbot.service.queue.PendingResponseService;
import com.example.telegramuserbot.service.UserService;
import com.example.telegramuserbot.service.ratelimit.ResponseRateLimitGate;
import com.example.telegramuserbot.service.telegram.TelegramSelfUserIdResolver;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Единый фасад генерации ответа. Минимальная бизнес-логика: сбор контекста, вызов LLM, постобработка, телеметрия.
 */
@Service
public class ResponseOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ResponseOrchestrator.class);
    private static final Logger llmPayloadLog = LoggerFactory.getLogger("llm.payload");

    private final BotContextResolver botContextResolver;
    private final ContextCollector contextCollector;
    private final PromptBuilder promptBuilder;
    private final ConversationFormatter conversationFormatter;
    private final ResponsePostProcessor responsePostProcessor;
    private final LLMCaller llmCaller;
    private final ResponseRateLimitGate rateLimitGate;
    private final TelemetryRecorder telemetryRecorder;
    private final SearchAugmentor searchAugmentor;
    private final ValidationUtil validationUtil;
    private final ResponseMapper responseMapper;
    private final MdcContext mdcContext;
    private final UserService userService;
    private final PendingResponseService pendingResponseService;
    private final TelegramSelfUserIdResolver selfUserIdResolver;
    private final ConciseResponseHandler conciseResponseHandler;
    private final EnhancedSingleResponseHandler enhancedSingleResponseHandler;
    private final MultiStageResponseHandler multiStageResponseHandler;

    @Value("${llm.logging.payload.enabled:true}")
    private boolean payloadLoggingEnabled;

    @Value("${llm.logging.payload.max-chars:60000}")
    private int payloadMaxChars;

    @Value("${llm.pending-context.max-items:3}")
    private int maxPendingContextItems;

    @Value("${llm.service.multiStage.enabled:true}")
    private boolean globalMultiStageEnabled;

    public ResponseOrchestrator(BotContextResolver botContextResolver,
                                ContextCollector contextCollector,
                                PromptBuilder promptBuilder,
                                ConversationFormatter conversationFormatter,
                                ResponsePostProcessor responsePostProcessor,
                                LLMCaller llmCaller,
                                ResponseRateLimitGate rateLimitGate,
                                TelemetryRecorder telemetryRecorder,
                                SearchAugmentor searchAugmentor,
                                ValidationUtil validationUtil,
                                ResponseMapper responseMapper,
                                MdcContext mdcContext,
                                UserService userService,
                                PendingResponseService pendingResponseService,
                                TelegramSelfUserIdResolver selfUserIdResolver,
                                ConciseResponseHandler conciseResponseHandler,
                                EnhancedSingleResponseHandler enhancedSingleResponseHandler,
                                MultiStageResponseHandler multiStageResponseHandler) {
        this.botContextResolver = botContextResolver;
        this.contextCollector = contextCollector;
        this.promptBuilder = promptBuilder;
        this.conversationFormatter = conversationFormatter;
        this.responsePostProcessor = responsePostProcessor;
        this.llmCaller = llmCaller;
        this.rateLimitGate = rateLimitGate;
        this.telemetryRecorder = telemetryRecorder;
        this.searchAugmentor = searchAugmentor;
        this.validationUtil = validationUtil;
        this.responseMapper = responseMapper;
        this.mdcContext = mdcContext;
        this.userService = userService;
        this.pendingResponseService = pendingResponseService;
        this.selfUserIdResolver = selfUserIdResolver;
        this.conciseResponseHandler = conciseResponseHandler;
        this.enhancedSingleResponseHandler = enhancedSingleResponseHandler;
        this.multiStageResponseHandler = multiStageResponseHandler;
    }

    public Mono<ResponsePayload> reply(long chatId, long triggeringMessageId, String rawText) {
        Mono<ResponsePayload> pipeline = validationUtil.requireChatId(chatId)
                .flatMap(validChatId -> validationUtil.requireMessageId(triggeringMessageId).thenReturn(validChatId))
                .flatMap(botContextResolver::resolve)
                .flatMap(cfg -> {
                    String botId = cfg != null ? cfg.botInstanceId() : null;
                    return rateLimitGate.tryAcquire(cfg.config(), cfg.rateLimits())
                            .flatMap(decision -> {
                                if (!decision.allowed()) {
                                    if (decision.newlyBlocked()) {
                                        return telemetryRecorder.recordFailure(chatId, "limit", decision.reason())
                                                .then(Mono.empty());
                                    }
                                    log.debug("[Chat {}] RateLimitGate denied (cached) botId={} blockedUntil={}",
                                            chatId, botId, decision.blockedUntil());
                                    return Mono.empty();
                                }
                                return telemetryRecorder.recordStart(chatId, "reply", botId)
                                        .thenReturn(new java.util.AbstractMap.SimpleEntry<>(cfg, botId));
                            });
                })
                .flatMap(entry -> {
                    BotContextResolver.ResolvedConfig configData = entry.getKey();
                    String botId = entry.getValue();
                    Mono<ResponsePayload> flow = selectHandler(chatId, triggeringMessageId, rawText, configData);

                    return flow.flatMap(resp -> {
                        if (resp == null) {
                            return Mono.empty();
                        }
                        return telemetryRecorder.recordSuccess(chatId, resp.pipeline(), botId)
                                .thenReturn(resp);
                    });
                })
                .doOnError(e -> log.error("[Chat {}] Ошибка генерации ответа: {}", chatId, e.getMessage(), e));

        return mdcContext.withTrace(chatId, triggeringMessageId, "reply", pipeline);
    }

    /**
     * Entry point when конфиг уже известен (мультиперсона).
     */
    public Mono<ResponsePayload> replyWithConfig(long chatId, long triggeringMessageId, String rawText, BotContextResolver.ResolvedConfig cfg) {
        return replyWithConfig(chatId, triggeringMessageId, rawText, cfg, false);
    }

    /**
     * Entry point when конфиг уже известен (мультиперсона), optionally skipping RateLimitGate.
     * <p>
     * Used when the caller already reserved daily quota for this message.
     */
    public Mono<ResponsePayload> replyWithConfig(long chatId,
                                                long triggeringMessageId,
                                                String rawText,
                                                BotContextResolver.ResolvedConfig cfg,
                                                boolean skipRateLimitGate) {
        return replyWithConfig(chatId, triggeringMessageId, rawText, cfg, skipRateLimitGate, null);
    }

    /**
     * Entry point when конфиг уже известен (мультиперсона), optionally skipping RateLimitGate,
     * with optional shaping directives from the decision engine.
     * <p>
     * When directives is null behavior is byte-identical to the 5-arg overload.
     * Directives are only applied when {@code bot.decision-gate.shape-replies=true}.
     *
     * @param directives nullable shaping directives from ResponseDecisionEngine.decide()
     */
    public Mono<ResponsePayload> replyWithConfig(long chatId,
                                                long triggeringMessageId,
                                                String rawText,
                                                BotContextResolver.ResolvedConfig cfg,
                                                boolean skipRateLimitGate,
                                                ResponseDirectives directives) {
        Mono<ResponsePayload> pipeline = validationUtil.requireChatId(chatId)
                .flatMap(validChatId -> validationUtil.requireMessageId(triggeringMessageId).thenReturn(validChatId))
                .flatMap(id -> {
                    if (skipRateLimitGate) {
                        return telemetryRecorder.recordStart(chatId, "reply", cfg != null ? cfg.botInstanceId() : null)
                                .thenReturn(cfg);
                    }

                    return rateLimitGate.tryAcquire(cfg.config(), cfg.rateLimits())
                            .flatMap(decision -> {
                                if (!decision.allowed()) {
                                    if (decision.newlyBlocked()) {
                                        return telemetryRecorder.recordFailure(chatId, "limit", decision.reason())
                                                .then(Mono.empty());
                                    }
                                    log.debug("[Chat {}] RateLimitGate denied (cached) botId={} blockedUntil={}",
                                            chatId, cfg != null ? cfg.botInstanceId() : null, decision.blockedUntil());
                                    return Mono.empty();
                                }
                                return telemetryRecorder.recordStart(chatId, "reply", cfg != null ? cfg.botInstanceId() : null)
                                        .thenReturn(cfg);
                            });
                })
                .flatMap(conf -> selectHandler(chatId, triggeringMessageId, rawText, conf, directives))
                .flatMap(resp -> {
                    if (resp == null) {
                        return Mono.empty();
                    }
                    return telemetryRecorder.recordSuccess(chatId, resp.pipeline(), cfg != null ? cfg.botInstanceId() : null)
                            .thenReturn(resp);
                })
                .doOnError(e -> log.error("[Chat {}] Ошибка генерации ответа: {}", chatId, e.getMessage(), e));

        return mdcContext.withTrace(chatId, triggeringMessageId, "reply", pipeline);
    }

    private Mono<ResponsePayload> runConcise(long chatId, long triggeringMessageId, String rawText, BotContextResolver.ResolvedConfig cfg) {
        return conciseResponseHandler.handle(chatId, triggeringMessageId, rawText, cfg);
    }

    private Mono<ResponsePayload> runEnhanced(long chatId, long triggeringMessageId, String rawText, BotContextResolver.ResolvedConfig cfg, ResponseDirectives directives) {
        return enhancedSingleResponseHandler.handle(chatId, triggeringMessageId, rawText, cfg, directives);
    }

    /** Selects the appropriate handler (legacy 4-arg overload, no directives). */
    private Mono<ResponsePayload> selectHandler(long chatId, long triggeringMessageId, String rawText, BotContextResolver.ResolvedConfig cfg) {
        return selectHandler(chatId, triggeringMessageId, rawText, cfg, null);
    }

    /**
     * Selects the appropriate handler and threads optional shaping directives.
     * CONCISE style bypasses shaping (directives ignored) for minimal-overhead responses.
     */
    private Mono<ResponsePayload> selectHandler(long chatId, long triggeringMessageId, String rawText, BotContextResolver.ResolvedConfig cfg, ResponseDirectives directives) {
        if (cfg.template() != null && cfg.template().getResponseStyle() == ResponseStyle.CONCISE) {
            return runConcise(chatId, triggeringMessageId, rawText, cfg);
        }
        if (globalMultiStageEnabled && cfg.config() != null && cfg.config().isMultiStageEnabled()) {
            return multiStageResponseHandler.handle(chatId, triggeringMessageId, rawText, cfg, directives);
        }
        return runEnhanced(chatId, triggeringMessageId, rawText, cfg, directives);
    }

    private Mono<List<ApiMessage>> buildApiMessagesWithSystem(long chatId, ContextCollector.ConversationContext context, BotContextResolver.ResolvedConfig cfg, String pipelineLabel) {
        String botInstanceId = cfg != null ? cfg.botInstanceId() : null;
        Mono<Long> selfUserIdMono = selfUserIdResolver.resolveSelfUserId(botInstanceId).defaultIfEmpty(0L);

        Mono<java.util.List<com.example.telegramuserbot.domain.PendingResponse>> pendingMono = pendingResponseService
                .findActiveForChatBeforeMessage(
                        chatId,
                        context != null && context.triggeringMessage() != null ? context.triggeringMessage().getMessageId() : null,
                        Math.max(0, maxPendingContextItems),
                        botInstanceId
                )
                .defaultIfEmpty(java.util.List.of());

        ConversationFormatter.FormatResult fallback = conversationFormatter.format(
                context.contextMessages(),
                context.triggeringMessage(),
                botInstanceId,
                null
        );
        Mono<ConversationFormatter.FormatResult> conversationMono = selfUserIdMono
                .map(selfUserId -> selfUserId != null && selfUserId != 0L
                        ? conversationFormatter.format(
                                context.contextMessages(),
                                context.triggeringMessage(),
                                botInstanceId,
                                selfUserId
                        )
                        : fallback)
                .onErrorReturn(fallback);

        var userMono = resolveUserForPrompt(context)
                .map(java.util.Optional::ofNullable)
                .switchIfEmpty(Mono.just(java.util.Optional.empty()));

        return Mono.zip(userMono, conversationMono, pendingMono)
                .map(tuple -> {
                    var user = tuple.getT1().orElse(null);
                    var conversation = tuple.getT2();
                    var pending = tuple.getT3();
                    java.util.LinkedList<ApiMessage> finalMessages = new java.util.LinkedList<>(conversation.messages());
                    LlmSpeakerContext speakers = conversation.speakerContext();
                    EnhancedPromptRequest promptRequest = EnhancedPromptRequest.builder()
                            .template(cfg.template())
                            .chatConfig(cfg.config())
                            .rateLimits(cfg.rateLimits())
                            .fallbackPrompt("Respond naturally with context.")
                            .fallbackLanguage(cfg.config() != null ? cfg.config().getLanguage() : "auto")
                            .user(user)
                            .speakerContext(speakers)
                            .pendingResponses(pending)
                            .build();
                    finalMessages.addFirst(new ApiMessage("system", promptBuilder.buildEnhancedPrompt(promptRequest)));
                    logLlmRequest(chatId, pipelineLabel, finalMessages, context, cfg.template(), cfg.config(), speakers, pending != null ? pending.size() : 0);
                    return finalMessages;
                });
    }

    private Mono<Boolean> maybeQueuePending(long chatId,
                                            long triggeringMessageId,
                                            BotContextResolver.ResolvedConfig cfg,
                                            String preparedContent,
                                            ResponseTone tone,
                                            String responseIntent) {
        ChatConfig chatConfig = cfg.config();
        int requiredDelta = chatConfig != null && chatConfig.getWaitForHumanRepliesCount() != null
                ? chatConfig.getWaitForHumanRepliesCount()
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

        Instant eligibleAt = delaySeconds > 0 ? Instant.now().plusSeconds(delaySeconds) : Instant.now();

	        String responseLength = Optional.ofNullable(cfg.template())
	                .map(ResponseTemplate::getResponseStyle)
	                .map(Enum::name)
	                .orElse(null);
	        String toneName = tone != null ? tone.name() : null;

            String botInstanceId = cfg != null ? cfg.botInstanceId() : null;
            if (botInstanceId == null || botInstanceId.isBlank()) {
                return Mono.just(false);
            }

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

    private void logLlmRequest(long chatId,
                               String pipeline,
                               List<ApiMessage> messages,
                               ContextCollector.ConversationContext context,
                               ResponseTemplate template,
                               ChatConfig chatConfig,
                               LlmSpeakerContext speakerContext,
                               int pendingCount) {
        if (!log.isDebugEnabled() || messages == null || messages.isEmpty()) {
            return;
        }
        int contextCount = context != null ? context.contextMessages().size() : 0;
        String lang = chatConfig != null ? chatConfig.getLanguage() : "auto";
        Integer window = chatConfig != null ? chatConfig.getContextWindowSize() : null;
        String botId = speakerContext != null ? speakerContext.botInstanceId() : null;

        StringBuilder sb = new StringBuilder();
        sb.append("[LLM REQUEST] chatId=").append(chatId)
                .append(" pipeline=").append(pipeline)
                .append(" botId=").append(botId)
                .append(" selfUserId=").append(speakerContext != null ? speakerContext.selfTelegramUserId() : null)
                .append(" participants=").append(speakerContext != null && speakerContext.participants() != null ? speakerContext.participants().size() : null)
                .append(" pending=").append(pendingCount)
                .append(" chatConfigId=").append(chatConfig != null ? chatConfig.getId() : null)
                .append(" templateId=").append(template != null ? template.getId() : null)
                .append(" lang=").append(lang)
                .append(" window=").append(window)
                .append(" ctxMessages=").append(contextCount)
                .append(" totalMessages=").append(messages.size());

        log.debug(sb.toString());
    }

    private String preview(String text) {
        if (text == null) return "";
        int limit = 2000;
        return text.length() > limit ? text.substring(0, limit) + " ...[truncated]" : text;
    }

    private Mono<String> callLlm(long chatId,
                                 long triggeringMessageId,
                                 String pipeline,
                                 List<ApiMessage> messages,
                                 ChatConfig chatConfig) {
        Integer maxTokens = chatConfig != null ? chatConfig.getMaxTokens() : null;
        Double temperature = chatConfig != null ? chatConfig.getTemperature() : null;
        DeepSeekChatRequest request = new DeepSeekChatRequest(messages, null, maxTokens, temperature);
        logLlmRequestBody(chatId, pipeline, request);
        return llmCaller.callEnhanced(chatId, triggeringMessageId, request)
                .doOnNext(resp -> logLlmResponseBody(chatId, pipeline, resp))
                .doOnSuccess(resp -> {
                    if (resp == null || resp.isBlank()) {
                        log.debug("[LLM RESPONSE BODY] chatId={} pipeline={} empty response", chatId, pipeline);
                    }
                });
    }

    private void logLlmRequestBody(long chatId, String pipeline, DeepSeekChatRequest request) {
        if (!payloadLoggingEnabled || !llmPayloadLog.isDebugEnabled() || request == null) {
            return;
        }

        List<ApiMessage> messages = request.messages();
        if (messages == null || messages.isEmpty()) {
            llmPayloadLog.debug("[LLM REQUEST BODY] chatId={} pipeline={} body: empty messages", chatId, pipeline);
            return;
        }

        ApiMessage system = messages.get(0);
        String systemContent = system != null ? system.content() : null;

        StringBuilder sb = new StringBuilder();
        sb.append("[LLM REQUEST BODY] chatId=").append(chatId)
                .append(" pipeline=").append(pipeline)
                .append(" max_tokens=").append(request.max_tokens())
                .append(" temperature=").append(request.temperature())
                .append(" messages=").append(messages.size())
                .append("\n----- SYSTEM (system prompt) -----\n")
                .append(truncate(systemContent))
                .append("\n----- CONVERSATION (prepared messages) -----");
        for (int i = 1; i < messages.size(); i++) {
            ApiMessage msg = messages.get(i);
            if (msg == null || msg.content() == null || msg.content().isBlank()) {
                continue;
            }
            sb.append("\n").append(truncate(msg.content()));
        }
        sb.append("\n----- END -----");
        llmPayloadLog.debug(sb.toString());
    }

    private void logLlmResponseBody(long chatId, String pipeline, String response) {
        if (!payloadLoggingEnabled || !llmPayloadLog.isDebugEnabled()) {
            return;
        }
        if (response == null) {
            llmPayloadLog.debug("[LLM RESPONSE BODY] chatId={} pipeline={} body: null", chatId, pipeline);
            return;
        }
        llmPayloadLog.debug("[LLM RESPONSE BODY] chatId={} pipeline={} body:\n{}", chatId, pipeline, truncate(response));
    }

    private void logLlmResponseNormalizedIfNeeded(long chatId, String pipeline, String raw, String normalized) {
        if (!payloadLoggingEnabled || !llmPayloadLog.isDebugEnabled()) {
            return;
        }
        if (raw == null || normalized == null) {
            return;
        }
        if (raw.equals(normalized)) {
            return;
        }
        llmPayloadLog.debug("[LLM RESPONSE NORMALIZED] chatId={} pipeline={} body:\n{}", chatId, pipeline, truncate(normalized));
    }

    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        int limit = payloadMaxChars > 0 ? payloadMaxChars : 60000;
        if (text.length() <= limit) {
            return text;
        }
        return text.substring(0, limit) + "\n...[truncated " + (text.length() - limit) + " chars]";
    }

    private Mono<com.example.telegramuserbot.domain.User> resolveUserForPrompt(ContextCollector.ConversationContext context) {
        MessageEntity triggering = context.triggeringMessage();
        if (triggering == null || triggering.getSenderId() == null) {
            return Mono.empty();
        }
        return userService.getUserByTelegramId(triggering.getSenderId())
                .onErrorResume(e -> Mono.empty());
    }
}
