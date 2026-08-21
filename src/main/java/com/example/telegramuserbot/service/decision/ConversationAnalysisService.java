package com.example.telegramuserbot.service.decision;

import com.example.telegramuserbot.domain.MessageEntity;
import com.example.telegramuserbot.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Анализирует контекст беседы для принятия решения об ответе.
 * Имитирует человеческий процесс анализа ленты сообщений.
 */
@Service
public final class ConversationAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(ConversationAnalysisService.class);

    private final MessageRepository messageRepository;

    public ConversationAnalysisService(MessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    /**
     * Анализирует контекст беседы для принятия решения об ответе
     */
    public Mono<ConversationContext> analyze(long chatId, long triggeringMessageId) {
        log.info("🔍 АНАЛИЗ НАЧАТ: чат={}, сообщение={}", chatId, triggeringMessageId);

        return getMessagesForAnalysis(chatId, triggeringMessageId)
                .flatMap(allMessages -> {
                    if (allMessages.isEmpty()) {
                        log.warn("❌ АНАЛИЗ ПРЕРВАН: не найдено сообщений для анализа");
                        return Mono.just(ConversationContext.empty());
                    }

                    MessageEntity triggeringMessage = allMessages.stream()
                            .filter(msg -> msg.getMessageId().equals(triggeringMessageId))
                            .findFirst()
                            .orElse(null);

                    if (triggeringMessage == null) {
                        log.warn("❌ АНАЛИЗ ПРЕРВАН: сообщение-триггер не найдено среди полученных");
                        return Mono.just(ConversationContext.empty());
                    }

                    log.info("📝 ТРИГГЕР: senderId={}, content='{}', date={}",
                            triggeringMessage.getSenderId(),
                            truncateForLog(triggeringMessage.getContent()),
                            triggeringMessage.getDate());

                    List<MessageEntity> recentMessages = allMessages.stream()
                            .filter(msg -> !msg.getMessageId().equals(triggeringMessageId))
                            .toList();

                    log.info("📚 КОНТЕКСТ: найдено {} сообщений за последние 2 часа", recentMessages.size());

                    ConversationActivity activity = analyzeActivity(recentMessages, triggeringMessage.getDate());
                    log.info("📊 АКТИВНОСТЬ: скорость={} сообщ/мин, участников={}, последнее_сообщение_назад={}мин",
                            String.format("%.2f", activity.messagesPerMinute()),
                            activity.activeParticipants(),
                            activity.minutesSinceLastMessage());

                    ConversationTopic topic = analyzeTopic(recentMessages, triggeringMessage);
                    log.info("💭 ТЕМА: тип={}, сложность={}, направленность={}",
                            topic.topicType(), topic.complexity(), topic.directness());

                    return Mono.just(new ConversationContext(
                            triggeringMessage,
                            recentMessages,
                            activity,
                            topic
                    ));
                })
                .defaultIfEmpty(ConversationContext.empty());
    }

    /**
     * ОПТИМИЗАЦИЯ: Получает триггерное сообщение и контекст оптимально
     */
    private Mono<List<MessageEntity>> getMessagesForAnalysis(long chatId, long triggeringMessageId) {
        return messageRepository.findByChatIdAndMessageId(chatId, triggeringMessageId)
                .flatMap(triggeringMessage -> {
                    Instant twoHoursAgo = triggeringMessage.getDate().minus(Duration.ofHours(2));
                    Flux<MessageEntity> contextFlux = messageRepository.findByChatIdAndDateAfterOrderByDateAsc(chatId, twoHoursAgo);
                    return contextFlux
                            .collectList()
                            .map(messages -> ensureTriggerIncluded(messages, triggeringMessage));
                })
                .switchIfEmpty(Mono.just(List.of()));
    }

    private List<MessageEntity> ensureTriggerIncluded(List<MessageEntity> messages, MessageEntity triggeringMessage) {
        boolean containsTrigger = messages.stream()
                .anyMatch(msg -> msg.getMessageId().equals(triggeringMessage.getMessageId()));
        if (containsTrigger) {
            return messages;
        }
        List<MessageEntity> augmented = new ArrayList<>(messages.size() + 1);
        augmented.add(triggeringMessage);
        augmented.addAll(messages);
        augmented.sort(Comparator.comparing(MessageEntity::getDate));
        return List.copyOf(augmented);
    }

    private ConversationActivity analyzeActivity(List<MessageEntity> recentMessages, Instant triggerTime) {
        if (recentMessages.isEmpty()) {
            return ConversationActivity.inactive();
        }

        // Подсчитываем активность
        long timeSpanMinutes = Duration.between(
                recentMessages.get(0).getDate(),
                triggerTime
        ).toMinutes();

        double messagesPerMinute = timeSpanMinutes > 0 ?
                (double) recentMessages.size() / timeSpanMinutes : 0;

        // Подсчитываем уникальных участников
        long activeParticipants = recentMessages.stream()
                .map(MessageEntity::getSenderId)
                .filter(id -> id != null)
                .distinct()
                .count();

        // Находим последнее сообщение (исключая триггер)
        long minutesSinceLastMessage = recentMessages.stream()
                .filter(msg -> msg.getDate().isBefore(triggerTime))
                .map(msg -> Duration.between(msg.getDate(), triggerTime).toMinutes())
                .min(Long::compare)
                .orElse(120L);

        return new ConversationActivity(
                messagesPerMinute,
                activeParticipants,
                minutesSinceLastMessage
        );
    }

    // В классе ConversationAnalysisService

    private ConversationTopic analyzeTopic(List<MessageEntity> recentMessages, MessageEntity triggeringMessage) {
        String triggerContent = triggeringMessage.getContent();
        if (triggerContent == null || triggerContent.trim().isEmpty()) {
            return ConversationTopic.unknown();
        }

        // Определяем тип темы
        TopicType topicType = determineTopicType(triggerContent, recentMessages);

        // Определяем сложность
        TopicComplexity complexity = determineComplexity(triggerContent);

        // Определяем направленность
        TopicDirectness directness = determineDirectness(triggerContent, triggeringMessage.getSenderId());

        // ИСПРАВЛЕНО: Добавляем генерацию summary
        String summary = triggerContent.length() > 100 ? triggerContent.substring(0, 97) + "..." : triggerContent;

        // ИСПРАВЛЕНО: Передаем все 4 аргумента в конструктор
        return new ConversationTopic(topicType, complexity, summary, directness);
    }

    private TopicType determineTopicType(String content, List<MessageEntity> recentMessages) {
        String lower = content.toLowerCase();

        // Прямые вопросы
        if (lower.contains("?") || lower.contains("що") || lower.contains("как") ||
                lower.contains("где") || lower.contains("когда") || lower.contains("почему") ||
                lower.contains("зачем") || lower.contains("кто")) {
            return TopicType.QUESTION;
        }

        // Приветствие
        if (lower.contains("привет") || lower.contains("здравствуй") ||
                lower.contains("добро") || (lower.matches("\\d+") && content.length() < 5)) {
            return TopicType.GREETING;
        }

        // Информационные/образовательные статьи
        if (content.length() > 200 ||
                lower.contains("как влияет") || lower.contains("что нужно") ||
                lower.contains("правильно подобранные") || lower.contains("например") ||
                lower.contains("давайте разбираться") || lower.contains("исследования показ") ||
                lower.contains("специалисты рекомендуют") || lower.contains("психология называет") ||
                lower.contains("механизм переноса") || lower.contains("психоаналитик") ||
                lower.contains("феномен как") || lower.contains("описывала этот") ||
                lower.contains("поэтому человек может") || lower.contains("в итоге эмоции") ||
                lower.contains("американский психо") || lower.contains("анна фрейд") ||
                (lower.contains("мы вынуждены") && lower.contains("среде")) ||
                (lower.contains("подавляются") && lower.contains("никуда не исчезают"))) {
            return TopicType.INFORMATION;
        }

        // Обсуждение (если есть активный контекст)
        if (recentMessages.size() > 5) {
            return TopicType.DISCUSSION;
        }

        return TopicType.CASUAL;
    }

    private TopicComplexity determineComplexity(String content) {
        if (content.length() > 200) {
            return TopicComplexity.HIGH;
        }
        if (content.length() > 50) {
            return TopicComplexity.MEDIUM;
        }
        return TopicComplexity.LOW;
    }

    private TopicDirectness determineDirectness(String content, Long senderId) {
        String lower = content.toLowerCase();

        // Прямое обращение (есть @, имена, прямые вопросы к группе)
        if (lower.contains("@") || lower.contains("скажи") || lower.contains("расскажи") ||
                lower.contains("как думаете") || lower.contains("что скажете") ||
                (lower.contains("?") && (lower.contains("вы") || lower.contains("ты")))) {
            return TopicDirectness.DIRECT;
        }

        // Косвенное (приглашение к обсуждению без прямого вопроса)
        if (lower.contains("интересно") || lower.contains("может") ||
                lower.contains("думаю") || lower.contains("считаю") ||
                lower.contains("давайте разбираться") || lower.contains("стоит подумать")) {
            return TopicDirectness.INDIRECT;
        }

        // Общая информация (статьи, новости, факты)
        return TopicDirectness.GENERAL;
    }

    private String truncateForLog(String content) {
        if (content == null) {
            return "null";
        }
        return content.length() > 50 ? content.substring(0, 50) + "..." : content;
    }

    public enum TopicType {
        QUESTION, GREETING, DISCUSSION, INFORMATION, CASUAL
    }

    public enum TopicComplexity {
        LOW, MEDIUM, HIGH
    }

    public enum TopicDirectness {
        DIRECT, INDIRECT, GENERAL
    }

    public record ConversationActivity(
            double messagesPerMinute,
            long activeParticipants,
            long minutesSinceLastMessage
    ) {
        public static ConversationActivity inactive() {
            return new ConversationActivity(0.0, 0, 120);
        }
    }

    public record ConversationTopic(
            TopicType topicType,
            TopicComplexity complexity,
            String summary,
            TopicDirectness directness
    ) {
        public static ConversationTopic unknown() {
            return new ConversationTopic(TopicType.CASUAL, TopicComplexity.LOW, "Unknown topic", TopicDirectness.GENERAL);
        }
    }

    public record ConversationContext(
            MessageEntity triggeringMessage,
            List<MessageEntity> recentMessages,
            ConversationActivity activity,
            ConversationTopic topic
    ) {
        public static ConversationContext empty() {
            return new ConversationContext(null, List.of(), ConversationActivity.inactive(), ConversationTopic.unknown());
        }

        public boolean isValid() {
            return triggeringMessage != null;
        }
    }
}
