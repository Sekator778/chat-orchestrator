package com.example.telegramuserbot.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Tracks user communication patterns for behavioral mimicry.
 * Analyzes and stores user's communication style for adaptation.
 */
@Table("user_communication_profiles")
public class UserCommunicationProfile {

    @Id
    private Long id;

    @Column("user_id")
    private Long userId;

    // Communication style analysis
    @Column("avg_message_length")
    private Integer avgMessageLength;

    @Column("formality_level")
    private Integer formalityLevel; // 1-5 scale

    @Column("emoticon_usage_frequency")
    private Double emoticonUsageFrequency; // 0.0-1.0

    @Column("punctuation_style")
    private String punctuationStyle; // minimal, standard, excessive

    @Column("response_speed_preference")
    private String responseSpeedPreference; // quick, moderate, thoughtful

    @Column("vocabulary_complexity")
    private Integer vocabularyComplexity; // 1-5 scale

    @Column("uses_slang")
    private Boolean usesSlang = false;

    @Column("uses_abbreviations")
    private Boolean usesAbbreviations = false;

    @Column("typical_greeting_style")
    private String typicalGreetingStyle;

    // Temporal patterns
    @Column("most_active_time")
    private String mostActiveTime; // morning, afternoon, evening, night

    @Column("conversation_length_preference")
    private String conversationLengthPreference; // brief, moderate, extended

    // Emotional patterns
    @Column("emotional_expressiveness")
    private Integer emotionalExpressiveness; // 1-5 scale

    @Column("humor_appreciation")
    private Boolean humorAppreciation = true;

    @Column("prefers_direct_communication")
    private Boolean prefersDirectCommunication = false;

    // Learning metadata
    @Column("message_sample_count")
    private Long messageSampleCount = 0L;

    @Column("confidence_score")
    private Double confidenceScore = 0.0; // 0.0-1.0, how confident we are in the profile

    @Column("last_updated_at")
    private LocalDateTime lastUpdatedAt = LocalDateTime.now();

    @Column("created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    // Constructors
    public UserCommunicationProfile() {}

    public UserCommunicationProfile(Long userId) {
        this.userId = userId;
    }

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public Integer getAvgMessageLength() { return avgMessageLength; }
    public void setAvgMessageLength(Integer avgMessageLength) { this.avgMessageLength = avgMessageLength; }

    public Integer getFormalityLevel() { return formalityLevel; }
    public void setFormalityLevel(Integer formalityLevel) { this.formalityLevel = formalityLevel; }

    public Double getEmoticonUsageFrequency() { return emoticonUsageFrequency; }
    public void setEmoticonUsageFrequency(Double emoticonUsageFrequency) { this.emoticonUsageFrequency = emoticonUsageFrequency; }

    public String getPunctuationStyle() { return punctuationStyle; }
    public void setPunctuationStyle(String punctuationStyle) { this.punctuationStyle = punctuationStyle; }

    public String getResponseSpeedPreference() { return responseSpeedPreference; }
    public void setResponseSpeedPreference(String responseSpeedPreference) { this.responseSpeedPreference = responseSpeedPreference; }

    public Integer getVocabularyComplexity() { return vocabularyComplexity; }
    public void setVocabularyComplexity(Integer vocabularyComplexity) { this.vocabularyComplexity = vocabularyComplexity; }

    public Boolean getUsesSlang() { return usesSlang; }
    public void setUsesSlang(Boolean usesSlang) { this.usesSlang = usesSlang; }

    public Boolean getUsesAbbreviations() { return usesAbbreviations; }
    public void setUsesAbbreviations(Boolean usesAbbreviations) { this.usesAbbreviations = usesAbbreviations; }

    public String getTypicalGreetingStyle() { return typicalGreetingStyle; }
    public void setTypicalGreetingStyle(String typicalGreetingStyle) { this.typicalGreetingStyle = typicalGreetingStyle; }

    public String getMostActiveTime() { return mostActiveTime; }
    public void setMostActiveTime(String mostActiveTime) { this.mostActiveTime = mostActiveTime; }

    public String getConversationLengthPreference() { return conversationLengthPreference; }
    public void setConversationLengthPreference(String conversationLengthPreference) { this.conversationLengthPreference = conversationLengthPreference; }

    public Integer getEmotionalExpressiveness() { return emotionalExpressiveness; }
    public void setEmotionalExpressiveness(Integer emotionalExpressiveness) { this.emotionalExpressiveness = emotionalExpressiveness; }

    public Boolean getHumorAppreciation() { return humorAppreciation; }
    public void setHumorAppreciation(Boolean humorAppreciation) { this.humorAppreciation = humorAppreciation; }

    public Boolean getPrefersDirectCommunication() { return prefersDirectCommunication; }
    public void setPrefersDirectCommunication(Boolean prefersDirectCommunication) { this.prefersDirectCommunication = prefersDirectCommunication; }

    public Long getMessageSampleCount() { return messageSampleCount; }
    public void setMessageSampleCount(Long messageSampleCount) { this.messageSampleCount = messageSampleCount; }

    public Double getConfidenceScore() { return confidenceScore; }
    public void setConfidenceScore(Double confidenceScore) { this.confidenceScore = confidenceScore; }

    public LocalDateTime getLastUpdatedAt() { return lastUpdatedAt; }
    public void setLastUpdatedAt(LocalDateTime lastUpdatedAt) { this.lastUpdatedAt = lastUpdatedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    /**
     * Update profile with new message analysis
     */
    public void updateFromMessage(String messageText) {
        // Update message sample count
        this.messageSampleCount++;

        // Update average message length
        if (this.avgMessageLength == null) {
            this.avgMessageLength = messageText.length();
        } else {
            this.avgMessageLength = (int) ((this.avgMessageLength * (messageSampleCount - 1) + messageText.length()) / messageSampleCount);
        }

        // Update last updated timestamp
        this.lastUpdatedAt = LocalDateTime.now();

        // Recalculate confidence score based on sample size
        this.confidenceScore = Math.min(1.0, messageSampleCount / 50.0); // Full confidence after 50 messages
    }

    /**
     * Check if profile has sufficient data for reliable adaptation
     */
    public boolean isReliable() {
        return messageSampleCount >= 10 && confidenceScore >= 0.2;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UserCommunicationProfile that = (UserCommunicationProfile) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "UserCommunicationProfile{" +
                "id=" + id +
                ", userId=" + userId +
                ", avgMessageLength=" + avgMessageLength +
                ", formalityLevel=" + formalityLevel +
                ", confidenceScore=" + confidenceScore +
                '}';
    }
}