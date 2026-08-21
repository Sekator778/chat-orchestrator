package com.example.telegramuserbot.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * Domain entity mapping {@code bot.web_sources}.
 *
 * <p>Each row represents one RSS/Atom feed endpoint whose articles are harvested by
 * {@link com.example.telegramuserbot.service.web.WebNewsCollectorService} into
 * {@code bot.messages} as first-class news rows (type {@link MessageType#WEB_NEWS}).
 *
 * <p>The {@code synthetic_channel_id} is a stable negative long (band {@code -9000000001...-9999999999})
 * that identifies the outlet as a {@code tgscan.channels} row pre-seeded by migration T1.
 * Using this as the {@code chat_id} in {@code bot.messages} lets the existing ranking brain
 * ({@code fn_recompute_importance}) and candidate query join it exactly like a real TG channel.
 */
@Table(name = "web_sources", schema = "bot")
public class WebSource {

    @Id
    private Long id;

    @Column("outlet_name")
    private String outletName;

    @Column("feed_url")
    private String feedUrl;

    @Column("registrable_domain")
    private String registrableDomain;

    @Column("synthetic_channel_id")
    private Long syntheticChannelId;

    @Column("trust")
    private Double trust;

    @Column("geo")
    private String geo;

    @Column("enabled")
    private Boolean enabled;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;

    // -----------------------------------------------------------------------
    // Getters and setters
    // -----------------------------------------------------------------------

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getOutletName() { return outletName; }
    public void setOutletName(String outletName) { this.outletName = outletName; }

    public String getFeedUrl() { return feedUrl; }
    public void setFeedUrl(String feedUrl) { this.feedUrl = feedUrl; }

    public String getRegistrableDomain() { return registrableDomain; }
    public void setRegistrableDomain(String registrableDomain) { this.registrableDomain = registrableDomain; }

    public Long getSyntheticChannelId() { return syntheticChannelId; }
    public void setSyntheticChannelId(Long syntheticChannelId) { this.syntheticChannelId = syntheticChannelId; }

    public Double getTrust() { return trust; }
    public void setTrust(Double trust) { this.trust = trust; }

    public String getGeo() { return geo; }
    public void setGeo(String geo) { this.geo = geo; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public String toString() {
        return "WebSource{id=" + id + ", outletName='" + outletName + "', feedUrl='" + feedUrl
                + "', syntheticChannelId=" + syntheticChannelId + ", enabled=" + enabled + '}';
    }
}
