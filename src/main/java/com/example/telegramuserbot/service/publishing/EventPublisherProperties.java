package com.example.telegramuserbot.service.publishing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for Event Publisher service.
 * Controls polling behavior and processing limits.
 */
@Component
@ConfigurationProperties(prefix = "events.publisher")
public final class EventPublisherProperties {

    private boolean enabled = true;
    private int pollIntervalMs = 5000; // 5 seconds (see tasks_and_manuals/events_and_alerts_pipeline.md)
    private int batchSize = 10;

    /**
     * Default constructor for Spring configuration.
     */
    public EventPublisherProperties() {
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

    public int batchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    @Override
    public String toString() {
        return String.format(
            "EventPublisherProperties[enabled=%s, pollIntervalMs=%d, batchSize=%d]",
            enabled, pollIntervalMs, batchSize
        );
    }
}
