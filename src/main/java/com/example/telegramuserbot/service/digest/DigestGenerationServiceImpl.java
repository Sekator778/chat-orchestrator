package com.example.telegramuserbot.service.digest;

import com.example.telegramuserbot.domain.DigestHistory;
import com.example.telegramuserbot.domain.DigestPersona;
import com.example.telegramuserbot.domain.DigestPersonaStyle;
import com.example.telegramuserbot.domain.MessageEntity;
import com.example.telegramuserbot.dto.digest.GeneratedDigestDto;
import com.example.telegramuserbot.repository.DigestHistoryRepository;
import com.example.telegramuserbot.repository.DigestPersonaRepository;
import com.example.telegramuserbot.repository.MessageRepository;
import com.example.telegramuserbot.repository.SourceTrustRepository;
import com.example.telegramuserbot.service.humanization.AntiDetectionService;
import com.example.telegramuserbot.service.humanization.PersonaService;
import com.example.telegramuserbot.service.humanization.ResponseRefinerService;
import com.example.telegramuserbot.service.llm.client.DeepSeekApiClient;
import com.example.telegramuserbot.service.llm.dto.ApiMessage;
import com.example.telegramuserbot.service.llm.dto.DeepSeekChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Implementation of DigestGenerationService.
 * Generates persona-aware news digests using clustering and LLM synthesis.
 */
@Service
public final class DigestGenerationServiceImpl implements DigestGenerationService {

    private static final Logger log = LoggerFactory.getLogger(DigestGenerationServiceImpl.class);
    private static final Duration OPERATION_TIMEOUT = Duration.ofSeconds(5);
    private static final int LLM_TIMEOUT_SECONDS = 60;
    private static final int MAX_CONTENT_LENGTH = 500;
    private static final long DIGEST_CHAT_ID = -1L;
    private static final java.util.Random FORMAT_RANDOM = new java.util.Random();

    private static final String BASE_RULES = """
            STRICT RULES:
            - No markdown (**, *, #, ###). Plain text only.
            - No hashtags, no URLs, no "subscribe/like".
            - Max one emoji per message, only if it fits naturally.
            - Never say "digest", "summary", "review", "news roundup", "дайджест", "обзор", "сводка".
            - Never reveal you are a bot/AI/program. You are a real person.
            - Write about THIS ONE specific news item only. Do not combine multiple topics.
            - ALWAYS weave the source name into the text naturally, like a person referencing where they read it. Examples: "как пишет Банкстер", "по данным ВЕДОМОСТИ", "according to CoinDesk", "на канале Банкофф пишут что...". The source is in [Source] tag. NEVER copy [Source: ...] literally — paraphrase it as part of a sentence. Never invent source names.
            """;

    private static final String[] POST_FORMATS_RU = {
            // Format 1: Opinion with context (3-5 sentences)
            """
            Напиши своё мнение об этой новости. 3-5 предложений.
            Сначала кратко обозначь суть (чтобы читатель понял о чём речь), потом свой комментарий.
            Покажи эмоцию — удивление, раздражение, интерес, беспокойство.
            Влетай в тему сразу, без вступлений типа "Доброе утро" или "Итак".
            """,
            // Format 2: Analysis-lite (1 short paragraph)
            """
            Прокомментируй эту новость с позиции человека, который разбирается в теме. 4-6 предложений.
            Объясни почему это важно или к чему приведёт. Будь конкретен, но разговорен.
            Не будь занудой — пиши живо, как умный друг рассказывает в чате.
            """,
            // Format 3: Story-style (2 short paragraphs)
            """
            Расскажи об этой новости как историю. 2 коротких абзаца.
            Первый абзац — что произошло, своими словами, не копируя источник.
            Второй абзац — почему тебя это зацепило и что ты об этом думаешь.
            """,
            // Format 4: Question to audience
            """
            Кратко расскажи об этой новости (3-4 предложения) и задай вопрос читателям.
            Вопрос должен быть искренним, не риторическим. Как будто тебе правда интересно мнение.
            Пример окончания: "... а вы как думаете?" или "... у вас такое было?"
            """,
            // Format 5: Fact + personal take
            """
            Начни с сути новости (1-2 предложения фактов), потом дай свою оценку (2-3 предложения).
            Оценка должна быть личной — что ТЫ думаешь, а не общие фразы.
            Всего 4-5 предложений. Говори от первого лица.
            """
    };

