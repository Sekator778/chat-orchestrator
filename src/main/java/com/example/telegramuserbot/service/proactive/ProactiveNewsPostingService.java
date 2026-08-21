package com.example.telegramuserbot.service.proactive;

import com.example.telegramuserbot.domain.DigestPersona;
import com.example.telegramuserbot.domain.MessageEntity;
import com.example.telegramuserbot.domain.NewsPost;
import com.example.telegramuserbot.repository.DigestPersonaRepository;
import com.example.telegramuserbot.repository.MessageRepository;
import com.example.telegramuserbot.repository.NewsPostRepository;
import com.example.telegramuserbot.service.config.AppSettingsService;
import com.example.telegramuserbot.service.digest.DigestGenerationService;
import com.example.telegramuserbot.service.embedding.QdrantVectorStore;
import com.example.telegramuserbot.service.llm.client.DeepSeekApiClient;
import com.example.telegramuserbot.service.llm.dto.ApiMessage;
import com.example.telegramuserbot.service.llm.dto.DeepSeekChatRequest;
import com.example.telegramuserbot.service.orchestration.PersonaScheduleService;
import com.example.telegramuserbot.service.orchestration.ResponsePostProcessor;
import com.example.telegramuserbot.service.publishing.TelegramMessageSender;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * P1 v1 — Proactive single-item news posting (scheduled, value-gated, default-off).
 *
 * <p>On each tick the service:
 * <ol>
 *   <li>Reads the master enable flag from {@link AppSettingsService} at run time (not startup).</li>
 *   <li>Loads all enabled {@code bot.digest_personas} rows as persona configs.</li>
 *   <li>For each persona: checks active-hours window, daily cap, selects the highest-value
 *       unposted news item, generates a short human-style first-person post via the existing
 *       LLM path, sends it via {@link TelegramMessageSender}, and records a {@link NewsPost}.</li>
 * </ol>
 *
 * <p>Concurrency: rows are processed sequentially via {@code concatMap} to avoid send bursts.
 * One row's failure is isolated by {@code onErrorResume} so others still run.
 *
 * <p>Reactive safety: {@code @Scheduled} fires on Spring's scheduler thread. The reactive
 * pipeline is subscribed via {@code subscribeOn(Schedulers.boundedElastic())} so no
 * {@code .block()} is ever called on a {@code parallel} scheduler thread. All blocking is
 * limited to the {@code AppSettingsService.getBoolean/getInt/getDouble} snapshot reads, which
 * are simple volatile map look-ups — not I/O — so calling them on any thread is safe.
 */
@Service
public class ProactiveNewsPostingService {

    private static final Logger log = LoggerFactory.getLogger(ProactiveNewsPostingService.class);

    /**
     * Default number of candidate messages to fetch from the DB before Java-side keyword filtering.
     * Overridable at runtime via {@code bot.app_settings} key
     * {@code news.proactive-posting.scan-limit} (no redeploy needed).
     * Raised from 20 → 150 so topic-specialized personas (e.g. crypto) find keyword-matching
     * items that rank below the previous narrow top-20 window dominated by macro/geopolitics.
     */
    private static final int DEFAULT_SCAN_LIMIT = 150;

    /** Timeout for a single LLM call. */
    private static final int LLM_TIMEOUT_SECONDS = 60;

    /** Pseudo chat-id for LLM logging (no real chat context). */
    private static final long NEWS_CHAT_ID = -99L;

    /**
     * Global shill/promo content filter — conservative, high-precision list of markers that are
     * almost never found in legitimate news (РИА, Банк России, Spydell, Сигналы РЦБ market
     * analysis) but are signature crypto-pump / airdrop / promo text patterns.
     *
     * <p>Deliberately excludes generic finance words (сигнал, прогноз, профит, ставка, биткоин,
     * ethereum) that appear in legitimate analysis. This is a <em>content</em> filter on POST TEXT,
     * so it does NOT blacklist channels by name — "Сигналы РЦБ" posts substantive analysis and
     * won't contain these markers.
     *
     * <p>Overridable at runtime via {@code bot.app_settings} key
     * {@code news.proactive-posting.shill-denylist}. Set to a single space or empty string to
     * disable entirely. Newline- or comma-separated.
     */
    private static final String DEFAULT_SHILL_DENYLIST =
            "залетай\n" +
            "залетаем\n" +
            "пресейл\n" +
            "presale\n" +
            "памп\n" +
            "бонус на депозит\n" +
            "промокод\n" +
            "реферальн\n" +
            "успей купить\n" +
            "не упусти\n" +
            "академия трейдинга\n" +
            "обучение трейдингу\n" +
            "бесплатный сигнал\n" +
            "сигнал дня\n" +
            "vip-клуб\n" +
            "вип-клуб\n" +
            "приватный канал\n" +
            "airdrop\n" +
            "аирдроп\n" +
            "аірдроп\n" +
            "раздача токенов";

    /**
     * Shill-denylist tokens that must only match as <em>whole words</em> to avoid false-positives
     * on legitimate content (e.g. "x10 zoom", "марафонский забег"). These are tested via
     * {@link #matchesWholeWord} instead of bare substring contains.
     */
    private static final java.util.Set<String> SHILL_WHOLE_WORD_MARKERS =
            java.util.Set.of("x10", "х10", "марафон");

