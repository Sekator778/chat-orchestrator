package com.example.telegramuserbot.service;

import com.example.telegramuserbot.domain.MessageEntity;
import com.example.telegramuserbot.domain.MessageType;
import com.example.telegramuserbot.domain.ProblematicChatReason;
import com.example.telegramuserbot.dto.KafkaTelegramMessage;
import com.example.telegramuserbot.dto.MessageContextDto;
import com.example.telegramuserbot.repository.MessageRepository;
import com.example.telegramuserbot.service.config.AppSettingsService;
import com.example.telegramuserbot.service.decision.ConversationAnalysisService;
import com.example.telegramuserbot.service.decision.ResponseDecisionEngine;
import com.example.telegramuserbot.service.decision.ResponseDecisionEngine.ResponseDecision;
import com.example.telegramuserbot.service.orchestration.BotContextResolver;
import com.example.telegramuserbot.service.orchestration.ChatPersonaDispatchPlanner;
import com.example.telegramuserbot.service.orchestration.PersonaScheduleService;
import com.example.telegramuserbot.service.orchestration.ResponseOrchestrator;
import com.example.telegramuserbot.service.orchestration.dto.ResponseDirectives;
import com.example.telegramuserbot.service.persistence.MessagePersistenceService;
import com.example.telegramuserbot.service.processing.IdempotencyService;
import com.example.telegramuserbot.service.ratelimit.ResponseRateLimitGate;
import com.example.telegramuserbot.service.telegram.OwnAccountSenderFilter;
import com.example.telegramuserbot.service.telegram.SendFailureClassifier;
import com.example.telegramuserbot.telegram.TelegramClientFacade;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;


@Service
public class KafkaMessageConsumerService {

    private static final Logger log = LoggerFactory.getLogger(KafkaMessageConsumerService.class);
    private static final Long ADMIN_CHAT_ID = 1000000001L;
    private static final Duration PROCESSING_TIMEOUT = Duration.ofMinutes(3);

    private final TelegramClientManager telegramClientManager;
    private final ObjectMapper objectMapper;
    private final MessageRepository messageRepository;
    private final ResponseOrchestrator responseOrchestrator;
    private final IdempotencyService idempotencyService;
    private final ProblematicChatService problematicChatService;
    private final BotContextResolver botContextResolver;
    private final MessagePersistenceService messagePersistenceService;
    private final ResponseRateLimitGate responseRateLimitGate;
    private final ChatPersonaDispatchPlanner chatPersonaDispatchPlanner;

    // Decision-gate dependencies (injected, used only when decisionGateEnabled=true)
    private final ResponseDecisionEngine responseDecisionEngine;
    private final ConversationAnalysisService conversationAnalysisService;

    // Bot-to-bot replies are banned product-wide: messages authored by ANY of our
    // accounts are dropped before the gate and before persona fan-out.
    private final OwnAccountSenderFilter ownAccountSenderFilter;

    // Owner control plane: runtime-tunable behavior knobs read from bot.app_settings (TTL cache).
    private final AppSettingsService appSettings;

    // Persona activity schedule: DM replies respect the same schedule window as group replies.
    private final PersonaScheduleService personaScheduleService;

    @Value("${llm.persona-fanout.concurrency:4}")
    private int personaFanOutConcurrency;

    // --- Behavior knobs: table-driven via bot.app_settings (AppSettingsService TTL cache) ---
    // The owner tunes these by editing a row, not an env var. Fallbacks below are only a
    // safety net for a missing row; the seeded table value (changeset 064) wins. Reads are
    // in-memory map lookups, safe per-message. Each method reads once into a local where the
    // value is used more than once in a single computation.
    //
    //   chain_limit.max_bot_messages_per_post  — cap on the bot conversation chain per post
    //   reply_timing.typing.*                  — typing-indicator window (ms/char, capped)
    //   reply_timing.random_delay.*            — pre-send "thinking" delay window (max<=0 = off)
    //   reply_timing.stagger.step_ms           — per-persona stagger so two never reply at once
    //   reply_timing.test_chat_id              — retained for backward compat; no longer a gate
    //                                            (random-delay + stagger now apply to all chats)
    //   decision_gate.enabled / shape_replies / fail_open — ResponseDecisionEngine gate flags
    private int maxBotMessagesPerPost() { return appSettings.getInt("chain_limit.max_bot_messages_per_post", 4); }
    private int typingMsPerChar()        { return appSettings.getInt("reply_timing.typing.ms_per_char", 60); }
    private int typingCapMs()            { return appSettings.getInt("reply_timing.typing.cap_ms", 8000); }
    private long replyRandomDelayMinMs() { return appSettings.getLong("reply_timing.random_delay.min_ms", 0L); }
    private long replyRandomDelayMaxMs() { return appSettings.getLong("reply_timing.random_delay.max_ms", 0L); }
    private long replyStaggerStepMs()    { return appSettings.getLong("reply_timing.stagger.step_ms", 3000L); }
    private long replyTimingTestChatId() { return appSettings.getLong("reply_timing.test_chat_id", -4964162923L); }
    private boolean decisionGateEnabled() { return appSettings.getBoolean("decision_gate.enabled", false); }
    private boolean shapeRepliesEnabled() { return appSettings.getBoolean("decision_gate.shape_replies", false); }
    private boolean failOpen()            { return appSettings.getBoolean("decision_gate.fail_open", true); }

