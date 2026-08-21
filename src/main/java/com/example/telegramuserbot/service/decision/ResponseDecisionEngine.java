package com.example.telegramuserbot.service.decision;

import com.example.telegramuserbot.domain.*;
import com.example.telegramuserbot.repository.*;
import com.example.telegramuserbot.service.config.AppSettingsService;
import com.example.telegramuserbot.service.decision.ConversationAnalysisService.ConversationContext;
import com.example.telegramuserbot.service.decision.ConversationAnalysisService.TopicComplexity;
import com.example.telegramuserbot.service.processing.MessageRateLimiterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Принимает решение о том, стоит ли отвечать на сообщение.
 * Использует настройки триггеров из БД (TriggerCondition) для определения КОГДА отвечать.
 */
@Service
public final class ResponseDecisionEngine {

    private static final Logger log = LoggerFactory.getLogger(ResponseDecisionEngine.class);
    private static final Duration BOT_SUSPICION_WINDOW = Duration.ofHours(2);
    private static final Duration REPOSITORY_TIMEOUT = Duration.ofSeconds(5);
    private static final String[] DIRECT_BOT_PHRASES = {
            "ты бот",
            "вы бот",
            "этот бот",
            "эта бот",
            "этого бота",
            "бот галим",
            "бот туп",
            "бот иди",
            "бот вон",
            "бот выключ",
            "бот забан",
            "бот бан",
            "бот уход",
            "бот свал",
            "бот исчез",
            "бот вали",
            "бот пропад",
            "бот убер",
            "бот пошол"
    };
    private static final Pattern FORCEFUL_BOT_COMMAND_PATTERN =
            Pattern.compile("бот[а-яa-z0-9\\s\\-]{0,12}(пош[еёо]л|пошла|иди|уйди|вали|свали|уходи|пропади|исчезни|убери|уберите|забань|забаньте|баньте|удали|удалите)");
    private static final Pattern FORCEFUL_COMMAND_BEFORE_BOT_PATTERN =
            Pattern.compile("(пош[еёо]л|пошла|иди|уйди|вали|свали|уходи|пропади|исчезни|убери|уберите|забань|забаньте|баньте|удали|удалите)[а-яa-z0-9\\s\\-]{0,12}бот");

    /**
     * Matches a real @mention token: an @username preceded by start-of-input or whitespace.
     * This avoids false positives from email addresses or any stray '@' in the middle of a word.
     */
    private static final Pattern AT_MENTION_PATTERN =
            Pattern.compile("(^|\\s)@\\w+", Pattern.UNICODE_CHARACTER_CLASS);

    /**
     * Matches the word "бот" or "bot" only as a whole Unicode word (not as a substring of
     * "работа", "суббота", "subbota", etc.).
     * UNICODE_CHARACTER_CLASS makes \b honour Cyrillic letter class.
     */
    private static final Pattern BOT_WORD_PATTERN =
            Pattern.compile("\\bбот\\b|\\bbot\\b", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);

    private final Random random = new Random();
    private final ChatConfigRepository chatConfigRepository;
    private final RateLimitsRepository rateLimitsRepository;
    private final TopicRestrictionRepository topicRestrictionRepository;
    private final TriggerConditionRepository triggerConditionRepository;
    private final MessageRateLimiterService rateLimiterService;
    private final MessageRepository messageRepository;
    private final CooldownService cooldownService;
    private final AppSettingsService appSettings;

    // Participant engagement (Phase 1): a human message shorter than this is filler ("спс",
    // emoji) and never worth engaging; longer = substantive enough to consider a reply.
    private static final int PARTICIPANT_MIN_CHARS = 15;

    /**
     * MUST-FIX #7: Cooldown ACTIVATION guard — disabled by default (false) so a single
     * user message with "бот" token cannot mute the chat.
     *
     * <p>The cooldown CHECK (isCooldownActive) is NOT gated — an already-active cooldown
     * is always respected. Only the spurious automatic ACTIVATION on bot-detection is
     * suppressed when this flag is false. Set {@code BOT_DECISION_GATE_COOLDOWN_ENABLED=true}
     * to restore the original bot-detection mute behaviour.
     */
    @Value("${bot.decision-gate.cooldown-enabled:false}")
    private boolean cooldownEnabled;

    public ResponseDecisionEngine(ChatConfigRepository chatConfigRepository,
                                  RateLimitsRepository rateLimitsRepository,
                                  TopicRestrictionRepository topicRestrictionRepository,
                                  TriggerConditionRepository triggerConditionRepository,
                                  MessageRateLimiterService rateLimiterService,
                                  MessageRepository messageRepository,
                                  CooldownService cooldownService,
                                  AppSettingsService appSettings) {
        this.chatConfigRepository = chatConfigRepository;
        this.rateLimitsRepository = rateLimitsRepository;
        this.topicRestrictionRepository = topicRestrictionRepository;
        this.triggerConditionRepository = triggerConditionRepository;
        this.rateLimiterService = rateLimiterService;
        this.messageRepository = messageRepository;
        this.cooldownService = cooldownService;
        this.appSettings = appSettings;
    }

    private enum SourceType {
        NOT_RELEVANT,
        FORWARD_FROM_PRIMARY,
        REPLY_TO_BOT
    }

    private enum UserMood {
        NEUTRAL, AGGRESSIVE, NEGATIVE
    }

// В классе ResponseDecisionEngine

