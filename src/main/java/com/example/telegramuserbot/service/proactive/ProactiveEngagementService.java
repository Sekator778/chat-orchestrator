package com.example.telegramuserbot.service.proactive;

import com.example.telegramuserbot.domain.ProactiveEngagement;
import com.example.telegramuserbot.repository.MessageRepository;
import com.example.telegramuserbot.repository.ProactiveEngagementRepository;
import com.example.telegramuserbot.service.llm.dto.ApiMessage;
import com.example.telegramuserbot.service.orchestration.BotContextResolver;
import com.example.telegramuserbot.service.orchestration.LlmCallService;
import com.example.telegramuserbot.service.orchestration.PromptBuilder;
import com.example.telegramuserbot.service.orchestration.dto.EnhancedPromptRequest;
import com.example.telegramuserbot.service.publishing.TelegramMessageSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages proactive engagement: one bot-initiated message per day per persona per chat.
 *
 * <p>Generation uses {@link LlmCallService} directly with the per-persona system prompt
 * from {@link BotContextResolver#resolveForBot}, bypassing the pending response queue
 * (which is for reactive replies, not organic proactive messages).
 * This produces contextual, persona-faithful responses while preserving daily send limits
 * enforced by the anchor/day logic.
 *
 * <p>Anchor check: if no new messages arrived in the chat since the last proactive send,
 * the slot is skipped to avoid spamming idle chats.
 */
@Service
public final class ProactiveEngagementService {

    private static final Logger log = LoggerFactory.getLogger(ProactiveEngagementService.class);

    private final ProactiveEngagementRepository engagementRepository;
    private final MessageRepository messageRepository;
    private final TelegramMessageSender messageSender;
    private final BotContextResolver botContextResolver;
    private final PromptBuilder promptBuilder;
    private final LlmCallService llmCallService;

    public ProactiveEngagementService(ProactiveEngagementRepository engagementRepository,
                                      MessageRepository messageRepository,
                                      TelegramMessageSender messageSender,
                                      BotContextResolver botContextResolver,
                                      PromptBuilder promptBuilder,
                                      LlmCallService llmCallService) {
        this.engagementRepository = engagementRepository;
        this.messageRepository = messageRepository;
        this.messageSender = messageSender;
        this.botContextResolver = botContextResolver;
        this.promptBuilder = promptBuilder;
        this.llmCallService = llmCallService;
    }

    /**
     * Creates a proactive engagement schedule for a chat+persona pair if one does not exist yet.
     * Called by {@code ChannelIngestionServiceImpl} after joining a new chat.
     */
    public Mono<ProactiveEngagement> ensureEngagement(long chatId, String botInstanceId, String language) {
        return engagementRepository.findByChatIdAndBotInstanceId(chatId, botInstanceId)
                .switchIfEmpty(Mono.defer(() -> createEngagement(chatId, botInstanceId, language)))
                .timeout(Duration.ofSeconds(5));
    }

    /**
     * Entry point called once per UTC hour by {@code ProactiveEngagementScheduler}.
     * Processes all engagements due in the current hour that have not yet been sent today.
     */
    public Mono<Void> processDueEngagements() {
        short currentHour = (short) java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).getHour();
        log.info("Proactive engagement check for UTC hour {}", currentHour);

        return engagementRepository.findDueEngagements(currentHour)
                .timeout(Duration.ofSeconds(5))
                .flatMap(this::processOne, 1)
                .then();
    }

    private Mono<Void> processOne(ProactiveEngagement engagement) {
        long chatId = engagement.getChatId();
        String botId = engagement.getBotInstanceId();

        return messageRepository.findMaxMessageIdByChatId(chatId)
                .timeout(Duration.ofSeconds(5))
                .switchIfEmpty(Mono.fromRunnable(() ->
                        log.info("Proactive skip chatId={} botId={}: no messages in DB", chatId, botId)))
                .flatMap(maxId -> {
                    Long anchor = engagement.getLastAnchorMessageId();
                    if (anchor != null && maxId != null && maxId <= anchor) {
                        log.info("Proactive skip chatId={} botId={}: no new messages (maxId={} anchor={})",
                                chatId, botId, maxId, anchor);
                        return Mono.empty();
                    }
                    return generateAndSend(engagement, maxId).thenReturn(maxId);
                })
                .then();
    }

    /**
     * Generates content via LLM with the per-persona system prompt, then sends directly.
     * The pending response queue is intentionally bypassed: proactive messages originate
     * from the bot's own initiative, not as a reaction to a triggering human message.
     */
    private Mono<Void> generateAndSend(ProactiveEngagement engagement, Long anchorId) {
        long chatId = engagement.getChatId();
        String botId = engagement.getBotInstanceId();

        return messageRepository.findByChatIdOrderByDateDesc(chatId, PageRequest.of(0, 10))
                .filter(m -> !m.isOutgoing())
                .collectList()
                .timeout(Duration.ofSeconds(5))
                .filter(msgs -> !msgs.isEmpty())
                .switchIfEmpty(Mono.fromRunnable(() ->
                        log.info("Proactive skip chatId={} botId={}: no non-outgoing messages", chatId, botId)))
                .flatMap(recentMessages ->
                        botContextResolver.resolveForBot(chatId, botId)
                                .timeout(Duration.ofSeconds(5))
                                .flatMap(cfg -> {
                                    EnhancedPromptRequest promptRequest = EnhancedPromptRequest.builder()
                                            .template(cfg.template())
                                            .chatConfig(cfg.config())
                                            .rateLimits(cfg.rateLimits())
                                            .llmParameters(cfg.llmParameters())
                                            .fallbackPrompt("Respond naturally as yourself.")
                                            .fallbackLanguage(cfg.config() != null ? cfg.config().getLanguage() : "ru")
                                            .build();

                                    List<ApiMessage> messages = new ArrayList<>();
                                    messages.add(new ApiMessage("system", promptBuilder.buildEnhancedPrompt(promptRequest)));

                                    recentMessages.stream()
                                            .limit(5)
                                            .forEach(m -> {
                                                String role = m.isOutgoing() ? "assistant" : "user";
                                                String content = m.getContent() != null ? m.getContent() : "";
                                                if (!content.isBlank()) {
                                                    messages.add(new ApiMessage(role, content));
                                                }
                                            });

                                    long triggeringMsgId = recentMessages.get(0).getMessageId();
                                    return llmCallService.call(chatId, triggeringMsgId, "PROACTIVE", messages,
                                                    cfg.config(), cfg.llmParameters())
                                            .timeout(Duration.ofSeconds(60));
                                }))
                .flatMap(text -> {
                    if (text == null || text.isBlank()) {
                        log.warn("Proactive LLM returned empty chatId={} botId={}", chatId, botId);
                        return Mono.empty();
                    }
                    return messageSender.send(botId, chatId, text.trim())
                            .timeout(Duration.ofSeconds(30))
                            .flatMap(sent -> engagementRepository.markSent(
                                            engagement.getId(), Instant.now(), anchorId)
                                    .timeout(Duration.ofSeconds(5))
                                    .doOnSuccess(rows -> log.info("Proactive sent chatId={} botId={} markedSent={}",
                                            chatId, botId, rows))
                                    .then());
                })
                .onErrorResume(ex -> {
                    log.error("Proactive send failed chatId={} botId={}: {}", chatId, botId, ex.getMessage());
                    return Mono.empty();
                });
    }

    private Mono<ProactiveEngagement> createEngagement(long chatId, String botInstanceId, String language) {
        ProactiveEngagement engagement = new ProactiveEngagement();
        engagement.setChatId(chatId);
        engagement.setBotInstanceId(botInstanceId);
        engagement.setLanguage(language != null ? language : "ru");
        engagement.setSendHourUtc(randomSendHour(botInstanceId));
        engagement.setEnabled(true);
        engagement.setCreatedAt(Instant.now());
        engagement.setUpdatedAt(Instant.now());
        return engagementRepository.save(engagement)
                .timeout(Duration.ofSeconds(5));
    }

    /**
     * Random UTC hour in [15, 20] (18–23 Moscow time).
     * Two personas get different offsets to spread load.
     */
    private short randomSendHour(String botInstanceId) {
        int base = 15 + (int) (Math.random() * 5);
        if (botInstanceId != null && botInstanceId.hashCode() % 2 != 0) {
            base = Math.min(20, base + 1);
        }
        return (short) base;
    }
}
