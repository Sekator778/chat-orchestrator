package com.example.telegramuserbot.service.persistence;

import com.example.telegramuserbot.domain.MessageEntity;
import com.example.telegramuserbot.repository.MessageRepository;
import com.example.telegramuserbot.service.TelegramClientManager;
import com.example.telegramuserbot.telegram.TelegramClientFacade;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Backfills reply chains by fetching parent messages from Telegram.
 * Addresses the issue of broken reply references when parent messages
 * were sent before monitoring started or were deleted.
 *
 * Note: This class is not final to allow Spring CGLIB proxying for @Lazy injection.
 *
 * Example usage:
 * <pre>
 * backfillService.backfill(chatId, replyToMessageId, maxDepth)
 *     .subscribe(
 *         count -> log.info("Backfilled {} messages", count),
 *         error -> log.error("Backfill failed", error)
 *     );
 * </pre>
 */
@Service
public class ReplyChainBackfillService {

    private static final Logger log = LoggerFactory.getLogger(ReplyChainBackfillService.class);
    private static final int DEFAULT_MAX_DEPTH = 10;
    private static final int MAX_ABSOLUTE_DEPTH = 50;

    private final TelegramClientManager telegramClientManager;
    private final MessageRepository repository;
    private final MessagePersistenceService persistence;

    public ReplyChainBackfillService(
            TelegramClientManager telegramClientManager,
            MessageRepository repository,
            MessagePersistenceService persistence
    ) {
        this.telegramClientManager = telegramClientManager;
        this.repository = repository;
        this.persistence = persistence;
    }

    /**
     * Backfills a reply chain starting from the specified message.
     * Fetches parent messages recursively up to the specified depth.
     *
     * @param chatId Telegram chat ID
     * @param messageId Message ID to start backfilling from
     * @return Mono containing the count of backfilled messages
     */
    public Mono<Integer> backfill(Long chatId, Long messageId) {
        return backfill(chatId, messageId, DEFAULT_MAX_DEPTH);
    }

    /**
     * Backfills a reply chain with a custom maximum depth.
     *
     * @param chatId Telegram chat ID
     * @param messageId Message ID to start backfilling from
     * @param maxDepth Maximum number of parent messages to fetch
     * @return Mono containing the count of backfilled messages
     */
    public Mono<Integer> backfill(Long chatId, Long messageId, Integer maxDepth) {
        Integer depth = validate(chatId, messageId, maxDepth);
        log.info("Starting reply chain backfill for chat {} message {} (maxDepth={})",
                chatId, messageId, depth);
        return execute(chatId, messageId, depth, 0);
    }

    /**
     * Backfills parent message if it doesn't exist in the database.
     * This is the "lazy" version - only backfills one level.
     *
     * @param chatId Telegram chat ID
     * @param messageId Message ID to backfill
     * @return Mono completing when backfill is done (or skipped)
     */
    public Mono<Void> backfillIfMissing(Long chatId, Long messageId) {
        if (chatId == null || messageId == null) {
            return Mono.empty();
        }
        return repository.findByChatIdAndMessageId(chatId, messageId)
                .hasElement()
                .flatMap(exists -> {
                    if (exists) {
                        log.trace("Message {}/{} already exists, skipping backfill", chatId, messageId);
                        return Mono.empty();
                    }
                    log.debug("Parent message {}/{} missing, fetching from Telegram", chatId, messageId);
                    return fetch(chatId, messageId)
                            .flatMap(msg -> persistence.persistMessage(chatId, msg))
                            .then();
                })
                .onErrorResume(error -> {
                    if (!(error instanceof MessageNotAccessibleException)) {
                        log.warn("Failed to backfill message {}/{}: {}", chatId, messageId, error.getMessage());
                    }
                    return Mono.empty();
                });
    }

