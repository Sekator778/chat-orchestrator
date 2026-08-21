package com.example.telegramuserbot.service.startup;

import com.example.telegramuserbot.domain.ChatConfig;
import com.example.telegramuserbot.domain.RateLimits;
import com.example.telegramuserbot.domain.TriggerCondition;
import com.example.telegramuserbot.domain.TriggerType;
import com.example.telegramuserbot.repository.ChatConfigRepository;
import com.example.telegramuserbot.repository.RateLimitsRepository;
import com.example.telegramuserbot.repository.TriggerConditionRepository;
import com.example.telegramuserbot.service.config.AppSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Idempotent default-config provisioner for persona chats discovered at startup.
 * <p>
 * Called once per discovered chat immediately after a {@code bot.persona_chat_bindings}
 * row is recorded. If a {@code bot.chat_configs} row already exists for that chat the
 * method is a no-op (additive / skip-if-exists). Otherwise it seeds:
 * <ol>
 *   <li>A {@code bot.chat_configs} row with minimal defaults.</li>
 *   <li>A {@code bot.rate_limits} row capping daily replies to keep activity believable.</li>
 *   <li>Two {@code bot.trigger_conditions} rows — {@code QUESTION_DETECTED} and
 *       {@code CONTINUOUS} — to give the persona a reactive reply footprint.</li>
 * </ol>
 * All default values are read from {@link AppSettingsService} (keyed under
 * {@code chat.default.*}) so the owner can tune them via {@code bot.app_settings}
 * rows without a redeploy.  The hard-coded fallbacks below are the production
 * defaults described in the feature spec.
 */
@Service
public class PersonaChatDefaultConfigService {

    private static final Logger log = LoggerFactory.getLogger(PersonaChatDefaultConfigService.class);

    // AppSettings keys
    private static final String KEY_ENABLED            = "chat.default.enabled";
    private static final String KEY_LANGUAGE           = "chat.default.language";
    private static final String KEY_CONTEXT_WINDOW     = "chat.default.context_window";
    private static final String KEY_MAX_REPLIES_PER_DAY = "chat.default.max_replies_per_day";
    private static final String KEY_CONTINUOUS_PROB    = "chat.default.continuous_probability";

    private final ChatConfigRepository chatConfigRepository;
    private final RateLimitsRepository rateLimitsRepository;
    private final TriggerConditionRepository triggerConditionRepository;
    private final AppSettingsService appSettings;

    public PersonaChatDefaultConfigService(ChatConfigRepository chatConfigRepository,
                                           RateLimitsRepository rateLimitsRepository,
                                           TriggerConditionRepository triggerConditionRepository,
                                           AppSettingsService appSettings) {
        this.chatConfigRepository = chatConfigRepository;
        this.rateLimitsRepository = rateLimitsRepository;
        this.triggerConditionRepository = triggerConditionRepository;
        this.appSettings = appSettings;
    }

    /**
     * Ensures a default config exists for {@code chatId}.  Skips entirely when a
     * {@code chat_configs} row is already present, so manually configured chats are
     * never overwritten or duplicated.
     *
     * @param chatId TDLib chat ID (may be negative for groups)
     * @return Mono completing when provisioning is done (or skipped)
     */
    public Mono<Void> ensureDefaultConfig(long chatId) {
        return chatConfigRepository.findByChannelChatId(chatId)
                .flatMap(existing -> {
                    log.debug("Default config skipped for chatId={}: chat_configs row already exists (id={})",
                            chatId, existing.getId());
                    return Mono.<Void>empty();
                })
                .switchIfEmpty(
                        Mono.defer(() -> createDefaults(chatId))
                );
    }

    private Mono<Void> createDefaults(long chatId) {
        boolean enabled       = appSettings.getBoolean(KEY_ENABLED, true);
        String language       = appSettings.getString(KEY_LANGUAGE, "ru");
        int contextWindow     = appSettings.getInt(KEY_CONTEXT_WINDOW, 10);
        int maxRepliesPerDay  = appSettings.getInt(KEY_MAX_REPLIES_PER_DAY, 2);
        int continuousProb    = appSettings.getInt(KEY_CONTINUOUS_PROB, 25);

        ChatConfig config = new ChatConfig();
        config.setChannelId(chatId);
        config.setEnabled(enabled);
        config.setLanguage(language);
        config.setContextWindowSize(contextWindow);

        return chatConfigRepository.save(config)
                .flatMap(saved -> {
                    log.info("Auto-created default chat_config id={} for chatId={}", saved.getId(), chatId);
                    return createRateLimits(saved.getId(), maxRepliesPerDay)
                            .then(createTriggers(saved.getId(), continuousProb));
                })
                .onErrorResume(e -> {
                    log.warn("Failed to provision default config for chatId={}: {}", chatId, e.getMessage());
                    return Mono.empty();
                });
    }

    private Mono<Void> createRateLimits(long chatConfigId, int maxRepliesPerDay) {
        RateLimits rl = new RateLimits(chatConfigId);
        rl.setMaxMessagesPerDay(maxRepliesPerDay);
        return rateLimitsRepository.save(rl)
                .doOnNext(saved -> log.debug("Auto-created rate_limits id={} for chatConfigId={} maxPerDay={}",
                        saved.getId(), chatConfigId, maxRepliesPerDay))
                .then();
    }

    private Mono<Void> createTriggers(long chatConfigId, int continuousProb) {
        TriggerCondition question = new TriggerCondition(chatConfigId,
                "auto-question-detected", TriggerType.QUESTION_DETECTED);
        question.setProbabilityPercent(100);
        question.setTimeDelaySeconds(4);
        question.setPriority(2);

        TriggerCondition continuous = new TriggerCondition(chatConfigId,
                "auto-continuous", TriggerType.CONTINUOUS);
        continuous.setProbabilityPercent(continuousProb);
        continuous.setTimeDelaySeconds(6);
        continuous.setPriority(1);

        return triggerConditionRepository.save(question)
                .doOnNext(t -> log.debug("Auto-created QUESTION_DETECTED trigger id={} for chatConfigId={}", t.getId(), chatConfigId))
                .then(triggerConditionRepository.save(continuous))
                .doOnNext(t -> log.debug("Auto-created CONTINUOUS trigger id={} prob={}% for chatConfigId={}", t.getId(), continuousProb, chatConfigId))
                .then();
    }
}
