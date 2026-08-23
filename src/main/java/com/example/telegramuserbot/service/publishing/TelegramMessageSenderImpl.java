package com.example.telegramuserbot.service.publishing;

import com.example.telegramuserbot.config.BotInstanceProvider;
import com.example.telegramuserbot.service.TelegramClientManager;
import com.example.telegramuserbot.service.safety.OutboundReplyGuard;
import com.example.telegramuserbot.telegram.TelegramClientFacade;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Implementation of TelegramMessageSender routing messages to the correct TDLib session.
 * Persona-aware: new send(botId, ...) overloads use the persona's own TDLib client.
 * Backward-compatible: old send(chatId, ...) overloads delegate to the primary bot.
 *
 * <p>Outbound moderation is applied here: text sent through this class passes
 * {@link OutboundReplyGuard#shouldSuppress} first, fail-closed — a guard error
 * suppresses the send and logs a warning. This is not the only door, though:
 * the reactive reply path and the pending queue build their own SendMessage and
 * call the client facade directly, so each applies the guard itself. Adding a
 * new send path means adding the guard to it.
 */
@Component
public final class TelegramMessageSenderImpl implements TelegramMessageSender {

    private static final Logger log = LoggerFactory.getLogger(TelegramMessageSenderImpl.class);

    private final TelegramClientManager clientManager;
    private final BotInstanceProvider botInstanceProvider;
    private final OutboundReplyGuard outboundReplyGuard;

    /**
     * Constructs sender with client manager, primary bot provider, and outbound guard.
     *
     * @param clientManager manages all TDLib sessions by botId
     * @param botInstanceProvider provides the primary bot instance ID
     * @param outboundReplyGuard last-gate moderation: suppresses self-identifying/denylisted text
     */
    public TelegramMessageSenderImpl(
            TelegramClientManager clientManager,
            BotInstanceProvider botInstanceProvider,
            OutboundReplyGuard outboundReplyGuard) {
        this.clientManager = clientManager;
        this.botInstanceProvider = botInstanceProvider;
        this.outboundReplyGuard = outboundReplyGuard;
    }

    @Override
    public Mono<TdApi.Message> send(Long chatId, String text) {
        return send(botInstanceProvider.getInstanceId(), chatId, text);
    }

    @Override
    public Mono<TdApi.Message> send(Long chatId, Long replyToMessageId, String text) {
        return send(botInstanceProvider.getInstanceId(), chatId, replyToMessageId, text);
    }

    @Override
    public boolean isBackingOff(String botId) {
        TelegramClientFacade client = clientManager.getClient(botId);
        return client != null && client.isBackingOff();
    }

    @Override
    public Mono<TdApi.Message> send(String botId, Long chatId, String text) {
        try {
            if (outboundReplyGuard.shouldSuppress(text)) {
                log.warn("⊘ OUTBOUND GUARD suppressed send to chatId={} botId={} — staying silent", chatId, botId);
                return Mono.empty();
            }
        } catch (Exception guardEx) {
            log.warn("⊘ OUTBOUND GUARD error (fail-closed) for chatId={} botId={}: {}", chatId, botId, guardEx.getMessage());
            return Mono.empty();
        }
        TelegramClientFacade client = clientManager.getClient(botId);
        if (client == null) {
            log.error("No TDLib client found for botId={}, cannot send to chatId={}", botId, chatId);
            return Mono.error(new IllegalStateException("No TDLib client for botId=" + botId));
        }
        return Mono.<TdApi.Message>create(sink -> {
            TdApi.InputMessageContent content = new TdApi.InputMessageText(
                new TdApi.FormattedText(text, null),
                null,
                false
            );

            TdApi.SendMessage request = new TdApi.SendMessage(
                chatId,
                0,      // message thread id
                null,   // reply to
                null,   // options
                null,   // reply markup
                content
            );

            client.send(request, result -> {
                if (result.isError()) {
                    sink.error(new RuntimeException(
                        "Telegram API error (botId=" + botId + "): " + result.getError().message
                    ));
                } else {
                    sink.success(result.get());
                }
            });
        }).timeout(Duration.ofSeconds(30), Mono.error(
                new RuntimeException("Telegram send timed out after 30s (botId=" + botId + ", chatId=" + chatId + ")")));
    }

    @Override
    public Mono<TdApi.Message> send(String botId, Long chatId, Long replyToMessageId, String text) {
        try {
            if (outboundReplyGuard.shouldSuppress(text)) {
                log.warn("⊘ OUTBOUND GUARD suppressed reply to chatId={} msgId={} botId={} — staying silent",
                        chatId, replyToMessageId, botId);
                return Mono.empty();
            }
        } catch (Exception guardEx) {
            log.warn("⊘ OUTBOUND GUARD error (fail-closed) for chatId={} botId={}: {}", chatId, botId, guardEx.getMessage());
            return Mono.empty();
        }
        TelegramClientFacade client = clientManager.getClient(botId);
        if (client == null) {
            log.error("No TDLib client found for botId={}, cannot send to chatId={}", botId, chatId);
            return Mono.error(new IllegalStateException("No TDLib client for botId=" + botId));
        }
        return Mono.<TdApi.Message>create(sink -> {
            TdApi.InputMessageContent content = new TdApi.InputMessageText(
                new TdApi.FormattedText(text, null),
                null,
                false
            );

            TdApi.InputMessageReplyToMessage replyTo = new TdApi.InputMessageReplyToMessage();
            replyTo.chatId = chatId;
            replyTo.messageId = replyToMessageId;

            TdApi.SendMessage request = new TdApi.SendMessage(
                chatId,
                0,      // message thread id
                replyTo, // reply to specific message
                null,   // options
                null,   // reply markup
                content
            );

            client.send(request, result -> {
                if (result.isError()) {
                    sink.error(new RuntimeException(
                        "Telegram API error (botId=" + botId + "): " + result.getError().message
                    ));
                } else {
                    sink.success(result.get());
                }
            });
        }).timeout(Duration.ofSeconds(30), Mono.error(
                new RuntimeException("Telegram send timed out after 30s (botId=" + botId + ", chatId=" + chatId + ", replyTo=" + replyToMessageId + ")")));
    }
}
