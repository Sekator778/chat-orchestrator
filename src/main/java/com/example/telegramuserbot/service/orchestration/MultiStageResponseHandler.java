package com.example.telegramuserbot.service.orchestration;

import com.example.telegramuserbot.domain.ContextSettings;
import com.example.telegramuserbot.domain.LlmParameters;
import com.example.telegramuserbot.domain.LlmQueryPhase;
import com.example.telegramuserbot.domain.LlmQueryStatus;
import com.example.telegramuserbot.domain.PendingResponse;
import com.example.telegramuserbot.domain.ResponseStyle;
import com.example.telegramuserbot.domain.ResponseTemplate;
import com.example.telegramuserbot.domain.ResponseTone;
import com.example.telegramuserbot.domain.User;
import com.example.telegramuserbot.dto.ResponsePayload;
import com.example.telegramuserbot.repository.ContextSettingsRepository;
import com.example.telegramuserbot.service.UserService;
import com.example.telegramuserbot.service.common.ReplyLanguage;
import com.example.telegramuserbot.service.humanization.PersonaService;
import com.example.telegramuserbot.service.humanization.PersonaStyle;
import com.example.telegramuserbot.service.humanization.ReplyHumanizer;
import com.example.telegramuserbot.service.llm.EnhancedLlmService;
import com.example.telegramuserbot.service.llm.conversation.ConversationFormatter;
import com.example.telegramuserbot.service.llm.conversation.LlmSpeakerContext;
import com.example.telegramuserbot.service.llm.dto.ApiMessage;
import com.example.telegramuserbot.service.orchestration.dto.EnhancedPromptRequest;
import com.example.telegramuserbot.service.orchestration.dto.ResponseDirectives;
import com.example.telegramuserbot.service.telegram.TelegramSelfUserIdResolver;
import com.example.telegramuserbot.service.tracking.LlmQueryTracker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Component
public class MultiStageResponseHandler {

    private static final Logger log = LoggerFactory.getLogger(MultiStageResponseHandler.class);

    private static final int ANALYSIS_MAX_TOKENS = 600;
    private static final int PLANNING_MAX_TOKENS = 600;
    private static final double ANALYSIS_TEMPERATURE = 0.2;
    private static final double PLANNING_TEMPERATURE = 0.35;
    private static final double DRAFT_TEMPERATURE = 0.85;
    private static final int MAX_DRAFT_ATTEMPTS = 2;

    private static final String CONTEXT_ANALYSIS_SYSTEM_PROMPT = """
            You are an analyst of Telegram conversations.
            Return ONLY valid JSON (no Markdown) with the following shape:
            {
              "summary": "...",
              "userMood": "...",
              "riskLevel": "...",
              "mustAddress": ["..."],
              "followUps": ["..."],
              "styleHints": ["..."]
            }
            """;

    private static final String RESPONSE_PLANNING_SYSTEM_PROMPT = """
            You are a response strategist.
            Return ONLY valid JSON (no Markdown) with the following shape:
            {
              "objective": "...",
              "tone": "...",
              "styleGuidelines": ["..."],
              "replyOutline": ["..."],
              "followUpQuestion": "...",
              "checks": ["..."],
              "openingIdea": "...",
              "closingIdea": "..."
            }
            """;

    private final ContextCollector contextCollector;
    private final ContextSettingsRepository contextSettingsRepository;
    private final LlmCallService llmCallService;
    private final PromptBuilder promptBuilder;
    private final ConversationFormatter conversationFormatter;
    private final SearchAugmentor searchAugmentor;
    private final ResponsePostProcessor responsePostProcessor;
    private final ResponseMapper responseMapper;
    private final PendingResponseCoordinator pendingResponseCoordinator;
    private final LlmTrackingFacade trackingFacade;
    private final PersonaService personaService;
    private final ReplyHumanizer replyHumanizer;
    private final EnhancedSingleResponseHandler fallbackHandler;
    private final UserService userService;
    private final ObjectMapper objectMapper;
    private final TelegramSelfUserIdResolver selfUserIdResolver;