    public Mono<ResponseDecision> decide(ConversationContext context) {
        if (!context.isValid()) {
            log.warn("🚫 РЕШЕНИЕ: контекст невалидный - НЕ ОТВЕЧАЕМ");
            return Mono.just(ResponseDecision.skip("Невалидный контекст"));
        }

        if (isSelfMalfunctionDetected(context)) {
            log.warn("!!! ОБНАРУЖЕН СОБСТВЕННЫЙ СБОЙ. Обработка прекращена. Summary: {}", context.topic().summary());
            return Mono.just(ResponseDecision.skip("Self-malfunction detected"));
        }

        long chatId = context.triggeringMessage().getChatId();

        if (cooldownService.isSilenced(chatId)) {
            log.info("🚫 РЕШЕНИЕ: в чате {} активен режим тишины после негатива", chatId);
            return Mono.just(ResponseDecision.skip("Активен режим тишины"));
        }

        return chatConfigRepository.findByChannelChatId(chatId)
                .flatMap(chatConfig -> {
                    if (!chatConfig.isEnabled()) {
                        log.info("🚫 РЕШЕНИЕ: чат {} отключен в конфигурации", chatId);
                        return Mono.just(ResponseDecision.skip("Чат отключен"));
                    }
                    log.debug("📋 КОНФИГУРАЦИЯ: чат {} настроен и включен", chatId);

                    MessageEntity triggeringMessage = context.triggeringMessage();

                    // Anti-loop guard (FR-003 / FR-004): outgoing messages are persona-sent.
                    // Suppress them early unless the message is a directed Telegram reply
                    // whose reply_to_message_id resolves to a stored outgoing message —
                    // that pattern is an intentional persona-to-persona exchange.
                    if (triggeringMessage.isOutgoing()) {
                        return evaluateOutgoingAntiLoop(chatId, triggeringMessage)
                                .flatMap(allowed -> {
                                    if (!allowed) {
                                        log.info("INFO [chat={}] anti-loop guard: outgoing non-directed message {} suppressed",
                                                chatId, triggeringMessage.getMessageId());
                                        return Mono.just(ResponseDecision.skip("anti-loop guard: outgoing non-directed message suppressed"));
                                    }
                                    log.info("INFO [chat={}] anti-loop guard: outgoing directed reply {} allowed, proceeding to trigger evaluation",
                                            chatId, triggeringMessage.getMessageId());
                                    return respondWithRateLimits(context, chatConfig);
                                });
                    }

                    // ИСПРАВЛЕНО: Теперь мы сначала асинхронно получаем sourceType
                    return checkMessageSource(chatConfig, triggeringMessage)
                            .flatMap(sourceType -> {
                                // Теперь sourceType - это обычный enum, и мы можем с ним работать
                                UserMood mood = determineMood(triggeringMessage.getContent());

                                if (sourceType == SourceType.REPLY_TO_BOT && mood == UserMood.AGGRESSIVE) {
                                    // MUST-FIX #7: gate cooldown ACTIVATION behind the cooldown-enabled flag.
                                    // The cooldown CHECK (isCooldownActive above) is always active.
                                    // Only suppress the spurious auto-ACTIVATION here.
                                    if (cooldownEnabled) {
                                        log.warn("😡 ОБНАРУЖЕНА АГРЕССИЯ в ответе боту в чате {}. Активируем режим тишины.", chatId);
                                        cooldownService.silenceNow(chatId);
                                    } else {
                                        log.info("😡 Aggression detected in chat {} (cooldown-enabled=false, skipping activation)", chatId);
                                    }
                                    return Mono.just(ResponseDecision.skip("Обнаружена агрессия в адрес бота"));
                                }

                                return shouldActivateCooldownDueToBotDetection(context, sourceType, mood)
                                        .flatMap(shouldActivate -> {
                                            if (shouldActivate) {
                                                // Deflect-then-silence (owner decision): the FIRST accusation
                                                // in the window is laughed off — the persona keeps replying
                                                // naturally; a REPEAT silences the chat for the cooldown.
                                                boolean repeat = cooldownEnabled && cooldownService.registerStrike(chatId);
                                                if (repeat) {
                                                    log.warn("😡 ПОВТОРНОЕ подозрение на бота в чате {} — режим тишины.", chatId);
                                                    return Mono.just(ResponseDecision.skip("Повторное подозрение на бота — тишина"));
                                                }
                                                log.warn("😏 Первое подозрение на бота в чате {} — отвечаем естественно (уход от темы).", chatId);
                                            }

                                            if (sourceType == SourceType.FORWARD_FROM_PRIMARY || sourceType == SourceType.REPLY_TO_BOT) {
                                                log.info("✅ РЕШЕНИЕ: Прямое срабатывание по источнику '{}'.", sourceType);
                                                return respondWithRateLimits(context, chatConfig);
                                            }

                                            if (chatConfig.getPrimaryChannelId() != null) {
                                                // Participant engagement (Phase 1): in allow-listed discussion chats, let a
                                                // persona occasionally join a SUBSTANTIVE human message (not just author
                                                // forwards / replies-to-bot). Default OFF (empty allowlist); low probability,
                                                // and the per-hour rate cap + per-persona reply_probability still bound it.
                                                if (isParticipantEngagementEnabled(chatId)
                                                        && isSubstantiveParticipantMessage(triggeringMessage)
                                                        && random.nextDouble() < participantEngagementProbability()) {
                                                    log.info("✅ РЕШЕНИЕ: участник-в-обсуждение — содержательное сообщение {} в чате {}",
                                                            triggeringMessage.getMessageId(), chatId);
                                                    return respondWithRateLimits(context, chatConfig);
                                                }
                                                log.info("🚫 РЕШЕНИЕ: сообщение {} в чате {} не является форвардом или ответом боту.", triggeringMessage.getMessageId(), chatId);
                                                return Mono.just(ResponseDecision.skip("Источник не релевантен для кросс-канальной логики"));
                                            }

                                            return evaluateTriggers(chatId, triggeringMessage.getContent(), context)
                                                    .flatMap(triggerResult -> {
                                                        if (!triggerResult.triggered()) {
                                                            log.info("🚫 РЕШЕНИЕ: триггеры не сработали для чата {} - {}", chatId, triggerResult.reason());
                                                            return Mono.just(ResponseDecision.skip(triggerResult.reason()));
                                                        }
                                                        log.info("✅ ТРИГГЕР СРАБОТАЛ: '{}' для чата {} (приоритет={})",
                                                                triggerResult.triggerName(), chatId, triggerResult.priority());

                                                        return isTopicRestricted(chatId, triggeringMessage.getContent())
                                                                .flatMap(isRestricted -> {
                                                                    if (isRestricted) {
                                                                        log.info("🚫 РЕШЕНИЕ: сообщение заблокировано ограничениями по темам для чата {}", chatId);
                                                                        return Mono.just(ResponseDecision.skip("Тема ограничена"));
                                                                    }
                                                                    return respondWithRateLimits(chatId, context, chatConfig, triggerResult);
                                                                });
                                                    });
                                        });
                            });
                })
                .switchIfEmpty(Mono.fromSupplier(() -> {
                    log.info("🚫 РЕШЕНИЕ: чат {} не настроен", chatId);
                    return ResponseDecision.skip("Чат не настроен");
                }))
                // NFR-004: emit a structured INFO entry for every decide() outcome
                .doOnNext(decision ->
                        log.info("DECIDE [chat={}] shouldRespond={} skipReason={}",
                                chatId, decision.shouldRespond(),
                                decision.reason() != null ? decision.reason() : "-"));
    }