    public KafkaMessageConsumerService(TelegramClientManager telegramClientManager,
                                       ObjectMapper objectMapper,
                                       MessageRepository messageRepository,
                                       ResponseOrchestrator responseOrchestrator,
                                       IdempotencyService idempotencyService,
                                       ProblematicChatService problematicChatService,
                                       BotContextResolver botContextResolver,
                                       MessagePersistenceService messagePersistenceService,
                                       ResponseRateLimitGate responseRateLimitGate,
                                       ChatPersonaDispatchPlanner chatPersonaDispatchPlanner,
                                       ResponseDecisionEngine responseDecisionEngine,
                                       ConversationAnalysisService conversationAnalysisService,
                                       OwnAccountSenderFilter ownAccountSenderFilter,
                                       AppSettingsService appSettings,
                                       PersonaScheduleService personaScheduleService) {
        this.telegramClientManager = telegramClientManager;
        this.objectMapper = objectMapper;
        this.messageRepository = messageRepository;
        this.responseOrchestrator = responseOrchestrator;
        this.idempotencyService = idempotencyService;
        this.problematicChatService = problematicChatService;
        this.botContextResolver = botContextResolver;
        this.messagePersistenceService = messagePersistenceService;
        this.responseRateLimitGate = responseRateLimitGate;
        this.chatPersonaDispatchPlanner = chatPersonaDispatchPlanner;
        this.responseDecisionEngine = responseDecisionEngine;
        this.conversationAnalysisService = conversationAnalysisService;
        this.ownAccountSenderFilter = ownAccountSenderFilter;
        this.appSettings = appSettings;
        this.personaScheduleService = personaScheduleService;
    }

    @KafkaListener(topics = "${kafka.topic.incoming-messages}")
    public void handleIncomingMessage(
            @Payload String message,
            @Header(KafkaHeaders.RECEIVED_KEY) String key,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment acknowledgment) {

        log.info(">>> KAFKA RECV: Raw msg from topic='{}', partition={}, offset={}, key='{}'", topic, partition, offset, key);

        Mono.fromCallable(() -> objectMapper.readValue(message, KafkaTelegramMessage.class))
                .doOnError(e -> log.error("!!! KAFKA RECV ERROR: Deserialization failed. Raw: {}", message, e))
                .flatMap(this::processKafkaMessage)
                .then(Mono.fromRunnable(() -> {
                    log.info("<<< ✅ KAFKA ACK: Acknowledging offset {} for key '{}'", offset, key);
                    acknowledgment.acknowledge(); // <--- ПОДТВЕРЖДАЕМ СООБЩЕНИЕ
                })).subscribe(
                        null, // onNext - не нужен для Mono<Void>
                        error -> log.error(
                                "💥 UNHANDLED ERROR in Kafka consumer chain for key='{}', offset={}. This shouldn't happen.",
                                key, offset, error
                        )
                );
    }

    private Mono<Void> processKafkaMessage(KafkaTelegramMessage kafkaMessage) {
        // Using original TDLib chat ID directly
        long chatId = kafkaMessage.getChatId();
        long messageId = kafkaMessage.getMessageId();

        String idempotencyKey = chatId + ":" + messageId;
        if (!idempotencyService.checkAndSet(idempotencyKey)) {
            log.warn("Duplicate message received from Kafka for key '{}'. Skipping processing.", idempotencyKey);
            return Mono.empty(); // Просто завершаем обработку, так как это дубликат
        }

        log.info("<<< KAFKA RECV OK: Deserialized for chat={}, message={}. Starting processing...", chatId, messageId);

        return verifyMessageExists(chatId, messageId)
                // ENTERPRISE: Рефакторинг. Основная логика вынесена в отдельный метод.
                .flatMap(this::filterAndOrchestrateResponse)
                // ENTERPRISE: Глобальный таймаут для всей цепочки.
                .timeout(PROCESSING_TIMEOUT)
                .doOnSuccess(v -> log.info("<<< ✅ KAFKA PROCESSING COMPLETE for chat={}, message={}", chatId, messageId))
                .doOnError(error -> log.error("!!! 💥 KAFKA PROCESSING FAILED for chat={}, message={}: {}", chatId, messageId, error.getMessage(), error))
                // ENTERPRISE: Обработка ошибок, включая TimeoutException.
                .onErrorResume(error -> {
                    if (error instanceof TimeoutException) {
                        log.error("!!! ⏰ KAFKA PROCESSING TIMEOUT for chat={}, message={}", chatId, messageId);
                        return reportErrorToAdmin(
                                new TimeoutException("Processing timed out after " + PROCESSING_TIMEOUT.getSeconds() + "s"),
                                chatId, messageId
                        );
                    }
                    return reportErrorToAdmin(error, chatId, messageId);
                });
        // FIX: Удален лишний и ломающий цепочку оператор .then()
    }

