package com.example.telegramuserbot.service.context;

import com.example.telegramuserbot.domain.MessageEntity;
import com.example.telegramuserbot.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class ContextTreeAssembler {

    private static final Logger log = LoggerFactory.getLogger(ContextTreeAssembler.class);

    private final MessageRepository messageRepository;

    public ContextTreeAssembler(MessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    public Mono<List<MessageEntity>> buildAncestorChain(long chatId, MessageEntity triggering, int maxDepth) {
        if (triggering == null) {
            return Mono.just(List.of());
        }
        int depth = Math.max(1, maxDepth);
        return fetchAncestorPath(chatId, triggering, depth, 0);
    }

    public Mono<List<MessageEntity>> collectDescendants(long chatId,
                                                        List<MessageEntity> ancestorChain,
                                                        ContextLimits limits) {
        if (ancestorChain == null || ancestorChain.isEmpty() || limits == null) {
            return Mono.just(List.of());
        }
        Set<Long> seedIds = new LinkedHashSet<>();
        for (MessageEntity msg : ancestorChain) {
            if (msg != null && msg.getMessageId() != null) {
                seedIds.add(msg.getMessageId());
            }
        }
        if (seedIds.isEmpty()) {
            return Mono.just(List.of());
        }

        int remaining = Math.max(0, limits.maxMessages());
        return collectDescendantsRecursive(chatId, seedIds, new LinkedHashSet<>(seedIds), limits.cutoff(), limits.upperBound(), remaining);
    }

    private Mono<List<MessageEntity>> fetchAncestorPath(long chatId,
                                                        MessageEntity current,
                                                        int maxDepth,
                                                        int depth) {
        if (current == null) {
            return Mono.just(List.of());
        }
        if (depth >= maxDepth) {
            return Mono.just(List.of(current));
        }
        Long parentId = current.getReplyToMessageId();
        if (parentId == null) {
            return Mono.just(List.of(current));
        }
        Long parentChatId = current.getReplyToChatId() != null ? current.getReplyToChatId() : chatId;

        return messageRepository.findByChatIdAndMessageId(parentChatId, parentId)
                .flatMap(parent -> fetchAncestorPath(parentChatId, parent, maxDepth, depth + 1)
                        .map(list -> {
                            List<MessageEntity> combined = new ArrayList<>(list);
                            combined.add(current);
                            return combined;
                        }))
                .switchIfEmpty(Mono.just(List.of(current)));
    }

    private Mono<List<MessageEntity>> collectDescendantsRecursive(long chatId,
                                                                  Set<Long> frontier,
                                                                  Set<Long> seenIds,
                                                                  Instant cutoff,
                                                                  Instant upperBound,
                                                                  int remaining) {
        if (frontier == null || frontier.isEmpty() || remaining <= 0) {
            return Mono.just(List.of());
        }

        return messageRepository.findRepliesToMessagesInRange(chatId, frontier, cutoff, upperBound)
                .filter(msg -> msg != null && msg.getMessageId() != null && !seenIds.contains(msg.getMessageId()))
                .collectList()
                .flatMap(found -> {
                    if (found.isEmpty()) {
                        return Mono.just(List.<MessageEntity>of());
                    }
                    List<MessageEntity> limited = found.size() > remaining ? found.subList(0, remaining) : found;
                    Set<Long> newFrontier = new LinkedHashSet<>();
                    for (MessageEntity msg : limited) {
                        if (msg != null && msg.getMessageId() != null) {
                            newFrontier.add(msg.getMessageId());
                            seenIds.add(msg.getMessageId());
                        }
                    }

                    int newRemaining = remaining - limited.size();
                    return collectDescendantsRecursive(chatId, newFrontier, seenIds, cutoff, upperBound, newRemaining)
                            .map(rest -> {
                                List<MessageEntity> combined = new ArrayList<>(limited);
                                combined.addAll(rest);
                                return combined;
                            });
                })
                .onErrorResume(error -> {
                    log.warn("Failed to collect descendants for chat {}: {}", chatId, error.getMessage());
                    return Mono.just(List.<MessageEntity>of());
                });
    }
}
