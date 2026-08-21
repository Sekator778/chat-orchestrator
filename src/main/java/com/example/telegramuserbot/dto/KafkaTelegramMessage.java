package com.example.telegramuserbot.dto;

/**
 * @author Sekator
 * @created 27 кві, 2025
 */
public class KafkaTelegramMessage {
    private long chatId;
    private long messageId; // Telegram message ID

    // Обов'язковий конструктор без аргументів для Jackson
    public KafkaTelegramMessage() {
    }

    public KafkaTelegramMessage(long chatId, long messageId) {
        this.chatId = chatId;
        this.messageId = messageId;
    }

    // Getters and Setters
    public long getChatId() {
        return chatId;
    }

    public void setChatId(long chatId) {
        this.chatId = chatId;
    }

    public long getMessageId() {
        return messageId;
    }

    public void setMessageId(long messageId) {
        this.messageId = messageId;
    }

    @Override
    public String toString() {
        return "KafkaTelegramMessage{" +
                "chatId=" + chatId +
                ", messageId=" + messageId +
                '}';
    }
}