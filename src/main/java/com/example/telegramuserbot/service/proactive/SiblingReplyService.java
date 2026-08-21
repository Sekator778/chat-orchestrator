package com.example.telegramuserbot.service.proactive;

import com.example.telegramuserbot.domain.SiblingReply;
import com.example.telegramuserbot.domain.TelegramAccount;
import com.example.telegramuserbot.repository.BotPersonaRepository;
import com.example.telegramuserbot.repository.PersonaChatBindingRepository;
import com.example.telegramuserbot.repository.SiblingReplyRepository;
import com.example.telegramuserbot.repository.TelegramAccountRepository;
import com.example.telegramuserbot.service.config.AppSettingsService;
import com.example.telegramuserbot.service.humanization.PersonaService;
import com.example.telegramuserbot.service.llm.client.DeepSeekApiClient;
import com.example.telegramuserbot.service.llm.dto.ApiMessage;
import com.example.telegramuserbot.service.llm.dto.DeepSeekChatRequest;
import com.example.telegramuserbot.service.orchestration.PersonaScheduleService;
import com.example.telegramuserbot.service.orchestration.ResponsePostProcessor;
import com.example.telegramuserbot.service.publishing.TelegramMessageSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Controlled inter-persona "sibling replies": when persona A makes a proactive news post,
 * one or more other personas B may reply to it — giving the group chat a lively, multi-voice
 * feel while staying tightly controlled to avoid detection.
 *
 * <h3>Why depth-1 is guaranteed</h3>
 * The ONLY trigger is an in-process event from {@link ProactiveNewsPostingService} after a
 * successful proactive send. {@link com.example.telegramuserbot.service.TelegramListenerService}
 * never invokes this path — the listener's {@code isBotPersonaMessage} early-return is untouched.
 * Sibling replies go directly through {@link TelegramMessageSender} (which hits TDLib), so they
 * never re-enter the Kafka listener → consumer pipeline and therefore can never trigger
 * additional sibling replies.
 *
 * <h3>Guard checklist (all enforced on every invocation)</h3>
 * <ol>
 *   <li>Master flag {@code persona.sibling-reply.enabled} must be {@code true}.</li>
 *   <li>Group-only: {@code chatId} must be negative (groups have negative IDs in TDLib).</li>
 *   <li>Candidates from {@code persona_chat_bindings} for the target chat.</li>
 *   <li>Exclude the origin poster (no self-reply).</li>
 *   <li>Exclude collector accounts (they must never chat).</li>
 *   <li>Per-persona probability roll from {@code telegram_accounts.sibling_reply_probability}.</li>
 *   <li>Global cap: {@code persona.sibling-reply.max-per-post} total replies per origin post.</li>
 *   <li>Per-persona daily cap from {@code telegram_accounts.sibling_reply_max_per_day}.</li>
 *   <li>Human delay: random jitter in [{@code sibling_reply_min_delay_sec}, {@code sibling_reply_max_delay_sec}].</li>
 *   <li>Active-hours check ({@link PersonaScheduleService#isActiveNow}) re-evaluated after delay.</li>
 *   <li>LLM generation with persona voice + liveliness-floor, distinct-take user prompt.</li>
 *   <li>Send via {@link TelegramMessageSender} (OutboundReplyGuard/kill-switch apply automatically).</li>
 * </ol>
 *
 * <p>Defaults: master flag {@code false}, per-persona probability {@code 0.0} — ships fully OFF.
 */
@Service
public class SiblingReplyService {

    private static final Logger log = LoggerFactory.getLogger(SiblingReplyService.class);

    /** Pseudo chat-id for LLM call logging (no real chat context for sibling replies). */
    private static final long SIBLING_CHAT_ID = -98L;

    /** Timeout for LLM call. */
    private static final int LLM_TIMEOUT_SECONDS = 60;

    private final AppSettingsService appSettings;
    private final PersonaChatBindingRepository personaChatBindingRepository;
    private final TelegramAccountRepository telegramAccountRepository;
    private final BotPersonaRepository botPersonaRepository;
    private final SiblingReplyRepository siblingReplyRepository;
    private final PersonaScheduleService personaScheduleService;
    private final TelegramMessageSender telegramSender;
    private final PersonaService personaService;
    private final DeepSeekApiClient deepSeekApiClient;
    private final ResponsePostProcessor responsePostProcessor;

    @Value("${deepseek.model:deepseek-chat}")
    private String defaultModel;

    public SiblingReplyService(
            AppSettingsService appSettings,
            PersonaChatBindingRepository personaChatBindingRepository,
            TelegramAccountRepository telegramAccountRepository,
            BotPersonaRepository botPersonaRepository,
            SiblingReplyRepository siblingReplyRepository,
            PersonaScheduleService personaScheduleService,
            TelegramMessageSender telegramSender,
            PersonaService personaService,
            DeepSeekApiClient deepSeekApiClient,
            ResponsePostProcessor responsePostProcessor) {
        this.appSettings = Objects.requireNonNull(appSettings);
        this.personaChatBindingRepository = Objects.requireNonNull(personaChatBindingRepository);
        this.telegramAccountRepository = Objects.requireNonNull(telegramAccountRepository);
        this.botPersonaRepository = Objects.requireNonNull(botPersonaRepository);
        this.siblingReplyRepository = Objects.requireNonNull(siblingReplyRepository);
        this.personaScheduleService = Objects.requireNonNull(personaScheduleService);
        this.telegramSender = Objects.requireNonNull(telegramSender);
        this.personaService = Objects.requireNonNull(personaService);
        this.deepSeekApiClient = Objects.requireNonNull(deepSeekApiClient);
        this.responsePostProcessor = Objects.requireNonNull(responsePostProcessor);
    }

    /**
     * Called by {@link ProactiveNewsPostingService} immediately after a successful proactive send.
     * Returns quickly by scheduling the delayed work as independent subscriptions.
     * MUST NOT block or throw — chained with {@code .onErrorResume(e -> Mono.empty())}.
     *
     * @param chatId          the group chat where the post was sent
     * @param originMessageId the Telegram message id of persona A's post (the reply-to target)
     * @param content         the post text (used as context in the sibling reply prompt)
     * @param originBotId     the botId of persona A (the poster — excluded from candidate list)
     */
    public Mono<Void> onProactivePost(Long chatId, Long originMessageId, String content, String originBotId) {
        // Guard 1: master flag
        if (!appSettings.getBoolean("persona.sibling-reply.enabled", false)) {
            log.debug("[SiblingReply] disabled (master flag false) — skipping chatId={}", chatId);
            return Mono.empty();
        }

        // Guard 2: group-only (group chats have negative IDs in TDLib)
        if (chatId == null || chatId >= 0) {
            log.debug("[SiblingReply] non-group chatId={} — skipping", chatId);
            return Mono.empty();
        }

        int maxPerPost = appSettings.getInt("persona.sibling-reply.max-per-post", 1);

        return personaChatBindingRepository.findEnabledBindingsByChatId(chatId)
                .collectList()
                .flatMap(bindings -> {
                    // Guard 3+4: exclude origin poster
                    List<String> candidates = new ArrayList<>();
                    for (var b : bindings) {
                        if (!b.getBotId().equals(originBotId)) {
                            candidates.add(b.getBotId());
                        }
                    }

                    if (candidates.isEmpty()) {
                        log.debug("[SiblingReply] no candidate personas after excluding poster={} in chatId={}",
                                originBotId, chatId);
                        return Mono.empty();
                    }

                    // Resolve TelegramAccount for each candidate, filter collectors, roll probability
                    // Build a Flux that emits botIds that should fire
                    return buildFiringPersonas(candidates, maxPerPost)
                            .flatMap(firingBotId ->
                                    scheduleOneReply(firingBotId, chatId, originMessageId, content, originBotId),
                                    1 /* sequential — avoid parallel scheduling storms */)
                            .then();
                })
                .onErrorResume(e -> {
                    log.warn("[SiblingReply] Error during candidate resolution for chatId={}: {}",
                            chatId, e.getMessage(), e);
                    return Mono.empty();
                });
    }

    /**
     * Resolves which personas from the candidate list actually fire, enforcing:
     * Guard 5: collector exclusion
     * Guard 6: per-persona probability roll
     * Guard 7: global per-post cap
     */
    private Flux<String> buildFiringPersonas(List<String> candidateBotIds, int maxPerPost) {
        // For each candidate, resolve the TelegramAccount row then apply guards
        return Flux.fromIterable(candidateBotIds)
                .flatMap(botId -> telegramAccountRepository.findByBotId(botId)
                        .map(account -> new Object[]{ botId, account })
                        .onErrorResume(e -> {
                            log.warn("[SiblingReply] Could not resolve account for botId={}: {}", botId, e.getMessage());
                            return Mono.empty();
                        }),
                        4 /* parallel lookups up to 4 */)
                .filter(pair -> {
                    String botId = (String) pair[0];
                    TelegramAccount account = (TelegramAccount) pair[1];

                    // Guard 5: exclude collector
                    if (account.isCollector()) {
                        log.debug("[SiblingReply] Skipping collector botId={}", botId);
                        return false;
                    }

                    // Guard 6: per-persona probability roll
                    double prob = account.getSiblingReplyProbability();
                    if (prob <= 0.0) {
                        log.debug("[SiblingReply] botId={} probability=0 — skip", botId);
                        return false;
                    }
                    boolean fires = ThreadLocalRandom.current().nextDouble() < prob;
                    log.info("[SiblingReply] botId={} probability={} → fires={}", botId, prob, fires);
                    return fires;
                })
                .map(pair -> (String) pair[0])
                .take(maxPerPost); // Guard 7: global per-post cap
    }

    /**
     * Schedules a single sibling reply with random human delay for {@code replyingBotId}.
     * Fires-and-forgets as an independent subscription so the caller (onProactivePost) returns fast.
     */
    private Mono<Void> scheduleOneReply(String replyingBotId, Long chatId, Long originMessageId,
                                         String content, String originBotId) {
        // Claim first (idempotency) — before the delay so a restart can't double-send
        SiblingReply claim = new SiblingReply();
        claim.setPersonaBotId(replyingBotId);
        claim.setChatId(chatId);
        claim.setInReplyToMessageId(originMessageId);
        claim.setOriginBotId(originBotId);

        return siblingReplyRepository.save(claim)
                .<Void>flatMap(savedClaim -> checkCapAndSchedule(replyingBotId, chatId, originMessageId, content))
                .onErrorResume(DataIntegrityViolationException.class, e -> {
                    log.info("[SiblingReply] botId={} already replied to originMsgId={} (idempotency) — skip",
                            replyingBotId, originMessageId);
                    return Mono.empty();
                })
                .onErrorResume(e -> {
                    log.warn("[SiblingReply] Claim/scheduling failed botId={} originMsgId={}: {}",
                            replyingBotId, originMessageId, e.getMessage(), e);
                    return Mono.empty();
                });
    }

    private Mono<Void> checkCapAndSchedule(String replyingBotId, Long chatId, Long originMessageId, String content) {
        Instant todayStart = Instant.now().truncatedTo(ChronoUnit.DAYS);
        return siblingReplyRepository.countByPersonaBotIdAndPostedAtAfter(replyingBotId, todayStart)
                .flatMap(countToday -> telegramAccountRepository.findByBotId(replyingBotId)
                        .flatMap(account -> {
                            // Guard 8: daily cap — count includes the claim we just inserted
                            if (countToday > account.getSiblingReplyMaxPerDay()) {
                                log.info("[SiblingReply] botId={} daily cap reached ({}/{}), skipping",
                                        replyingBotId, countToday, account.getSiblingReplyMaxPerDay());
                                return Mono.<Void>empty();
                            }

                            int minDelay = account.getSiblingReplyMinDelaySec();
                            int maxDelay = account.getSiblingReplyMaxDelaySec();
                            int delaySec = minDelay + (maxDelay > minDelay
                                    ? ThreadLocalRandom.current().nextInt(maxDelay - minDelay)
                                    : 0);

                            log.info("[SiblingReply] Scheduling reply: botId={} chatId={} originMsgId={} delay={}s",
                                    replyingBotId, chatId, originMessageId, delaySec);

                            // Fire-and-forget: subscribe independently so we return immediately
                            Mono.delay(Duration.ofSeconds(delaySec))
                                    .publishOn(Schedulers.boundedElastic())
                                    .flatMap(tick -> executeReply(replyingBotId, chatId, originMessageId, content))
                                    .onErrorResume(e -> {
                                        log.warn("[SiblingReply] Reply failed botId={} chatId={}: {}",
                                                replyingBotId, chatId, e.getMessage(), e);
                                        return Mono.<Void>empty();
                                    })
                                    .subscribe();

                            return Mono.<Void>empty();
                        }));
    }

    /**
     * The actual reply: re-checks active hours, generates text via LLM, sends through TelegramMessageSender.
     */
    private Mono<Void> executeReply(String replyingBotId, Long chatId, Long originMessageId, String content) {
        // Guard 9: active-hours re-check after the delay
        return personaScheduleService.isActiveNow(replyingBotId)
                .flatMap(active -> {
                    if (!active) {
                        log.info("[SiblingReply] botId={} outside active hours after delay — skipping reply",
                                replyingBotId);
                        return Mono.empty();
                    }

                    // Resolve language for persona voice
                    return botPersonaRepository.findByBotId(replyingBotId)
                            .next() // first row (persona may have multiple language rows)
                            .map(persona -> persona.getLanguage() != null ? persona.getLanguage() : "ru")
                            .defaultIfEmpty("ru")
                            .flatMap(language -> generateAndSend(replyingBotId, language, chatId, originMessageId, content));
                });
    }

    /**
     * Builds system prompt (persona voice + liveliness-floor), generates LLM reply, sends it.
     */
    private Mono<Void> generateAndSend(String replyingBotId, String language,
                                        Long chatId, Long originMessageId, String content) {
        // Build system prompt: persona identity + liveliness floor (same as proactive path)
        String baseSystemPrompt = buildBaseSystemPrompt(language);
        String systemPrompt = personaService.buildPersonaSystemPrompt(baseSystemPrompt, language, replyingBotId);

        // Append liveliness-floor for RU/UK/base personas (reuses same key + constant as proactive path)
        String lang = language != null ? language.toLowerCase() : "";
        if (lang.isBlank() || lang.startsWith("ru") || lang.startsWith("uk") || lang.startsWith("base")) {
            String floor = appSettings.getString(
                    "news.proactive-posting.liveliness-floor-ru",
                    ProactiveNewsPostingService.DEFAULT_LIVELINESS_FLOOR_RU);
            if (floor != null && !floor.isBlank()) {
                systemPrompt = systemPrompt + "\n\n" + floor;
            }
        }

        String userPrompt = buildUserPrompt(content, language);

        List<ApiMessage> apiMessages = List.of(
                new ApiMessage("system", systemPrompt),
                new ApiMessage("user", userPrompt)
        );

        DeepSeekChatRequest request = new DeepSeekChatRequest(apiMessages, defaultModel, 200, 0.9);

        return deepSeekApiClient.chat(request, SIBLING_CHAT_ID, LLM_TIMEOUT_SECONDS)
                .defaultIfEmpty("")
                .flatMap(rawText -> {
                    if (rawText.isBlank()) {
                        log.warn("[SiblingReply] LLM returned empty for botId={}", replyingBotId);
                        return Mono.empty();
                    }
                    String cleanText = responsePostProcessor.postProcess(rawText, null);
                    if (cleanText == null || cleanText.isBlank()) {
                        log.warn("[SiblingReply] Post-processed reply is blank for botId={}", replyingBotId);
                        return Mono.empty();
                    }

                    log.info("[SiblingReply] Sending reply botId={} chatId={} replyTo={} len={}",
                            replyingBotId, chatId, originMessageId, cleanText.length());

                    // Guard 10: send through the choke point — OutboundReplyGuard + kill-switch apply automatically
                    return telegramSender.send(replyingBotId, chatId, originMessageId, cleanText)
                            .doOnSuccess(msg -> log.info("[SiblingReply] Sent botId={} chatId={} tgMsgId={}",
                                    replyingBotId, chatId, msg != null ? msg.id : null))
                            .onErrorResume(e -> {
                                log.warn("[SiblingReply] Send failed botId={} chatId={}: {}", replyingBotId, chatId, e.getMessage());
                                return Mono.empty();
                            })
                            .then();
                });
    }

    /**
     * Base system prompt instructing persona to play an ordinary group chat participant.
     * Kept minimal — persona identity layers on top via {@link PersonaService#buildPersonaSystemPrompt}.
     */
    private String buildBaseSystemPrompt(String language) {
        boolean isRu = language == null || language.isBlank()
                || language.toLowerCase().startsWith("ru")
                || language.toLowerCase().startsWith("uk")
                || language.toLowerCase().startsWith("base");
        if (isRu) {
            return "Ты участник группового чата. Общайся как живой человек — кратко, по делу, своим характером.";
        }
        return "You are a participant in a group chat. Communicate as a real human — briefly, on point, in your own character.";
    }

    /**
     * User prompt that forces a distinct take instead of an echo/restatement.
     */
    private String buildUserPrompt(String content, String language) {
        boolean isRu = language == null || language.isBlank()
                || language.toLowerCase().startsWith("ru")
                || language.toLowerCase().startsWith("uk")
                || language.toLowerCase().startsWith("base");
        if (isRu) {
            return "Другой участник только что написал пост:\n\n«" + content + "»\n\n"
                    + "Коротко ответь репликой в своём характере — добавь свой угол: факт, скептицизм, "
                    + "неожиданное следствие или встречный вопрос. "
                    + "НЕ повторяй и не пересказывай его пост, не поддакивай. "
                    + "1–2 предложения, живым разговорным языком.";
        }
        return "Another participant just wrote:\n\n\"" + content + "\"\n\n"
                + "Reply briefly in your own character — add your angle: a fact, skepticism, "
                + "an unexpected implication, or a counter-question. "
                + "DO NOT restate or echo the post, don't just agree. "
                + "1–2 sentences, natural conversational language.";
    }
}