    public MultiStageResponseHandler(ContextCollector contextCollector,
                                    ContextSettingsRepository contextSettingsRepository,
                                    LlmCallService llmCallService,
                                    PromptBuilder promptBuilder,
                                    ConversationFormatter conversationFormatter,
                                    SearchAugmentor searchAugmentor,
                                    ResponsePostProcessor responsePostProcessor,
                                    ResponseMapper responseMapper,
                                    PendingResponseCoordinator pendingResponseCoordinator,
                                    LlmTrackingFacade trackingFacade,
                                    PersonaService personaService,
                                    ReplyHumanizer replyHumanizer,
                                    EnhancedSingleResponseHandler fallbackHandler,
                                    UserService userService,
                                    ObjectMapper objectMapper,
                                    TelegramSelfUserIdResolver selfUserIdResolver) {
        this.contextCollector = contextCollector;
        this.contextSettingsRepository = contextSettingsRepository;
        this.llmCallService = llmCallService;
        this.promptBuilder = promptBuilder;
        this.conversationFormatter = conversationFormatter;
        this.searchAugmentor = searchAugmentor;
        this.responsePostProcessor = responsePostProcessor;
        this.responseMapper = responseMapper;
        this.pendingResponseCoordinator = pendingResponseCoordinator;
        this.trackingFacade = trackingFacade;
        this.personaService = personaService;
        this.replyHumanizer = replyHumanizer;
        this.fallbackHandler = fallbackHandler;
        this.userService = userService;
        this.objectMapper = objectMapper;
        this.selfUserIdResolver = selfUserIdResolver;
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

        return Mono.zip(
                        contextCollector.collectForBot(chatId, triggeringMessageId, cfg.botInstanceId()),
                        resolveContextSettings(cfg),
                        resolveSelfUserId(cfg)
                )
                .flatMap(tuple -> {
                    ContextCollector.ConversationContext context = tuple.getT1();
                    ContextSettings settings = tuple.getT2();
                    Long selfUserId = tuple.getT3();
                    return trackingFacade.start(chatId, triggeringMessageId, rawText, "MULTI_STAGE", cfg)
                            .map(Optional::of)
                            .defaultIfEmpty(Optional.empty())
                            .flatMap(tracker -> generateMultiStage(chatId, triggeringMessageId, rawText, cfg, template, context, settings, selfUserId, tracker.orElse(null), directives)
                                    .onErrorResume(e -> {
                                        log.error("[Chat {}] MULTI_STAGE failed: {} — response suppressed (multi_stage_enabled=true, no fallback allowed)", chatId, e.getMessage());
                                        return Mono.empty();
                                    }));
                });
    }