    /**
     * ENTERPRISE: Новый метод, инкапсулирующий логику фильтрации и вызова оркестратора.
     */
    private Mono<Void> filterAndOrchestrateResponse(MessageEntity messageEntity) {
        log.info("🧠 Starting orchestrated processing for chat={}, message={}",
                messageEntity.getChatId(), messageEntity.getMessageId());

        boolean hasText = (messageEntity.getContent() != null && !messageEntity.getContent().isBlank())
                || (messageEntity.getCaption() != null && !messageEntity.getCaption().isBlank());
        if (!hasText) {
            log.info("⊘ SKIP NO-TEXT: chat={}, message={}, mediaType={} — no text to respond to",
                    messageEntity.getChatId(), messageEntity.getMessageId(), messageEntity.getMediaType());
            return Mono.empty();
        }

        return ownAccountSenderFilter.isOwnSender(messageEntity.getSenderId())
                .flatMap(ownSender -> {
                    if (ownSender) {
                        log.info("⊘ SKIP OWN-ACCOUNT: chat={}, message={}, senderId={} — authored by one of our accounts, bot-to-bot replies are banned",
                                messageEntity.getChatId(), messageEntity.getMessageId(), messageEntity.getSenderId());
                        return Mono.<Void>empty();
                    }
                    return orchestrateForeignMessage(messageEntity);
                });
    }

    private Mono<Void> orchestrateForeignMessage(MessageEntity messageEntity) {
        return messageRepository
                .countOutgoingMessagesSinceLastInbound(messageEntity.getChatId(), messageEntity.getMessageId())
                .defaultIfEmpty(0L)
                .flatMap(chainLength -> {
                    int chainLimit = maxBotMessagesPerPost();
                    if (chainLength >= chainLimit) {
                        log.info("⊘ SKIP CHAIN-LIMIT: chat={}, message={}, chainLength={}/{} — bot conversation chain limit reached",
                                messageEntity.getChatId(), messageEntity.getMessageId(),
                                chainLength, chainLimit);
                        return Mono.<Void>empty();
                    }
                    return problematicChatService.shouldProcess(messageEntity.getChatId())
                .flatMap(shouldProcess -> {
                    if (!shouldProcess) {
                        log.warn("🚫 Skipping processing for problematic chat {} message {}", messageEntity.getChatId(), messageEntity.getMessageId());
                        return Mono.empty();
                    }
                    if (responseRateLimitGate.isChatFullyBlocked(messageEntity.getChatId())) {
                        log.debug("🚫 RateLimitGate: chat {} fully blocked (all known botInstanceIds). Skipping resolveAll for message {}",
                                messageEntity.getChatId(), messageEntity.getMessageId());
                        return Mono.empty();
                    }
                    // DM (private chat): the listener already gated on reply_to_direct, so the
                    // addressed persona replies unconditionally — no decision gate, no dispatch
                    // planner. A private chat cannot have a chat_configs row (tgscan.channels
                    // CHECK id < 0), so the reply runs on global defaults (every downstream
                    // layer is null-config tolerant).
                    // Safety rail: respect the persona's activity schedule on the DM path
                    // (same as the planner applies for group chats).
                    if (messageEntity.getChatId() > 0 && messageEntity.getReceivedByBotId() != null) {
                        String dmPersonaId = messageEntity.getReceivedByBotId();
                        return personaScheduleService.isActiveNow(dmPersonaId)
                                .flatMap(active -> {
                                    if (!active) {
                                        log.info("⊘ SKIP DM SCHEDULE: chat={} msg={} — persona {} is outside its activity window",
                                                messageEntity.getChatId(), messageEntity.getMessageId(), dmPersonaId);
                                        return Mono.<Void>empty();
                                    }
                                    log.info("📩 DM DISPATCH chat={} msg={} → persona {} replies directly",
                                            messageEntity.getChatId(), messageEntity.getMessageId(), dmPersonaId);
                                    return runFanOut(messageEntity,
                                            new BotContextResolver.ResolvedBaseConfig(null, null, null, null),
                                            null,
                                            List.of(dmPersonaId));
                                });
                    }
                    return botContextResolver.resolveBase(messageEntity.getChatId())
                            .flatMap(base -> {
                                boolean gateOn = decisionGateEnabled();
                                // FORWARD-DROP (flag-keyed to decision-gate.enabled):
                                // Skip forwarded bot messages if config disallows them.
                                // This runs before the gate so it is always evaluated when gate is on.
                                if (gateOn
                                        && base.config() != null
                                        && !base.config().isRespondToForwardedBotMessages()
                                        && messageEntity.getForwardFromChatId() != null) {
                                    log.info("⊘ SKIP FORWARD chat={} msg={} — respondToForwardedBotMessages=false and message is forwarded",
                                            messageEntity.getChatId(), messageEntity.getMessageId());
                                    return Mono.<Void>empty();
                                }

                                if (gateOn) {
                                    // Decision gate ON: run analyze → decide, then fan-out or skip
                                    return runDecisionGate(messageEntity, base);
                                } else {
                                    // Decision gate OFF: exact legacy behavior
                                    return runFanOut(messageEntity, base, null);
                                }
                            });
                });
                });
    }

