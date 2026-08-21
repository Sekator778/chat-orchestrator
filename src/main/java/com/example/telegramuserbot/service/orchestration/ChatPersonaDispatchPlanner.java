package com.example.telegramuserbot.service.orchestration;

import com.example.telegramuserbot.domain.Channel;
import com.example.telegramuserbot.domain.RateLimits;
import com.example.telegramuserbot.repository.ChannelRepository;
import com.example.telegramuserbot.repository.PersonaChatBindingRepository;
import com.example.telegramuserbot.repository.RateLimitsRepository;
import com.example.telegramuserbot.repository.TelegramAccountRepository;
import com.example.telegramuserbot.service.TelegramClientManager;
import com.example.telegramuserbot.service.config.AppSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Builds a per-message dispatch plan for chat personas:
 * - source of personas is tgscan.channels.bot_instance_id (TEXT[])
 * - validates that each botId has a Telegram client; if not -> removes it from the array and errors
 * - selects at most {@code bot.responders.max-per-message} personas (random pick;
 *   interest-based affinity is the planned upgrade) — a real chat never has
 *   everyone answering the same message
 * - applies chat-scoped daily quota and returns only first N botIds (ordered)
 */
@Component
public class ChatPersonaDispatchPlanner {

    private static final Logger log = LoggerFactory.getLogger(ChatPersonaDispatchPlanner.class);

    private final ChannelRepository channelRepository;
    private final TelegramClientManager telegramClientManager;
    private final RateLimitsRepository rateLimitsRepository;
    private final PersonaChatBindingRepository personaChatBindingRepository;
    private final PersonaScheduleService personaScheduleService;
    private final TelegramAccountRepository telegramAccountRepository;
    private final AppSettingsService appSettings;

    @Value("${llm.persona-fanout.daily-quota.reserve-retries:2}")
    private int reserveRetries;

    // Dispatch behavior is owner-tunable via bot.app_settings (read through the
    // AppSettingsService TTL cache), not env/compiled defaults. The fallbacks
    // below are a safety net for a missing row; the seeded table values win.
    //
    //   bindings.enabled                         — candidates from persona_chat_bindings
    //                                              (per-persona chat membership); chats with
    //                                              no bindings fall back to the legacy array.
    //   responders.per_persona_decision_enabled  — no cap; each persona rolls its own
    //                                              reply_probability (count is emergent 0..N).
    //                                              When false, the legacy max-per-message cap.
    //   responders.default_reply_probability     — reply chance for a binding with no explicit
    //                                              probability (raise toward 1.0 for 1->1).
    //   responders.max_per_message               — legacy cap, only when per-persona is OFF.
    private boolean bindingsEnabled() {
        return appSettings.getBoolean("bindings.enabled", false);
    }

    private boolean perPersonaDecisionEnabled() {
        return appSettings.getBoolean("responders.per_persona_decision_enabled", false);
    }

    private double defaultReplyProbability() {
        return appSettings.getDouble("responders.default_reply_probability", 0.5);
    }

    private int maxRespondersPerMessage() {
        return appSettings.getInt("responders.max_per_message", 1);
    }

    public ChatPersonaDispatchPlanner(ChannelRepository channelRepository,
                                      TelegramClientManager telegramClientManager,
                                      RateLimitsRepository rateLimitsRepository,
                                      PersonaChatBindingRepository personaChatBindingRepository,
                                      PersonaScheduleService personaScheduleService,
                                      TelegramAccountRepository telegramAccountRepository,
                                      AppSettingsService appSettings) {
        this.channelRepository = channelRepository;
        this.telegramClientManager = telegramClientManager;
        this.rateLimitsRepository = rateLimitsRepository;
        this.personaChatBindingRepository = personaChatBindingRepository;
        this.personaScheduleService = personaScheduleService;
        this.telegramAccountRepository = telegramAccountRepository;
        this.appSettings = appSettings;
    }

    public Mono<List<String>> planBotIds(long chatId, Long chatConfigId) {
        return resolveCandidates(chatId)
                .map(this::normalizeAndDedupePreservingOrder)
                .doOnNext(botIds -> log.info("[Chat {}] Normalized dispatch candidates: {}", chatId, botIds))
                .flatMap(botIds -> validateClientsOrFail(chatId, botIds).thenReturn(botIds))
                // A collector persona is harvest-only BY DEFAULT, but an explicit enabled
                // reply-binding for THIS chat is the owner's per-persona opt-in (each persona =
                // a DB toggle) and lets it reply here. Personas outside their activity window
                // are also not candidates (schedule-based activity; NULL window = always on).
                .flatMap(botIds -> Flux.fromIterable(botIds)
                        .filterWhen(botId -> isAllowedToReply(chatId, botId))
                        .filterWhen(personaScheduleService::isActiveNow)
                        .collectList())
                .flatMap(botIds -> selectFinalResponders(chatId, botIds))
                .flatMap(botIds -> applyDailyQuota(chatId, chatConfigId, botIds, Math.max(0, reserveRetries))
                        .doOnNext(selected -> log.info("[Chat {}] Persona fan-out final list: {} (chatConfigId={})",
                                chatId, selected, chatConfigId)));
    }

