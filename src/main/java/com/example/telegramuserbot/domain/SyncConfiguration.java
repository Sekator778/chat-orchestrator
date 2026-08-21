package com.example.telegramuserbot.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Per-chat configuration for history synchronization.
 * Defines default sync depth and other sync-related settings for each channel.
 */
@Table("sync_configurations")
public class SyncConfiguration {

    @Id
    private Long id;

    @Column("channel_id")
    private Long channelId;

    /**
     * Default sync depth in days for this channel.
     * Null means no default configured.
     */
    @Column("default_sync_depth_days")
    private Integer defaultSyncDepthDays;

    /**
     * Maximum allowed sync depth for this channel to prevent abuse.
     */
    @Column("max_sync_depth_days")
    private Integer maxSyncDepthDays = 3; // Default: 1 year max

    /**
     * Whether automatic periodic sync is enabled for this channel.
     */
    @Column("auto_sync_enabled")
    private boolean autoSyncEnabled = false;

    /**
     * How often to perform automatic sync (in days).
     */
    @Column("auto_sync_interval_days")
    private Integer autoSyncIntervalDays = 7; // Default: weekly

    /**
     * Last time an automatic sync was performed.
     */
    @Column("last_auto_sync_at")
    private LocalDateTime lastAutoSyncAt;

    /**
     * Maximum number of concurrent sync jobs allowed for this channel.
     */
    @Column("max_concurrent_syncs")
    private Integer maxConcurrentSyncs = 1;

    @Column("created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column("updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    @Column("bot_instance_id")
    private String botInstanceId;

    // Constructors
    public SyncConfiguration() {}

    public SyncConfiguration(Long channelId) {
        this.channelId = channelId;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getChannelId() { return channelId; }
    public void setChannelId(Long channelId) { this.channelId = channelId; }

    public Integer getDefaultSyncDepthDays() { return defaultSyncDepthDays; }
    public void setDefaultSyncDepthDays(Integer defaultSyncDepthDays) { this.defaultSyncDepthDays = defaultSyncDepthDays; }

    public Integer getMaxSyncDepthDays() { return maxSyncDepthDays; }
    public void setMaxSyncDepthDays(Integer maxSyncDepthDays) { this.maxSyncDepthDays = maxSyncDepthDays; }

    public boolean isAutoSyncEnabled() { return autoSyncEnabled; }
    public void setAutoSyncEnabled(boolean autoSyncEnabled) { this.autoSyncEnabled = autoSyncEnabled; }

    public Integer getAutoSyncIntervalDays() { return autoSyncIntervalDays; }
    public void setAutoSyncIntervalDays(Integer autoSyncIntervalDays) { this.autoSyncIntervalDays = autoSyncIntervalDays; }

    public LocalDateTime getLastAutoSyncAt() { return lastAutoSyncAt; }
    public void setLastAutoSyncAt(LocalDateTime lastAutoSyncAt) { this.lastAutoSyncAt = lastAutoSyncAt; }

    public Integer getMaxConcurrentSyncs() { return maxConcurrentSyncs; }
    public void setMaxConcurrentSyncs(Integer maxConcurrentSyncs) { this.maxConcurrentSyncs = maxConcurrentSyncs; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public String getBotInstanceId() { return botInstanceId; }
    public void setBotInstanceId(String botInstanceId) { this.botInstanceId = botInstanceId; }

    /**
     * Validates if the given sync depth is allowed for this channel.
     */
    public boolean isValidSyncDepth(Integer syncDepthDays) {
        if (syncDepthDays == null || syncDepthDays <= 0) {
            return false;
        }
        return maxSyncDepthDays == null || syncDepthDays <= maxSyncDepthDays;
    }

    /**
     * Gets the effective sync depth to use - uses default if available, otherwise a sensible default.
     */
    public Integer getEffectiveSyncDepth(Integer requestedDepth) {
        if (requestedDepth != null && isValidSyncDepth(requestedDepth)) {
            return requestedDepth;
        }
        return defaultSyncDepthDays != null ? defaultSyncDepthDays : 0; // 30 days default
    }

    /**
     * Checks if automatic sync is due based on interval and last sync time.
     */
    public boolean isAutoSyncDue() {
        if (!autoSyncEnabled || autoSyncIntervalDays == null) {
            return false;
        }
        if (lastAutoSyncAt == null) {
            return true; // Never synced, so due
        }
        return lastAutoSyncAt.plusDays(autoSyncIntervalDays).isBefore(LocalDateTime.now());
    }

    /**
     * Updates the last auto sync timestamp to now.
     */
    public void markAutoSyncCompleted() {
        this.lastAutoSyncAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SyncConfiguration that = (SyncConfiguration) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "SyncConfiguration{" +
                "id=" + id +
                ", channelId=" + channelId +
                ", defaultSyncDepthDays=" + defaultSyncDepthDays +
                ", maxSyncDepthDays=" + maxSyncDepthDays +
                ", autoSyncEnabled=" + autoSyncEnabled +
                '}';
    }
}
