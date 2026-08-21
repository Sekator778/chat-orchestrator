package com.example.telegramuserbot.service.command; // Новий пакет

import reactor.core.publisher.Mono;

import java.util.Optional;

/**
 * Processes commands received via Telegram messages from authorized chats.
 */
public interface CommandService {

    /**
     * Parses and executes a command from a text message.
     *
     * @param chatId    The ID of the chat where the command was received.
     * @param messageId The ID of the message containing the command.
     * @param commandText The full text of the command message (e.g., "/get_config 12345").
     * @return A Mono emitting an Optional containing the response text to be sent back,
     *         or an empty Mono if the text is not a valid/handled command.
     */
    Mono<Optional<String>> processCommand(long chatId, long messageId, String commandText);

    /**
     * Parses and executes a command from a text message with sender information.
     *
     * @param chatId      The ID of the chat where the command was received.
     * @param messageId   The ID of the message containing the command.
     * @param senderId    The Telegram user ID of the command sender.
     * @param commandText The full text of the command message.
     * @return A Mono emitting an Optional containing the response text to be sent back,
     *         or an empty Mono if the text is not a valid/handled command.
     */
    Mono<Optional<String>> processCommand(long chatId, long messageId, Long senderId, String commandText);
}