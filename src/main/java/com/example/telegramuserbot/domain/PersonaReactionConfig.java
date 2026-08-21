package com.example.telegramuserbot.domain;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.Objects;

/**
 * Configuration entity for persona reaction behavior on a specific channel.
 * Controls how many reactions a persona can post per day on a given channel.
 */
@Table(schema = "bot", name = "persona_reaction_config")
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY)
public final class PersonaReactionConfig {

    @Id
    private Long id;

    @Column("persona_id")
    private String personaId;

    @Column("channel_id")
    private Long channelId;

    @Column("max_per_day")
    private int maxPerDay;

    @Column("enabled")
    private boolean enabled;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;

    /**
     * Default constructor for R2DBC mapping.
     */
    public PersonaReactionConfig() {
        this.maxPerDay = 2;
        this.enabled = true;
    }

    /**
     * Constructor with required fields.
     *
     * @param personaId the persona identifier
     * @param channelId the channel ID to monitor
     * @param maxPerDay maximum reactions per day on this channel
     */
    public PersonaReactionConfig(String personaId, Long channelId, int maxPerDay) {
        this();
        this.personaId = personaId;
        this.channelId = channelId;
        this.maxPerDay = maxPerDay;
    }

    /**
     * Returns the primary key identifier.
     *
     * @return the entity id
     */
    public Long id() {
        return id;
    }

    /**
     * Sets the primary key identifier.
     *
     * @param id the entity id
     */
    public void setId(Long id) {
        this.id = id;
    }

    /**
     * Returns the persona identifier.
     *
     * @return persona id string
     */
    public String personaId() {
        return personaId;
    }

    /**
     * Sets the persona identifier.
     *
     * @param personaId the persona id string
     */
    public void setPersonaId(String personaId) {
        this.personaId = personaId;
    }

    /**
     * Returns the Telegram channel ID.
     *
     * @return channel id
     */
    public Long channelId() {
        return channelId;
    }

    /**
     * Sets the Telegram channel ID.
     *
     * @param channelId the channel id
     */
    public void setChannelId(Long channelId) {
        this.channelId = channelId;
    }

    /**
     * Returns the maximum reactions allowed per day on this channel.
     *
     * @return max reactions per day
     */
    public int maxPerDay() {
        return maxPerDay;
    }

    /**
     * Sets the maximum reactions per day on this channel.
     *
     * @param maxPerDay max reactions count
     */
    public void setMaxPerDay(int maxPerDay) {
        this.maxPerDay = maxPerDay;
    }

    /**
     * Returns whether this configuration is active.
     *
     * @return true if enabled
     */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Sets whether this configuration is active.
     *
     * @param enabled the enabled flag
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Returns the creation timestamp.
     *
     * @return created at instant
     */
    public Instant createdAt() {
        return createdAt;
    }

    /**
     * Sets the creation timestamp.
     *
     * @param createdAt the instant of creation
     */
    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    /**
     * Returns the last update timestamp.
     *
     * @return updated at instant
     */
    public Instant updatedAt() {
        return updatedAt;
    }

    /**
     * Sets the last update timestamp.
     *
     * @param updatedAt the instant of last update
     */
    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PersonaReactionConfig that = (PersonaReactionConfig) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return String.format(
            "PersonaReactionConfig[id=%d, personaId=%s, channelId=%d, maxPerDay=%d, enabled=%s]",
            id, personaId, channelId, maxPerDay, enabled
        );
    }
}