    private Mono<ResponsePayload> generateMultiStage(long chatId,
                                                     long triggeringMessageId,
                                                     String rawText,
                                                     BotContextResolver.ResolvedConfig cfg,
                                                     ResponseTemplate template,
                                                     ContextCollector.ConversationContext context,
                                                     ContextSettings settings,
                                                     Long selfUserId,
                                                     LlmQueryTracker tracker,
                                                     ResponseDirectives directives) {
        Mono<ContextSnapshot> analysisMono = settings != null && settings.isContextSummaryEnabled()
                ? runContextAnalysis(chatId, triggeringMessageId, cfg, context, settings, selfUserId, tracker)
                : Mono.just(ContextSnapshot.empty());

        String lang = cfg != null && cfg.config() != null ? cfg.config().getLanguage() : null;
        PersonaStyle style = personaService.resolveStyle(cfg != null ? cfg.botInstanceId() : null, lang);

        return analysisMono
                .flatMap(snapshot -> runPlanning(chatId, triggeringMessageId, cfg, context, settings, snapshot, selfUserId, tracker)
                        .flatMap(plan -> generateDraftWithValidation(chatId, triggeringMessageId, rawText, cfg, template, context, settings, snapshot, plan, selfUserId, tracker, 1, directives, lang, style, null)))
                .flatMap(outcome -> {
                    if (outcome.skip()) {
                        return skipSilently(chatId, tracker, "MULTI_STAGE: humanizer не оставил текста для отправки (skip/aiTell после ретрая)");
                    }
                    return searchAugmentor.augmentIfNeeded(outcome.text(), rawText != null ? rawText : "", chatId).defaultIfEmpty(outcome.text())
                            .flatMap(content -> {
                                String processed = responsePostProcessor.postProcess(content, template, lang, style);
                                llmCallService.logNormalizedIfChanged(chatId, "MULTI_STAGE", content, processed);
                                if (processed == null || processed.isBlank()) {
                                    return skipSilently(chatId, tracker, "MULTI_STAGE humanizer вернул пустой текст после поиска (skip/aiTell)");
                                }
                                return Mono.just(responseMapper.mapEnhanced(
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
                                        context.totalCharacters()));
                            });
                })
                .flatMap(payload -> pendingResponseCoordinator.maybeQueuePending(chatId, triggeringMessageId, cfg, payload.content(), payload.tone(), "MULTI_STAGE")
                        .flatMap(queued -> trackingFacade.markCompletedOrSkip(tracker, payload.content(), queued)
                                .then(queued ? Mono.empty() : Mono.just(payload))));
    }

    /**
     * Человек, которому нечего сказать, молчит — никакой шаблонной фразы в чат.
     * Запрос помечается SKIPPED, сообщение не отправляется. Зеркалит
     * EnhancedSingleResponseHandler.skipSilently.
     */
    private Mono<ResponsePayload> skipSilently(long chatId, LlmQueryTracker tracker, String reason) {
        log.warn("[Chat {}] {} — пропускаем отправку без fallback-фразы", chatId, reason);
        if (tracker == null) {
            return Mono.empty();
        }
        return tracker.markCompleted(LlmQueryStatus.SKIPPED, null, reason, null, false)
                .onErrorResume(e -> Mono.empty())
                .then(Mono.empty());
    }

    private Mono<ContextSettings> resolveContextSettings(BotContextResolver.ResolvedConfig cfg) {
        Long chatConfigId = cfg != null && cfg.config() != null ? cfg.config().getId() : null;
        if (chatConfigId == null) {
            return Mono.just(new ContextSettings(null));
        }
        return contextSettingsRepository.findByChatConfigId(chatConfigId)
                .defaultIfEmpty(new ContextSettings(chatConfigId));
    }

    private Mono<ContextSnapshot> runContextAnalysis(long chatId,
                                                     long triggeringMessageId,
                                                     BotContextResolver.ResolvedConfig cfg,
                                                     ContextCollector.ConversationContext context,
                                                     ContextSettings settings,
                                                     Long selfUserId,
                                                     LlmQueryTracker tracker) {
        String digest = buildConversationDigest(context, settings, selfUserId);
        List<ApiMessage> messages = List.of(
                new ApiMessage("system", CONTEXT_ANALYSIS_SYSTEM_PROMPT),
                new ApiMessage("user", digest)
        );

        LlmParameters stageParams = stageParams(cfg.llmParameters(), ANALYSIS_MAX_TOKENS, ANALYSIS_TEMPERATURE);
        return llmCallService.call(chatId, triggeringMessageId, "MULTI_STAGE/ANALYSIS", messages, null, stageParams,
                        tracker, LlmQueryPhase.CONTEXT_ANALYSIS, 1, Map.of("stage", "analysis"))
                .flatMap(raw -> parseContextSnapshot(raw).switchIfEmpty(Mono.error(new IllegalStateException("analysis JSON parse failed"))));
    }