    /** Phase-1 participant engagement: is this chat in the runtime allow-list? Default OFF. */
    private boolean isParticipantEngagementEnabled(long chatId) {
        String csv = appSettings.getString("decision.participant-engagement.chat-ids", "");
        if (csv == null || csv.isBlank()) {
            return false;
        }
        for (String token : csv.split(",")) {
            if (token.strip().equals(Long.toString(chatId))) {
                return true;
            }
        }
        return false;
    }

    /** Coin-flip gate for joining a participant message (separate from per-persona reply_probability). */
    private double participantEngagementProbability() {
        return appSettings.getDouble("decision.participant-engagement.probability", 0.2);
    }

    /** A participant message is worth engaging only if it carries real text (not "спс"/emoji/sticker). */
    private boolean isSubstantiveParticipantMessage(MessageEntity message) {
        String text = message.getContent() != null && !message.getContent().isBlank()
                ? message.getContent()
                : message.getCaption();
        return text != null && text.strip().length() >= PARTICIPANT_MIN_CHARS;
    }

    private boolean isSelfMalfunctionDetected(ConversationContext context) {
        if (context == null || context.topic() == null || context.topic().summary() == null) {
            return false;
        }
        String summary = context.topic().summary().toLowerCase();
        // Ключевые слова, указывающие на самоанализ сбоя
        return summary.contains("сбой в работе") ||
                summary.contains("технический сбой") ||
                summary.contains("повторяет") && summary.contains("сообщения");
    }

    private Mono<ResponseDecision> respondWithRateLimits(long chatId, ConversationContext context, ChatConfig chatConfig, TriggerEvaluationResult triggerResult) {
        // checkRateLimits теперь должен быть асинхронным
        return checkRateLimits(chatId)
	                .flatMap(rateLimitResult -> {
	                    if (!rateLimitResult.allowed()) {
	                        log.info("🚫 РЕШЕНИЕ: сообщение заблокировано rate limits: {}", rateLimitResult.reason());
	                        return Mono.just(ResponseDecision.skip("Rate limit: " + rateLimitResult.reason()));
	                    }

	                    incrementRateLimitCounters(chatId); // Это синхронный "fire-and-forget" метод

	                    ResponseIntent intent = determineResponseIntent(context);
	                    ResponseTone tone = determineResponseTone(context);
                    ResponseLength responseLength = triggerResult.responseLength();
                    int delaySeconds = triggerResult.delaySeconds();

                    log.info("✅ РЕШЕНИЕ: ОТВЕЧАЕМ с задержкой {} сек (триггер='{}', намерение={}, тон={}, длина={})",
                            delaySeconds, triggerResult.triggerName(), intent, tone, responseLength);

                    return Mono.just(ResponseDecision.respond(1.0, intent, tone, responseLength, delaySeconds));
                });
    }


    private Mono<ResponseDecision> respondWithRateLimits(ConversationContext context, ChatConfig chatConfig) {
        long chatId = context.triggeringMessage().getChatId();

        // Асинхронно получаем триггеры
        return triggerConditionRepository
                .findByChatConfigChannelChatIdAndActiveOrderByPriorityDesc(chatId, true)
                .collectList()
                .flatMap(conditions -> {
                    TriggerCondition defaultTrigger = conditions.stream().findFirst().orElse(null);

                    int delaySeconds = defaultTrigger != null && defaultTrigger.getTimeDelaySeconds() != null ? defaultTrigger.getTimeDelaySeconds() : 5;
                    ResponseLength length = defaultTrigger != null ? defaultTrigger.getResponseLength() : ResponseLength.SHORT;
                    String triggerName = defaultTrigger != null ? defaultTrigger.getConditionName() : "direct_source";

                    TriggerEvaluationResult pseudoResult = TriggerEvaluationResult.triggered(triggerName, 99, length, delaySeconds);

                    // Вызываем уже асинхронную версию respondWithRateLimits
                    return respondWithRateLimits(chatId, context, chatConfig, pseudoResult);
                });
    }