    /**
     * Runs the ResponseDecisionEngine gate (chat-scoped, called ONCE before persona fan-out).
     *
     * <p>Fail-open contract (MUST-FIX #1):
     * - analyze() returns ConversationContext.empty() (isValid()==false) when the trigger table
     *   is empty or data is missing. An invalid/empty context PROCEEDS to runFanOut (mute-averse).
     * - Only a deliberate ResponseDecision.skip() (shouldRespond=false from the engine) mutes.
     * - Note: even with fail-open=true, if the engine returns shouldRespond=false the message IS
     *   muted — that is an intentional skip decision, not a data starvation error.
     *
     * <p>Error handling: any exception in analyze/decide is caught and fail-open routes to runFanOut.
     * If fail-open=false, exceptions result in Mono.empty() (silent drop).
     */
    private Mono<Void> runDecisionGate(MessageEntity messageEntity, BotContextResolver.ResolvedBaseConfig base) {
        long chatId = messageEntity.getChatId();
        long msgId = messageEntity.getMessageId();
        boolean failOpen = failOpen();
        boolean shapeRepliesEnabled = shapeRepliesEnabled();

        Mono<ResponseDecision> decideMono = conversationAnalysisService.analyze(chatId, msgId)
                .flatMap(ctx -> {
                    if (!ctx.isValid()) {
                        // MUST-FIX #1: invalid/empty context (data starvation) → PROCEED, not skip.
                        // Only a deliberate decide().shouldRespond=false mutes the chat.
                        log.debug("DECIDE [chat={}] context invalid (empty trigger table?) — proceeding (fail-open)", chatId);
                        return Mono.<ResponseDecision>empty(); // empty => use fail-open path below
                    }
                    return responseDecisionEngine.decide(ctx);
                });

        // Capture "was there a decision?" explicitly. A previous switchIfEmpty
        // here also fired AFTER a successful runFanOut (which returns an empty
        // Mono<Void>), so the happy path ran fan-out and THEN the fail-open path
        // ran it again — two replies to one message. Optional makes the decision's
        // presence explicit, so fail-open only triggers when analyze was truly
        // empty/invalid, never after a real reply.
        return decideMono
                .map(java.util.Optional::of)
                .defaultIfEmpty(java.util.Optional.empty())
                .flatMap(maybeDecision -> {
                    if (maybeDecision.isEmpty()) {
                        // analyze returned empty OR context was invalid — apply fail-open
                        if (failOpen) {
                            log.debug("DECIDE [chat={}] empty/invalid context — failing open (proceeding to fan-out)", chatId);
                            return runFanOut(messageEntity, base, null);
                        }
                        log.debug("DECIDE [chat={}] empty/invalid context — fail-open=false, skipping", chatId);
                        return Mono.<Void>empty();
                    }
                    ResponseDecision decision = maybeDecision.get();
                    if (!decision.shouldRespond()) {
                        log.info("⊘ SKIP DECIDE chat={} msg={} reason={}", chatId, msgId, decision.reason());
                        // Ack-safe: Mono.empty() not Mono.error
                        return Mono.<Void>empty();
                    }
                    // Engine said respond — build directives if shaping is on, then fan-out
                    ResponseDirectives directives = shapeRepliesEnabled
                            ? ResponseDirectives.fromDecision(decision)
                            : null;
                    return runFanOut(messageEntity, base, directives);
                })
                .onErrorResume(err -> {
                    if (failOpen) {
                        log.error("DECIDE ERROR chat={} msg={} — failing open (proceeding to fan-out): {}",
                                chatId, msgId, err.getMessage(), err);
                        return runFanOut(messageEntity, base, null);
                    }
                    log.error("DECIDE ERROR chat={} msg={} — fail-open=false, dropping: {}",
                            chatId, msgId, err.getMessage(), err);
                    return Mono.<Void>empty();
                });
    }

    /**
     * Fan-out across bot personas (helper extracted from the original inline lambda).
     * Replaces the Flux.range + responseOrchestrator.replyWithConfig block that was inline
     * in filterAndOrchestrateResponse. Called ONCE per chat (decision is chat-scoped), then
     * fans across personas with per-persona stagger.
     *
     * <p>MUST-FIX #8: incrementRateLimitCounters now runs inside the decision engine path;
     * the consumer here does not duplicate that count — rate-limit is enforced at engine level.
     *
     * @param decision nullable when gate is off or fail-open; null means no directives
     */
    private Mono<Void> runFanOut(MessageEntity messageEntity,
                                 BotContextResolver.ResolvedBaseConfig base,
                                 ResponseDirectives decision) {
        return runFanOut(messageEntity, base, decision, null);
    }