    private Mono<ResponsePlan> runPlanning(long chatId,
                                          long triggeringMessageId,
                                          BotContextResolver.ResolvedConfig cfg,
                                          ContextCollector.ConversationContext context,
                                          ContextSettings settings,
                                          ContextSnapshot snapshot,
                                          Long selfUserId,
                                          LlmQueryTracker tracker) {
        StringBuilder sb = new StringBuilder();
        sb.append("Conversation digest:\n").append(buildConversationDigest(context, settings, selfUserId)).append("\n\n");
        sb.append("Summary: ").append(snapshot.summary()).append("\n");
        sb.append("User mood: ").append(snapshot.userMood()).append("\n");
        sb.append("Risk: ").append(snapshot.riskLevel()).append("\n");
        if (!snapshot.mustAddress().isEmpty()) {
            sb.append("Must address: ").append(String.join("; ", snapshot.mustAddress())).append("\n");
        }
        if (!snapshot.styleHints().isEmpty()) {
            sb.append("Style hints: ").append(String.join("; ", snapshot.styleHints())).append("\n");
        }

        List<ApiMessage> messages = List.of(
                new ApiMessage("system", RESPONSE_PLANNING_SYSTEM_PROMPT),
                new ApiMessage("user", sb.toString())
        );

        LlmParameters stageParams = stageParams(cfg.llmParameters(), PLANNING_MAX_TOKENS, PLANNING_TEMPERATURE);
        return llmCallService.call(chatId, triggeringMessageId, "MULTI_STAGE/PLAN", messages, null, stageParams,
                        tracker, LlmQueryPhase.RESPONSE_PLANNING, 1, Map.of("stage", "planning"))
                .flatMap(raw -> parseResponsePlan(raw).switchIfEmpty(Mono.error(new IllegalStateException("planning JSON parse failed"))));
    }

    /**
     * Generates a draft and, before accepting it, runs it through the humanizer to catch
     * an AI-tell. One retry is allowed (MAX_DRAFT_ATTEMPTS): the retry adds an extra
     * user-turn instruction, in the reply language, asking for an ordinary chat rewrite.
     * If the retry still reads as AI-written (or the humanizer says silence outright),
     * the draft is dropped — a human who can't phrase it naturally says nothing.
     */
    private Mono<DraftOutcome> generateDraftWithValidation(long chatId,
                                                     long triggeringMessageId,
                                                     String rawText,
                                                     BotContextResolver.ResolvedConfig cfg,
                                                     ResponseTemplate template,
                                                     ContextCollector.ConversationContext context,
                                                     ContextSettings settings,
                                                     ContextSnapshot snapshot,
                                                     ResponsePlan plan,
                                                     Long selfUserId,
                                                     LlmQueryTracker tracker,
                                                     int attempt,
                                                     ResponseDirectives directives,
                                                     String lang,
                                                     PersonaStyle style,
                                                     String extraInstruction) {
        int resolvedAttempt = Math.max(1, attempt);
        return runDraft(chatId, triggeringMessageId, cfg, template, context, settings, snapshot, plan, selfUserId, tracker, resolvedAttempt, directives, extraInstruction)
                .flatMap(draft -> {
                    ReplyHumanizer.Humanized humanized = replyHumanizer.humanize(draft, lang, style);
                    if (humanized.skip()) {
                        return Mono.just(DraftOutcome.silence());
                    }
                    if (humanized.aiTell()) {
                        if (resolvedAttempt < MAX_DRAFT_ATTEMPTS) {
                            String normalizedLang = ReplyLanguage.normalize(lang);
                            return generateDraftWithValidation(chatId, triggeringMessageId, rawText, cfg, template, context, settings, snapshot, plan, selfUserId, tracker, resolvedAttempt + 1, directives, lang, style, rewriteInstruction(normalizedLang));
                        }
                        // Attempts exhausted and it still reads as AI-written — never send it as-is.
                        return Mono.just(DraftOutcome.silence());
                    }
                    return Mono.just(DraftOutcome.accept(draft));
                });
    }

