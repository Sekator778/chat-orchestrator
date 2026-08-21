package com.example.telegramuserbot.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * Tracks every proactive news post sent by a persona to a target chat.
 * Used to (a) prevent duplicate posting of the same message_id and
 * (b) enforce a per-persona daily-cap via countByPersonaBotIdAndTargetChatIdAndPostedAtAfter.
 */
@Table(schema = "bot", name = "news_posts")
public final class NewsPost {

    @Id
    private Long id;

    @Column("message_id")
    private Long messageId;

    @Column("persona_bot_id")
    private String personaBotId;

    @Column("target_chat_id")
    private Long targetChatId;

    @Column("telegram_message_id")
    private Long telegramMessageId;

    @Column("value_score")
    private Double valueScore;

    @Column("posted_at")
    private Instant postedAt;

    @Column("status")
    private String status;

    @Column("error_message")
    private String errorMessage;

    public NewsPost() {
        this.status = "SENT";
        this.postedAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getMessageId() { return messageId; }
    public void setMessageId(Long messageId) { this.messageId = messageId; }

    public String getPersonaBotId() { return personaBotId; }
    public void setPersonaBotId(String personaBotId) { this.personaBotId = personaBotId; }

    public Long getTargetChatId() { return targetChatId; }
    public void setTargetChatId(Long targetChatId) { this.targetChatId = targetChatId; }

    public Long getTelegramMessageId() { return telegramMessageId; }
    public void setTelegramMessageId(Long telegramMessageId) { this.telegramMessageId = telegramMessageId; }

    public Double getValueScore() { return valueScore; }
    public void setValueScore(Double valueScore) { this.valueScore = valueScore; }

    public Instant getPostedAt() { return postedAt; }
    public void setPostedAt(Instant postedAt) { this.postedAt = postedAt; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