    /**
     * Default liveliness-floor constraints appended to every RU/UK persona system prompt.
     * Overridable at runtime via app_settings key {@code news.proactive-posting.liveliness-floor-ru}.
     * Non-Russian personas are not affected.
     * Package-visible so {@link SiblingReplyService} can reuse the same default without forking.
     */
    static final String DEFAULT_LIVELINESS_FLOOR_RU = """
            ОБЩИЕ ПРАВИЛА ЖИВОСТИ:
            1. Не начинай с междометий и обращений (Ого, Вау, Слушай, Прикинь, Чувак, Йо, ребята, друзья). Начинай с факта, цифры или конкретного следствия.
            2. Запрещены штампы-хайп: «просто бомба/ракета/космос/огонь», «реально круто», «снова в игре», «будущее за децентрализацией», «революция», «меняет всё», «to the moon», «газуем».
            3. Не оценивай словами «круто/важно». Покажи конкретику — читатель сам решит.
            4. Уровень эмоции соответствует новости, а не всегда на максимуме.""";

    /**
     * Default top-K for Qdrant scored search — generous so most recall candidates hit.
     * Overridable via {@code news.relevance.qdrant-top-k}.
     */
    private static final int DEFAULT_RELEVANCE_TOP_K = 200;

    /**
     * Default cliché / banned-opener denylist for the post-generation filter.
     * Each entry is a case-insensitive substring; matching against the full post text covers both
     * opener and inline occurrences. Overridable at runtime via
     * {@code news.liveliness.cliche-denylist}. Set to a single space or empty to disable.
     * Newline- or comma-separated.
     */
    static final String DEFAULT_CLICHE_DENYLIST =
            "Ого\n" +
            "Вау\n" +
            "Ничего себе\n" +
            "Это меняет всё\n" +
            "Это меняет все\n" +
            "будущее за\n" +
            "огонь\n" +
            "прорыв\n" +
            "революция в\n" +
            "to the moon\n" +
            "просто бомба\n" +
            "реально круто\n" +
            "снова в игре";

    private final AppSettingsService appSettings;
    private final DigestPersonaRepository digestPersonaRepository;
    private final MessageRepository messageRepository;
    private final NewsPostRepository newsPostRepository;
    private final DeepSeekApiClient deepSeekApiClient;
    private final DigestGenerationService digestGenerationService;
    private final ResponsePostProcessor responsePostProcessor;
    private final TelegramMessageSender telegramSender;
    private final PersonaScheduleService personaScheduleService;
    private final PrimarySourceEnricher primarySourceEnricher;
    private final SiblingReplyService siblingReplyService;
    private final PersonaProfileService personaProfileService;
    private final QdrantVectorStore qdrantVectorStore;

