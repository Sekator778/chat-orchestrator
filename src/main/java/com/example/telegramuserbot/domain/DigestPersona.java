package com.example.telegramuserbot.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.time.LocalTime;
import java.util.Objects;

/**
 * Represents a digest generation persona.
 * Each persona defines how and when digests are generated and published.
 */
@Table(schema = "bot", name = "digest_personas")
public final class DigestPersona {

    @Id
    private Long id;

    @Column("name")
    private String name;

    @Column("description")
    private String description;

    @Column("bot_id")
    private Long botId;

    @Column("target_channel_id")
    private Long targetChannelId;

    @Column("enabled")
    private Boolean enabled;

    @Column("persona_style")
    private String personaStyle;

    @Column("custom_system_prompt")
    private String customSystemPrompt;

    @Column("schedule_cron")
    private String scheduleCron;

    @Column("schedule_timezone")
    private String scheduleTimezone;

    @Column("active_hours_start")
    private LocalTime activeHoursStart;

    @Column("active_hours_end")
    private LocalTime activeHoursEnd;

    @Column("lookback_hours")
    private Integer lookbackHours;

    @Column("max_messages")
    private Integer maxMessages;

    @Column("language")
    private String language;

    @Column("min_cluster_size")
    private Integer minClusterSize;

    @Column("min_importance_score")
    private Double minImportanceScore;

    @Column("source_trust_threshold")
    private Double sourceTrustThreshold;

    @Column("excluded_channel_ids")
    private Long[] excludedChannelIds;

    @Column("topic_keywords")
    private String[] topicKeywords;

    @Column("negative_keywords")
    private String[] negativeKeywords;

    @Column("model_name")
    private String modelName;

    @Column("temperature")
    private Double temperature;

    @Column("max_tokens")
    private Integer maxTokens;

    @Column("last_run_at")
    private Instant lastRunAt;

    @Column("last_published_digest_id")
    private String lastPublishedDigestId;

    @Column("total_digests_published")
    private Integer totalDigestsPublished;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;

    @Column("publish_mode")
    private String publishMode;

    @Column("random_delay_max_minutes")
    private Integer randomDelayMaxMinutes;

    /**
     * Audience geo scope for proactive news filtering.
     * Only messages whose geo is NULL, 'GLOBAL', or equal to this value are eligible.
     * Default 'GLOBAL' means the persona accepts only globally-relevant news (no country-specific items).
     */
    @Column("audience_geo")
    private String audienceGeo;

    /**
     * Default constructor for R2DBC mapping.
     */
    public DigestPersona() {
        this.enabled = false;
        this.personaStyle = DigestPersonaStyle.PROFESSIONAL.name();
        this.scheduleTimezone = "UTC";
        this.lookbackHours = 24;
        this.maxMessages = 10;
        this.language = "en";
        this.minClusterSize = 2;
        this.minImportanceScore = 0.0;
        this.sourceTrustThreshold = 0.0;
        this.temperature = 0.7;
        this.maxTokens = 1000;
        this.totalDigestsPublished = 0;
        this.publishMode = "DIGEST";
        this.randomDelayMaxMinutes = 0;
    }

    /**
     * Constructor with required fields.
     *
     * @param name persona name
     * @param botId bot user ID
     * @param targetChannelId target channel ID
     */
    public DigestPersona(String name, Long botId, Long targetChannelId) {
        this();
        this.name = name;
        this.botId = botId;
        this.targetChannelId = targetChannelId;
    }

    public Long id() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String description() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Long botId() {
        return botId;
    }

    public void setBotId(Long botId) {
        this.botId = botId;
    }

    public Long targetChannelId() {
        return targetChannelId;
    }

    public void setTargetChannelId(Long targetChannelId) {
        this.targetChannelId = targetChannelId;
    }

    public Boolean enabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public String personaStyle() {
        return personaStyle;
    }

    public void setPersonaStyle(String personaStyle) {
        this.personaStyle = personaStyle;
    }

    /**
     * Returns the persona style as enum.
     *
     * @return the persona style enum value
     */
    public DigestPersonaStyle personaStyleEnum() {
        if (personaStyle == null) {
            return DigestPersonaStyle.PROFESSIONAL;
        }
        try {
            return DigestPersonaStyle.valueOf(personaStyle);
        } catch (IllegalArgumentException e) {
            return DigestPersonaStyle.PROFESSIONAL;
        }
    }

    public String customSystemPrompt() {
        return customSystemPrompt;
    }