    private static final String[] POST_FORMATS_EN = {
            "Share your opinion on this news. 3-5 sentences. First briefly set the context, then your take. Show emotion. Jump right in, no greetings.",
            "Comment on this news from someone who understands the topic. 4-6 sentences. Explain why it matters. Be specific but conversational.",
            "Tell this news as a story. 2 short paragraphs. First: what happened, in your own words. Second: why it caught your attention and what you think.",
            "Briefly explain this news (3-4 sentences) and ask your readers a genuine question. The question should be sincere, not rhetorical.",
            "Start with the core fact (1-2 sentences), then give your personal take (2-3 sentences). Speak in first person. 4-5 sentences total."
    };

    private static final String[] STYLE_HINTS_RU = {
            "Тон: спокойный, рассудительный. Как аналитик, но без формализма.",
            "Тон: ироничный, с лёгким сарказмом. Видишь абсурд в ситуации.",
            "Тон: взволнованный, это важная новость и ты хочешь поделиться быстро.",
            "Тон: скептический. Ты не всему веришь и показываешь это.",
            "Тон: задумчивый. Тебя эта новость заставила задуматься о чём-то большем."
    };

    private static final String[] STYLE_HINTS_EN = {
            "Tone: calm, thoughtful. Like an analyst but without formality.",
            "Tone: ironic, slightly sarcastic. You see the absurdity in the situation.",
            "Tone: excited, this is important news and you want to share fast.",
            "Tone: skeptical. You don't buy everything and you show it.",
            "Tone: reflective. This news made you think about something bigger."
    };

    private final DigestPersonaRepository personaRepository;
    private final DigestHistoryRepository historyRepository;
    private final MessageRepository messageRepository;
    private final SourceTrustRepository sourceTrustRepository;
    private final DeepSeekApiClient deepSeekApiClient;
    private final PersonaService personaService;
    private final AntiDetectionService antiDetectionService;
    private final ResponseRefinerService responseRefinerService;

    @Value("${deepseek.model:deepseek-chat}")
    private String defaultModel;

    public DigestGenerationServiceImpl(
            DigestPersonaRepository personaRepository,
            DigestHistoryRepository historyRepository,
            MessageRepository messageRepository,
            SourceTrustRepository sourceTrustRepository,
            DeepSeekApiClient deepSeekApiClient,
            PersonaService personaService,
            AntiDetectionService antiDetectionService,
            ResponseRefinerService responseRefinerService) {
        this.personaRepository = Objects.requireNonNull(personaRepository);
        this.historyRepository = Objects.requireNonNull(historyRepository);
        this.messageRepository = Objects.requireNonNull(messageRepository);
        this.sourceTrustRepository = Objects.requireNonNull(sourceTrustRepository);
        this.deepSeekApiClient = Objects.requireNonNull(deepSeekApiClient);
        this.personaService = Objects.requireNonNull(personaService);
        this.antiDetectionService = Objects.requireNonNull(antiDetectionService);
        this.responseRefinerService = Objects.requireNonNull(responseRefinerService);
    }

    @Override
    public Mono<GeneratedDigestDto> generateDigest(Long personaId) {
        Objects.requireNonNull(personaId, "personaId must not be null");
        log.info("Starting digest generation for persona: id={}", personaId);
        return personaRepository.findById(personaId)
                .timeout(OPERATION_TIMEOUT)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Persona not found: " + personaId)))
                .flatMap(this::generateDigestInternal);
    }

    @Override
    public Mono<GeneratedDigestDto> generateDigest(DigestPersona persona) {
        Objects.requireNonNull(persona, "persona must not be null");
        return generateDigestInternal(persona);
    }

    @Override
    public Mono<GeneratedDigestDto> generateTestDigest(Long personaId) {
        Objects.requireNonNull(personaId, "personaId must not be null");
        log.info("Generating test digest for persona: id={}", personaId);
        return personaRepository.findById(personaId)
                .timeout(OPERATION_TIMEOUT)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Persona not found: " + personaId)))
                .flatMap(this::generateDigestWithoutPersistence);
    }

    @Override
    public Mono<GeneratedDigestDto> generateDigest(Long personaId, int lookbackHours) {
        Objects.requireNonNull(personaId, "personaId must not be null");
        log.info("Generating digest for persona: id={}, lookbackHours={}", personaId, lookbackHours);
        return personaRepository.findById(personaId)
                .timeout(OPERATION_TIMEOUT)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Persona not found: " + personaId)))
                .map(persona -> {
                    DigestPersona modified = copyPersona(persona);
                    modified.setLookbackHours(lookbackHours);
                    return modified;
                })
                .flatMap(this::generateDigestInternal);
    }