    /** The user-turn nudge used to regenerate a draft that reads as AI-written, per reply language. */
    private String rewriteInstruction(String normalizedLang) {
        return switch (normalizedLang) {
            case ReplyLanguage.RU -> "Перепиши как обычный человек сказал бы в чате; никогда не упоминай ИИ, модели или программы.";
            case ReplyLanguage.UK -> "Перепиши так, як звичайна людина сказала б у чаті; ніколи не згадуй ШІ, моделі або програми.";
            default -> "Rewrite as an ordinary person would say it in a chat; never mention AI, models or programs.";
        };
    }

    private Mono<String> runDraft(long chatId,
                                 long triggeringMessageId,
                                 BotContextResolver.ResolvedConfig cfg,
                                 ResponseTemplate template,
                                 ContextCollector.ConversationContext context,
                                 ContextSettings settings,
                                 ContextSnapshot snapshot,
                                 ResponsePlan plan,
                                 Long selfUserId,
                                 LlmQueryTracker tracker,
                                 int attempt,
                                 ResponseDirectives directives,
                                 String extraInstruction) {
        Long beforeMessageId = context != null && context.triggeringMessage() != null ? context.triggeringMessage().getMessageId() : null;

        LlmSpeakerContext speakers = conversationFormatter.format(
                context != null ? context.contextMessages() : List.of(),
                context != null ? context.triggeringMessage() : null,
                cfg != null ? cfg.botInstanceId() : null,
                selfUserId
        ).speakerContext();

        Mono<List<PendingResponse>> pendingMono = pendingResponseCoordinator.loadPendingContext(chatId, beforeMessageId, cfg != null ? cfg.botInstanceId() : null)
                .defaultIfEmpty(List.of());

        Mono<java.util.Optional<User>> userMono = resolveUserForPrompt(context, settings)
                .map(java.util.Optional::of)
                .defaultIfEmpty(java.util.Optional.empty());

        return Mono.zip(pendingMono, userMono)
                .flatMap(tuple -> {
                    List<PendingResponse> pending = tuple.getT1() != null ? tuple.getT1() : List.of();
                    User user = tuple.getT2().orElse(null);

                    EnhancedPromptRequest promptRequest = EnhancedPromptRequest.builder()
                            .template(template)
                            .chatConfig(cfg.config())
                            .rateLimits(cfg.rateLimits())
                            .llmParameters(cfg.llmParameters())
                            .fallbackPrompt("Respond naturally with context.")
                            .fallbackLanguage(cfg.config() != null ? cfg.config().getLanguage() : "auto")
                            .user(user)
                            .speakerContext(speakers)
                            .pendingResponses(pending)
                            .directives(directives)
                            .build();
                    String system = promptBuilder.buildEnhancedPrompt(promptRequest);

                    String userPrompt = buildDraftPrompt(context, settings, snapshot, plan, attempt, selfUserId);
                    List<ApiMessage> messages = new ArrayList<>(List.of(
                            new ApiMessage("system", system),
                            new ApiMessage("user", userPrompt)
                    ));
                    if (extraInstruction != null && !extraInstruction.isBlank()) {
                        messages.add(new ApiMessage("user", extraInstruction));
                    }

                    LlmParameters stageParams = stageParams(cfg.llmParameters(), null, DRAFT_TEMPERATURE);
                    return llmCallService.call(chatId, triggeringMessageId, "MULTI_STAGE/DRAFT", messages, cfg.config(), stageParams,
                            tracker, LlmQueryPhase.DRAFT_GENERATION, attempt, Map.of("stage", "draft", "attempt", attempt));
                });
    }

    private Mono<User> resolveUserForPrompt(ContextCollector.ConversationContext context, ContextSettings settings) {
        if (settings != null && !settings.isIncludeUserInfo()) {
            return Mono.empty();
        }
        if (context == null || context.triggeringMessage() == null || context.triggeringMessage().getSenderId() == null) {
            return Mono.empty();
        }
        return userService.getUserByTelegramId(context.triggeringMessage().getSenderId())
                .onErrorResume(e -> Mono.empty());
    }