    /** Re-entrancy guard: prevents a new tick from starting while the previous pipeline is still running. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Value("${deepseek.model:deepseek-chat}")
    private String defaultModel;

    public ProactiveNewsPostingService(
            AppSettingsService appSettings,
            DigestPersonaRepository digestPersonaRepository,
            MessageRepository messageRepository,
            NewsPostRepository newsPostRepository,
            DeepSeekApiClient deepSeekApiClient,
            DigestGenerationService digestGenerationService,
            ResponsePostProcessor responsePostProcessor,
            TelegramMessageSender telegramSender,
            PersonaScheduleService personaScheduleService,
            PrimarySourceEnricher primarySourceEnricher,
            SiblingReplyService siblingReplyService,
            PersonaProfileService personaProfileService,
            QdrantVectorStore qdrantVectorStore) {
        this.appSettings = Objects.requireNonNull(appSettings);
        this.digestPersonaRepository = Objects.requireNonNull(digestPersonaRepository);
        this.messageRepository = Objects.requireNonNull(messageRepository);
        this.newsPostRepository = Objects.requireNonNull(newsPostRepository);
        this.deepSeekApiClient = Objects.requireNonNull(deepSeekApiClient);
        this.digestGenerationService = Objects.requireNonNull(digestGenerationService);
        this.responsePostProcessor = Objects.requireNonNull(responsePostProcessor);
        this.telegramSender = Objects.requireNonNull(telegramSender);
        this.personaScheduleService = Objects.requireNonNull(personaScheduleService);
        this.primarySourceEnricher = Objects.requireNonNull(primarySourceEnricher);
        this.siblingReplyService = Objects.requireNonNull(siblingReplyService);
        this.personaProfileService = Objects.requireNonNull(personaProfileService);
        this.qdrantVectorStore = Objects.requireNonNull(qdrantVectorStore);
    }

    /**
     * Scheduler tick. Default cadence: every 5 minutes.
     * Controlled via {@code news.proactive-posting.tick-ms} in application properties.
     *
     * <p>The master enable flag ({@code news.proactive-posting.enabled}) is read inside
     * the pipeline — at run time, after the AppSettingsService has loaded DB values —
     * so startup race is not possible.
     *
     * <p>Threading guarantee: the lambda fires on Spring's task scheduler thread, but the
     * entire reactive pipeline is subscribed via {@code subscribeOn(Schedulers.boundedElastic())}
     * which moves execution to a bounded-elastic thread. No {@code .block()} call is made
     * anywhere in this class.
     */
    @Scheduled(fixedDelayString = "${news.proactive-posting.tick-ms:300000}",
               initialDelayString = "${news.proactive-posting.tick-ms:300000}")
    public void tick() {
        if (!running.compareAndSet(false, true)) {
            log.debug("[ProactiveNews] previous tick still running — skipping");
            return;
        }
        buildPipeline()
                .doFinally(sig -> running.set(false))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        v -> { /* individual outcomes logged inside */ },
                        err -> log.error("[ProactiveNews] Unhandled error in tick pipeline", err)
                );
    }

    /**
     * Builds the full reactive pipeline for one tick.
     * Reading {@code news.proactive-posting.enabled} here (not in constructor / @PostConstruct)
     * guarantees we see the DB value that AppSettingsService loaded after startup.
     */
    private Mono<Void> buildPipeline() {
        // Read gating flag AT RUN TIME — AppSettingsService has already loaded by now
        boolean enabled = appSettings.getBoolean("news.proactive-posting.enabled", false);
        if (!enabled) {
            log.debug("[ProactiveNews] Disabled via news.proactive-posting.enabled — skipping tick");
            return Mono.empty();
        }

        int maxPerDay      = appSettings.getInt   ("news.proactive-posting.max-per-day",      1);
        double minValue    = appSettings.getDouble ("news.proactive-posting.min-value",        0.8);
        int minSubscribers = appSettings.getInt   ("news.proactive-posting.min-subscribers", 1000);

        log.debug("[ProactiveNews] Tick started — maxPerDay={}, minValue={}, minSubscribers={}",
                maxPerDay, minValue, minSubscribers);

        return digestPersonaRepository.findAllEnabled()
                .concatMap(persona -> processPersona(persona, maxPerDay, minValue, minSubscribers)
                        .onErrorResume(err -> {
                            log.error("[ProactiveNews] Error processing persona id={} name={}: {}",
                                    persona.id(), persona.name(), err.getMessage(), err);
                            return Mono.empty();
                        }))
                .then();
    }

    private Mono<Void> processPersona(DigestPersona persona, int maxPerDay, double minValue, int minSubscribers) {
        Long botId = persona.botId();
        Long targetChatId = persona.targetChannelId();

        if (botId == null || targetChatId == null) {
            log.debug("[ProactiveNews] Skipping persona id={} — botId or targetChannelId is null", persona.id());
            return Mono.empty();
        }

        String botIdStr = String.valueOf(botId);

        // Skip BEFORE the expensive recall + LLM generation if the account is in FLOOD_WAIT
        // backoff — the send would only be suppressed and the generated post dropped.
        if (telegramSender.isBackingOff(botIdStr)) {
            log.debug("[ProactiveNews] persona id={} botId={} — client in FLOOD_WAIT backoff, skipping tick", persona.id(), botId);
            return Mono.empty();
        }

        // Check active-hours window via the shared per-account persona schedule (timezone-aware)
        return personaScheduleService.isActiveNow(botIdStr)
                .flatMap(active -> {
                    if (!active) {
                        log.debug("[ProactiveNews] persona id={} outside active hours", persona.id());
                        return Mono.<Void>empty();
                    }

                    // -----------------------------------------------------------------------
                    // Gate 1: DAILY 50% COIN-FLIP — stable per (persona, chat, UTC date).
                    //
                    // We derive a deterministic pseudo-random draw from a seeded Random so
                    // that repeated 5-min ticks on the same day always agree: if the first
                    // tick today says "skip", every later tick will also say "skip", and
                    // vice-versa.  ThreadLocalRandom is intentionally avoided because it is
                    // not seedable and therefore not stable across ticks.
                    //
                    // The salt value (1) is different from the jitter gate (2) below so the
                    // two draws are independent even though they share the same base inputs.
                    // -----------------------------------------------------------------------
                    double dailyPostProbability = appSettings.getDouble(
                            "news.proactive-posting.daily-post-probability", 0.5);
                    {
                        LocalDate today = LocalDate.now(ZoneOffset.UTC);
                        long coinSeed = Objects.hash(botId, targetChatId, today, 1);
                        double coinDraw = new Random(coinSeed).nextDouble();
                        if (coinDraw >= dailyPostProbability) {
                            log.debug("[ProactiveNews] persona id={} botId={} chat={} — skipping today (coin-flip: draw={} >= probability={})",
                                    persona.id(), botId, targetChatId, String.format("%.4f", coinDraw), dailyPostProbability);
                            return Mono.<Void>empty();
                        }
                    }

                    // -----------------------------------------------------------------------
                    // Gate 2: RANDOM TARGET TIME — stable per (persona, chat, UTC date).
                    //
                    // When random_delay_max_minutes > 0 we add a seeded per-day jitter to
                    // the persona's active-hours-start (or to UTC midnight if no window is
                    // set) and only allow the post once wall-clock time has passed that
                    // target.  This de-synchronises post times across days and across
                    // personas.  If random_delay_max_minutes == 0 the gate is bypassed and
                    // current behaviour (post as soon as other gates clear) is preserved.
                    // -----------------------------------------------------------------------
                    int randomDelayMaxMinutes = persona.randomDelayMaxMinutes() != null
                            ? persona.randomDelayMaxMinutes() : 0;
                    if (randomDelayMaxMinutes > 0) {
                        LocalDate today = LocalDate.now(ZoneOffset.UTC);
                        long jitterSeed = Objects.hash(botId, targetChatId, today, 2);
                        int jitterMinutes = new Random(jitterSeed).nextInt(randomDelayMaxMinutes);

                        // Base = active-hours-start in UTC; fall back to midnight.
                        LocalTime baseTime = (persona.activeHoursStart() != null)
                                ? persona.activeHoursStart()
                                : LocalTime.MIDNIGHT;
                        Instant targetTime = LocalDateTime.of(today, baseTime)
                                .plusMinutes(jitterMinutes)
                                .toInstant(ZoneOffset.UTC);

                        if (Instant.now().isBefore(targetTime)) {
                            log.debug("[ProactiveNews] persona id={} botId={} chat={} — waiting for target time {} (jitter={}min)",
                                    persona.id(), botId, targetChatId, targetTime, jitterMinutes);
                            return Mono.<Void>empty();
                        }
                    }

                    // Minimum interval gate — prevents burst posting after restart/downtime
                    int minIntervalHours = appSettings.getInt("news.proactive-posting.min-interval-hours", 2);
                    if (persona.lastRunAt() != null &&
                            Duration.between(persona.lastRunAt(), Instant.now()).compareTo(Duration.ofHours(minIntervalHours)) < 0) {
                        log.debug("[ProactiveNews] persona id={} min-interval not elapsed (last_run_at={})",
                                persona.id(), persona.lastRunAt());
                        return Mono.<Void>empty();
                    }

                    // Daily cap check
                    Instant startOfDay = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
                    return newsPostRepository.countByPersonaBotIdAndTargetChatIdAndPostedAtAfter(botIdStr, targetChatId, startOfDay)
                            .flatMap(count -> {
                                if (count >= maxPerDay) {
                                    log.debug("[ProactiveNews] Persona id={} already posted {} time(s) today (cap={}), skipping",
                                            persona.id(), count, maxPerDay);
                                    return Mono.<Void>empty();
                                }
                                return selectAndPost(persona, botIdStr, targetChatId, minValue, minSubscribers);
                            });
                });
    }

    /**
     * Selects the best candidate and sends it as a proactive post.
     *
     * <h3>Ranking strategy (A-T3 value×cosine blend)</h3>
     * <pre>
     *   blendedScore(msg) = value_score × (cosineBase + cosine)
     *
     *   where cosineBase = news.relevance.cosine-base  (default 0.5)
     *         cosine     = cosine similarity of msg to persona profile, or 0 when unknown
     * </pre>
     *
     * <p>This formula preserves {@code value_score} as a structural floor — every recall
     * candidate already cleared the SQL {@code >= minValue} gate, so a high cosine alone
     * cannot surface junk. A neutral cosine of 0 demotes, but the item is never dropped;
     * the ordering degrades smoothly to pure value-score when vectors are absent.
     *
     * <p><b>Fail-open guarantee (#112 anti-starvation):</b> if the persona's profile vector is
     * absent (EmbeddingClient returned empty) OR the Qdrant search returns empty, the method
     * falls through to the existing value-only {@code .next()} path unchanged. Cosine enters
     * RANKING only — never a hard filter. A persona always has its value-ranked candidates.
     *
     * <p><b>Reactive safety:</b> no {@code .block()} — the blend runs inside a single
     * {@code Mono.zip + flatMap} chain on the boundedElastic scheduler.
     */
    private Mono<Void> selectAndPost(DigestPersona persona, String botIdStr, Long targetChatId,
                                     double minValue, int minSubscribers) {
        int lookbackHours = persona.lookbackHours() != null ? persona.lookbackHours() : 48;
        Instant since = Instant.now().minus(Duration.ofHours(lookbackHours));

        String audienceGeo = persona.audienceGeo(); // non-null: falls back to "GLOBAL" in domain method
        int scanLimit = appSettings.getInt("news.proactive-posting.scan-limit", DEFAULT_SCAN_LIMIT);

        // Build keyword pre-filter for SQL: each topic keyword → "%keyword%" ILIKE pattern.
        // When the persona has no topic keywords, hasKeywords=false so the ILIKE condition is
        // skipped in SQL and all rows pass (current behavior preserved).
        String[] topicKws = persona.topicKeywords();
        String[] keywordPatterns = (topicKws == null) ? new String[0] :
                Arrays.stream(topicKws)
                        .filter(kw -> kw != null && !kw.isBlank())
                        .map(kw -> "%" + kw.toLowerCase() + "%")
                        .toArray(String[]::new);
        boolean hasKeywords = keywordPatterns.length > 0;

        // Collect the filtered recall set first (we need all candidates for re-ranking).
        Mono<List<MessageEntity>> candidatesMono = messageRepository
                .findUnpostedNewsCandidatesForPersona(
                        since, minSubscribers, minValue, targetChatId, scanLimit,
                        audienceGeo, hasKeywords, keywordPatterns)
                .filter(msg -> passesNegativeKeywordFilter(msg, persona))
                .filter(this::passesGlobalShillFilter)
                .collectList();

        // Read relevance knobs (code-defaults; no Liquibase row needed).
        double cosineBase = appSettings.getDouble("news.relevance.cosine-base", 0.5);
        // qdrant-top-k is no longer used for the id-filtered search path (searchScoredAmong),
        // but the app_settings row is kept for operator reference / future use.

        // Try to obtain the persona profile vector + Qdrant search scores in parallel.
        // Both are fail-open — if either returns empty we fall back to value-only ordering.
        Mono<float[]> profileVectorMono = personaProfileService
                .getProfileVector(botIdStr, persona)
                .onErrorResume(ex -> {
                    log.warn("[ProactiveNews] PersonaProfileService error for botId={}: {} — value-only fallback",
                            botIdStr, ex.getMessage());
                    return Mono.empty();
                });

        return candidatesMono.flatMap(candidates -> {
            if (candidates.isEmpty()) {
                log.debug("[ProactiveNews] No candidates for persona id={} after filters", persona.id());
                return Mono.<Void>empty();
            }

            // Collect candidate ids for the id-filtered cosine search.
            // searchScoredAmong sends a has_id filter so only these ~150 candidates are scored —
            // the response stays small regardless of how large the Qdrant collection grows
            // (fixes DataBufferLimitException at 16K+ points that silently killed cosine ranking).
            List<Long> candidateIds = candidates.stream()
                    .map(MessageEntity::getId)
                    .collect(java.util.stream.Collectors.toList());

            // Attempt to get the profile vector; if empty, rank by value directly.
            return profileVectorMono
                    .flatMap(profileVector ->
                            // Qdrant scored search — only the recall candidates (id-filtered).
                            qdrantVectorStore.searchScoredAmong(profileVector, candidateIds)
                                    .map(scoredHits -> blendAndPick(candidates, scoredHits, cosineBase, botIdStr, persona.id()))
                    )
                    .switchIfEmpty(Mono.fromSupplier(() -> {
                        // No profile vector (or no Qdrant hits) → pure value-only ordering.
                        log.debug("[ProactiveNews] No profile vector for botId={} — value-only ranking", botIdStr);
                        return candidates.get(0); // SQL already orders by value DESC
                    }))
                    .flatMap(best -> generateAndSend(persona, botIdStr, targetChatId, best));
        });
    }

    /**
     * Blends value_score × (cosineBase + cosine) for each recall candidate, picks the top one.
     *
     * <p>Candidates not found in {@code scoredHits} (i.e. not yet embedded, or outside topK)
     * receive cosine = 0, yielding a blend of {@code value × cosineBase}.  This means:
     * <ul>
     *   <li>A high-value unembedded item is still competitive at a 0.5× weight.</li>
     *   <li>Cosine is a RANKING factor, never a hard filter — no starvation (#112).</li>
     * </ul>
     *
     * @param candidates recall set from Postgres (already value-ordered, filtered)
     * @param scoredHits Qdrant top-K results (id → cosine score)
     * @param cosineBase additive base so value_score is always weighted (e.g. 0.5)
     * @param botIdStr   for logging
     * @param personaId  for logging
     * @return the highest-blended candidate
     */
    private MessageEntity blendAndPick(List<MessageEntity> candidates,
                                       List<QdrantVectorStore.ScoredHit> scoredHits,
                                       double cosineBase,
                                       String botIdStr,
                                       Long personaId) {
        // Build id → cosine map from Qdrant hits.
        Map<Long, Double> cosineById = new HashMap<>();
        for (QdrantVectorStore.ScoredHit hit : scoredHits) {
            cosineById.put(hit.id(), hit.score());
        }

        MessageEntity best = null;
        double bestBlend = Double.NEGATIVE_INFINITY;
        boolean bestWasCosine = false;

        for (MessageEntity msg : candidates) {
            double value  = computeValueScore(msg);
            double cosine = cosineById.getOrDefault(msg.getId(), 0.0);
            // Blend: value_score × max(0, cosineBase + cosine)
            // cosineBase ensures value still contributes even when cosine = 0.
            // Clamp to ≥0 so a negative cosine (very off-topic, cosine ∈ [-1,1]) cannot invert
            // the ranking — value_score remains a true floor, not a multiplier on a negative.
            double blend  = value * Math.max(0.0, cosineBase + cosine);
            if (blend > bestBlend) {
                bestBlend    = blend;
                best         = msg;
                bestWasCosine = (cosine > 0.0);
            }
        }

        if (best != null) {
            String driver = bestWasCosine ? "cosine+value" : "value-only (no vector)";
            log.info("[ProactiveNews] Persona id={} botId={} selected msg id={} blend={} driver={}",
                    personaId, botIdStr, best.getId(), String.format("%.4f", bestBlend), driver);
        }
        // Safe: candidates is non-empty (caller guards), so best != null.
        return best != null ? best : candidates.get(0);
    }

    /**
     * Generates a short, human-style first-person post about the news item and sends it.
     * Uses the same {@link DigestGenerationService#buildSystemPrompt} path for persona style
     * and the same {@link DeepSeekApiClient} used by digest generation.
     * Output is run through {@link ResponsePostProcessor#postProcess} (strips markdown).
     */
    private Mono<Void> generateAndSend(DigestPersona persona, String botIdStr, Long targetChatId,
                                       MessageEntity msg) {
        log.info("[ProactiveNews] Persona id={} name={} generating post for message id={} chatId={}",
                persona.id(), persona.name(), msg.getId(), msg.getChatId());

        String systemPrompt = digestGenerationService.buildSystemPrompt(persona);
        // Append universal liveliness-floor for RU/UK personas (no-op for others).
        String lang = persona.language();
        if (lang == null || lang.isBlank()
                || lang.toLowerCase().startsWith("ru")
                || lang.toLowerCase().startsWith("uk")
                || lang.toLowerCase().startsWith("base")) {
            String floor = appSettings.getString(
                    "news.proactive-posting.liveliness-floor-ru", DEFAULT_LIVELINESS_FLOOR_RU);
            if (floor != null && !floor.isBlank()) {
                systemPrompt = systemPrompt + "\n\n" + floor;
            }
        }
        String newsContent  = extractContent(msg);
        if (newsContent == null || newsContent.isBlank()) {
            log.debug("[ProactiveNews] Message id={} has no usable content — skipping", msg.getId());
            return Mono.empty();
        }

        // PSE Phase-1: optionally find the ORIGINAL web source for high-value items and graft it
        // into the USER prompt. Gate (enabled + value) here; the enricher is fully fail-open.
        boolean enrich = appSettings.getBoolean("news.web-enrich.enabled", false)
                && computeValueScore(msg) >= appSettings.getDouble("news.web-enrich.min-value", 1.0);
        Mono<String> sourceBlockMono = enrich
                ? primarySourceEnricher.findPrimarySource(msg, newsContent)
                : Mono.just("");

        // P1 v2: fetch this persona's recent post openers for anti-repetition hint.
        // n <= 0 disables the feature entirely (no query). Fail-open: any error yields empty list.
        int recentOpenersCount = appSettings.getInt("news.proactive-posting.recent-openers-count", 5);
        Mono<List<String>> openersMono = (recentOpenersCount <= 0)
                ? Mono.just(List.of())
                : messageRepository.findRecentOutgoingByPersona(targetChatId, botIdStr, recentOpenersCount)
                        .map(m -> extractOpenerFragment(m.getContent()))
                        .filter(f -> f != null && !f.isBlank())
                        .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))
                        .<List<String>>map(set -> List.copyOf(set))
                        .onErrorResume(e -> {
                            log.warn("[ProactiveNews] Failed to fetch recent openers for persona id={}: {}",
                                    persona.id(), e.getMessage());
                            return Mono.just(List.of());
                        });

        final String content = newsContent;
        final String finalSystemPrompt = systemPrompt;
        return Mono.zip(sourceBlockMono, openersMono).flatMap(tuple -> {
            String sourceBlock = tuple.getT1();
            List<String> recentOpeners = tuple.getT2();
            String userPrompt = buildUserPrompt(msg, content, sourceBlock, recentOpeners);

            String model     = persona.modelName() != null ? persona.modelName() : defaultModel;
            Integer maxTok   = persona.maxTokens() != null ? persona.maxTokens() : 400;
            Double  temp     = persona.temperature() != null ? persona.temperature() : 0.85;

            // Liveliness Phase 2 — sampling penalties (runtime-tunable, default 0.3 each).
            // Reduce within-text repetition (frequency) and topic-stuckness (presence).
            Double freqPenalty = appSettings.getDouble("news.liveliness.frequency-penalty", 0.3);
            Double presPenalty = appSettings.getDouble("news.liveliness.presence-penalty", 0.3);

            List<ApiMessage> apiMessages = List.of(
                    new ApiMessage("system", finalSystemPrompt),
                    new ApiMessage("user", userPrompt)
            );

            DeepSeekChatRequest request = new DeepSeekChatRequest(
                    apiMessages, model, maxTok, temp, freqPenalty, presPenalty, null, false);

            return deepSeekApiClient.chat(request, NEWS_CHAT_ID, LLM_TIMEOUT_SECONDS)
                    .defaultIfEmpty("")
                    .flatMap(rawText -> {
                        if (rawText.isBlank()) {
                            log.warn("[ProactiveNews] LLM returned empty for persona id={} msg id={}",
                                    persona.id(), msg.getId());
                            return Mono.empty();
                        }
                        // Strip markdown as the #65 path does
                        String cleanText = responsePostProcessor.postProcess(rawText, null);
                        if (cleanText == null || cleanText.isBlank()) {
                            log.warn("[ProactiveNews] Post-processed text is blank for persona id={}", persona.id());
                            return Mono.empty();
                        }
                        // Liveliness Phase 2 — deterministic cliché post-filter (fail-open backstop).
                        // If the post hits the denylist and the retry knob is on, regenerate ONCE
                        // with a stronger nudge. If retry is off, or retry still hits, post as-is.
                        boolean regenEnabled = appSettings.getBoolean("news.liveliness.regenerate-on-cliche", true);
                        if (regenEnabled && hitsClicheDenylist(cleanText)) {
                            log.info("[ProactiveNews] Cliché hit on first pass for persona id={} — retrying once",
                                    persona.id());
                            String nudgedPrompt = userPrompt +
                                    "\n\nВАЖНО: Избегай клише, хайп-штампов и восклицательных зачинов. " +
                                    "Начни с конкретного факта, цифры или следствия — не с эмоции.";
                            List<ApiMessage> retryMessages = List.of(
                                    new ApiMessage("system", finalSystemPrompt),
                                    new ApiMessage("user", nudgedPrompt)
                            );
                            DeepSeekChatRequest retryRequest = new DeepSeekChatRequest(
                                    retryMessages, model, maxTok, temp, freqPenalty, presPenalty, null, false);
                            final String fallbackText = cleanText;
                            return deepSeekApiClient.chat(retryRequest, NEWS_CHAT_ID, LLM_TIMEOUT_SECONDS)
                                    .defaultIfEmpty("")
                                    .flatMap(retryRaw -> {
                                        String retryClean = retryRaw.isBlank()
                                                ? null
                                                : responsePostProcessor.postProcess(retryRaw, null);
                                        if (retryClean == null || retryClean.isBlank()) {
                                            log.warn("[ProactiveNews] Cliché retry returned blank for persona id={} — posting original",
                                                    persona.id());
                                            return sendAndRecord(persona, botIdStr, targetChatId, msg, fallbackText);
                                        }
                                        if (hitsClicheDenylist(retryClean)) {
                                            log.warn("[ProactiveNews] Cliché retry STILL hits denylist for persona id={} — posting retry text anyway",
                                                    persona.id());
                                        }
                                        return sendAndRecord(persona, botIdStr, targetChatId, msg, retryClean);
                                    })
                                    .onErrorResume(retryErr -> {
                                        log.warn("[ProactiveNews] Cliché retry error for persona id={} — posting original: {}",
                                                persona.id(), retryErr.getMessage());
                                        return sendAndRecord(persona, botIdStr, targetChatId, msg, fallbackText);
                                    });
                        }
                        return sendAndRecord(persona, botIdStr, targetChatId, msg, cleanText);
                    });
        });
    }

    private Mono<Void> sendAndRecord(DigestPersona persona, String botIdStr, Long targetChatId,
                                     MessageEntity msg, String text) {
        double valueScore = computeValueScore(msg);

        return telegramSender.send(botIdStr, targetChatId, text)
                .flatMap(sentMsg -> {
                    Long tgMsgId = sentMsg != null ? sentMsg.id : null;
                    log.info("[ProactiveNews] Sent successfully: persona={} target={} tgMsgId={} score={}",
                            botIdStr, targetChatId, tgMsgId, valueScore);
                    // thenReturn(sentMsg) keeps a non-empty value flowing so switchIfEmpty below
                    // is NOT triggered on the success path — preventing the spurious SUPPRESSED insert.
                    return recordPost(msg.getId(), botIdStr, targetChatId, tgMsgId, valueScore, "SENT", null)
                            .then(updateLastRunAt(persona))
                            .then(Mono.fromRunnable(() ->
                                    // Fire-and-forget sibling reply (must NOT block or fail this path)
                                    siblingReplyService.onProactivePost(targetChatId, tgMsgId, text, botIdStr)
                                            .onErrorResume(e -> {
                                                log.warn("[ProactiveNews] SiblingReplyService.onProactivePost error (ignored): {}", e.getMessage());
                                                return Mono.empty();
                                            })
                                            .subscribe()
                            ))
                            .thenReturn(sentMsg);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    // send() returned empty = suppressed by OutboundReplyGuard / kill-switch.
                    // This branch fires ONLY when send() itself completes empty (before flatMap).
                    return recordPost(msg.getId(), botIdStr, targetChatId, null, valueScore, "SUPPRESSED", null)
                            .then(Mono.empty());
                }))
                .onErrorResume(sendErr -> {
                    // A FLOOD_WAIT backoff suppression is a benign transient (already WARN-logged
                    // at the facade) — don't let it surface as the log's only ERROR.
                    String em = sendErr.getMessage();
                    if (em != null && em.contains("FLOOD_WAIT backoff active")) {
                        log.warn("[ProactiveNews] Send suppressed (FLOOD_WAIT backoff) for persona id={} msg id={}",
                                persona.id(), msg.getId());
                    } else {
                        log.error("[ProactiveNews] Send failed for persona id={} msg id={}: {}",
                                persona.id(), msg.getId(), sendErr.getMessage());
                    }
                    return recordPost(msg.getId(), botIdStr, targetChatId, null, valueScore,
                            "FAILED", sendErr.getMessage())
                            .then(Mono.empty());
                })
                .then();
    }

    private Mono<Void> recordPost(Long messageId, String botIdStr, Long targetChatId,
                                  Long tgMsgId, double valueScore, String status, String errorMsg) {
        NewsPost post = new NewsPost();
        post.setMessageId(messageId);
        post.setPersonaBotId(botIdStr);
        post.setTargetChatId(targetChatId);
        post.setTelegramMessageId(tgMsgId);
        post.setValueScore(valueScore);
        post.setStatus(status);
        post.setErrorMessage(errorMsg);
        post.setPostedAt(Instant.now());
        return newsPostRepository.save(post)
                .doOnSuccess(p -> log.debug("[ProactiveNews] Recorded news_post id={} status={}", p.getId(), status))
                .doOnError(e -> log.warn("[ProactiveNews] Failed to record news_post: {}", e.getMessage()))
                .onErrorResume(e -> Mono.empty())
                .then();
    }

    private Mono<Void> updateLastRunAt(DigestPersona persona) {
        if (persona.id() == null) return Mono.empty();
        return digestPersonaRepository.updateLastRunAt(persona.id(), Instant.now())
                .doOnError(e -> log.warn("[ProactiveNews] Failed to update last_run_at for persona {}: {}",
                        persona.id(), e.getMessage()))
                .onErrorResume(e -> Mono.empty())
                .then();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} when {@code text} contains any entry from the runtime cliché denylist
     * ({@code news.liveliness.cliche-denylist}). Matching is case-insensitive substring, same
     * pattern as {@link #passesGlobalShillFilter}. Returns {@code false} (no match) when the
     * denylist is empty or disabled (single space), so the filter is always fail-open.
     */
    private boolean hitsClicheDenylist(String text) {
        String raw = appSettings.getString("news.liveliness.cliche-denylist", DEFAULT_CLICHE_DENYLIST);
        if (raw == null || raw.isBlank()) {
            return false; // denylist explicitly disabled
        }
        List<String> markers = Arrays.stream(raw.split("[\\n,]"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toLowerCase)
                .toList();
        if (markers.isEmpty()) {
            return false;
        }
        String lower = text.toLowerCase();
        for (String marker : markers) {
            if (matchesWholeWord(lower, marker)) {
                log.debug("[ProactiveNews] Cliché marker '{}' found in generated text", marker);
                return true;
            }
        }
        return false;
    }

    private boolean passesNegativeKeywordFilter(MessageEntity msg, DigestPersona persona) {
        String[] negative = persona.negativeKeywords();
        if (negative == null || negative.length == 0) return true;
        String text = extractContent(msg);
        if (text == null || text.isBlank()) return true;
        return Arrays.stream(negative)
                .filter(Objects::nonNull)
                .noneMatch(kw -> matchesWholeWord(text, kw));
    }

    /**
     * Global shill/promo content filter. Applies to every persona (GLOBAL safety net).
     *
     * <p>Reads the denylist from {@code bot.app_settings} key
     * {@code news.proactive-posting.shill-denylist} with {@link #DEFAULT_SHILL_DENYLIST} as the
     * fallback. Markers are split on newlines and commas, trimmed, lower-cased, blanks dropped.
     * If the resulting list is empty the filter is disabled (fail-open — posting is never broken).
     *
     * <p>Matching is case-insensitive <em>substring</em> (not whole-word) because shill markers
     * are distinctive multi-char phrases/tokens (e.g. "бонус на депозит", "+242% за") that must
     * match mid-text and would never appear as standalone words.
     *
     * @return {@code true} if the message is clean (may be posted), {@code false} if it matches a
     *         shill marker and should be skipped
     */
    private boolean passesGlobalShillFilter(MessageEntity msg) {
        String raw = appSettings.getString("news.proactive-posting.shill-denylist", DEFAULT_SHILL_DENYLIST);
        if (raw == null || raw.isBlank()) {
            return true; // filter explicitly disabled
        }
        List<String> markers = Arrays.stream(raw.split("[\\n,]"))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(s -> !s.isEmpty())
                .toList();
        if (markers.isEmpty()) {
            return true; // nothing to match — fail-open
        }
        String text = extractContent(msg);
        if (text == null || text.isBlank()) {
            return true; // no content — can't match; pass through
        }
        String lower = text.toLowerCase();
        for (String marker : markers) {
            boolean matched = SHILL_WHOLE_WORD_MARKERS.contains(marker)
                    ? matchesWholeWord(text, marker)
                    : lower.contains(marker);
            if (matched) {
                log.debug("[ProactiveNews] shill filter rejected msg {} (marker '{}')", msg.getId(), marker);
                return false;
            }
        }
        return true;
    }

    /**
     * Case-insensitive, unicode-aware, WORD-BOUNDARY match. Replaces a bare {@code contains}
     * substring test so a short topic keyword like {@code "ai"} matches the word "AI" but NOT
     * "again"/"remain"/"Ukraine" (which leaked the topic routing — every persona matched almost
     * everything). Multi-word phrases (e.g. "central bank", "artificial intelligence") still match
     * on their outer boundaries. Same matching family as {@code GeoTaggingService}/{@code KeywordMatchingService}.
     */
    private static boolean matchesWholeWord(String text, String keyword) {
        String kw = keyword.trim();
        if (kw.isEmpty()) return false;
        return java.util.regex.Pattern
                .compile("\\b" + java.util.regex.Pattern.quote(kw) + "\\b",
                        java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CHARACTER_CLASS)
                .matcher(text).find();
    }

    private String extractContent(MessageEntity msg) {
        if (msg.getContent() != null && !msg.getContent().isBlank()) return msg.getContent();
        if (msg.getCaption() != null && !msg.getCaption().isBlank()) return msg.getCaption();
        return null;
    }

    private double computeValueScore(MessageEntity msg) {
        // Prefer the fully-computed ranking value (importance * ln(greatest(subscribers,2)))
        // that the SQL query projects as value_score. Fall back to raw importance if null.
        Double computed = msg.getValueScore();
        if (computed != null) return computed;
        log.warn("[ProactiveNews] value_score NULL for msg id={} — falling back to raw importance"
                + " (scale mismatch; check recall column order)", msg.getId());
        Double importance = msg.getImportance();
        return importance != null ? importance : 0.0;
    }

    private String buildUserPrompt(MessageEntity msg, String content) {
        return buildUserPrompt(msg, content, "", List.of());
    }

    private String buildUserPrompt(MessageEntity msg, String content, String sourceBlock) {
        return buildUserPrompt(msg, content, sourceBlock, List.of());
    }

    /**
     * Builds the USER prompt. When {@code sourceBlock} is blank and {@code recentOpeners} is empty
     * this is byte-for-byte the original prompt (regression-safe with both features off).
     *
     * <p>When a verified primary source is found, it is appended as additive reference material
     * with a strict citation contract — persona voice is unaffected (it lives in the SYSTEM prompt).
     *
     * <p>When {@code recentOpeners} is non-empty, a short Russian-language anti-repetition
     * instruction is appended asking the model to open with a different first phrase.
     */
    private String buildUserPrompt(MessageEntity msg, String content, String sourceBlock,
                                   List<String> recentOpeners) {
        StringBuilder sb = new StringBuilder();
        sb.append("News item to react to:\n\n");
        if (msg.getSenderName() != null && !msg.getSenderName().isBlank()) {
            sb.append("[Source: ").append(msg.getSenderName()).append("]\n");
        }
        int maxLen = 800;
        if (content.length() > maxLen) {
            content = content.substring(0, maxLen) + "...";
        }
        sb.append(content);
        if (sourceBlock != null && !sourceBlock.isBlank()) {
            sb.append("\n\nPrimary source (original reporting — rewrite in your own words, do not copy; "
                    + "you may name the outlet ONLY; do not invent quotes, stats, dates or names not present above):\n");
            sb.append(sourceBlock);
        }
        if (recentOpeners != null && !recentOpeners.isEmpty()) {
            sb.append("\n\nТвои недавние посты начинались так: ");
            for (int i = 0; i < recentOpeners.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append("«").append(recentOpeners.get(i)).append("»");
            }
            sb.append(". Начни ЭТОТ пост ИНАЧЕ — другой первой фразой, не повторяй эти начала.");
        }
        return sb.toString();
    }

    /**
     * Extracts a short opening fragment from a post's text — up to the first sentence-ending
     * punctuation (., !, ?) or approximately the first 6 words, capped at 60 characters.
     * Returns {@code null} when {@code text} is blank.
     */
    static String extractOpenerFragment(String text) {
        if (text == null || text.isBlank()) return null;
        String trimmed = text.strip();
        // Find first sentence-end marker within the first 60 chars
        int cap = Math.min(trimmed.length(), 60);
        for (int i = 0; i < cap; i++) {
            char c = trimmed.charAt(i);
            if (c == '.' || c == '!' || c == '?') {
                return trimmed.substring(0, i + 1).strip();
            }
        }
        // No sentence end found — take up to 6 whitespace-separated tokens or 60 chars
        String[] tokens = trimmed.split("\\s+", 8);
        int take = Math.min(6, tokens.length);
        String fragment = String.join(" ", Arrays.copyOf(tokens, take));
        if (fragment.length() > 60) fragment = fragment.substring(0, 60);
        return fragment.strip();
    }
}
