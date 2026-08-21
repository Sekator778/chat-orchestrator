package com.example.telegramuserbot.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * A candidate Telegram channel discovered by the keyword-driven search scheduler.
 * Rows live in {@code tgscan.channel_candidates}. The candidate is the TDLib chat-id
 * stored as text. A separate follow-up process (F0b) will decide whether to join.
 */
@Table(name = "channel_candidates", schema = "tgscan")
public class ChannelCandidate {

    @Id
    private Long id;

    @Column("candidate")
    private String candidate;

    @Column("source_channel")
    private Long sourceChannel;

    @Column("source_msg_id")
    private Long sourceMsgId;

    @Column("discovered_at")
    private Instant discoveredAt;

    @Column("processed")
    private Boolean processed;

    @Column("note")
    private String note;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getCandidate() { return candidate; }
    public void setCandidate(String candidate) { this.candidate = candidate; }

    public Long getSourceChannel() { return sourceChannel; }
    public void setSourceChannel(Long sourceChannel) { this.sourceChannel = sourceChannel; }

    public Long getSourceMsgId() { return sourceMsgId; }
    public void setSourceMsgId(Long sourceMsgId) { this.sourceMsgId = sourceMsgId; }

    public Instant getDiscoveredAt() { return discoveredAt; }
    public void setDiscoveredAt(Instant discoveredAt) { this.discoveredAt = discoveredAt; }

    public Boolean getProcessed() { return processed; }
    public void setProcessed(Boolean processed) { this.processed = processed; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