    private Mono<SourceType> checkMessageSource(ChatConfig chatConfig, MessageEntity message) {
        if (message == null || message.isOutgoing()) {
            return Mono.just(SourceType.NOT_RELEVANT);
        }

        Long primaryChannelId = chatConfig.getPrimaryChannelId();

        // isReplyToBotMessage теперь асинхронный
        return isReplyToBotMessage(chatConfig, message)
                .map(isReply -> {
                    if (isReply) {
                        // Не отвечать на комментарии в основном канале
                        if (primaryChannelId != null && primaryChannelId.equals(message.getChatId())) {
                            log.info("🔎 SOURCE CHECK: {} — комментарий в основном канале {}, игнорируем",
                                    message.getMessageId(), primaryChannelId);
                            return SourceType.NOT_RELEVANT;
                        }

                        log.info("🔎 SOURCE CHECK: {} — классифицировано как ответ на сообщение бота в чате обсуждений",
                                message.getMessageId());
                        return SourceType.REPLY_TO_BOT;
                    }

                    if (primaryChannelId != null) {
                        Long forwardFrom = message.getForwardFromChatId();
                        Long senderId = message.getSenderId();
                        if (primaryChannelId.equals(forwardFrom) || primaryChannelId.equals(senderId)) {
                            log.info("🔎 SOURCE CHECK: {} — классифицировано как форвард из основного канала {}", message.getMessageId(), primaryChannelId);
                            return SourceType.FORWARD_FROM_PRIMARY;
                        }
                    }

                    return SourceType.NOT_RELEVANT;
                });
    }

    private UserMood determineMood(String content) {
        if (content == null || content.isBlank()) {
            return UserMood.NEUTRAL;
        }
        String lower = content.toLowerCase();

        String[] aggressiveWords = {
                "тупой", "бот", "забаньте", "удали", "галимый", "ботяра", "пошел вон",
                "админ", "забань", "удалите" // Добавляем новые слова
        };
        for (String word : aggressiveWords) {
            // Ищем слова, которые могут быть частью призыва к админу
            if (lower.contains("админ") && (lower.contains("удал") || lower.contains("забан"))) {
                return UserMood.AGGRESSIVE;
            }
            // Стандартная проверка на целые слова
            if (Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(lower).find()) {
                return UserMood.AGGRESSIVE;
            }
        }

        String[] negativeWords = {"бред", "чушь", "не согласен", "плохо", "ужас"};
        for (String word : negativeWords) {
            if (Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(lower).find()) {
                return UserMood.NEGATIVE;
            }
        }
        return UserMood.NEUTRAL;
    }

    /**
     * Он определяет, нужно ли активировать режим тишины из-за подозрительного
     * упоминания бота.
     */
    private Mono<Boolean> shouldActivateCooldownDueToBotDetection(ConversationContext context,
                                                                  SourceType sourceType,
                                                                  UserMood mood) {
        boolean isIndirectSource = sourceType == SourceType.NOT_RELEVANT || sourceType == SourceType.FORWARD_FROM_PRIMARY;
        if (!isIndirectSource || mood != UserMood.AGGRESSIVE) {
            return Mono.just(false); // Синхронная проверка, возвращаем Mono с результатом
        }

        MessageEntity trigger = context.triggeringMessage();
        if (trigger == null) {
            return Mono.just(false);
        }
        String content = trigger.getContent();
        if (content == null || content.isBlank() || !containsBotKeyword(content)) {
            return Mono.just(false);
        }

        // Начинаем асинхронную часть
        return findLastBotMessageBeforeTrigger(context)
                .map(lastBotMessage -> {
                    // Случай 1: Последнее сообщение бота НАЙДЕНО
                    if (trigger.getDate() == null || lastBotMessage.getDate() == null) {
                        return true; // Не можем сравнить даты, на всякий случай активируем
                    }

                    Duration sinceLastBotMessage = Duration.between(lastBotMessage.getDate(), trigger.getDate());
                    if (sinceLastBotMessage.isNegative()) {
                        return false; // Триггерное сообщение было раньше, чем последнее от бота - странно, но игнорируем
                    }
                    // Если с момента последнего ответа бота прошло меньше времени, чем окно подозрения, активируем
                    return sinceLastBotMessage.compareTo(BOT_SUSPICION_WINDOW) <= 0;
                })
                // Случай 2: Последнее сообщение бота НЕ НАЙДЕНО (Mono был пустой)
                .defaultIfEmpty(containsDirectAccusation(content));
    }

