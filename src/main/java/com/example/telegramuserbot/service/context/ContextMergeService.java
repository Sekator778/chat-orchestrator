package com.example.telegramuserbot.service.context;

import com.example.telegramuserbot.domain.MessageEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class ContextMergeService {

    private static final Logger log = LoggerFactory.getLogger(ContextMergeService.class);

    public List<MessageEntity> merge(List<MessageEntity> ancestorChain,
                                     List<MessageEntity> descendants,
                                     List<MessageEntity> interjections,
                                     MessageEntity triggeringMessage,
                                     ContextLimits limits) {
        List<MessageEntity> chain = safeList(ancestorChain);
        List<MessageEntity> chainWithoutTrigger = removeTrigger(chain, triggeringMessage);

        int maxMessages = limits != null ? limits.maxMessages() : 0;
        if (chainWithoutTrigger.size() > maxMessages) {
            return reduceChain(chainWithoutTrigger, maxMessages);
        }

        List<MessageEntity> merged = new ArrayList<>();
        merged.addAll(chainWithoutTrigger);
        merged.addAll(safeList(descendants));
        merged.addAll(safeList(interjections));

        List<MessageEntity> deduped = dedupeByMessageKey(merged);
        deduped.sort(messageComparator());

        return applyCap(deduped, chainWithoutTrigger, maxMessages);
    }

    private List<MessageEntity> applyCap(List<MessageEntity> merged,
                                         List<MessageEntity> chainWithoutTrigger,
                                         int maxMessages) {
        if (maxMessages <= 0) {
            return List.of();
        }
        if (merged.size() <= maxMessages) {
            return merged;
        }

        Set<String> chainKeys = messageKeys(chainWithoutTrigger);
        List<MessageEntity> kept = new ArrayList<>(chainWithoutTrigger);

        for (int i = merged.size() - 1; i >= 0 && kept.size() < maxMessages; i--) {
            MessageEntity msg = merged.get(i);
            String key = messageKey(msg);
            if (key == null) {
                continue;
            }
            if (!chainKeys.contains(key)) {
                kept.add(msg);
            }
        }

        kept.sort(messageComparator());
        return kept.size() <= maxMessages ? kept : kept.subList(kept.size() - maxMessages, kept.size());
    }

    private List<MessageEntity> reduceChain(List<MessageEntity> chainWithoutTrigger, int maxMessages) {
        if (maxMessages <= 0 || chainWithoutTrigger.isEmpty()) {
            return List.of();
        }
        if (maxMessages == 1) {
            return List.of(chainWithoutTrigger.get(0));
        }

        MessageEntity root = chainWithoutTrigger.get(0);
        int tailCount = Math.max(0, maxMessages - 1);
        int tailStart = Math.max(0, chainWithoutTrigger.size() - tailCount);
        List<MessageEntity> tail = chainWithoutTrigger.subList(tailStart, chainWithoutTrigger.size());

        Map<String, MessageEntity> unique = new LinkedHashMap<>();
        String rootKey = messageKey(root);
        if (rootKey != null) {
            unique.put(rootKey, root);
        }
        for (MessageEntity msg : tail) {
            String key = messageKey(msg);
            if (key != null) {
                unique.put(key, msg);
            }
        }

        List<MessageEntity> result = new ArrayList<>(unique.values());
        result.sort(messageComparator());
        return result;
    }

    private List<MessageEntity> dedupeByMessageKey(List<MessageEntity> messages) {
        Map<String, MessageEntity> unique = new LinkedHashMap<>();
        for (MessageEntity msg : messages) {
            String key = messageKey(msg);
            if (key == null) {
                continue;
            }
            unique.putIfAbsent(key, msg);
            logMissingSender(msg);
        }
        return new ArrayList<>(unique.values());
    }

    private void logMissingSender(MessageEntity msg) {
        if (msg == null || msg.isOutgoing()) {
            return;
        }
        if (msg.getSenderId() == null && msg.getContent() != null && !msg.getContent().isBlank()) {
            log.warn("Context message {} missing sender_id (chatId={})", msg.getMessageId(), msg.getChatId());
        }
    }

    private List<MessageEntity> removeTrigger(List<MessageEntity> chain, MessageEntity triggeringMessage) {
        if (chain.isEmpty() || triggeringMessage == null || triggeringMessage.getMessageId() == null) {
            return chain;
        }
        Long triggerId = triggeringMessage.getMessageId();
        List<MessageEntity> filtered = new ArrayList<>();
        for (MessageEntity msg : chain) {
            if (msg != null && msg.getMessageId() != null && msg.getMessageId().equals(triggerId)) {
                continue;
            }
            filtered.add(msg);
        }
        return filtered;
    }

    private Set<String> messageKeys(List<MessageEntity> messages) {
        Set<String> keys = new LinkedHashSet<>();
        for (MessageEntity msg : messages) {
            String key = messageKey(msg);
            if (key != null) {
                keys.add(key);
            }
        }
        return keys;
    }

    private List<MessageEntity> safeList(List<MessageEntity> list) {
        return list != null ? list : List.of();
    }

    private String messageKey(MessageEntity msg) {
        if (msg == null || msg.getMessageId() == null || msg.getChatId() == null) {
            return null;
        }
        return msg.getChatId() + ":" + msg.getMessageId();
    }

    private Comparator<MessageEntity> messageComparator() {
        return Comparator.comparing(MessageEntity::getDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(MessageEntity::getMessageId, Comparator.nullsLast(Comparator.naturalOrder()));
    }
}
