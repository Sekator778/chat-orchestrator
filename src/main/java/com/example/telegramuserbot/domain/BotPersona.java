package com.example.telegramuserbot.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.Objects;

/**
 * Persona “legend” for LLM: who the bot pretends to be (stored in bot.bot_personas).
 * Not related to runtime Telegram account info (see BotInfoService).
 */
@Table(name = "bot_personas", schema = "bot")
public class BotPersona {
    @Id
    private Long id;
    @Column("bot_id")
    private String botId;
    @Column("language")
    private String language;
    @Column("name")
    private String name;
    @Column("description")
    private String description;
    @Column("behavior")
    private String behavior; // JSON or newline-joined list
    @Column("traits")
    private String traits;   // JSON or comma-joined list
    @Column("limitations")
    private String limitations; // JSON or comma-joined list
    @Column("metadata")
    private String metadata; // JSONB stored as string
    @Column("reply_to_direct")
    private Boolean replyToDirect = Boolean.FALSE; // answer private (direct) messages in the same DM
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

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getBehavior() {
        return behavior;
    }

    public void setBehavior(String behavior) {
        this.behavior = behavior;
    }

    public String getTraits() {
        return traits;
    }

    public void setTraits(String traits) {
        this.traits = traits;
    }

    public String getLimitations() {
        return limitations;
    }

    public void setLimitations(String limitations) {
        this.limitations = limitations;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }

    public Boolean getReplyToDirect() {
        return replyToDirect;
    }

    public void setReplyToDirect(Boolean replyToDirect) {
        this.replyToDirect = replyToDirect;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BotPersona that = (BotPersona) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
