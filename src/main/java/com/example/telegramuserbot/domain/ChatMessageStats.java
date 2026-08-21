package com.example.telegramuserbot.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table(name = "chat_message_stats", schema = "bot")
public class ChatMessageStats {

    @Id
    @Column("chat_id")
    private Long chatId;

    @Column("human_message_count")
    private Long humanMessageCount;

    public Long getChatId() {
        return chatId;
    }

    public void setChatId(Long chatId) {
        this.chatId = chatId;
    }

    public Long getHumanMessageCount() {
        return humanMessageCount;
    }

    public void setHumanMessageCount(Long humanMessageCount) {
        this.humanMessageCount = humanMessageCount;
    }
}