    private Mono<List<String>> resolveCandidates(long chatId) {
        if (!bindingsEnabled()) {
            return legacyCandidates(chatId);
        }
        return personaChatBindingRepository.findEnabledBotIdsByChatId(chatId)
                .collectList()
                .flatMap(bound -> {
                    if (!bound.isEmpty()) {
                        log.info("[Chat {}] Dispatch candidates from persona_chat_bindings: {}", chatId, bound);
                        return Mono.just(bound);
                    }
                    log.info("[Chat {}] No persona_chat_bindings — falling back to legacy channel bot_instance_id", chatId);
                    return legacyCandidates(chatId);
                });
    }

    private Mono<List<String>> legacyCandidates(long chatId) {
        return channelRepository.findByChatId(chatId)
                .switchIfEmpty(Mono.error(new IllegalStateException("Channel not found for chatId " + chatId)))
                .doOnNext(channel -> log.info("[Chat {}] Channel bot_instance_id configured: {}", chatId, channel.getBotInstanceIds()))
                .map(Channel::getBotInstanceIds);
    }

    /**
     * Final responder selection over the schedule-passing candidates.
     * <ul>
     *   <li>Per-persona model (owner): each persona is included independently
     *       with its own reply_probability — no cap, count is emergent (0..N).</li>
     *   <li>Legacy model: the old hard max-per-message cap.</li>
     * </ul>
     */
    private Mono<List<String>> selectFinalResponders(long chatId, List<String> scheduledBotIds) {
        if (scheduledBotIds.isEmpty()) {
            return Mono.just(List.of());
        }
        if (!perPersonaDecisionEnabled()) {
            return Mono.just(selectResponders(chatId, scheduledBotIds));
        }
        double defaultProbability = defaultReplyProbability();
        return personaChatBindingRepository.findEnabledBindingsByChatId(chatId)
                .collectMap(b -> b.getBotId(),
                        b -> b.getReplyProbability() != null ? b.getReplyProbability() : defaultProbability)
                .map(probabilities -> {
                    List<String> selected = new ArrayList<>();
                    for (String botId : scheduledBotIds) {
                        double p = probabilities.getOrDefault(botId, defaultProbability);
                        if (rollSucceeds(p)) {
                            selected.add(botId);
                        }
                    }
                    log.info("[Chat {}] Per-persona responders: {} of {} candidates chimed in -> {}",
                            chatId, selected.size(), scheduledBotIds.size(), selected);
                    return List.copyOf(selected);
                });
    }

    /**
     * Collector personas harvest news and must never reply (owner directive). Enforcing it
     * here in the dispatch planner guarantees a silent collector for every chat regardless of
     * stale {@code persona_chat_bindings} or {@code bot_instance_id} entries, at any persona count.
     * Non-collector ({@code is_collector=false}) and unknown botIds remain eligible.
     */
    /**
     * A non-collector may always reply. A collector is harvest-only by default, but the owner's
     * per-persona toggle — an explicit enabled reply-binding for this chat — overrides that and
     * lets the collector persona reply here (e.g. the crypto persona on the collector account).
     */
    private Mono<Boolean> isAllowedToReply(long chatId, String botId) {
        return telegramAccountRepository.isCollector(botId)
                .defaultIfEmpty(false)
                .flatMap(isCollector -> {
                    if (!Boolean.TRUE.equals(isCollector)) {
                        return Mono.just(true);
                    }
                    return personaChatBindingRepository.findEnabledBotIdsByChatId(chatId)
                            .any(bound -> bound.equals(botId))
                            .doOnNext(allowed -> log.info(
                                    "[Dispatch] Collector botId={} chat={}: explicit reply-binding={} → {}",
                                    botId, chatId, allowed, allowed ? "ALLOW reply" : "exclude (harvest-only)"));
                });
    }

    /** Independent Bernoulli roll for one persona's reply_probability. */
    boolean rollSucceeds(double probability) {
        if (probability >= 1.0) {
            return true;
        }
        if (probability <= 0.0) {
            return false;
        }
        return ThreadLocalRandom.current().nextDouble() < probability;
    }

