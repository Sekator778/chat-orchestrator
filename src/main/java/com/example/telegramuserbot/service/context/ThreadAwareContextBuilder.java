package com.example.telegramuserbot.service.context;

import com.example.telegramuserbot.domain.ContextSettings;
import com.example.telegramuserbot.domain.MessageEntity;
import com.example.telegramuserbot.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class ThreadAwareContextBuilder implements ContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(ThreadAwareContextBuilder.class);
    private static final int MAX_CHAIN_DEPTH = 50;

    private final MessageRepository messageRepository;
    private final ContextTreeAssembler treeAssembler;
    private final InterjectionSelector interjectionSelector;
    private final ContextMergeService mergeService;

    public ThreadAwareContextBuilder(MessageRepository messageRepository,
                                     ContextTreeAssembler treeAssembler,
                                     InterjectionSelector interjectionSelector,
                                     ContextMergeService mergeService) {
        this.messageRepository = messageRepository;
        this.treeAssembler = treeAssembler;
        this.interjectionSelector = interjectionSelector;
        this.mergeService = mergeService;
    }

    @Override
    public Mono<ContextWindow> build(long chatId, long triggeringMessageId, ContextSettings settings) {
        return buildForBot(chatId, triggeringMessageId, settings, null);
    }

    /**
     * {@inheritDoc}
     *
     * <p>For PRIVATE chats ({@code chatId > 0}) the window query is scoped to
     * {@code received_by_bot_id = botId} so that persona A never sees persona B's
     * turns with the same human. Group chats ({@code chatId < 0}) are unaffected —
     * shared history is intentional there.
     */
    @Override
    public Mono<ContextWindow> buildForBot(long chatId, long triggeringMessageId, ContextSettings settings, String botId) {
        return messageRepository.findByChatIdAndMessageId(chatId, triggeringMessageId)
                .flatMap(triggering -> buildForTrigger(chatId, triggering, settings, botId))
                .switchIfEmpty(Mono.fromCallable(() -> {
                    log.warn("[Chat {}] Triggering message {} not found for context build", chatId, triggeringMessageId);
                    return ContextWindow.empty();
                }));
    }

    private Mono<ContextWindow> buildForTrigger(long chatId, MessageEntity triggering, ContextSettings settings, String botId) {
        ContextLimits limits = ContextLimits.fromSettings(settings, triggering != null ? triggering.getDate() : null);
        int maxDepth = Math.min(MAX_CHAIN_DEPTH, Math.max(1, limits.maxMessages()));

        return treeAssembler.buildAncestorChain(chatId, triggering, maxDepth)
                .flatMap(chain -> {
                    List<MessageEntity> ancestorChain = chain.isEmpty() ? List.of(triggering) : chain;
                    boolean hasReplyChain = triggering != null && triggering.getReplyToMessageId() != null;

                    Mono<List<MessageEntity>> fallbackWindow = hasReplyChain
                            ? Mono.just(List.of())
                            : loadWindowContext(chatId, triggering.getMessageId(), limits, settings, botId);

                    return fallbackWindow.flatMap(fallbackMessages -> {
                        List<MessageEntity> descendantsBase = hasReplyChain ? List.of() : fallbackMessages;

                        return treeAssembler.collectDescendants(chatId, ancestorChain, limits)
                                .map(descendants -> {
                                    List<MessageEntity> combined = new ArrayList<>(descendantsBase);
                                    combined.addAll(descendants);
                                    return combined;
                                })
                                .flatMap(descendants -> {
                    Set<Long> excludedIds = new LinkedHashSet<>(messageIdsForChat(chatId, ancestorChain));
                    excludedIds.addAll(messageIdsForChat(chatId, descendants));
                                    Set<Long> participantIds = new LinkedHashSet<>(participantIds(ancestorChain));
                                    participantIds.addAll(participantIds(descendants));

                                    return interjectionSelector.select(chatId,
                                                    ancestorChain.get(0),
                                                    triggering,
                                                    excludedIds,
                                                    participantIds,
                                                    limits)
                                            .map(interjections -> {
                                                List<MessageEntity> merged = mergeService.merge(
                                                        ancestorChain,
                                                        descendants,
                                                        interjections,
                                                        triggering,
                                                        limits
                                                );
                                                log.debug("[Chat {}] Context tree built: chain={}, descendants={}, interjections={}, final={}",
                                                        chatId,
                                                        ancestorChain.size(),
                                                        descendants.size(),
                                                        interjections.size(),
                                                        merged.size());
                                                return new ContextWindow(merged, triggering);
                                            });
                                });
                    });
                });
    }

    /**
     * Loads the flat sliding-window context. For private chats ({@code chatId > 0}) with
     * a known {@code botId} the query is filtered to {@code received_by_bot_id = botId}
     * so each persona sees only its own DM thread. For group chats or when {@code botId}
     * is {@code null} the unfiltered query is used (unchanged behaviour).
     */
    private Mono<List<MessageEntity>> loadWindowContext(long chatId,
                                                        Long triggeringMessageId,
                                                        ContextLimits limits,
                                                        ContextSettings settings,
                                                        String botId) {
        if (triggeringMessageId == null || limits == null || limits.maxMessages() <= 0) {
            return Mono.just(List.of());
        }
        Instant cutoff = limits.cutoff();
        Instant upper = limits.upperBound();
        int pageSize = limits.maxMessages();
        Pageable pageable = PageRequest.of(0, pageSize);

        boolean isPrivateChat = chatId > 0;
        Mono<List<MessageEntity>> fetched = (isPrivateChat && botId != null)
                ? messageRepository.findLastMessagesBeforeWithinTimeRangeByBot(chatId, triggeringMessageId, cutoff, botId, pageable)
                        .collectList()
                : messageRepository.findLastMessagesBeforeWithinTimeRange(chatId, triggeringMessageId, cutoff, pageable)
                        .collectList();

        return fetched.map(list -> {
            List<MessageEntity> filtered = filterContextMessages(list, settings);
            List<MessageEntity> chronological = new ArrayList<>(filtered);
            java.util.Collections.reverse(chronological);
            return chronological.stream()
                    .filter(msg -> msg.getDate() == null || !msg.getDate().isAfter(upper))
                    .collect(Collectors.toList());
        });
    }

    private List<MessageEntity> filterContextMessages(List<MessageEntity> messages, ContextSettings settings) {
        boolean includeMedia = settings != null && settings.isIncludeMediaDescriptions();
        return messages.stream()
                .filter(msg -> {
                    if (msg == null) {
                        return false;
                    }
                    String content = msg.getContent();
                    if (content != null && !content.isBlank()) {
                        return true;
                    }
                    if (!includeMedia) {
                        return false;
                    }
                    String caption = msg.getCaption();
                    return (caption != null && !caption.isBlank()) || msg.getMediaType() != null;
                })
                .collect(Collectors.toList());
    }

    private Set<Long> messageIdsForChat(long chatId, List<MessageEntity> messages) {
        Set<Long> ids = new LinkedHashSet<>();
        for (MessageEntity msg : messages) {
            if (msg != null && msg.getMessageId() != null && msg.getChatId() != null && msg.getChatId() == chatId) {
                ids.add(msg.getMessageId());
            }
        }
        return ids;
    }

    private Set<Long> participantIds(List<MessageEntity> messages) {
        Set<Long> ids = new LinkedHashSet<>();
        for (MessageEntity msg : messages) {
            if (msg != null && msg.getSenderId() != null) {
                ids.add(msg.getSenderId());
            }
        }
        return ids;
    }
}