    private String buildConversationDigest(ContextCollector.ConversationContext context, ContextSettings settings, Long selfUserId) {
        StringBuilder sb = new StringBuilder();
        if (context == null) {
            return "";
        }
        if (context.contextMessages() != null) {
            for (var msg : context.contextMessages()) {
                appendDigestLine(sb, msg, settings, selfUserId);
            }
        }
        appendDigestLine(sb, context.triggeringMessage(), settings, selfUserId);

        int max = settings != null && settings.getMaxContextLength() != null ? settings.getMaxContextLength() : 4000;
        String built = sb.toString();
        if (max > 0 && built.length() > max) {
            return built.substring(0, max);
        }
        return built;
    }

    private void appendDigestLine(StringBuilder sb, com.example.telegramuserbot.domain.MessageEntity msg, ContextSettings settings, Long selfUserId) {
        if (msg == null) {
            return;
        }
        boolean includeMedia = settings == null || settings.isIncludeMediaDescriptions();

        String content = Optional.ofNullable(msg.getContent()).orElse("");
        String caption = Optional.ofNullable(msg.getCaption()).orElse("");

        if (content.isBlank() && includeMedia) {
            if (!caption.isBlank()) {
                content = caption;
            } else if (msg.getMediaType() != null) {
                content = "(media: " + msg.getMediaType().name().toLowerCase(Locale.ROOT) + ")";
            }
        }

        if (content.isBlank()) {
            return;
        }
        sb.append(isSelfMessage(msg, selfUserId) ? "ME: " : "P: ").append(content.strip()).append("\n");
    }

    private String buildDraftPrompt(ContextCollector.ConversationContext context,
                                   ContextSettings settings,
                                   ContextSnapshot snapshot,
                                   ResponsePlan plan,
                                   int attempt,
                                   Long selfUserId) {
        StringBuilder sb = new StringBuilder();
        sb.append("Conversation:\n").append(buildConversationDigest(context, settings, selfUserId)).append("\n");
        if (snapshot != null && !snapshot.summary().isBlank()) {
            sb.append("Analyst summary: ").append(snapshot.summary()).append("\n");
        }
        if (plan != null && !plan.replyOutline().isEmpty()) {
            sb.append("Plan outline:\n");
            for (String step : plan.replyOutline()) {
                sb.append("- ").append(step).append("\n");
            }
        }
        if (attempt > 1) {
            sb.append("\nRewrite more naturally and avoid any bot/AI-like phrasing. Keep it conversational.\n");
        } else {
            sb.append("\nWrite ONE final Telegram message. No explanations.\n");
        }
        return sb.toString();
    }

    private Mono<Long> resolveSelfUserId(BotContextResolver.ResolvedConfig cfg) {
        if (cfg == null) {
            return Mono.just(0L);
        }
        return selfUserIdResolver.resolveSelfUserId(cfg.botInstanceId()).defaultIfEmpty(0L);
    }

    private boolean isSelfMessage(com.example.telegramuserbot.domain.MessageEntity msg, Long selfUserId) {
        if (msg == null) {
            return false;
        }
        if (selfUserId == null || selfUserId == 0L) {
            return msg.isOutgoing();
        }
        Long senderId = msg.getSenderId();
        if (senderId == null) {
            return msg.isOutgoing();
        }
        return selfUserId.equals(senderId);
    }

    private LlmParameters stageParams(LlmParameters base, Integer maxTokensOverride, Double temperatureOverride) {
        LlmParameters resolved = new LlmParameters(base != null ? base.getChatConfigId() : null);
        if (base != null) {
            resolved.setModelName(base.getModelName());
            resolved.setTopP(base.getTopP());
            resolved.setFrequencyPenalty(base.getFrequencyPenalty());
            resolved.setPresencePenalty(base.getPresencePenalty());
            resolved.setSystemPrompt(base.getSystemPrompt());
            resolved.setCustomInstructions(base.getCustomInstructions());
            resolved.setResponseFormat(base.getResponseFormat());
            resolved.setMaxTokens(base.getMaxTokens());
            resolved.setTemperature(base.getTemperature());
        }
        if (maxTokensOverride != null) {
            resolved.setMaxTokens(maxTokensOverride);
        }
        if (temperatureOverride != null) {
            resolved.setTemperature(temperatureOverride);
        }
        return resolved;
    }