    private Mono<MessageEntity> findLastBotMessageBeforeTrigger(ConversationContext context) {
        MessageEntity trigger = context.triggeringMessage();
        if (trigger == null) {
            return Mono.empty();
        }

        // Сначала ищем в уже загруженном контексте (синхронная операция)
        Optional<MessageEntity> lastFromContext = context.recentMessages().stream()
                .filter(MessageEntity::isOutgoing)
                .filter(msg -> msg.getDate() != null && trigger.getDate() != null && !msg.getDate().isAfter(trigger.getDate()))
                .reduce((first, second) -> second);

        if (lastFromContext.isPresent()) {
            return Mono.just(lastFromContext.get());
        }

        // Если в контексте не нашли, делаем асинхронный запрос в БД
        return messageRepository
                .findTopByChatIdAndIsOutgoingTrueOrderByDateDesc(trigger.getChatId())
                .filter(latestBot -> {
                    // Убеждаемся, что найденное сообщение было до триггерного
                    if (latestBot.getDate() == null || trigger.getDate() == null) {
                        return false; // Не можем сравнить даты
                    }
                    return !latestBot.getDate().isAfter(trigger.getDate());
                });
    }

    private boolean containsBotKeyword(String content) {
        String[] tokens = content.toLowerCase(Locale.ROOT).split("\\PL+");
        for (String token : tokens) {
            if (token.isEmpty()) {
                continue;
            }
            if ("bot".equals(token) || token.startsWith("бот")) {
                return true;
            }
        }
        return false;
    }

    private boolean containsDirectAccusation(String content) {
        String lower = content.toLowerCase(Locale.ROOT);
        for (String phrase : DIRECT_BOT_PHRASES) {
            if (lower.contains(phrase)) {
                return true;
            }
        }
        return FORCEFUL_BOT_COMMAND_PATTERN.matcher(lower).find()
                || FORCEFUL_COMMAND_BEFORE_BOT_PATTERN.matcher(lower).find();
    }

    private Mono<Boolean> isTopicRestricted(long chatId, String messageText) {
        if (messageText == null || messageText.isBlank()) {
            return Mono.just(false);
        }
        return topicRestrictionRepository.findByChatConfigChannelChatIdAndActive(chatId, true)
                .collectList()
                .map(restrictions -> {
                    if (restrictions.isEmpty()) {
                        return false;
                    }
                    String lowerMessageText = messageText.toLowerCase(Locale.ROOT);
                    for (TopicRestriction restriction : restrictions) {
                        if (restriction.getRestrictedKeywords() != null) {
                            for (String keyword : restriction.getRestrictedKeywords()) {
                                String trimmedKeyword = keyword.trim().toLowerCase(Locale.ROOT);
                                if (!trimmedKeyword.isEmpty() && lowerMessageText.contains(trimmedKeyword)) {
                                    log.debug("Сообщение заблокировано ограничением '{}' (ключевое слово: '{}')", restriction.getRestrictionName(), trimmedKeyword);
                                    return true;
                                }
                            }
                        }
                    }
                    return false;
                });
    }

    private Mono<Boolean> isReplyToBotMessage(ChatConfig chatConfig, MessageEntity message) {
        if (message.getReplyToMessageId() == null) {
            return Mono.just(false);
        }

        // Игнорировать комментарии к постам из другого чата (например, комментарии к постам канала)
        // Если reply_to_chat_id != chat_id, значит это комментарий к посту из основного канала
        if (message.getReplyToChatId() != null && !message.getReplyToChatId().equals(message.getChatId())) {
            log.info("🔎 SOURCE CHECK: {} — комментарий к посту из другого чата (reply_to_chat_id={}, chat_id={}), игнорируем",
                    message.getMessageId(), message.getReplyToChatId(), message.getChatId());
            return Mono.just(false);
        }

        // КРИТИЧНО: НЕ отвечать на комментарии к forwards из primary channel!
        // Отвечать ТОЛЬКО на прямые replies к сообщениям БОТА
        return resolveReplyTarget(message)
                .flatMap(replied -> {
                    // Проверяем: это reply к нашему боту?
                    if (replied.isOutgoing()) {
                        log.info("🔎 SOURCE CHECK: {} — reply к СОБСТВЕННОМУ сообщению бота", message.getMessageId());
                        return Mono.just(true);
                    }

                    // КРИТИЧНО: если replied - это forward из primary channel, НЕ считаем это reply к боту!
                    Long primaryChannelId = chatConfig.getPrimaryChannelId();
                    if (primaryChannelId != null) {
                        boolean isForwardFromPrimary = primaryChannelId.equals(replied.getForwardFromChatId())
                                                    || primaryChannelId.equals(replied.getSenderId());
                        if (isForwardFromPrimary) {
                            log.info("🔎 SOURCE CHECK: {} — reply к FORWARD из primary channel, НЕ считаем reply к боту",
                                    message.getMessageId());
                            return Mono.just(false);
                        }
                    }

                    // Все остальные replies (к чужим сообщениям) НЕ считаем replies к боту
                    log.info("🔎 SOURCE CHECK: {} — reply к ЧУЖОМУ сообщению (sender_id={}, isOutgoing={}), НЕ к боту",
                            message.getMessageId(), replied.getSenderId(), replied.isOutgoing());
                    return Mono.just(false);
                })
                .defaultIfEmpty(false); // Если сообщение, на которое отвечают, не найдено
    }

    private boolean isOutgoingOrForwardedBotMessage(ChatConfig chatConfig, MessageEntity replied) {
        if (replied.isOutgoing()) {
            return true;
        }
        if (chatConfig.isRespondToForwardedBotMessages()) {
            Long primaryChannelId = chatConfig.getPrimaryChannelId();
            return primaryChannelId != null && (primaryChannelId.equals(replied.getForwardFromChatId()) || primaryChannelId.equals(replied.getSenderId()));
        }
        return false;
    }

