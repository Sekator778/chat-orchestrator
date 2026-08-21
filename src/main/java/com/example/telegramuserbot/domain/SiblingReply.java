package com.example.telegramuserbot.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * Tracks every sibling reply sent by persona B in response to a proactive post by persona A.
 * Used for (a) idempotency — unique constraint on (persona_bot_id, in_reply_to_message_id)
 * prevents a persona from replying twice to the same origin post, and (b) daily-cap counting.
 */
@Table(schema = "bot", name = "sibling_replies")
public final class SiblingReply {

    @Id
    private Long id;

    @Column("persona_bot_id")
    private String personaBotId;

    @Column("chat_id")
    private Long chatId;

    @Column("in_reply_to_message_id")
    private Long inReplyToMessageId;

    @Column("origin_bot_id")
    private String originBotId;

    @Column("posted_at")
    private Instant postedAt;

    public SiblingReply() {
        this.postedAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPersonaBotId() { return personaBotId; }
    public void setPersonaBotId(String personaBotId) { this.personaBotId = personaBotId; }

    public Long getChatId() { return chatId; }
    public void setChatId(Long chatId) { this.chatId = chatId; }

    public Long getInReplyToMessageId() { return inReplyToMessageId; }
    public void setInReplyToMessageId(Long inReplyToMessageId) { this.inReplyToMessageId = inReplyToMessageId; }

    public String getOriginBotId() { return originBotId; }
    public void setOriginBotId(String originBotId) { this.originBotId = originBotId; }

    public Instant getPostedAt() { return postedAt; }
    public void setPostedAt(Instant postedAt) { this.postedAt = postedAt; }
}
