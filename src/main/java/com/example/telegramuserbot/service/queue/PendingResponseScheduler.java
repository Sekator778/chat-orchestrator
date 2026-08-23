package com.example.telegramuserbot.service.queue;

import com.example.telegramuserbot.domain.PendingResponse;
import com.example.telegramuserbot.service.TelegramClientManager;
import com.example.telegramuserbot.service.persistence.MessagePersistenceService;
import com.example.telegramuserbot.service.safety.OutboundReplyGuard;
import com.example.telegramuserbot.telegram.TelegramClientFacade;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import org.springframework.beans.factory.annotation.Value;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scheduler service that periodically checks and processes pending responses.
 *
 * This service runs background tasks to:
 * 1. Find eligible responses and send them to Telegram
 * 2. Mark expired responses
 * 3. Clean up old expired responses from the database
 *
 * All operations are reactive and run on the bounded elastic scheduler
 * to avoid blocking the main event loop.
 */
@Service
@ConditionalOnProperty(
        prefix = "pending-response",
        name = "scheduler.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public final class PendingResponseScheduler {

    private static final Logger log = LoggerFactory.getLogger(PendingResponseScheduler.class);

    private final PendingResponseService pendingResponseService;
    private final TelegramClientManager telegramClientManager;
    private final MessagePersistenceService messagePersistenceService;
    private final OutboundReplyGuard outboundReplyGuard;

    @Value("${pending-response.scheduler.send-concurrency:8}")
    private int sendConcurrency;

    @Value("${pending-response.scheduler.claim-limit-multiplier:4}")
    private int claimLimitMultiplier;

    private final AtomicBoolean processing = new AtomicBoolean(false);

    public PendingResponseScheduler(
            PendingResponseService pendingResponseService,
            TelegramClientManager telegramClientManager,
            MessagePersistenceService messagePersistenceService,
            OutboundReplyGuard outboundReplyGuard
    ) {
        this.pendingResponseService = pendingResponseService;
        this.telegramClientManager = telegramClientManager;
        this.messagePersistenceService = messagePersistenceService;
        this.outboundReplyGuard = outboundReplyGuard;
    }

    /**
     * Periodically processes the pending queue:
     * 1) Marks responses that satisfied delay/reply requirements as eligible
     * 2) Sends all eligible responses
     * Runs every 30 seconds by default.
     */
    @Scheduled(
            fixedDelayString = "${pending-response.scheduler.check-eligible-interval-ms:30000}",
            initialDelayString = "${pending-response.scheduler.initial-delay-ms:10000}"
    )
    public void processPendingQueue() {
        if (!processing.compareAndSet(false, true)) {
            log.warn("🔄 SCHEDULER: Skipping pending processing - previous run still in progress");
            return;
        }
        log.info("🔄 SCHEDULER: Processing pending responses (eligibility + dispatch)...");

        int limit = Math.max(1, sendConcurrency) * Math.max(1, claimLimitMultiplier);

        pendingResponseService.findPendingThatReachedThreshold()
                .thenMany(pendingResponseService.claimEligibleResponses(limit))
                .flatMap(this::sendEligibleResponse, Math.max(1, sendConcurrency))
                .subscribeOn(Schedulers.boundedElastic())
                .doFinally(signalType -> processing.set(false))
                .subscribe(
                        pending -> log.debug("📤 SCHEDULER: Processed pending id={}", pending.getId()),
                        error -> log.error("💥 SCHEDULER ERROR: Failed while processing pending queue", error),
                        () -> log.trace("🔄 SCHEDULER: Finished processing pending responses")
                );
    }

    /**
     * Sends a single eligible response to Telegram.
     *
     * @param pending the pending response to send
     * @return mono of the pending response after sending
     */
    private Mono<PendingResponse> sendEligibleResponse(PendingResponse pending) {
        Long chatId = pending.getChatId();
        String botId = pending.getBotInstanceId();

        log.info("📤 SENDING: Eligible pending response id={} for chat={}, triggeringMsg={}",
                pending.getId(), chatId, pending.getTriggeringMessageId());

        TelegramClientFacade client = telegramClientManager.getClient(botId);
        if (client == null) {
            log.error("📤 SEND FAIL: No telegram client for pending id={} botId={}; marking as expired", pending.getId(), botId);
            return pendingResponseService.markAsExpiredById(pending.getId()).thenReturn(pending);
        }

        return sendReply(client, chatId, pending.getTriggeringMessageId(), pending.getPreparedResponse())
                .flatMap(sentMessage -> messagePersistenceService.persistMessage(botId, chatId, sentMessage).thenReturn(sentMessage))
                .flatMap(sentMessage -> {
                    log.info("📤 SENT OK: Pending response id={} sent as msgId={} to chat={} botId={}",
                            pending.getId(), sentMessage.id, chatId, botId);
                    return pendingResponseService.markAsSent(pending.getId());
                })
                .onErrorResume(error -> {
                    String msg = error.getMessage();
                    if (isPermanentSendError(msg)) {
                        log.warn("📤 SEND ABANDONED: pending id={} chat={} — permanent error, not retrying: {}",
                                pending.getId(), chatId, msg);
                        return pendingResponseService.markAsExpiredById(pending.getId()).thenReturn(pending);
                    }
                    log.error("📤 SEND FAIL: Failed to send pending response id={} to chat={} botId={}: {}",
                            pending.getId(), chatId, botId, msg, error);
                    return pendingResponseService.revertToEligible(pending.getId()).thenReturn(pending);
                });
    }

    private Mono<TdApi.Message> sendReply(TelegramClientFacade client, Long chatId, Long replyToMessageId, String text) {
        if (text == null || text.isBlank()) {
            return Mono.empty();
        }
        // A queued reply was generated minutes ago and never passed outbound moderation:
        // this path talks to the client facade directly, bypassing TelegramMessageSenderImpl
        // where the guard lives.
        try {
            if (outboundReplyGuard.shouldSuppress(text)) {
                log.warn("⊘ OUTBOUND GUARD suppressed pending reply to chat={} — staying silent", chatId);
                return Mono.empty();
            }
        } catch (Exception guardError) {
            log.warn("⊘ OUTBOUND GUARD error (fail-closed) for chat={}: {}", chatId, guardError.getMessage());
            return Mono.empty();
        }
        return Mono.<TdApi.Message>create(sink -> {
            TdApi.InputMessageContent content = new TdApi.InputMessageText(new TdApi.FormattedText(text, null), null, false);
            TdApi.InputMessageReplyToMessage replyTo = new TdApi.InputMessageReplyToMessage();
            replyTo.chatId = chatId;
            replyTo.messageId = replyToMessageId != null ? replyToMessageId : 0L;
            TdApi.SendMessage request = new TdApi.SendMessage(chatId, 0, replyTo, null, null, content);
            client.send(request, result -> {
                if (result.isError()) {
                    sink.error(new RuntimeException("Telegram API error: " + result.getError().message));
                } else {
                    sink.success(result.get());
                }
            });
        }).timeout(Duration.ofSeconds(30), Mono.error(
                new RuntimeException("Telegram send timed out after 30s (chatId=" + chatId + ")")));
    }

    /**
     * Periodically marks responses that have expired.
     * Runs every 5 minutes by default.
     */
    @Scheduled(
            fixedDelayString = "${pending-response.scheduler.mark-expired-interval-ms:300000}",
            initialDelayString = "${pending-response.scheduler.initial-delay-ms:10000}"
    )
    public void markExpiredResponses() {
        log.debug("🔄 SCHEDULER: Marking expired pending responses...");

        pendingResponseService.markExpiredResponses()
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        count -> {
                            if (count > 0) {
                                log.info("⏰ SCHEDULER: Marked {} pending responses as expired", count);
                            }
                        },
                        error -> log.error("💥 SCHEDULER ERROR: Failed to mark expired responses", error)
                );
    }

    /**
     * Returns true when the error message indicates a permanent send failure
     * (the bot permanently lacks rights to write in that chat). Such errors
     * should not be retried — the row is marked TERMINAL (expired) immediately.
     */
    private static boolean isPermanentSendError(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("not enough rights")
                || lower.contains("chat_write_forbidden")
                || lower.contains("have no rights to send")
                || lower.contains("chat_send_plain_forbidden")
                || lower.contains("chat write forbidden");
    }

    /**
     * Periodically cleans up old expired responses from the database.
     * Runs every hour by default.
     */
    @Scheduled(
            fixedDelayString = "${pending-response.scheduler.cleanup-interval-ms:3600000}",
            initialDelayString = "${pending-response.scheduler.initial-delay-ms:10000}"
    )
    public void cleanupExpiredResponses() {
        log.debug("🔄 SCHEDULER: Cleaning up expired pending responses...");

        pendingResponseService.cleanupExpiredResponses()
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        count -> {
                            if (count > 0) {
                                log.info("🧹 SCHEDULER: Deleted {} expired pending responses", count);
                            }
                        },
                        error -> log.error("💥 SCHEDULER ERROR: Failed to cleanup expired responses", error)
                );
    }

}