    public void setCustomSystemPrompt(String customSystemPrompt) {
        this.customSystemPrompt = customSystemPrompt;
    }

    public String scheduleCron() {
        return scheduleCron;
    }

    public void setScheduleCron(String scheduleCron) {
        this.scheduleCron = scheduleCron;
    }

    public String scheduleTimezone() {
        return scheduleTimezone;
    }

    public void setScheduleTimezone(String scheduleTimezone) {
        this.scheduleTimezone = scheduleTimezone;
    }

    public LocalTime activeHoursStart() {
        return activeHoursStart;
    }

    public void setActiveHoursStart(LocalTime activeHoursStart) {
        this.activeHoursStart = activeHoursStart;
    }

    public LocalTime activeHoursEnd() {
        return activeHoursEnd;
    }

    public void setActiveHoursEnd(LocalTime activeHoursEnd) {
        this.activeHoursEnd = activeHoursEnd;
    }

    public Integer lookbackHours() {
        return lookbackHours;
    }

    public void setLookbackHours(Integer lookbackHours) {
        this.lookbackHours = lookbackHours;
    }

    public Integer maxMessages() {
        return maxMessages;
    }

    public void setMaxMessages(Integer maxMessages) {
        this.maxMessages = maxMessages;
    }

    public String language() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public Integer minClusterSize() {
        return minClusterSize;
    }

    public void setMinClusterSize(Integer minClusterSize) {
        this.minClusterSize = minClusterSize;
    }

    public Double minImportanceScore() {
        return minImportanceScore;
    }

    public void setMinImportanceScore(Double minImportanceScore) {
        this.minImportanceScore = minImportanceScore;
    }

    public Double sourceTrustThreshold() {
        return sourceTrustThreshold;
    }

    public void setSourceTrustThreshold(Double sourceTrustThreshold) {
        this.sourceTrustThreshold = sourceTrustThreshold;
    }

    public Long[] excludedChannelIds() {
        return excludedChannelIds;
    }

    public void setExcludedChannelIds(Long[] excludedChannelIds) {
        this.excludedChannelIds = excludedChannelIds;
    }

    public String[] topicKeywords() {
        return topicKeywords;
    }

    public void setTopicKeywords(String[] topicKeywords) {
        this.topicKeywords = topicKeywords;
    }

    public String[] negativeKeywords() {
        return negativeKeywords;
    }

    public void setNegativeKeywords(String[] negativeKeywords) {
        this.negativeKeywords = negativeKeywords;
    }

    public String modelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public Double temperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Integer maxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public Instant lastRunAt() {
        return lastRunAt;
    }

    public void setLastRunAt(Instant lastRunAt) {
        this.lastRunAt = lastRunAt;
    }

    public String lastPublishedDigestId() {
        return lastPublishedDigestId;
    }

    public void setLastPublishedDigestId(String lastPublishedDigestId) {
        this.lastPublishedDigestId = lastPublishedDigestId;
    }

    public Integer totalDigestsPublished() {
        return totalDigestsPublished;
    }

    public void setTotalDigestsPublished(Integer totalDigestsPublished) {
        this.totalDigestsPublished = totalDigestsPublished;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String publishMode() {
        return publishMode;
    }

    public void setPublishMode(String publishMode) {
        this.publishMode = publishMode;
    }

    public Integer randomDelayMaxMinutes() {
        return randomDelayMaxMinutes;
    }

    public void setRandomDelayMaxMinutes(Integer randomDelayMaxMinutes) {
        this.randomDelayMaxMinutes = randomDelayMaxMinutes;
    }

    /**
     * Returns the audience geo scope (RU/UA/KZ/BY/GLOBAL).
     * Falls back to "GLOBAL" when not set, so callers may always treat this as non-null.
     */
    public String audienceGeo() {
        return audienceGeo != null ? audienceGeo : "GLOBAL";
    }

    public void setAudienceGeo(String audienceGeo) {
        this.audienceGeo = audienceGeo;
    }

    /**
     * Increments the total published count.
     */
    public void incrementPublishedCount() {
        if (this.totalDigestsPublished == null) {
            this.totalDigestsPublished = 0;
        }
        this.totalDigestsPublished++;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DigestPersona that = (DigestPersona) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return String.format(
            "DigestPersona[id=%d, name=%s, botId=%d, targetChannelId=%d, enabled=%s, style=%s]",
            id,
            name,
            botId,
            targetChannelId,
            enabled,
            personaStyle
        );
    }
}