    @Override
    public String buildSystemPrompt(DigestPersona persona) {
        Objects.requireNonNull(persona, "persona must not be null");
        String language = persona.language() != null ? persona.language() : "en";
        boolean isRussian = "ru".equalsIgnoreCase(language) || "uk".equalsIgnoreCase(language);
        DigestPersonaStyle style = persona.personaStyleEnum();
        String stylePrompt;
        if (style == DigestPersonaStyle.CUSTOM && persona.customSystemPrompt() != null) {
            stylePrompt = persona.customSystemPrompt();
        } else {
            String[] formats = isRussian ? POST_FORMATS_RU : POST_FORMATS_EN;
            String[] hints = isRussian ? STYLE_HINTS_RU : STYLE_HINTS_EN;
            String format = formats[FORMAT_RANDOM.nextInt(formats.length)];
            String hint = pickStyleHint(style, hints);
            stylePrompt = format + "\n" + hint + "\n" + BASE_RULES;
        }
        String botId = persona.botId() != null ? String.valueOf(persona.botId()) : null;
        String basePrompt = personaService.buildPersonaSystemPrompt(stylePrompt, language, botId);
        return basePrompt + buildDateGroundingBlock(isRussian);
    }

    private static String buildDateGroundingBlock(boolean isRussian) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (isRussian) {
            String[] months = {
                "января","февраля","марта","апреля","мая","июня",
                "июля","августа","сентября","октября","ноября","декабря"
            };
            String dateRu = today.getDayOfMonth() + " " + months[today.getMonthValue() - 1] + " " + today.getYear();
            return "\n\nСегодня " + dateRu + ". Пиши с позиции этого момента; не называй прошедшие годы будущим временем. " +
                    "Комментируй только факты, которые есть в новости — не выдумывай конкретные цифры, проценты, годы или сроки, которых нет в источнике.";
        } else {
            String dateEn = today.getDayOfMonth() + " " + today.getMonth().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH) + " " + today.getYear();
            return "\n\nToday is " + dateEn + ". Write from this moment; do not refer to past years as the future. " +
                    "Comment only on facts present in the news item — do not invent specific numbers, percentages, years, or timeframes that are not in the source.";
        }
    }

    private String pickStyleHint(DigestPersonaStyle style, String[] hints) {
        return switch (style) {
            case PROFESSIONAL -> hints[0];
            case IRONIC -> hints[1];
            case BREAKING_NEWS -> hints[2];
            case TECHNICAL -> hints[3];
            default -> hints[FORMAT_RANDOM.nextInt(hints.length)];
        };
    }

    private Mono<GeneratedDigestDto> generateDigestInternal(DigestPersona persona) {
        long startTime = System.currentTimeMillis();
        String digestId = generateDigestId();
        log.info("Generating single-topic post: personaId={}, name={}, digestId={}, lookback={}h",
                persona.id(), persona.name(), digestId, persona.lookbackHours());
        return fetchFilteredMessages(persona)
                .take(3)
                .collectList()
                .filter(msgs -> !msgs.isEmpty())
                .flatMap(messages -> {
                    MessageEntity topMessage = messages.get(0);
                    log.info("Top message for post: chatId={}, importance={}, totalContext={}, content={}",
                            topMessage.getChatId(), topMessage.getImportance(), messages.size(),
                            topMessage.getContent() != null ? topMessage.getContent().substring(0, Math.min(100, topMessage.getContent().length())) : "null");
                    return synthesizeDigest(persona, messages, digestId, startTime)
                            .flatMap(dto -> persistHistory(persona, dto).thenReturn(dto));
                })
                .switchIfEmpty(
                    personaRepository.updateLastRunAt(persona.id(), Instant.now())
                        .timeout(OPERATION_TIMEOUT)
                        .then(Mono.fromCallable(() -> {
                            log.info("No messages found for post: personaId={}, lastRunAt updated", persona.id());
                            return GeneratedDigestDto.empty(persona.id(), persona.name());
                        }))
                )
                .doOnSuccess(dto -> log.info("Post generation completed: personaId={}, digestId={}, time={}ms",
                        persona.id(), dto.digestId(), dto.generationTimeMs()))
                .doOnError(e -> log.error("Post generation failed: personaId={}, error={}", persona.id(), e.getMessage()));
    }

    private Mono<GeneratedDigestDto> generateDigestWithoutPersistence(DigestPersona persona) {
        long startTime = System.currentTimeMillis();
        String digestId = "test-" + generateDigestId();
        log.info("Generating test digest: personaId={}, name={}", persona.id(), persona.name());
        return fetchFilteredMessages(persona)
                .take(3)
                .collectList()
                .filter(msgs -> !msgs.isEmpty())
                .flatMap(messages -> synthesizeDigest(persona, messages, digestId, startTime))
                .switchIfEmpty(Mono.fromCallable(() -> GeneratedDigestDto.empty(persona.id(), persona.name())));
    }

    private static final int MIN_SUBSCRIBERS_DEFAULT = 50000;

    private Flux<MessageEntity> fetchFilteredMessages(DigestPersona persona) {
        Instant lastRun = persona.lastRunAt();
        Instant lookbackSince = Instant.now().minus(Duration.ofHours(persona.lookbackHours()));
        Instant since = lastRun != null && lastRun.isAfter(lookbackSince) ? lastRun : lookbackSince;
        int limit = persona.maxMessages() != null ? persona.maxMessages() : 10;
        int minSubs = persona.minClusterSize() != null && persona.minClusterSize() > 1000
                ? persona.minClusterSize() : MIN_SUBSCRIBERS_DEFAULT;
        log.info("Fetching quality messages: since={} (lastRun={}), limit={}, minSubscribers={}",
                since, lastRun, limit, minSubs);
        return messageRepository.findQualityMessagesForDigest(since, minSubs, limit * 5)
                .timeout(OPERATION_TIMEOUT)
                .filter(msg -> passesImportanceFilter(msg, persona))
                .filterWhen(msg -> passesSourceTrustFilter(msg, persona))
                .filter(msg -> passesKeywordFilter(msg, persona))
                .filter(msg -> passesExclusionFilter(msg, persona))
                .take(limit);
    }

    private boolean passesImportanceFilter(MessageEntity message, DigestPersona persona) {
        Double minScore = persona.minImportanceScore();
        if (minScore == null || minScore <= 0) {
            return true;
        }
        Double importance = message.getImportance();
        return importance != null && importance >= minScore;
    }

    private Mono<Boolean> passesSourceTrustFilter(MessageEntity message, DigestPersona persona) {
        Double threshold = persona.sourceTrustThreshold();
        if (threshold == null || threshold <= 0) {
            return Mono.just(true);
        }
        Long chatId = message.getChatId();
        if (chatId == null) {
            return Mono.just(false);
        }
        return sourceTrustRepository.getTrustScoreOrDefault(chatId)
                .timeout(OPERATION_TIMEOUT)
                .map(score -> score >= threshold)
                .onErrorReturn(true);
    }

    private boolean passesKeywordFilter(MessageEntity message, DigestPersona persona) {
        String[] keywords = persona.topicKeywords();
        if (keywords == null || keywords.length == 0) {
            return true;
        }
        String content = extractContent(message);
        if (content == null || content.isBlank()) {
            return false;
        }
        String lowerContent = content.toLowerCase();
        return Arrays.stream(keywords)
                .anyMatch(kw -> kw != null && lowerContent.contains(kw.toLowerCase()));
    }

    private boolean passesExclusionFilter(MessageEntity message, DigestPersona persona) {
        String[] negativeKeywords = persona.negativeKeywords();
        Long[] excludedChannels = persona.excludedChannelIds();
        String content = extractContent(message);
        if (negativeKeywords != null && negativeKeywords.length > 0 && content != null) {
            String lowerContent = content.toLowerCase();
            boolean hasNegative = Arrays.stream(negativeKeywords)
                    .anyMatch(kw -> kw != null && lowerContent.contains(kw.toLowerCase()));
            if (hasNegative) {
                return false;
            }
        }
        if (excludedChannels != null && excludedChannels.length > 0) {
            Long chatId = message.getChatId();
            if (chatId != null) {
                Set<Long> excluded = new HashSet<>(Arrays.asList(excludedChannels));
                if (excluded.contains(chatId)) {
                    return false;
                }
            }
        }
        return true;
    }

    private Mono<GeneratedDigestDto> synthesizeDigest(
            DigestPersona persona,
            List<MessageEntity> messages,
            String digestId,
            long startTime) {
        String systemPrompt = buildSystemPrompt(persona);
        String userContent = formatMessagesForLlm(messages);
        List<ApiMessage> apiMessages = new ArrayList<>();
        apiMessages.add(new ApiMessage("system", systemPrompt));
        apiMessages.add(new ApiMessage("user", userContent));
        String model = persona.modelName() != null ? persona.modelName() : defaultModel;
        Integer maxTokens = persona.maxTokens() != null ? persona.maxTokens() : 1000;
        Double temperature = persona.temperature() != null ? persona.temperature() : 0.7;
        DeepSeekChatRequest request = new DeepSeekChatRequest(
                apiMessages,
                model,
                maxTokens,
                temperature
        );
        log.info("=== DIGEST PIPELINE START [{}] personaId={} name={} style={} ===",
                digestId, persona.id(), persona.name(), persona.personaStyle());
        log.info("--- SYSTEM PROMPT ---\n{}", systemPrompt);
        log.info("--- SELECTED MESSAGES ({}) ---\n{}", messages.size(), buildMessageListLog(messages));
        log.info("--- USER PROMPT (sent to LLM) ---\n{}", userContent);
        log.info("LLM call: model={}, maxTokens={}, temperature={}", model, maxTokens, temperature);
        return deepSeekApiClient.chat(request, DIGEST_CHAT_ID, LLM_TIMEOUT_SECONDS)
                .switchIfEmpty(Mono.just("No content generated"))
                .flatMap(rawContent -> {
                    log.info("--- RAW LLM RESPONSE ({} chars) ---\n{}", rawContent.length(), rawContent);
                    return humanizeContent(rawContent, persona);
                })
                .map(content -> {
                    long generationTime = System.currentTimeMillis() - startTime;
                    Set<String> clusters = messages.stream()
                            .map(MessageEntity::getClusterId)
                            .filter(Objects::nonNull)
                            .collect(Collectors.toSet());
                    List<String> sources = messages.stream()
                            .map(m -> m.getChatId() != null ? m.getChatId().toString() : "unknown")
                            .distinct()
                            .limit(5)
                            .collect(Collectors.toList());
                    log.info("--- HUMANIZED RESPONSE ({} chars, {}ms) ---\n{}", content.length(),
                            System.currentTimeMillis() - startTime, content);
                    log.info("=== DIGEST PIPELINE END [{}] messages={} clusters={} ===",
                            digestId, messages.size(), clusters.size());
                    return new GeneratedDigestDto(
                            digestId,
                            persona.id(),
                            persona.name(),
                            content,
                            messages.size(),
                            clusters.size(),
                            sources,
                            System.currentTimeMillis() - startTime,
                            Instant.now()
                    );
                })
                .onErrorResume(e -> {
                    log.error("LLM synthesis failed: personaId={}, error={}", persona.id(), e.getMessage());
                    long generationTime = System.currentTimeMillis() - startTime;
                    return Mono.just(new GeneratedDigestDto(
                            digestId,
                            persona.id(),
                            persona.name(),
                            "Digest generation failed: " + e.getMessage(),
                            messages.size(),
                            0,
                            List.of(),
                            generationTime,
                            Instant.now()
                    ));
                });
    }

    private Mono<String> humanizeContent(String rawContent, DigestPersona persona) {
        if (antiDetectionService.hasAiPatterns(rawContent)) {
            log.info("AI patterns detected in post, running refiner");
            // DigestPersona carries a Telegram user id, not the String bot instance id
            // the persona registry is keyed by, so the refiner keeps its previous
            // behaviour here and speaks as the primary persona.
            return responseRefinerService.refineResponse(rawContent, "news post", persona.botId(), null)
                    .onErrorReturn(rawContent);
        }
        return Mono.just(rawContent);
    }

    private Mono<Void> persistHistory(DigestPersona persona, GeneratedDigestDto dto) {
        if (dto.digestId() == null || dto.digestId().startsWith("test-")) {
            return Mono.empty();
        }
        DigestHistory history = new DigestHistory(persona.id(), dto.digestId(), dto.content());
        history.setMessagesIncluded(dto.messagesIncluded());
        history.setClustersUsed(dto.clustersUsed());
        history.setGenerationTimeMs(dto.generationTimeMs());
        history.setCreatedAt(Instant.now());
        log.debug("Persisting digest history: digestId={}, personaId={}", dto.digestId(), persona.id());
        return historyRepository.save(history)
                .timeout(OPERATION_TIMEOUT)
                .then(personaRepository.updateLastRunAt(persona.id(), Instant.now()))
                .timeout(OPERATION_TIMEOUT)
                .then()
                .doOnSuccess(v -> log.debug("Digest history persisted: digestId={}", dto.digestId()))
                .doOnError(e -> log.error("Failed to persist digest history: digestId={}, error={}",
                        dto.digestId(), e.getMessage()));
    }

    private String buildMessageListLog(List<MessageEntity> messages) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < messages.size(); i++) {
            MessageEntity msg = messages.get(i);
            String content = extractContent(msg);
            String snippet = content != null && content.length() > 150
                    ? content.substring(0, 150) + "..."
                    : (content != null ? content : "[no content]");
            sb.append(String.format("  #%d id=%-8s chatId=%-16s date=%s importance=%-5s cluster=%s%n",
                    i + 1,
                    msg.getId(),
                    msg.getChatId(),
                    msg.getDate() != null ? msg.getDate().toString().substring(0, 19) : "null",
                    msg.getImportance() != null ? String.format("%.2f", msg.getImportance()) : "n/a",
                    msg.getClusterId() != null ? msg.getClusterId().substring(0, Math.min(16, msg.getClusterId().length())) : "none"));
            sb.append(String.format("       %s%n", snippet));
        }
        return sb.toString();
    }

    private String formatMessagesForLlm(List<MessageEntity> messages) {
        StringBuilder sb = new StringBuilder("News messages to analyze:\n\n");
        for (int i = 0; i < messages.size(); i++) {
            MessageEntity msg = messages.get(i);
            String content = extractContent(msg);
            if (content == null || content.isBlank()) {
                continue;
            }
            if (content.length() > MAX_CONTENT_LENGTH) {
                content = content.substring(0, MAX_CONTENT_LENGTH) + "...";
            }
            sb.append(i + 1).append(". ");
            if (msg.getSenderName() != null && !msg.getSenderName().isBlank()) {
                sb.append("[Source: ").append(msg.getSenderName()).append("] ");
            }
            if (msg.getClusterId() != null) {
                sb.append("[Cluster: ").append(msg.getClusterId().substring(0, 8)).append("] ");
            }
            if (msg.getImportance() != null) {
                sb.append("[Score: ").append(String.format("%.2f", msg.getImportance())).append("] ");
            }
            sb.append(content).append("\n\n");
        }
        return sb.toString();
    }

    private String extractContent(MessageEntity message) {
        if (message.getContent() != null && !message.getContent().isBlank()) {
            return message.getContent();
        }
        if (message.getCaption() != null && !message.getCaption().isBlank()) {
            return message.getCaption();
        }
        return null;
    }

    private String generateDigestId() {
        return "d" + Long.toHexString(System.currentTimeMillis()) + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private DigestPersona copyPersona(DigestPersona source) {
        DigestPersona copy = new DigestPersona();
        copy.setId(source.id());
        copy.setName(source.name());
        copy.setDescription(source.description());
        copy.setBotId(source.botId());
        copy.setTargetChannelId(source.targetChannelId());
        copy.setEnabled(source.enabled());
        copy.setPersonaStyle(source.personaStyle());
        copy.setCustomSystemPrompt(source.customSystemPrompt());
        copy.setScheduleCron(source.scheduleCron());
        copy.setScheduleTimezone(source.scheduleTimezone());
        copy.setActiveHoursStart(source.activeHoursStart());
        copy.setActiveHoursEnd(source.activeHoursEnd());
        copy.setLookbackHours(source.lookbackHours());
        copy.setMaxMessages(source.maxMessages());
        copy.setLanguage(source.language());
        copy.setMinClusterSize(source.minClusterSize());
        copy.setMinImportanceScore(source.minImportanceScore());
        copy.setSourceTrustThreshold(source.sourceTrustThreshold());
        copy.setExcludedChannelIds(source.excludedChannelIds());
        copy.setTopicKeywords(source.topicKeywords());
        copy.setNegativeKeywords(source.negativeKeywords());
        copy.setModelName(source.modelName());
        copy.setTemperature(source.temperature());
        copy.setMaxTokens(source.maxTokens());
        copy.setCreatedAt(source.createdAt());
        copy.setUpdatedAt(source.updatedAt());
        copy.setPublishMode(source.publishMode());
        copy.setRandomDelayMaxMinutes(source.randomDelayMaxMinutes());
        copy.setLastRunAt(source.lastRunAt());
        copy.setTotalDigestsPublished(source.totalDigestsPublished());
        copy.setLastPublishedDigestId(source.lastPublishedDigestId());
        return copy;
    }
}