    private Mono<MessageEntity> resolveReplyTarget(MessageEntity message) {
        Long replyToMessageId = message.getReplyToMessageId();
        if (replyToMessageId == null) {
            return Mono.empty();
        }
        long lookupChatId = message.getReplyToChatId() != null ? message.getReplyToChatId() : message.getChatId();
        return messageRepository.findByChatIdAndMessageId(lookupChatId, replyToMessageId);
    }

    /**
     * Anti-loop gate for outgoing (persona-sent) messages (FR-003 / FR-004 / NFR-001).
     *
     * <p>Returns {@code true} (allowed) only when the outgoing triggering message is a
     * Telegram reply ({@code reply_to_message_id} non-null) whose target resolves to a
     * stored {@link MessageEntity} with {@code isOutgoing=true} — i.e., a directed reply
     * from one persona to another persona's prior message.
     *
     * <p>All other outgoing messages (no reply, reply to incoming human message, reply
     * target not found in DB) return {@code false} (suppress).
     *
     * <p>Uses the already-resolved {@link MessageEntity} in the conversation context and
     * reuses the existing {@link #resolveReplyTarget} DB call — no additional query beyond
     * what the method already performs (NFR-001).
     *
     * @param chatId           the chat being evaluated — used only for logging
     * @param outgoingMessage  the outgoing message under evaluation
     * @return {@code Mono<Boolean>} — {@code true} means proceed, {@code false} means suppress
     */
    private Mono<Boolean> evaluateOutgoingAntiLoop(long chatId, MessageEntity outgoingMessage) {
        Long replyToMessageId = outgoingMessage.getReplyToMessageId();
        if (replyToMessageId == null) {
            log.debug("anti-loop [chat={}] message={} has no reply_to — suppressing", chatId, outgoingMessage.getMessageId());
            return Mono.just(false);
        }
        return resolveReplyTarget(outgoingMessage)
                .map(replied -> {
                    boolean isDirectedReply = replied.isOutgoing();
                    log.debug("anti-loop [chat={}] message={} reply_to={} isOutgoing={} → directed={}",
                            chatId, outgoingMessage.getMessageId(), replyToMessageId, replied.isOutgoing(), isDirectedReply);
                    return isDirectedReply;
                })
                .defaultIfEmpty(false); // reply target not found in DB — suppress
    }

	    private Mono<RateLimitResult> checkRateLimits(long chatId) {
	        return rateLimitsRepository.findByChatConfigChannelChatId(chatId)
	                .map(rateLimits -> {
	                    // Все проверки ниже - синхронные
	                    if (rateLimits.getMaxMessagesPerHour() != null && rateLimiterService.getCurrentHourlyUsage(chatId) >= rateLimits.getMaxMessagesPerHour()) {
	                        return RateLimitResult.reject("Превышен часовой лимит");
	                    }
	                    if (rateLimits.getMaxMessagesPerMinute() != null && rateLimiterService.getCurrentMinuteUsage(chatId) >= rateLimits.getMaxMessagesPerMinute()) {
	                        return RateLimitResult.reject("Превышен минутный лимит");
                    }
                    return RateLimitResult.ok();
                })
	                // Если rateLimits для чата не найдены, то ограничений нет
	                .defaultIfEmpty(RateLimitResult.ok());
	    }

    private void incrementRateLimitCounters(long chatId) {
        rateLimiterService.incrementUsageCounters(chatId, 0); // Burst window can be refined if needed
    }

    // В классе ResponseDecisionEngine

    /**
     * ИСПРАВЛЕНО: Метод теперь полностью реактивный и возвращает Mono<TriggerEvaluationResult>.
     * Он асинхронно находит и оценивает триггеры для данного сообщения.
     */
    private Mono<TriggerEvaluationResult> evaluateTriggers(long chatId, String messageText, ConversationContext context) {
        // 1. Асинхронно получаем поток (Flux) активных триггеров, уже отсортированных по приоритету.
        return triggerConditionRepository.findByChatConfigChannelChatIdAndActiveOrderByPriorityDesc(chatId, true)
                .collectList() // Собираем в список, чтобы залогировать количество
                .flatMap(activeConditions -> {
                    if (activeConditions.isEmpty()) {
                        return Mono.just(TriggerEvaluationResult.notTriggered("Нет настроенных триггеров"));
                    }
                    log.info("📋 НАЙДЕНО {} активных триггеров для чата {}: {}", activeConditions.size(), chatId, activeConditions.stream().map(TriggerCondition::getConditionName).toList());

                    // Превращаем список обратно в Flux для дальнейшей обработки
                    return Flux.fromIterable(activeConditions)
                            .concatMap(condition -> evaluateCondition(condition, messageText, context, LocalDateTime.now())
                                    .filter(isTriggered -> isTriggered) // Пропускаем дальше только если isTriggered == true
                                    .map(isTriggered -> condition) // Преобразуем в сам объект TriggerCondition
                            )
                            .next() // Берем ПЕРВЫЙ сработавший триггер
                            .map(condition -> {
                                log.info("✅ ТРИГГЕР СРАБОТАЛ: '{}' для чата {} (приоритет={})",
                                        condition.getConditionName(), chatId, condition.getPriority());
                                return TriggerEvaluationResult.triggered(
                                        condition.getConditionName(),
                                        condition.getPriority(),
                                        condition.getResponseLength(),
                                        condition.getTimeDelaySeconds() != null ? condition.getTimeDelaySeconds() : 0
                                );
                            })
                            .switchIfEmpty(Mono.fromSupplier(() -> {
                                log.info("🚫 РЕШЕНИЕ: триггеры не сработали для чата {}", chatId);
                                return TriggerEvaluationResult.notTriggered("Ни один триггер не сработал");
                            }));
                });
    }