    /**
     * Picks at most maxRespondersPerMessage personas from the candidates.
     * Legacy cap (used only when per-persona decision is OFF).
     */
    private List<String> selectResponders(long chatId, List<String> botIds) {
        int maxResponders = maxRespondersPerMessage();
        if (maxResponders <= 0 || botIds.size() <= maxResponders) {
            return botIds;
        }
        List<String> shuffled = new ArrayList<>(botIds);
        for (int i = shuffled.size() - 1; i > 0; i--) {
            int j = ThreadLocalRandom.current().nextInt(i + 1);
            String tmp = shuffled.get(i);
            shuffled.set(i, shuffled.get(j));
            shuffled.set(j, tmp);
        }
        List<String> selected = List.copyOf(shuffled.subList(0, maxResponders));
        log.info("[Chat {}] Responder selection: {} of {} candidates -> {}",
                chatId, selected.size(), botIds.size(), selected);
        return selected;
    }

    private List<String> normalizeAndDedupePreservingOrder(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String botId : raw) {
            String normalized = botId != null ? botId.trim() : null;
            if (normalized == null || normalized.isBlank()) {
                continue;
            }
            seen.add(normalized);
        }
        return new ArrayList<>(seen);
    }

    private Mono<Void> validateClientsOrFail(long chatId, List<String> botIds) {
        if (botIds == null || botIds.isEmpty()) {
            return Mono.empty();
        }

        return Flux.fromIterable(botIds)
                .concatMap(botId -> {
                    if (isCorruptedBotId(botId)) {
                        return channelRepository.removeBotInstanceId(chatId, botId)
                                .doOnNext(updated -> log.error("[Chat {}] Removed corrupted botId={} from tgscan.channels (updatedRows={})",
                                        chatId, botId, updated))
                                .then(Mono.error(new IllegalStateException("Corrupted botId value in tgscan.channels.bot_instance_id: " + botId)));
                    }

                    if (telegramClientManager.getClient(botId) == null) {
                        return channelRepository.removeBotInstanceId(chatId, botId)
                                .doOnNext(updated -> log.error("[Chat {}] Removed botId={} without Telegram client from tgscan.channels (updatedRows={})",
                                        chatId, botId, updated))
                                .then(Mono.error(new IllegalStateException("No Telegram client for botId " + botId + " (removed from tgscan.channels.bot_instance_id)")));
                    }
                    return Mono.empty();
                })
                .then();
    }

    private Mono<List<String>> applyDailyQuota(long chatId, Long chatConfigId, List<String> botIds, int retriesLeft) {
        if (botIds == null || botIds.isEmpty()) {
            return Mono.just(List.of());
        }
        if (chatConfigId == null) {
            log.warn("[Chat {}] Missing chatConfigId while planning persona fan-out (allowing all botIds)", chatId);
            return Mono.just(botIds);
        }

        return ensureRateLimits(chatConfigId)
                .flatMap(limits -> {
                    Integer maxDaily = limits.getMaxMessagesPerDay();
                    Integer currentDaily = limits.getCurrentDailyMessages();

                    if (maxDaily == null || maxDaily <= 0) {
                        return Mono.just(botIds);
                    }

                    int remaining = Math.max(0, maxDaily - (currentDaily != null ? currentDaily : 0));
                    int desired = Math.min(remaining, botIds.size());
                    if (desired <= 0) {
                        log.debug("[Chat {}] Daily quota exhausted (chatConfigId={}, maxDaily={}, currentDaily={})",
                                chatId, chatConfigId, maxDaily, currentDaily);
                        return Mono.just(List.of());
                    }

                    return rateLimitsRepository.reserveDailySlotsIfAllowed(chatConfigId, desired)
                            .map(updated -> updated != null ? updated : 0)
                            .flatMap(updated -> {
                                if (updated > 0) {
                                    List<String> selected = botIds.subList(0, desired);
                                    log.debug("[Chat {}] Persona fan-out planned: selected {} of {} botIds (reserved {} slots, chatConfigId={})",
                                            chatId, selected.size(), botIds.size(), desired, chatConfigId);
                                    return Mono.just(selected);
                                }
                                if (retriesLeft <= 0) {
                                    log.debug("[Chat {}] Failed to reserve daily quota slots after retries (chatConfigId={})", chatId, chatConfigId);
                                    return Mono.just(List.of());
                                }
                                return applyDailyQuota(chatId, chatConfigId, botIds, retriesLeft - 1);
                            });
                });
    }

    private Mono<RateLimits> ensureRateLimits(Long chatConfigId) {
        if (chatConfigId == null) {
            return Mono.just(new RateLimits(null));
        }
        return rateLimitsRepository.findByChatConfigId(chatConfigId)
                .switchIfEmpty(rateLimitsRepository.save(new RateLimits(chatConfigId))
                        .onErrorResume(e -> rateLimitsRepository.findByChatConfigId(chatConfigId))
                        .defaultIfEmpty(new RateLimits(chatConfigId)));
    }

    private boolean isCorruptedBotId(String botId) {
        if (botId == null) {
            return true;
        }
        String trimmed = botId.trim();
        if (trimmed.isBlank()) {
            return true;
        }
        return trimmed.contains("{") || trimmed.contains("}") || trimmed.contains(",");
    }
}
