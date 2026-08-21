package com.example.telegramuserbot.service.messagesync;

import com.example.telegramuserbot.domain.Channel;
import com.example.telegramuserbot.domain.SyncJob;
import com.example.telegramuserbot.dto.SyncProgressDto;
import com.example.telegramuserbot.repository.ChannelRepository;
import com.example.telegramuserbot.repository.MessageRepository;
import com.example.telegramuserbot.service.TelegramClientManager;
import com.example.telegramuserbot.service.persistence.MessagePersistenceService;
import com.example.telegramuserbot.telegram.TelegramClientFacade;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class SyncExecutionServiceImpl implements SyncExecutionService {

    private static final Logger log = LoggerFactory.getLogger(SyncExecutionServiceImpl.class);
    private static final Duration BASE_DELAY = Duration.ofMillis(500); // Базовая задержка между запросами
    private static final int MAX_RETRIES = 5; // Максимум попыток при rate limit
    private static final int BATCH_SIZE = 50; // Уменьшенный размер батча для снижения нагрузки

    private final TelegramClientManager clientManager;
    private final MessagePersistenceService messagePersistenceService;
    private final ChannelRepository channelRepository;
    private final MessageRepository messageRepository;

    private final Set<Long> cancelledJobs = ConcurrentHashMap.newKeySet();

    public SyncExecutionServiceImpl(TelegramClientManager clientManager,
                                  MessagePersistenceService messagePersistenceService,
                                  ChannelRepository channelRepository,
                                  MessageRepository messageRepository) {
        this.clientManager = clientManager;
        this.messagePersistenceService = messagePersistenceService;
        this.channelRepository = channelRepository;
        this.messageRepository = messageRepository;
    }

    @Override
    public Flux<SyncProgressDto> executeSync(SyncJob job) {
        return channelRepository.findByIdForInstance(job.getChannelId())
                .switchIfEmpty(Mono.error(new IllegalStateException("Channel not found for sync job: " + job.getId())))
                .flatMapMany(channel -> resolveStartMessageId(job, channel.getChatId())
                        .flatMapMany(startMsgId -> {
                            if (startMsgId < 0) {
                                long alreadyHave = -startMsgId;
                                log.info("Sync job {} chat {}: already fully covered (oldest msg older than cutoff), {} msgs in DB",
                                        job.getId(), channel.getChatId(), alreadyHave);
                                return Flux.just(
                                        SyncProgressDto.started(job.getId(), channel.getChatId()),
                                        SyncProgressDto.completed(job.getId(), channel.getChatId(), alreadyHave));
                            }
                            log.info("Sync job {} chat {}: starting from msgId={} (0=latest, else oldest known)",
                                    job.getId(), channel.getChatId(), startMsgId);
                            return Flux.just(SyncProgressDto.started(job.getId(), channel.getChatId()))
                                    .concatWith(fetchPagesRecursively(job, channel, startMsgId, new AtomicLong(0), new AtomicLong(0), job.getBotInstanceId()));
                        })
                )
                .onErrorResume(e -> {
                    log.error("Sync job {} failed for chat {}: {}", job.getId(), job.getChannelId(), e.getMessage(), e);
                    return Flux.just(SyncProgressDto.failed(job.getId(), job.getChannelId(), e.getMessage()));
                })
                .doOnCancel(() -> cancelledJobs.add(job.getId()))
                .doFinally(signalType -> cancelledJobs.remove(job.getId()));
    }

    /**
     * Determines the starting message ID for a sync job.
     * Returns 0 if no messages exist (start from latest).
     * Returns the oldest known message_id if we have partial data (continue from there).
     * Returns a negative value (-count) if the DB already covers the full requested date range (skip sync).
     */
    private Mono<Long> resolveStartMessageId(SyncJob job, long chatId) {
        if (job.getSyncFromDate() == null) {
            return Mono.just(0L);
        }
        Instant cutoff = job.getSyncFromDate().toInstant(ZoneOffset.UTC);
        return Mono.zip(
                messageRepository.findMinMessageIdByChatId(chatId).defaultIfEmpty(0L),
                messageRepository.findOldestMessageEpochByChatId(chatId).defaultIfEmpty(Instant.now().getEpochSecond()),
                messageRepository.countByChatId(chatId).defaultIfEmpty(0L)
        ).map(tuple -> {
            long minMsgId = tuple.getT1();
            Instant oldestDate = Instant.ofEpochSecond(tuple.getT2());
            long count = tuple.getT3();
            if (minMsgId == 0L) {
                return 0L;
            }
            if (!oldestDate.isAfter(cutoff)) {
                return -count;
            }
            log.info("Chat {}: have {} msgs, oldest={}, cutoff={} — continuing from msgId={}",
                    chatId, count, oldestDate, cutoff, minMsgId);
            return minMsgId;
        });
    }

    private Flux<SyncProgressDto> fetchPagesRecursively(SyncJob job, Channel channel, long fromMsgId, AtomicLong totalProcessed, AtomicLong totalSeen, String botInstanceId) {
        if (cancelledJobs.contains(job.getId())) {
            return Flux.just(SyncProgressDto.failed(job.getId(), channel.getChatId(), "Cancelled by user"));
        }

        return fetchMessageBatch(channel.getChatId(), fromMsgId, botInstanceId)
                .delayElement(BASE_DELAY) // Rate limiting между батчами
                .flatMapMany(messages -> {
                    if (messages.messages == null || messages.messages.length == 0) {
                        return Flux.just(SyncProgressDto.completed(job.getId(), channel.getChatId(), totalProcessed.get()));
                    }

                    long oldestMsgIdInBatch = Arrays.stream(messages.messages).mapToLong(m -> m.id).min().orElse(0L);
                    long currentSeen = totalSeen.addAndGet(messages.messages.length);

                    return Flux.fromArray(messages.messages)
                            .takeWhile(msg -> job.getSyncFromDate() == null || !LocalDateTime.ofInstant(Instant.ofEpochSecond(msg.date), ZoneOffset.UTC).isBefore(job.getSyncFromDate()))
                            .filter(msg -> !isTrivialMessage(msg))
                            .concatMap(msg -> messagePersistenceService.forcePersistMessage(job.getBotInstanceId(), channel.getChatId(), msg)
                                    .doOnSuccess(v -> totalProcessed.incrementAndGet())
                                    .onErrorResume(e -> {
                                        log.warn("Failed to persist message {} for job {}: {}", msg.id, job.getId(), e.getMessage());
                                        return Mono.empty();
                                    }))
                            .then(Mono.just(1)) // Signal batch completion
                            .flatMapMany(ignored -> {
                                SyncProgressDto progress = SyncProgressDto.processing(job.getId(), channel.getChatId(), totalProcessed.get(), currentSeen, "Batch processed");
                                // Stop when any message in the batch is OLDER than the cutoff date
                                boolean dateLimitReached = job.getSyncFromDate() != null &&
                                        Arrays.stream(messages.messages).anyMatch(msg ->
                                                LocalDateTime.ofInstant(Instant.ofEpochSecond(msg.date), ZoneOffset.UTC)
                                                        .isBefore(job.getSyncFromDate()));

                                if (dateLimitReached || oldestMsgIdInBatch == 0) {
                                    log.info("Sync job {} stopping: dateLimitReached={} oldestMsgId={} processed={}",
                                            job.getId(), dateLimitReached, oldestMsgIdInBatch, totalProcessed.get());
                                    return Flux.just(progress, SyncProgressDto.completed(job.getId(), channel.getChatId(), totalProcessed.get()));
                                } else {
                                    return Flux.just(progress)
                                            .concatWith(fetchPagesRecursively(job, channel, oldestMsgIdInBatch, totalProcessed, new AtomicLong(currentSeen), botInstanceId));
                                }
                            });
                })
                .onErrorResume(e -> {
                    log.warn("Sync job {} hit error while fetching chat {} fromMsgId {}: {}", job.getId(), channel.getChatId(), fromMsgId, e.getMessage());
                    return Flux.just(SyncProgressDto.failed(job.getId(), channel.getChatId(), e.getMessage()));
                });
    }

    /**
     * Returns true if the message is trivial and should be skipped.
     * Text messages with 3 words or fewer are considered trivial (e.g. "ok", "hi", "how are you").
     * Non-text messages (photos, videos, documents, etc.) are never considered trivial.
     */
    private boolean isTrivialMessage(TdApi.Message msg) {
        if (!(msg.content instanceof TdApi.MessageText textContent)) {
            return false;
        }
        if (textContent.text == null || textContent.text.text == null) {
            return true;
        }
        String text = textContent.text.text.trim();
        return text.isEmpty() || text.split("\\s+").length <= 3;
    }

    private Mono<TdApi.Messages> fetchMessageBatch(long chatId, long fromMsgId, String botInstanceId) {
        return fetchMessageBatchWithRetry(chatId, fromMsgId, 0, botInstanceId);
    }

    private Mono<TdApi.Messages> fetchMessageBatchWithRetry(long chatId, long fromMsgId, int retryCount, String botInstanceId) {
        return Mono.<TdApi.Messages>create(sink -> {
            var request = new TdApi.GetChatHistory(chatId, fromMsgId, 0, BATCH_SIZE, false);
            TelegramClientFacade activeClient = botInstanceId != null ? clientManager.getClient(botInstanceId) : null;
            if (activeClient == null) activeClient = clientManager.getAnyClient();
            activeClient.send(request, result -> {
                if (result.isError()) {
                    TdApi.Error error = result.getError();
                    boolean isFloodWait = error.code == 420 || error.code == 429
                            || (error.message != null && error.message.contains("FLOOD_WAIT"));
                    if (isFloodWait) {
                        sink.error(new TelegramRateLimitException(error.message, error.code));
                    } else {
                        log.warn("TDLib error fetching history chat={} fromMsg={}: code={} msg={}",
                                chatId, fromMsgId, error.code, error.message);
                        sink.error(new RuntimeException("TDLib error " + error.code + ": " + error.message));
                    }
                } else {
                    sink.success(result.get());
                }
            });
        })
        .onErrorResume(TelegramRateLimitException.class, ex -> {
            if (retryCount >= MAX_RETRIES) {
                log.error("Max retries ({}) exhausted for chat {}. Giving up.", MAX_RETRIES, chatId);
                return Mono.error(new RuntimeException("Failed to fetch messages after " + MAX_RETRIES + " retries"));
            }
            Duration waitTime = extractWaitTime(ex.getMessage());
            log.warn("Rate limit hit for chat {}. Waiting {}s. Retry {}/{}",
                    chatId, waitTime.getSeconds(), retryCount + 1, MAX_RETRIES);
            return Mono.delay(waitTime)
                    .flatMap(tick -> fetchMessageBatchWithRetry(chatId, fromMsgId, retryCount + 1, botInstanceId));
        });
    }

    /**
     * Извлекает время ожидания из сообщения об ошибке "Too Many Requests: retry after X"
     */
    private Duration extractWaitTime(String errorMessage) {
        try {
            if (errorMessage != null && errorMessage.contains("retry after")) {
                String[] parts = errorMessage.split("retry after ");
                if (parts.length > 1) {
                    int seconds = Integer.parseInt(parts[1].trim().split("\\s+")[0]);
                    // Добавляем небольшой буфер к указанному времени
                    return Duration.ofSeconds(seconds + 2);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse retry time from error message: {}", errorMessage);
        }
        // По умолчанию ждём 5 секунд
        return Duration.ofSeconds(5);
    }

    /**
     * Исключение для обработки rate limit ошибок от Telegram
     */
    private static class TelegramRateLimitException extends RuntimeException {
        private final int errorCode;

        public TelegramRateLimitException(String message, int errorCode) {
            super(message);
            this.errorCode = errorCode;
        }

        public int getErrorCode() {
            return errorCode;
        }
    }

    @Override
    public Mono<Void> cancelSync(Long jobId) {
        return Mono.fromRunnable(() -> {
            cancelledJobs.add(jobId);
            log.info("Cancellation requested for sync job {}", jobId);
        });
    }
}