    /**
     * ИСПРАВЛЕНО: Метод теперь возвращает Mono<Boolean>.
     * Он асинхронно проверяет все условия для одного триггера.
     */
    private Mono<Boolean> evaluateCondition(TriggerCondition condition, String messageText, ConversationContext context, LocalDateTime currentTime) {
        // Сначала выполняем все быстрые, синхронные проверки
        if (!isActiveTime(condition, currentTime) || (condition.isMentionRequired() && !isMentionDetected(context))) {
            return Mono.just(false);
        }

        // Затем выполняем асинхронную проверку интервала
        return checkMinimumGap(condition, context)
                .flatMap(gapOk -> {
                    if (!gapOk) {
                        return Mono.just(false); // Если интервал не прошел, дальше не проверяем
                    }

                    // Интервал пройден, выполняем остальные синхронные проверки
                    boolean matches = switch (condition.getTriggerType()) {
                        case KEYWORD_MATCH -> evaluateKeywordMatch(condition, messageText);
                        case QUESTION_DETECTED ->
                                context.topic().topicType() == ConversationAnalysisService.TopicType.QUESTION;
                        case RANDOM ->
                                random.nextInt(100) < (condition.getProbabilityPercent() != null ? condition.getProbabilityPercent() : 100);
                        case CONTINUOUS -> true;
                        case CONTEXT_AWARE -> evaluateContextAware(condition, context);
                        default -> {
                            log.warn("Неподдерживаемый тип триггера: {}", condition.getTriggerType());
                            yield false;
                        }
                    };

                    // Применяем финальную проверку вероятности
                    if (matches && condition.getProbabilityPercent() != null && condition.getProbabilityPercent() < 100) {
                        return Mono.just(random.nextInt(100) < condition.getProbabilityPercent());
                    }

                    return Mono.just(matches);
                });
    }

    /**
     * ИСПРАВЛЕНО: Метод теперь возвращает Mono<Boolean>.
     */
    private Mono<Boolean> checkMinimumGap(TriggerCondition condition, ConversationContext context) {
        Integer minimumGapMinutes = condition.getMinimumGapMinutes();
        if (minimumGapMinutes == null || minimumGapMinutes <= 0) {
            return Mono.just(true); // Нет ограничений
        }

        MessageEntity trigger = context.triggeringMessage();
        if (trigger == null || trigger.getDate() == null) {
            return Mono.just(true);
        }

        // findLastBotMessageBeforeTrigger теперь асинхронный
        return findLastBotMessageBeforeTrigger(context)
                .map(lastBotMessage -> {
                    Duration gap = Duration.between(lastBotMessage.getDate(), trigger.getDate());
                    long secondsSinceLastBotMessage = Math.max(0, gap.getSeconds());
                    long requiredSeconds = minimumGapMinutes * 60L;

                    if (secondsSinceLastBotMessage < requiredSeconds) {
                        log.debug("⏱️ Триггер '{}' заблокирован: прошло {}, требуется {} мин",
                                condition.getConditionName(), formatDuration(secondsSinceLastBotMessage), minimumGapMinutes);
                        return false;
                    }
                    log.debug("⏱️ Триггер '{}' прошёл проверку интервала: {}, требуется {} мин",
                            condition.getConditionName(),
                            formatDuration(secondsSinceLastBotMessage),
                            minimumGapMinutes);
                    return true;
                })
                .defaultIfEmpty(true); // Если последнее сообщение бота не найдено, разрешаем
    }

    /**
     * Проверяет активное время триггера
     */
    private boolean isActiveTime(TriggerCondition condition, LocalDateTime currentTime) {
        // Проверка дня недели
        String activeDays = condition.getActiveDaysOfWeek();
        if (activeDays != null && !activeDays.isBlank()) {
            int currentDayOfWeek = currentTime.getDayOfWeek().getValue(); // Monday = 1, Sunday = 7
            List<String> allowedDays = Arrays.asList(activeDays.split(","));
            if (!allowedDays.contains(String.valueOf(currentDayOfWeek))) {
                return false;
            }
        }

        // Проверка времени
        LocalTime activeStart = condition.getActiveHoursStart();
        LocalTime activeEnd = condition.getActiveHoursEnd();

        if (activeStart != null && activeEnd != null) {
            LocalTime currentTimeOfDay = currentTime.toLocalTime();

            if (activeStart.isBefore(activeEnd)) {
                // Обычный диапазон (например, 09:00 - 17:00)
                return !currentTimeOfDay.isBefore(activeStart) && !currentTimeOfDay.isAfter(activeEnd);
            } else {
                // Переход через полночь (например, 22:00 - 06:00)
                return !currentTimeOfDay.isBefore(activeStart) || !currentTimeOfDay.isAfter(activeEnd);
            }
        }

        return true;
    }

    private String formatDuration(long totalSeconds) {
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        if (minutes <= 0) {
            return seconds + " сек";
        }
        if (seconds == 0) {
            return minutes + " мин";
        }
        return minutes + " мин " + seconds + " сек";
    }

