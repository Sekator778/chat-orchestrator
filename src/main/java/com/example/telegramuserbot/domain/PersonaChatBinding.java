package com.example.telegramuserbot.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * Which persona lives in which chat (stored in bot.persona_chat_bindings).
 * The dispatch source of truth; per-(persona, chat) overrides land here in
 * later increments.
 */
@Table(name = "persona_chat_bindings", schema = "bot")
public class PersonaChatBinding {
    @Id
    private Long id;
    @Column("bot_id")
    private String botId;
    @Column("chat_id")
    private Long chatId;
    @Column("reply_enabled")
    private boolean replyEnabled;
    @Column("reply_probability")
    private Double replyProbability;
    @Column("created_at")
    private Instant createdAt;
    @Column("updated_at")
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getBotId() {
        return botId;
    }

    public void setBotId(String botId) {
        this.botId = botId;
    }

    public Double getReplyProbability() {
        return replyProbability;
    }

    public void setReplyProbability(Double replyProbability) {
        this.replyProbability = replyProbability;
    }

    public Long getChatId() {
        return chatId;
    }

    public void setChatId(Long chatId) {
        this.chatId = chatId;
    }

    public boolean isReplyEnabled() {
        return replyEnabled;
    }

    public void setReplyEnabled(boolean replyEnabled) {
        this.replyEnabled = replyEnabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
