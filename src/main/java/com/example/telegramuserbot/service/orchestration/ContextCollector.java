package com.example.telegramuserbot.service.orchestration;

import com.example.telegramuserbot.domain.ContextSettings;
import com.example.telegramuserbot.domain.MessageEntity;
import com.example.telegramuserbot.repository.ContextSettingsRepository;
import com.example.telegramuserbot.repository.MessageRepository;
import com.example.telegramuserbot.service.context.ContextBuilder;
import com.example.telegramuserbot.service.context.ContextWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Сборщик контекста диалога: тянет последние сообщения и создаёт summary,
 * чтобы LLM понимала цепочку. Инкапсулирует старую MessageContextService.
 */
@Component
public class ContextCollector {

    private static final Logger log = LoggerFactory.getLogger(ContextCollector.class);
    private static final int DEFAULT_MAX_CONTEXT_LENGTH = 4000;
    private static final Pattern HASHTAG_PATTERN = Pattern.compile("#[\\p{L}\\p{N}_]+");
    private static final int TAG_CONTEXT_LIMIT = 3;

    private final ContextSettingsRepository contextSettingsRepository;
    private final ContextBuilder contextBuilder;
    private final MessageRepository messageRepository;

    public ContextCollector(ContextSettingsRepository contextSettingsRepository,
                            ContextBuilder contextBuilder,
                            MessageRepository messageRepository) {
        this.contextSettingsRepository = contextSettingsRepository;
        this.contextBuilder = contextBuilder;
        this.messageRepository = messageRepository;
    }

    /**
     * Возвращает контекст и summary для указанного сообщения.
     * Дополнительно ищет предыдущие сообщения с теми же хэштегами и добавляет их в контекст.
     */
    public Mono<ConversationContext> collect(long chatId, long triggeringMessageId) {
        return collectForBot(chatId, triggeringMessageId, null);
    }

    /**
     * Persona-aware variant. For PRIVATE chats ({@code chatId > 0}) the conversation
     * history is filtered to rows where {@code received_by_bot_id = botId}, preventing
     * persona A from seeing persona B's turns with the same human.
     * For GROUP chats ({@code chatId < 0}) or when {@code botId} is {@code null} the
     * behaviour is identical to {@link #collect(long, long)}.
     *
     * @param botId the responding persona's client instance ID (e.g. from
     *              {@code BotContextResolver.ResolvedConfig#botInstanceId()})
     */
    public Mono<ConversationContext> collectForBot(long chatId, long triggeringMessageId, String botId) {
        return getSettings(chatId)
                .flatMap(settings -> contextBuilder.buildForBot(chatId, triggeringMessageId, settings, botId)
                        .flatMap(window -> enrichWithTagContext(chatId, triggeringMessageId, window, settings))
                        .defaultIfEmpty(ConversationContext.empty()))
                .defaultIfEmpty(ConversationContext.empty());
    }

    private Mono<ConversationContext> enrichWithTagContext(long chatId,
                                                           long triggeringMessageId,
                                                           ContextWindow window,
                                                           ContextSettings settings) {
        if (window == null || window.triggeringMessage() == null) {
            log.warn("[Chat {}] Triggering message {} not found", chatId, triggeringMessageId);
            return Mono.just(ConversationContext.empty());
        }
        String content = window.triggeringMessage().getContent();
        List<String> hashtags = extractHashtags(content);
        if (hashtags.isEmpty()) {
            return Mono.just(buildContextFromWindow(chatId, triggeringMessageId, settings, window));
        }
        String primaryTag = hashtags.get(0);
        return messageRepository.findRecentByHashtagBefore(chatId, primaryTag, triggeringMessageId, TAG_CONTEXT_LIMIT)
                .collectList()
                .map(tagMessages -> buildContextWithTagHistory(chatId, triggeringMessageId, settings, window, primaryTag, tagMessages))
                .onErrorResume(e -> {
                    log.warn("[Chat {}] Tag context lookup failed for '{}': {}", chatId, primaryTag, e.getMessage());
                    return Mono.just(buildContextFromWindow(chatId, triggeringMessageId, settings, window));
                });
    }

    private List<String> extractHashtags(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> tags = new ArrayList<>();
        Matcher m = HASHTAG_PATTERN.matcher(text);
        while (m.find() && tags.size() < 3) {
            tags.add(m.group(0));
        }
        return tags;
    }

    private Mono<ContextSettings> getSettings(long chatId) {
        return contextSettingsRepository.findByChatConfigChannelChatId(chatId)
                .defaultIfEmpty(defaultSettings());
    }