    /**
     * Проверяет наличие упоминания бота в сообщении.
     *
     * <p>В приватных чатах (chatId > 0) всегда возвращает {@code true}.
     *
     * <p>В группах/каналах (chatId < 0) проверяет:
     * <ol>
     *   <li>Реальный @-тег — токен {@code @handle}, стоящий в начале строки или после пробела
     *       (исключает email-адреса и '@' внутри слова).</li>
     *   <li>Слово «бот»/«bot» как целое слово с Unicode-границами (не как подстроку
     *       «работа», «суббота» и т. п.).</li>
     * </ol>
     *
     * <p>TODO (follow-up): когда persona identity будет доступна в {@code ConversationContext},
     * сравнивать @-тег конкретно с username/именем этой персоны.
     */
    private boolean isMentionDetected(ConversationContext context) {
        long chatId = context.triggeringMessage().getChatId();

        // Приватный чат — всегда считается упоминанием
        if (chatId >= 0) {
            return true;
        }

        // Группа/канал: ищем реальный @-тег или целое слово "бот"/"bot"
        String messageText = context.triggeringMessage().getContent();
        if (messageText == null) {
            return false;
        }

        if (AT_MENTION_PATTERN.matcher(messageText).find()) {
            log.trace("isMentionDetected: найден @-тег в сообщении chatId={}", chatId);
            return true;
        }

        if (BOT_WORD_PATTERN.matcher(messageText).find()) {
            log.trace("isMentionDetected: найдено слово бот/bot в сообщении chatId={}", chatId);
            return true;
        }

        return false;
    }

    /**
     * Проверяет совпадение ключевых слов
     */
    private boolean evaluateKeywordMatch(TriggerCondition condition, String messageText) {
        String keywords = condition.getKeywords();
        if (keywords == null || keywords.isBlank() || messageText == null || messageText.isBlank()) {
            return false;
        }

        String[] keywordList = keywords.split(",");
        String lowerMessageText = messageText.toLowerCase(Locale.ROOT);

        for (String keyword : keywordList) {
            String trimmedKeyword = keyword.trim().toLowerCase(Locale.ROOT);
            if (!trimmedKeyword.isEmpty() && lowerMessageText.contains(trimmedKeyword)) {
                log.trace("Найдено ключевое слово '{}' в сообщении", trimmedKeyword);
                return true;
            }
        }

        return false;
    }

    /**
     * Проверяет контекстно-зависимый триггер
     */
    private boolean evaluateContextAware(TriggerCondition condition, ConversationContext context) {
        // Простая логика на основе типа темы
        return switch (context.topic().topicType()) {
            case QUESTION -> true;
            case GREETING -> true;
            case INFORMATION -> context.topic().complexity() == TopicComplexity.HIGH;
            case DISCUSSION -> context.activity().messagesPerMinute() > 1.0;
            case CASUAL -> false;
        };
    }

    private ResponseIntent determineResponseIntent(ConversationContext context) {
        return switch (context.topic().topicType()) {
            case QUESTION -> ResponseIntent.ANSWER;
            case GREETING -> ResponseIntent.GREET_BACK;
            case DISCUSSION -> ResponseIntent.CONTRIBUTE;
            case INFORMATION -> ResponseIntent.COMMENT;
            case CASUAL -> ResponseIntent.CASUAL_CHAT;
        };
    }

    private ResponseTone determineResponseTone(ConversationContext context) {
        if (context.activity().messagesPerMinute() > 3.0) {
            return ResponseTone.BRIEF;
        }
        return switch (context.topic().complexity()) {
            case HIGH -> ResponseTone.THOUGHTFUL;
            case MEDIUM -> ResponseTone.FRIENDLY;
            case LOW -> ResponseTone.CASUAL;
        };
    }


    public enum ResponseIntent {
        ANSWER,         // Ответить на вопрос
        GREET_BACK,     // Ответить на приветствие
        CONTRIBUTE,     // Внести вклад в обсуждение
        COMMENT,        // Прокомментировать информацию
        CASUAL_CHAT     // Обычная беседа
    }

    public enum ResponseTone {
        BRIEF,          // Краткий
        CASUAL,         // Обычный
        FRIENDLY,       // Дружелюбный
        THOUGHTFUL      // Вдумчивый
    }

    public record ResponseDecision(
            boolean shouldRespond,
            double confidence,
            ResponseIntent intent,
            ResponseTone tone,
            ResponseLength responseLength,
            int delaySeconds,
            String reason
    ) {
        public static ResponseDecision respond(double confidence, ResponseIntent intent, ResponseTone tone, ResponseLength responseLength, int delaySeconds) {
            return new ResponseDecision(true, confidence, intent, tone, responseLength, delaySeconds, null);
        }

        public static ResponseDecision skip(String reason) {
            return new ResponseDecision(false, 0.0, null, null, null, 0, reason);
        }
    }

    /**
     * Результат проверки rate limits
     */
    private record RateLimitResult(
            boolean allowed,
            String reason
    ) {
        public static RateLimitResult ok() {
            return new RateLimitResult(true, null);
        }

        public static RateLimitResult reject(String reason) {
            return new RateLimitResult(false, reason);
        }
    }

    /**
     * Результат оценки триггеров
     */
    private record TriggerEvaluationResult(
            boolean triggered,
            String triggerName,
            int priority,
            ResponseLength responseLength,
            int delaySeconds,
            String reason
    ) {
        public static TriggerEvaluationResult triggered(String triggerName, int priority, ResponseLength responseLength, int delaySeconds) {
            return new TriggerEvaluationResult(true, triggerName, priority, responseLength, delaySeconds, null);
        }

        public static TriggerEvaluationResult notTriggered(String reason) {
            return new TriggerEvaluationResult(false, null, 0, null, 0, reason);
        }
    }
}
