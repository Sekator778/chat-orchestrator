package com.example.telegramuserbot.service.events;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for Event Watcher service.
 * Controls polling behavior, thresholds, and processing limits.
 */
@Component
@ConfigurationProperties(prefix = "events.watcher")
public final class EventWatcherProperties {

    private boolean enabled = true;
    private int pollIntervalMs = 30000; // 30 seconds
    private double minConfidence = 0.5;
    private String minSeverity = "medium";
    private int batchSize = 10;
    private int ttlMinutes = 10; // Deduplication TTL

    /**
     * Default constructor for Spring configuration.
     */
    public EventWatcherProperties() {
    }

    public boolean enabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int pollIntervalMs() {
        return pollIntervalMs;
    }

    public void setPollIntervalMs(int pollIntervalMs) {
        this.pollIntervalMs = pollIntervalMs;
    }

    public double minConfidence() {
        return minConfidence;
    }

    public void setMinConfidence(double minConfidence) {
        this.minConfidence = minConfidence;
    }

    public String minSeverity() {
        return minSeverity;
    }

    public void setMinSeverity(String minSeverity) {
        this.minSeverity = minSeverity;
    }

    public int batchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int ttlMinutes() {
        return ttlMinutes;
    }

    public void setTtlMinutes(int ttlMinutes) {
        this.ttlMinutes = ttlMinutes;
    }

    @Override
    public String toString() {
        return String.format(
            "EventWatcherProperties[enabled=%s, pollIntervalMs=%d, minConfidence=%.2f, minSeverity=%s, batchSize=%d, ttlMinutes=%d]",
            enabled, pollIntervalMs, minConfidence, minSeverity, batchSize, ttlMinutes
        );
    }
}
