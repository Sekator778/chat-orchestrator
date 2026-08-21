package com.example.telegramuserbot.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.Objects;

/**
 * Represents source trust scores for channels.
 * Used to weight message importance based on channel credibility.
 */
@Table(name = "source_trust", schema = "tgscan")
public final class SourceTrust implements Persistable<Long> {
    @Id
    @Column("channel_id")
    private Long channelId;
    @Column("trust_score")
    private Double trustScore;
    @Column("is_official")
    private Boolean isOfficial;
    @Column("category")
    private String category;
    @Column("manual_override")
    private Boolean manualOverride;
    @Column("created_at")
    private Instant createdAt;
    @Column("last_updated")
    private Instant lastUpdated;
    @Transient
    private boolean newEntity;

    public SourceTrust() {
        this.trustScore = 0.5;
        this.isOfficial = false;
        this.manualOverride = false;
    }

    public SourceTrust(Long channelId, Double trustScore, String category) {
        this();
        this.channelId = channelId;
        this.trustScore = trustScore;
        this.category = category;
    }

    public SourceTrust markNew() {
        this.newEntity = true;
        return this;
    }

    public SourceTrust markPersisted() {
        this.newEntity = false;
        return this;
    }

    @Override
    public Long getId() {
        return channelId;
    }

    @Override
    public boolean isNew() {
        return newEntity || channelId == null;
    }

    public Long getChannelId() {
        return channelId;
    }

    public void setChannelId(Long channelId) {
        this.channelId = channelId;
    }

    public Double getTrustScore() {
        return trustScore;
    }

    public void setTrustScore(Double trustScore) {
        this.trustScore = trustScore;
    }

    public Boolean getIsOfficial() {
        return isOfficial;
    }

    public void setIsOfficial(Boolean official) {
        isOfficial = official;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public Boolean getManualOverride() {
        return manualOverride;
    }

    public void setManualOverride(Boolean manualOverride) {
        this.manualOverride = manualOverride;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(Instant lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SourceTrust that = (SourceTrust) o;
        return Objects.equals(channelId, that.channelId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(channelId);
    }

    @Override
    public String toString() {
        return "SourceTrust{" +
                "channelId=" + channelId +
                ", trustScore=" + trustScore +
                ", category='" + category + '\'' +
                ", isOfficial=" + isOfficial +
                '}';
    }
}
