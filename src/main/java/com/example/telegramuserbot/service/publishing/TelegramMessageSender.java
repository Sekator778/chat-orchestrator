package com.example.telegramuserbot.service.publishing;

import it.tdlight.jni.TdApi;
import reactor.core.publisher.Mono;

/**
 * Interface for sending messages to Telegram chats.
 * Abstracts Telegram API interaction for testability.
 */
public interface TelegramMessageSender {

    /**
     * Sends text message to Telegram chat using the primary bot's TDLib session.
     *
     * @param chatId target chat ID
     * @param text message text (may contain HTML)
     * @return mono with sent message details
     */
    Mono<TdApi.Message> send(Long chatId, String text);

    /**
     * Sends text message as a reply to a specific message in Telegram chat
     * using the primary bot's TDLib session.
     *
     * @param chatId target chat ID
     * @param replyToMessageId ID of the message to reply to
     * @param text message text (may contain HTML)
     * @return mono with sent message details
     */
    Mono<TdApi.Message> send(Long chatId, Long replyToMessageId, String text);

    /**
     * Sends text message to Telegram chat using a specific persona's TDLib session.
     * Used by the digest publishing system to send from the correct bot account.
     *
     * @param botId the bot/persona instance ID (e.g. "2000000002")
     * @param chatId target chat ID
     * @param text message text (may contain HTML)
     * @return mono with sent message details
     */
    Mono<TdApi.Message> send(String botId, Long chatId, String text);

    /**
     * Sends text message as a reply using a specific persona's TDLib session.
     *
     * @param botId the bot/persona instance ID (e.g. "2000000002")
     * @param chatId target chat ID
     * @param replyToMessageId ID of the message to reply to
     * @param text message text (may contain HTML)
     * @return mono with sent message details
     */
    Mono<TdApi.Message> send(String botId, Long chatId, Long replyToMessageId, String text);

    /**
     * Whether the given bot's client is in a FLOOD_WAIT backoff window (outbound sends
     * suppressed). Callers can skip expensive generation that would only be dropped at send.
     *
     * @param botId the bot/persona instance ID
     * @return true if backing off; false when unknown or no client
     */
    boolean isBackingOff(String botId);
}