    /**
     * @param fixedBotIds non-null to bypass the dispatch planner and fan out to exactly
     *                    these personas (DM flow: the addressed persona always replies)
     */
    private Mono<Void> runFanOut(MessageEntity messageEntity,
                                 BotContextResolver.ResolvedBaseConfig base,
                                 ResponseDirectives decision,
                                 List<String> fixedBotIds) {
        // Extract the decided delay (seconds) for the send path (MUST-FIX #3 / #4)
        // decisionDelayMs is >0 only when shape-replies=true and engine provided a delay
        Integer decidedDelaySeconds = decision != null ? decision.delaySeconds() : null;

        Mono<List<String>> botIdsMono = fixedBotIds != null
                ? Mono.just(fixedBotIds)
                : chatPersonaDispatchPlanner.planBotIds(messageEntity.getChatId(), base.config() != null ? base.config().getId() : null);

        return botIdsMono
                .flatMapMany(botIds -> {
                    // Surface the dispatch outcome on stdout (the planner itself logs to a
                    // file-only appender). Makes the per-persona decision visible during
                    // live testing: who chimes in, or that nobody did.
                    int count = botIds != null ? botIds.size() : 0;
                    log.info("👥 RESPONDERS chat={} msg={} → {} persona(s) will reply: {}",
                            messageEntity.getChatId(), messageEntity.getMessageId(), count,
                            botIds != null ? botIds : List.of());
                    if (botIds == null || botIds.isEmpty()) {
                        return Flux.empty();
                    }
                    responseRateLimitGate.registerKnownBots(messageEntity.getChatId(), botIds);
                    // Index the fan-out so each persona gets a deterministic stagger offset.
                    return Flux.range(0, botIds.size())
                            .flatMap(personaIndex -> {
                                String botId = botIds.get(personaIndex);
                                var cfg = new BotContextResolver.ResolvedConfig(
                                        base.config(),
                                        base.template(),
                                        base.rateLimits(),
                                        base.llmParameters(),
                                        botId
                                );
                                // rawText drives the CONCISE handler's user turn and the
                                // search augmentor. A media-only post (chart photo) keeps its
                                // text in caption, not content — fall back so the persona sees
                                // the substance instead of being suppressed as empty.
                                String triggerText =
                                        messageEntity.getContent() != null && !messageEntity.getContent().isBlank()
                                                ? messageEntity.getContent()
                                                : messageEntity.getCaption();
                                return responseOrchestrator.replyWithConfig(
                                                messageEntity.getChatId(),
                                                messageEntity.getMessageId(),
                                                triggerText,
                                                cfg,
                                                true,
                                                decision // nullable directives
                                        )
                                        .flatMap(payload -> dispatchOrchestratedResponse(messageEntity, payload, botId, personaIndex, decidedDelaySeconds))
                                        .onErrorResume(e -> {
                                            log.error("Orchestrator failed for chat={}, message={} botInstance={}: {}",
                                                    messageEntity.getChatId(), messageEntity.getMessageId(), botId, e.getMessage(), e);
                                            return Mono.empty();
                                        });
                            }, Math.max(1, personaFanOutConcurrency));
                })
                .then();
    }

    /**
     * Verifies message exists in DB with resilient retry strategy for eventual consistency.
     * <p>
     * Handles the race condition where Kafka events arrive before DB transactions commit.
     * If message is not found after all retries, returns empty (graceful skip) rather than
     * propagating an error — this prevents admin spam for stale Kafka messages pointing
     * to non-existent chats or deleted messages.
     * <p>
     * Strategy:
     * - Initial delay: 50ms (allows DB transaction to commit)
     * - Exponential backoff: 100ms → 200ms → 400ms → 800ms → 1600ms
     * - Total retries: 6 (max wait ~3.2 seconds)
     * - Jitter: Prevents thundering herd
     */
    private Mono<MessageEntity> verifyMessageExists(long chatId, long originalMessageId) {
        return Mono.delay(Duration.ofMillis(50))
                .then(messageRepository.findByChatIdAndMessageId(chatId, originalMessageId))
                .switchIfEmpty(Mono.error(new IllegalStateException("Message not found in DB")))
                .retryWhen(Retry.backoff(6, Duration.ofMillis(100))
                        .maxBackoff(Duration.ofSeconds(2))
                        .jitter(0.5)
                        .doBeforeRetry(retrySignal -> {
                            long attempt = retrySignal.totalRetries() + 1;
                            log.debug("⏳ Eventual consistency wait: Message {} in chat {} not found yet, retry {}/6",
                                    originalMessageId, chatId, attempt);
                        }))
                .onErrorResume(e -> {
                    log.warn("⚠️ KAFKA SKIP: Message {} in chat {} not found after retries (~3.2s), skipping — stale event or deleted message",
                            originalMessageId, chatId);
                    return Mono.empty();
                });
    }

