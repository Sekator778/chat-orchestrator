package com.example.telegramuserbot.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;

/**
 * Represents a point-in-time health snapshot of the scoring and publishing pipeline.
 * Stored in bot.pipeline_snapshots for trend analysis and anomaly detection.
 */
@Table(schema = "bot", name = "pipeline_snapshots")
public class PipelineSnapshot {

    @Id
    private Long id;

    @Column("snapshotted_at")
    private OffsetDateTime snapshottedAt;

    @Column("period_hours")
    private int periodHours;

    @Column("messages_ingested")
    private long messagesIngested;

    @Column("scored_fresh")
    private long scoredFresh;

    @Column("top_score")
    private Double topScore;

    @Column("min_nonzero_score")
    private Double minNonzeroScore;

    @Column("published_today")
    private long publishedToday;

    @Column("personas_published")
    private int personasPublished;

    @Column("top_messages_preview")
    private String topMessagesPreview;

    @Column("anomaly")
    private boolean anomaly;

    @Column("anomaly_reasons")
    private String[] anomalyReasons;

    /**
     * Default constructor required by R2DBC.
     */
    public PipelineSnapshot() {
    }

    /**
     * Creates a snapshot with a specific period.
     *
     * @param periodHours lookback window for "fresh" metrics
     */
    public PipelineSnapshot(int periodHours) {
        this.snapshottedAt = OffsetDateTime.now();
        this.periodHours = periodHours;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public OffsetDateTime getSnapshottedAt() {
        return snapshottedAt;
    }

    public void setSnapshottedAt(OffsetDateTime snapshottedAt) {
        this.snapshottedAt = snapshottedAt;
    }

    public int getPeriodHours() {
        return periodHours;
    }

    public void setPeriodHours(int periodHours) {
        this.periodHours = periodHours;
    }

    public long getMessagesIngested() {
        return messagesIngested;
    }

    public void setMessagesIngested(long messagesIngested) {
        this.messagesIngested = messagesIngested;
    }

    public long getScoredFresh() {
        return scoredFresh;
    }

    public void setScoredFresh(long scoredFresh) {
        this.scoredFresh = scoredFresh;
    }

    public Double getTopScore() {
        return topScore;
    }

    public void setTopScore(Double topScore) {
        this.topScore = topScore;
    }

    public Double getMinNonzeroScore() {
        return minNonzeroScore;
    }

    public void setMinNonzeroScore(Double minNonzeroScore) {
        this.minNonzeroScore = minNonzeroScore;
    }

    public long getPublishedToday() {
        return publishedToday;
    }

    public void setPublishedToday(long publishedToday) {
        this.publishedToday = publishedToday;
    }

    public int getPersonasPublished() {
        return personasPublished;
    }

    public void setPersonasPublished(int personasPublished) {
        this.personasPublished = personasPublished;
    }

    public String getTopMessagesPreview() {
        return topMessagesPreview;
    }

    public void setTopMessagesPreview(String topMessagesPreview) {
        this.topMessagesPreview = topMessagesPreview;
    }

    public boolean isAnomaly() {
        return anomaly;
    }

    public void setAnomaly(boolean anomaly) {
        this.anomaly = anomaly;
    }

    public String[] getAnomalyReasons() {
        return anomalyReasons;
    }

    public void setAnomalyReasons(String[] anomalyReasons) {
        this.anomalyReasons = anomalyReasons;
    }

    @Override
    public String toString() {
        return "PipelineSnapshot{"
                + "id=" + id
                + ", snapshottedAt=" + snapshottedAt
                + ", messagesIngested=" + messagesIngested
                + ", topScore=" + topScore
                + ", publishedToday=" + publishedToday
                + ", anomaly=" + anomaly
                + '}';
    }
}
