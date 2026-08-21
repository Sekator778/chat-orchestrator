package com.example.telegramuserbot.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Represents a Telegram channel in the tgscan schema.
 * This entity stores channel metadata, ranking scores, and lifecycle information
 * for channels discovered and monitored by the Python discovery service
 * and ingested by the Java real-time listener.
 */
@Table(name = "channels", schema = "tgscan")
public final class Channel implements Persistable<Long> {
    @Id
    @Column("id")
    private Long chatId;
    private String username;
    private String title;
    private String description;
    @Column("bot_instance_id")
    private List<String> botInstanceIds;
    @Column("sample_message")
    private String sampleMessage;
    private Long subscribers;
    @Column("raw_keyword_score")
    private Double rawKeywordScore;
    @Column("channel_score")
    private Double channelScore;
    @Column("score_activity")
    private Double scoreActivity;
    @Column("score_influence")
    private Double scoreInfluence;
    @Column("score_relevance")
    private Double scoreRelevance;
    private Double weight;
    @Column("join_status")
    private String joinStatus;
    @Column("join_attempts")
    private Integer joinAttempts;
    @Column("joined_at")
    private Instant joinedAt;
    @Column("mute_status")
    private String muteStatus;
    @Column("last_seen")
    private Instant lastSeen;
    @Column("last_ingestion_attempt_at")
    private Instant lastIngestionAttemptAt;
    @Column("is_channel")
    private Boolean isChannel;
    @Column("can_send_messages")
    private Boolean canSendMessages;
    @Transient
    private boolean newEntity;

    public Channel() {
    }

    public Channel markNew() {
        this.newEntity = true;
        return this;
    }

    public Channel markPersisted() {
        this.newEntity = false;
        return this;
    }

    @Override
    public Long getId() {
        return chatId;
    }

    public void setId(Long id) {
        this.chatId = id;
    }

    public Long getChatId() {
        return chatId;
    }

    public void setChatId(Long chatId) {
        this.chatId = chatId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<String> getBotInstanceIds() {
        return botInstanceIds;
    }

    public void setBotInstanceIds(List<String> botInstanceIds) {
        this.botInstanceIds = botInstanceIds;
    }

    // Backward-compatible accessors
    public String getBotInstanceId() {
        return botInstanceIds == null || botInstanceIds.isEmpty() ? null : botInstanceIds.get(0);
    }

    public void setBotInstanceId(String botInstanceId) {
        this.botInstanceIds = botInstanceId == null ? null : List.of(botInstanceId);
    }

    public void addBotInstanceId(String botInstanceId) {
        if (botInstanceId == null || botInstanceId.isBlank()) {
            return;
        }
        if (botInstanceIds == null || botInstanceIds.isEmpty()) {
            botInstanceIds = List.of(botInstanceId);
            return;
        }
        if (!botInstanceIds.contains(botInstanceId)) {
            var mutable = new java.util.ArrayList<>(botInstanceIds);
            mutable.add(botInstanceId);
            botInstanceIds = mutable;
        }
    }

    public String getSampleMessage() {
        return sampleMessage;
    }

    public void setSampleMessage(String sampleMessage) {
        this.sampleMessage = sampleMessage;
    }

    public Long getSubscribers() {
        return subscribers;
    }

    public void setSubscribers(Long subscribers) {
        this.subscribers = subscribers;
    }

    public Double getRawKeywordScore() { return rawKeywordScore; }
    public void setRawKeywordScore(Double rawKeywordScore) { this.rawKeywordScore = rawKeywordScore; }

    public Double getChannelScore() { return channelScore; }
    public void setChannelScore(Double channelScore) { this.channelScore = channelScore; }

    public Double getScoreActivity() { return scoreActivity; }
    public void setScoreActivity(Double scoreActivity) { this.scoreActivity = scoreActivity; }

    public Double getScoreInfluence() { return scoreInfluence; }
    public void setScoreInfluence(Double scoreInfluence) { this.scoreInfluence = scoreInfluence; }

    public Double getScoreRelevance() { return scoreRelevance; }
    public void setScoreRelevance(Double scoreRelevance) { this.scoreRelevance = scoreRelevance; }

    public Double getScore() { return channelScore; }
    public void setScore(Double score) { this.channelScore = score; }

    public Double getWeight() {
        return weight;
    }

    public void setWeight(Double weight) {
        this.weight = weight;
    }

    public String getJoinStatus() {
        return joinStatus;
    }

    public void setJoinStatus(String joinStatus) {
        this.joinStatus = joinStatus;
    }

    public Integer getJoinAttempts() {
        return joinAttempts;
    }

    public void setJoinAttempts(Integer joinAttempts) {
        this.joinAttempts = joinAttempts;
    }

    public Instant getJoinedAt() {
        return joinedAt;
    }

    public void setJoinedAt(Instant joinedAt) {
        this.joinedAt = joinedAt;
    }

    public String getMuteStatus() {
        return muteStatus;
    }

    public void setMuteStatus(String muteStatus) {
        this.muteStatus = muteStatus;
    }

    public Instant getLastSeen() {
        return lastSeen;
    }

    public void setLastSeen(Instant lastSeen) {
        this.lastSeen = lastSeen;
    }

    public Instant getLastIngestionAttemptAt() {
        return lastIngestionAttemptAt;
    }

    public void setLastIngestionAttemptAt(Instant lastIngestionAttemptAt) {
        this.lastIngestionAttemptAt = lastIngestionAttemptAt;
    }

    public Boolean isChannel() {
        return isChannel;
    }

    public void setChannel(Boolean channel) {
        isChannel = channel;
    }

    public Boolean getCanSendMessages() {
        return canSendMessages;
    }

    public void setCanSendMessages(Boolean canSendMessages) {
        this.canSendMessages = canSendMessages;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Channel channel = (Channel) o;
        return Objects.equals(chatId, channel.chatId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(chatId);
    }

    @Override
    public boolean isNew() {
        return newEntity || chatId == null;
    }
}