    /**
     * Dispatches an orchestrated response to Telegram, threading the optional decided delay.
     *
     * @param decidedDelaySeconds nullable per-engine decision delay (MUST-FIX #3)
     */
    private Mono<Void> dispatchOrchestratedResponse(MessageEntity originalMessageEntity,
                                                     com.example.telegramuserbot.dto.ResponsePayload payload,
                                                     String botId,
                                                     int personaIndex,
                                                     Integer decidedDelaySeconds) {
        if (payload == null || payload.content() == null || payload.content().isBlank()) {
            log.info("🚫 ORCH: Empty response generated for chat={}, message={}", originalMessageEntity.getChatId(), originalMessageEntity.getMessageId());
            return Mono.empty();
        }
        return sendTelegramReply(botId, originalMessageEntity.getChatId(), originalMessageEntity.getMessageId(), payload.content(), personaIndex, decidedDelaySeconds)
                .onErrorResume(e -> {
                    if (!SendFailureClassifier.isPermanentAccessError(e)) {
                        // Muting a chat is irreversible (there is no un-mark path), so it is
                        // reserved for errors that actually name an access problem. Everything
                        // else — flood waits, the kill switch, an unrecognized failure — costs
                        // one message and is retried when the next one arrives.
                        log.warn("[dispatch] non-permanent send failure for chat={} ({}) — not marking problematic: {}",
                                originalMessageEntity.getChatId(),
                                SendFailureClassifier.isTransientSendError(e) ? "known transient" : "unclassified",
                                SendFailureClassifier.extractMessage(e));
                        return Mono.empty();
                    }
                    log.warn("[dispatch] permanent access failure for chat={} — muting: {}",
                            originalMessageEntity.getChatId(), SendFailureClassifier.extractMessage(e));
                    return problematicChatService.markProblematic(originalMessageEntity.getChatId(),
                                    ProblematicChatReason.ACCESS_DENIED,
                                    SendFailureClassifier.extractMessage(e))
                            .onErrorResume(markError -> {
                                log.warn("Failed to persist problematic chat {}: {}", originalMessageEntity.getChatId(), markError.getMessage(), markError);
                                return Mono.empty();
                            })
                            .then(Mono.empty());
                })
                .flatMap(sent -> persistBotReplies(botId, List.of(sent)).then())
                .doOnSuccess(v -> log.info("💬 ORCH RESPONSE SENT for chat={} message={} style={} tone={}",
                        originalMessageEntity.getChatId(), originalMessageEntity.getMessageId(), payload.style(), payload.tone()))
                .onErrorResume(e -> {
                    log.error("!!! ORCH DISPATCH FAILED chat={} message={}: {}", originalMessageEntity.getChatId(), originalMessageEntity.getMessageId(), e.getMessage(), e);
                    return Mono.empty();
                });
    }

    // Legacy overload: preserves signature for internal calls without decided delay
    private Mono<Void> dispatchOrchestratedResponse(MessageEntity originalMessageEntity,
                                                     com.example.telegramuserbot.dto.ResponsePayload payload,
                                                     String botId,
                                                     int personaIndex) {
        return dispatchOrchestratedResponse(originalMessageEntity, payload, botId, personaIndex, null);
    }