    private Mono<ContextSnapshot> parseContextSnapshot(String raw) {
        return Mono.fromCallable(() -> readJsonNode(raw))
                .flatMap(json -> {
                    if (json == null) {
                        return Mono.empty();
                    }
                    return Mono.just(new ContextSnapshot(
                            safeText(json, "summary"),
                            safeText(json, "userMood"),
                            safeText(json, "riskLevel"),
                            jsonArrayToList(json.get("mustAddress")),
                            jsonArrayToList(json.get("followUps")),
                            jsonArrayToList(json.get("styleHints"))
                    ));
                });
    }

    private Mono<ResponsePlan> parseResponsePlan(String raw) {
        return Mono.fromCallable(() -> readJsonNode(raw))
                .flatMap(json -> {
                    if (json == null) {
                        return Mono.empty();
                    }
                    return Mono.just(new ResponsePlan(
                            safeText(json, "objective"),
                            safeText(json, "tone"),
                            jsonArrayToList(json.get("styleGuidelines")),
                            jsonArrayToList(json.get("replyOutline")),
                            safeText(json, "followUpQuestion"),
                            jsonArrayToList(json.get("checks")),
                            safeText(json, "openingIdea"),
                            safeText(json, "closingIdea")
                    ));
                });
    }

    private JsonNode readJsonNode(String raw) {
        if (raw == null) {
            return null;
        }
        String sanitized = sanitizeJson(raw);
        try {
            return objectMapper.readTree(sanitized);
        } catch (Exception e) {
            return null;
        }
    }

    private String sanitizeJson(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) {
                trimmed = trimmed.substring(firstNewline + 1).trim();
            }
            if (trimmed.regionMatches(true, 0, "json", 0, 4)) {
                trimmed = trimmed.substring(4).trim();
            }
            int closingFence = trimmed.lastIndexOf("```");
            if (closingFence > 0) {
                trimmed = trimmed.substring(0, closingFence).trim();
            }
        }
        int firstBrace = trimmed.indexOf('{');
        int lastBrace = trimmed.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            trimmed = trimmed.substring(firstBrace, lastBrace + 1);
        }
        return trimmed;
    }

    private String safeText(JsonNode node, String field) {
        if (node == null || !node.has(field)) {
            return "";
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return "";
        }
        if (value.isTextual()) {
            return value.asText("").trim();
        }
        if (value.isNumber() || value.isBoolean()) {
            return value.asText();
        }
        return value.toString();
    }

    private List<String> jsonArrayToList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        node.forEach(element -> {
            if (element != null && !element.isNull()) {
                String text = element.asText("").trim();
                if (!text.isEmpty()) {
                    items.add(text);
                }
            }
        });
        return items;
    }

    private record ContextSnapshot(String summary,
                                   String userMood,
                                   String riskLevel,
                                   List<String> mustAddress,
                                   List<String> followUps,
                                   List<String> styleHints) {
        static ContextSnapshot empty() {
            return new ContextSnapshot("", "", "", List.of(), List.of(), List.of());
        }
    }

    private record ResponsePlan(String objective,
                                String tone,
                                List<String> styleGuidelines,
                                List<String> replyOutline,
                                String followUpQuestion,
                                List<String> checks,
                                String openingIdea,
                                String closingIdea) { }

    /** Outcome of the draft-validation loop: either text ready to send, or silence. */
    private record DraftOutcome(String text, boolean skip) {
        static DraftOutcome accept(String text) {
            return new DraftOutcome(text, false);
        }

        static DraftOutcome silence() {
            return new DraftOutcome(null, true);
        }
    }
}
