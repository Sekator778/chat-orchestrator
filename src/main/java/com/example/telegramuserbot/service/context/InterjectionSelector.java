package com.example.telegramuserbot.service.context;

import com.example.telegramuserbot.domain.MessageEntity;
import com.example.telegramuserbot.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class InterjectionSelector {

    private static final Logger log = LoggerFactory.getLogger(InterjectionSelector.class);
    private static final int INTERJECTION_NEAR_MINUTES = 5;
    private static final int MIN_CANDIDATES = 50;
    private static final int CANDIDATE_MULTIPLIER = 4;
    private static final int MAX_KEYWORDS = 12;

    private final MessageRepository messageRepository;

    public InterjectionSelector(MessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    public Mono<List<MessageEntity>> select(long chatId,
                                            MessageEntity root,
                                            MessageEntity triggering,
                                            Set<Long> excludedIds,
                                            Set<Long> participantIds,
                                            ContextLimits limits) {
        if (limits == null || limits.maxMessages() <= 0) {
            return Mono.just(List.of());
        }

        Instant cutoff = limits.cutoff();
        Instant upperBound = limits.upperBound();
        int candidateLimit = Math.max(MIN_CANDIDATES, limits.maxMessages() * CANDIDATE_MULTIPLIER);

        Set<String> keywords = extractKeywords(root, triggering);
        Instant triggerTime = triggering != null ? triggering.getDate() : upperBound;

        return messageRepository.findByChatIdAndDateBetweenOrderByDateAsc(chatId, cutoff, upperBound, candidateLimit)
                .filter(msg -> msg != null && msg.getMessageId() != null)
                .filter(msg -> msg.getReplyToMessageId() == null)
                .filter(msg -> excludedIds == null || !excludedIds.contains(msg.getMessageId()))
                .filter(msg -> isRelevant(msg, participantIds, triggerTime, keywords))
                .collectList()
                .map(list -> {
                    list.sort(Comparator.comparing(MessageEntity::getDate, Comparator.nullsLast(Comparator.naturalOrder())));
                    return list;
                })
                .onErrorResume(error -> {
                    log.warn("Failed to select interjections for chat {}: {}", chatId, error.getMessage());
                    return Mono.just(List.of());
                });
    }

    private boolean isRelevant(MessageEntity msg,
                               Set<Long> participantIds,
                               Instant triggerTime,
                               Set<String> keywords) {
        if (msg == null) {
            return false;
        }
        Long senderId = msg.getSenderId();
        if (senderId != null && participantIds != null && participantIds.contains(senderId)) {
            return true;
        }
        if (isNearTrigger(msg.getDate(), triggerTime)) {
            return true;
        }
        return containsKeyword(msg, keywords);
    }

    private boolean isNearTrigger(Instant messageTime, Instant triggerTime) {
        if (messageTime == null || triggerTime == null) {
            return false;
        }
        long minutes = Math.abs(Duration.between(messageTime, triggerTime).toMinutes());
        return minutes <= INTERJECTION_NEAR_MINUTES;
    }

    private boolean containsKeyword(MessageEntity msg, Set<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return false;
        }
        String text = buildText(msg);
        if (text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private Set<String> extractKeywords(MessageEntity root, MessageEntity triggering) {
        Set<String> keywords = new LinkedHashSet<>();
        addKeywords(keywords, buildText(root));
        addKeywords(keywords, buildText(triggering));
        return keywords;
    }

    private void addKeywords(Set<String> keywords, String text) {
        if (keywords == null || text == null || text.isBlank()) {
            return;
        }
        String normalized = text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ");
        String[] parts = normalized.split("\\s+");
        for (String part : parts) {
            if (part.length() < 4) {
                continue;
            }
            keywords.add(part);
            if (keywords.size() >= MAX_KEYWORDS) {
                return;
            }
        }
    }

    private String buildText(MessageEntity msg) {
        if (msg == null) {
            return "";
        }
        List<String> parts = new ArrayList<>(2);
        if (msg.getContent() != null && !msg.getContent().isBlank()) {
            parts.add(msg.getContent());
        }
        if (msg.getCaption() != null && !msg.getCaption().isBlank()) {
            parts.add(msg.getCaption());
        }
        return String.join(" ", parts).trim();
    }
}