    private Mono<List<MessageEntity>> persistBotReplies(String botId, List<TdApi.Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return Mono.just(List.of());
        }
        return Flux.fromIterable(messages)
                .concatMap(msg -> msg.chatId > 0
                        ? messagePersistenceService.forcePersistMessage(botId, msg.chatId, msg)
                        : messagePersistenceService.persistMessage(botId, msg.chatId, msg))
                .collectList();
    }

    /**
     * Sends a Telegram reply, threading the optional decided delay into the timing path.
     *
     * @param decidedDelaySeconds nullable pre-send delay from the decision engine (MUST-FIX #3)
     */
    private Mono<TdApi.Message> sendTelegramReply(String botId, long chatId, long originalMessageId,
                                                   String cleanedReplyText, int personaIndex,
                                                   Integer decidedDelaySeconds) {
        if (cleanedReplyText == null || cleanedReplyText.isBlank()) {
            log.info("--- TG SEND SKIP: No reply text provided for chat={}, originalMessageId={}.", chatId, originalMessageId);
            return Mono.empty();
        }

        // Outbound moderation is now enforced centrally in TelegramMessageSenderImpl
        // (single choke point) — no per-path guard needed here.
        return showTypingIndicatorAndSendReply(botId, chatId, originalMessageId, cleanedReplyText, personaIndex, decidedDelaySeconds);
    }

    // Legacy overload: keeps existing call sites compiling without changes
    private Mono<TdApi.Message> sendTelegramReply(String botId, long chatId, long originalMessageId,
                                                   String cleanedReplyText, int personaIndex) {
        return sendTelegramReply(botId, chatId, originalMessageId, cleanedReplyText, personaIndex, null);
    }

    private Mono<TdApi.Message> showTypingIndicatorAndSendReply(String botId, long chatId, long originalMessageId,
                                                                  String cleanedReplyText, int personaIndex,
                                                                  Integer decidedDelaySeconds) {
        long preSendDelayMs = computeReplyDelayMs(chatId, personaIndex, decidedDelaySeconds);
        int typingDurationMs = computeTypingWindowMs(chatId, cleanedReplyText);
        log.info("--- TG TIMING: chat={} persona#{} preSendDelayMs={} typingMs={}",
                chatId, personaIndex, preSendDelayMs, typingDurationMs);

        Mono<Void> showTypingMono = Mono.create(sink -> {
            TdApi.SendChatAction chatAction = new TdApi.SendChatAction(chatId, 0, new TdApi.ChatActionTyping());
            TelegramClientFacade client = telegramClientManager.getClient(botId);
            if (client == null) {
                log.error("No telegram client found for botId={} when showing typing", botId);
                sink.success();
                return;
            }
            client.send(chatAction, result -> {
                if (result.isError()) {
                    log.debug("Failed to show typing indicator for chat {}: {}", chatId, result.getError());
                }
                sink.success();
            });
        });

        // Sequence: staggered random "thinking" pause -> typing bubble for a length-proportional
        // window -> send. preSendDelayMs is 0 for non-test chats, so they keep legacy behavior.
        Mono<Void> preSend = preSendDelayMs > 0 ? Mono.delay(Duration.ofMillis(preSendDelayMs)).then() : Mono.empty();
        return preSend
                .then(showTypingMono)
                .then(Mono.delay(Duration.ofMillis(typingDurationMs)))
                .then(sendActualReply(botId, chatId, originalMessageId, cleanedReplyText));
    }

    /**
     * Pre-send "thinking" delay: a random value in the configured window plus a
     * per-persona stagger (personaIndex * step-ms). Applies to ALL chats.
     * When {@code randomMax <= 0} the random part is skipped (feature off).
     *
     * <p>When decidedDelaySeconds is non-null (shape-replies=true and engine
     * provided a delay), the decided value is used as a FLOOR over the random part.
     * The decided floor bypasses the {@code randomMax<=0} check so engine-decided
     * delays still apply even when the random window is disabled.
     */
    private long computeReplyDelayMs(long chatId, int personaIndex, Integer decidedDelaySeconds) {
        long decidedMs = decidedDelaySeconds != null && decidedDelaySeconds > 0
                ? decidedDelaySeconds * 1000L
                : 0L;
        long randomMax = replyRandomDelayMaxMs();
        long staggerStep = replyStaggerStepMs();
        long stagger = (long) personaIndex * Math.max(0L, staggerStep);

        if (randomMax <= 0) {
            // Random window is off; apply decided floor + stagger if present
            return decidedMs > 0 ? decidedMs + stagger : 0L;
        }
        long min = Math.max(0L, replyRandomDelayMinMs());
        long max = Math.max(min + 1L, randomMax);
        long randomPart = ThreadLocalRandom.current().nextLong(min, max);
        long legacyDelay = randomPart + stagger;
        // Apply decided floor: take the larger of legacy random and engine-decided
        return Math.max(legacyDelay, decidedMs);
    }

    // Legacy overload without decidedDelaySeconds (preserves old call sites)
    private long computeReplyDelayMs(long chatId, int personaIndex) {
        return computeReplyDelayMs(chatId, personaIndex, null);
    }

    /**
     * Typing-indicator window. Config-driven (ms-per-char, capped) for ALL chats
     * when the random-delay feature is on ({@code randomMax > 0}); otherwise falls
     * back to the legacy heuristic so unchanged behaviour when feature is disabled.
     */
    private int computeTypingWindowMs(long chatId, String text) {
        if (replyRandomDelayMaxMs() <= 0) {
            return Math.min(calculateTypingDuration(text), 8000);
        }
        int length = text == null ? 0 : text.length();
        return Math.min(Math.max(1, length) * typingMsPerChar(), typingCapMs());
    }

    private Mono<TdApi.Message> sendActualReply(String botId, long chatId, long originalMessageId, String cleanedReplyText) {
        return Mono.create(sink -> {
            TelegramClientFacade client = telegramClientManager.getClient(botId);
            if (client == null) {
                sink.error(new IllegalStateException("No telegram client for botId " + botId));
                return;
            }
            TdApi.InputMessageContent content = new TdApi.InputMessageText(new TdApi.FormattedText(cleanedReplyText, null), null, false);
            TdApi.InputMessageReplyToMessage replyTo = new TdApi.InputMessageReplyToMessage();
            replyTo.chatId = chatId;
            replyTo.messageId = originalMessageId;
            TdApi.SendMessage sendMessageRequest = new TdApi.SendMessage(chatId, 0, replyTo, null, null, content);

            log.debug("--- TG SEND START: Sending reply for chat={}, originalMessageId={}", chatId, originalMessageId);
            client.send(sendMessageRequest, result -> {
                if (result.isError()) {
                    log.error("!!! TG SEND FAIL: chat={}, originalMessageId={}. Error: {}", chatId, originalMessageId, result.getError());
                    sink.error(new IOException("Failed to send Telegram message: " + result.getError().message));
                } else {
                    TdApi.Message sentMessage = result.get();
                    log.info("--- TG SEND OK: chat={}, originalMessageId={}, newBotMsgId={}", chatId, originalMessageId, sentMessage.id);
                    sink.success(sentMessage);
                }
            });
        });
    }

    private Mono<Void> reportErrorToAdmin(Throwable error, long chatId, long originalMessageId) {
        String adminErrorMessage = String.format(
                "🚨 KAFKA PROCESSING ERROR\nChat: %d\nMessage: %d\nError: %s",
                chatId, originalMessageId, error.getMessage()
        );
        return sendAdminReport(adminErrorMessage).then();
    }

    private Mono<TdApi.Message> sendAdminReport(String errorMessage) {
        return Mono.create(sink -> {
            TelegramClientFacade client = telegramClientManager.getAnyClient();
            if (client == null) {
                sink.error(new IllegalStateException("No telegram clients available for admin report"));
                return;
            }
            TdApi.InputMessageContent content = new TdApi.InputMessageText(new TdApi.FormattedText(errorMessage, null), null, false);
            TdApi.SendMessage sendMessageRequest = new TdApi.SendMessage(ADMIN_CHAT_ID, 0, null, null, null, content);
            log.debug("--- ADMIN REPORT: Sending error report to admin chat {}", ADMIN_CHAT_ID);
            client.send(sendMessageRequest, result -> {
                if (result.isError()) {
                    log.error("!!! ADMIN REPORT FAIL: Could not send report. Error: {}", result.getError());
                    sink.error(new IOException("Failed to send admin report: " + result.getError().message));
                } else {
                    log.info("--- ADMIN REPORT OK: Report sent, msgId={}", result.get().id);
                    sink.success(result.get());
                }
            });
        });
    }

    // Helper methods (unchanged)
    private boolean isBotDetectionProbe(MessageEntity originalMessageEntity) {
        if (originalMessageEntity == null) {
            return false;
        }
        // Ignore forwarded/channel posts — they regularly contain "you" or question marks
        if (originalMessageEntity.getForwardFromChatId() != null) {
            return false;
        }
        if (originalMessageEntity.getSenderId() != null && originalMessageEntity.getSenderId() < 0) {
            return false;
        }

        String content = Optional.ofNullable(originalMessageEntity.getContent()).orElse("");
        if (content.isBlank()) {
            return false;
        }
        if (content.length() > 200) {
            return false;
        }

        String lower = content.toLowerCase();
        boolean bruteForce = lower.contains("ты бот") || lower.contains("ви бот") || lower.contains("are you a bot")
                || lower.contains("you're a bot") || lower.contains("bot?") || lower.contains("бот?")
                || lower.contains("бот,") || lower.contains("bot ,") || lower.contains("ботя")
                || lower.contains("бот ") || lower.contains(" bot");

        if (bruteForce) {
            return true;
        }

        MessageContextDto context = MessageContextDto.withHistory(originalMessageEntity.getChatId(), content, List.of(), false);
        return context.containsBotDetectionKeywords();
    }

    private MessageContextDto buildDetectionContext(MessageEntity originalMessageEntity) {
        if (originalMessageEntity == null) {
            return MessageContextDto.basic(0L, "");
        }
        String content = Optional.ofNullable(originalMessageEntity.getContent()).orElse("");
        return MessageContextDto.withHistory(originalMessageEntity.getChatId(), content, List.of(), false).withElevatedSuspicion(true);
    }

    private String truncateForLog(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() > 80 ? normalized.substring(0, 77) + "..." : normalized;
    }


    private int calculateTypingDuration(String text) {
        if (text == null || text.isEmpty()) {
            return 1000;
        }
        int baseDuration = (text.length() * 60 * 1000) / 200;
        double variation = 0.8 + (Math.random() * 0.4);
        double complexityMultiplier = text.length() > 500 ? 1.3 : (text.length() > 200 ? 1.1 : 1.0);
        return (int) (baseDuration * variation * complexityMultiplier);
    }

    private static ReplyMetadata extractReplyMetadata(TdApi.MessageReplyTo replyTo) {
        if (replyTo instanceof TdApi.MessageReplyToMessage r) {
            return new ReplyMetadata(r.messageId, r.chatId == 0 ? null : r.chatId);
        }
        return new ReplyMetadata(null, null);
    }

    private record ReplyMetadata(Long messageId, Long chatId) {
    }
}