    private ContextSettings defaultSettings() {
        ContextSettings defaults = new ContextSettings();
        defaults.setMaxContextLength(DEFAULT_MAX_CONTEXT_LENGTH);
        defaults.setIncludeUserInfo(true);
        defaults.setIncludeTimeStamps(false);
        defaults.setIncludeMediaDescriptions(true);
        defaults.setContextSummaryEnabled(false);
        return defaults;
    }

    private ConversationContext buildContextFromWindow(long chatId,
                                                       long triggeringMessageId,
                                                       ContextSettings settings,
                                                       ContextWindow window) {
        if (window == null || window.triggeringMessage() == null) {
            log.warn("[Chat {}] Triggering message {} not found", chatId, triggeringMessageId);
            return ConversationContext.empty();
        }
        List<MessageEntity> contextMessages = window.contextMessages() != null ? window.contextMessages() : List.of();
        log.debug("[Chat {}] Collected {} context messages for triggering {}",
                chatId, contextMessages.size(), triggeringMessageId);
        return buildContext(settings, window.triggeringMessage(), contextMessages, List.of(), null);
    }

    private ConversationContext buildContextWithTagHistory(long chatId,
                                                           long triggeringMessageId,
                                                           ContextSettings settings,
                                                           ContextWindow window,
                                                           String hashtag,
                                                           List<MessageEntity> tagMessages) {
        List<MessageEntity> contextMessages = window.contextMessages() != null ? window.contextMessages() : List.of();
        if (!tagMessages.isEmpty()) {
            log.debug("[Chat {}] Found {} tag-related messages for '{}' before message {}",
                    chatId, tagMessages.size(), hashtag, triggeringMessageId);
        }
        return buildContext(settings, window.triggeringMessage(), contextMessages, tagMessages, hashtag);
    }

    private ConversationContext buildContext(ContextSettings settings,
                                             MessageEntity triggering,
                                             List<MessageEntity> contextMessages,
                                             List<MessageEntity> tagMessages,
                                             String hashtag) {
        String summary = buildSummary(contextMessages, triggering, settings, tagMessages, hashtag);
        int totalMessages = contextMessages.size() + 1;
        int totalChars = summary.length();

        return new ConversationContext(contextMessages, triggering, summary, totalMessages, totalChars);
    }

    private String buildSummary(List<MessageEntity> contextMessages,
                                MessageEntity triggering,
                                ContextSettings settings,
                                List<MessageEntity> tagMessages,
                                String hashtag) {
        boolean includeMedia = settings != null && settings.isIncludeMediaDescriptions();
        String triggerText = extractTextForContext(triggering, includeMedia);
        StringBuilder combined = new StringBuilder();
        if (!tagMessages.isEmpty()) {
            combined.append("📌 Предыдущие публикации по теме ").append(hashtag).append(":\n");
            tagMessages.forEach(msg -> {
                String text = extractTextForContext(msg, false);
                if (text != null && !text.isBlank()) {
                    String truncated = text.length() > 300 ? text.substring(0, 300) + "..." : text;
                    combined.append("— ").append(truncated).append("\n");
                }
            });
            combined.append("\n");
        }
        combined.append(triggerText);
        if (!contextMessages.isEmpty()) {
            combined.append("\n\n");
            combined.append(contextMessages.stream()
                    .map(msg -> extractTextForContext(msg, includeMedia))
                    .filter(c -> c != null && !c.isBlank())
                    .collect(Collectors.joining("\n")));
        }
        String result = combined.toString();
        Integer maxLength = settings != null ? settings.getMaxContextLength() : null;
        if (maxLength != null && maxLength > 0 && result.length() > maxLength) {
            return result.substring(0, maxLength);
        }
        return result;
    }

    private String extractTextForContext(MessageEntity message, boolean includeMedia) {
        if (message == null) {
            return "";
        }
        String content = message.getContent();
        if (content != null && !content.isBlank()) {
            return content;
        }
        if (!includeMedia) {
            return "";
        }
        String caption = message.getCaption();
        if (caption != null && !caption.isBlank()) {
            return caption;
        }
        if (message.getMediaType() != null) {
            return "(media: " + message.getMediaType().name() + ")";
        }
        return "";
    }

    /**
     * Простая DTO для контекста диалога, чтобы оркестратор не тянул доменные классы напрямую.
     */
    public record ConversationContext(List<MessageEntity> contextMessages,
                                      MessageEntity triggeringMessage,
                                      String summary,
                                      int totalMessages,
                                      int totalCharacters) {
        public static ConversationContext empty() {
            return new ConversationContext(Collections.emptyList(), null, "", 0, 0);
        }

        public boolean isEmpty() {
            return triggeringMessage == null || totalMessages == 0;
        }
    }
}
