package com.example.telegramuserbot.service;

import com.example.telegramuserbot.domain.MessageEntity;
import com.example.telegramuserbot.repository.MessageRepository;
import com.example.telegramuserbot.service.TelegramClientManager;
import com.example.telegramuserbot.telegram.TelegramClientFacade;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Service for deleting bot messages from Telegram channels with specified time depth.
 */
@Service
public class MessageDeletionService {

    private static final Logger log = LoggerFactory.getLogger(MessageDeletionService.class);

    private final TelegramClientManager telegramClientManager;
    private final MessageRepository messageRepository;

    public MessageDeletionService(TelegramClientManager telegramClientManager, MessageRepository messageRepository) {
        this.telegramClientManager = telegramClientManager;
        this.messageRepository = messageRepository;
    }

    /**
     * Deletes bot messages from a specific channel within the specified time depth.
     *
     * @param chatId     The Telegram chat ID
     * @param hoursDepth Number of hours to look back for messages to delete
     * @param botUserId  The bot's user ID
     * @return A Mono emitting a summary of the deletion operation.
     */
    public Mono<DeletionSummary> deleteMyMessagesFromChannel(long chatId, int hoursDepth, long botUserId) {
        log.info("Starting message deletion for chat {} with depth {} hours", chatId, hoursDepth);

        Instant cutoffTime = Instant.now().minusSeconds(hoursDepth * 3600L);
        Instant currentTime = Instant.now();

        return messageRepository.findBotMessagesInTimeRange(chatId, botUserId, cutoffTime, currentTime)
                .collectList()
                .flatMap(messagesToDelete -> {
                    if (messagesToDelete.isEmpty()) {
                        log.info("No bot messages found to delete in chat {} for the last {} hours", chatId, hoursDepth);
                        return Mono.just(new DeletionSummary(0, 0, 0, 0, 0));
                    }

                    log.info("Found {} bot messages to delete in chat {}", messagesToDelete.size(), chatId);

                    return Flux.fromIterable(messagesToDelete)
                            .delayElements(Duration.ofMillis(200)) // Rate limiting
                            .flatMap(message -> processSingleMessageDeletion(chatId, message))
                            .collectList()
                            .map(results -> buildSummary(messagesToDelete.size(), results));
                })
                .doOnError(e -> log.error("Error during message deletion process for chat {}: {}", chatId, e.getMessage(), e))
                .onErrorReturn(new DeletionSummary(0, 0, 0, 0, 0));
    }

    private Mono<DeletionResult> processSingleMessageDeletion(long chatId, MessageEntity message) {
        Mono<Boolean> telegramDeletionMono = deleteMessageFromTelegram(chatId, message.getMessageId());
        Mono<Boolean> dbDeletionMono = deleteMessageFromDatabase(message)
                .thenReturn(true)
                .onErrorReturn(false);

        return Mono.zip(telegramDeletionMono, dbDeletionMono, DeletionResult::new)
                .doOnError(e -> log.error("Unexpected error processing message {}: {}", message.getMessageId(), e.getMessage()))
                .onErrorReturn(new DeletionResult(false, false));
    }

    private DeletionSummary buildSummary(int totalFound, List<DeletionResult> results) {
        int telegramSuccess = (int) results.stream().filter(DeletionResult::telegramSuccess).count();
        int dbSuccess = (int) results.stream().filter(DeletionResult::dbSuccess).count();
        return new DeletionSummary(totalFound, telegramSuccess, totalFound - telegramSuccess, dbSuccess, totalFound - dbSuccess);
    }

    private Mono<Boolean> deleteMessageFromTelegram(long chatId, long messageId) {
        return Mono.<Boolean>create(sink -> {
            TelegramClientFacade telegramClient = telegramClientManager.getAnyClient();
            if (telegramClient == null) {
                log.warn("No Telegram client available to delete message {} in chat {}", messageId, chatId);
                sink.success(false);
                return;
            }
            TdApi.DeleteMessages deleteRequest = new TdApi.DeleteMessages(chatId, new long[]{messageId}, true);
            telegramClient.send(deleteRequest, result -> {
                if (result.isError()) {
                    TdApi.Error error = result.getError();
                    log.debug("Failed to delete message {} in chat {}: {} - {}", messageId, chatId, error.code, error.message);
                    sink.success(false);
                } else {
                    log.debug("Successfully deleted message {} from Telegram API", messageId);
                    sink.success(true);
                }
            });
        }).timeout(Duration.ofSeconds(5), Mono.just(false));
    }

    private Mono<Void> deleteMessageFromDatabase(MessageEntity message) {
        return messageRepository.delete(message)
                .doOnSuccess(v -> log.debug("Successfully deleted message {} from database", message.getMessageId()))
                .doOnError(e -> log.error("Failed to delete message {} from database: {}", message.getMessageId(), e.getMessage()));
    }

    private record DeletionResult(boolean telegramSuccess, boolean dbSuccess) { }

    public record DeletionSummary(
            int totalFound,
            int telegramSuccessful,
            int telegramFailed,
            int databaseDeleted,
            int databaseFailed
    ) {
        public boolean isFullySuccessful() {
            return databaseFailed == 0 && totalFound > 0;
        }

        public boolean hasPartialFailures() {
            return (telegramFailed > 0 || databaseFailed > 0) && (telegramSuccessful > 0 || databaseDeleted > 0);
        }

        public boolean isFullyFailed() {
            return databaseDeleted == 0 && totalFound > 0;
        }

        public int getTotalProcessed() {
            return telegramSuccessful + telegramFailed;
        }
    }
}