    private Mono<Integer> execute(Long chatId, Long messageId, Integer maxDepth, Integer currentDepth) {
        if (currentDepth >= maxDepth) {
            log.debug("Reached maximum depth {} for backfill in chat {}", maxDepth, chatId);
            return Mono.just(0);
        }
        return repository.findByChatIdAndMessageId(chatId, messageId)
                .flatMap(entity -> processExisting(entity, chatId, maxDepth, currentDepth))
                .switchIfEmpty(Mono.defer(() -> processNew(chatId, messageId, maxDepth, currentDepth)));
    }

    private Mono<Integer> processExisting(MessageEntity entity, Long chatId, Integer maxDepth, Integer currentDepth) {
        if (entity.getReplyToMessageId() == null) {
            log.trace("Message {}/{} has no parent reply, stopping backfill", chatId, entity.getMessageId());
            return Mono.just(0);
        }
        Long parentChatId = entity.getReplyToChatId() != null ? entity.getReplyToChatId() : chatId;
        log.debug("Message {}/{} exists, checking parent {}/{}", chatId, entity.getMessageId(), parentChatId, entity.getReplyToMessageId());
        return execute(parentChatId, entity.getReplyToMessageId(), maxDepth, currentDepth + 1);
    }

    private Mono<Integer> processNew(Long chatId, Long messageId, Integer maxDepth, Integer currentDepth) {
        log.info("Fetching missing message {}/{} from Telegram (depth {})", chatId, messageId, currentDepth);
        return fetch(chatId, messageId)
                .flatMap(msg -> persistence.persistMessage(chatId, msg)
                        .flatMap(persisted -> {
                            if (persisted.getReplyToMessageId() == null) {
                                return Mono.just(1);
                            }
                            Long parentChatId = persisted.getReplyToChatId() != null ? persisted.getReplyToChatId() : chatId;
                            return execute(parentChatId, persisted.getReplyToMessageId(), maxDepth, currentDepth + 1)
                                    .map(parentCount -> parentCount + 1);
                        })
                )
                .onErrorResume(error -> {
                    if (!(error instanceof MessageNotAccessibleException)) {
                        log.warn("Failed to fetch message {}/{}: {}", chatId, messageId, error.getMessage());
                    }
                    return Mono.just(0);
                });
    }

    private Mono<TdApi.Message> fetch(Long chatId, Long messageId) {
        return Mono.create(sink -> {
            TelegramClientFacade client = telegramClientManager.getAnyClient();
            if (client == null) {
                sink.error(new RuntimeException("No Telegram client available"));
                return;
            }
            TdApi.GetMessage request = new TdApi.GetMessage(chatId, messageId);
            client.send(request, result -> {
                if (result.isError()) {
                    TdApi.Error error = result.getError();
                    if (error.code == 400 || error.code == 404) {
                        log.debug("Message {}/{} not found or inaccessible (code={})", chatId, messageId, error.code);
                        sink.error(new MessageNotAccessibleException(error.message));
                    } else {
                        log.warn("Error fetching message {}/{}: {} - {}", chatId, messageId, error.code, error.message);
                        sink.error(new RuntimeException(error.message));
                    }
                } else {
                    sink.success(result.get());
                }
            });
        });
    }

    private static final class MessageNotAccessibleException extends RuntimeException {
        MessageNotAccessibleException(String message) {
            super(message);
        }
    }

    private Integer validate(Long chatId, Long messageId, Integer maxDepth) {
        if (chatId == null) {
            throw new IllegalArgumentException("chatId cannot be null");
        }
        if (messageId == null) {
            throw new IllegalArgumentException("messageId cannot be null");
        }
        if (maxDepth == null || maxDepth < 1) {
            return DEFAULT_MAX_DEPTH;
        }
        if (maxDepth > MAX_ABSOLUTE_DEPTH) {
            log.warn("Requested maxDepth {} exceeds limit {}, clamping", maxDepth, MAX_ABSOLUTE_DEPTH);
            return MAX_ABSOLUTE_DEPTH;
        }
        return maxDepth;
    }
}
