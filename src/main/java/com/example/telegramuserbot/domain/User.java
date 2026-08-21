package com.example.telegramuserbot.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.Objects;

@Table("users")
public class User {
    @Id
    private Long id;

    @Column("telegram_user_id")
    private Long telegramUserId;

    @Column("first_name")
    private String firstName;

    @Column("last_name")
    private String lastName;

    @Column("username")
    private String username;

    @Column("preferred_name")
    private String preferredName;

    @Column("preferred_title")
    private String preferredTitle;

    @Column("communication_style")
    private CommunicationStyle communicationStyle = CommunicationStyle.CASUAL;

    @Column("personality_traits")
    private String personalityTraits;

    @Column("relationship_context")
    private String relationshipContext;

    @Column("language_preference")
    private String languagePreference = "uk";

    @Column("response_length")
    private ResponseLength responseLength = ResponseLength.MEDIUM;

    @Column("ai_enabled")
    private boolean aiEnabled = true;

    @Column("created_at")
    private Instant createdAt = Instant.now();

    @Column("updated_at")
    private Instant updatedAt;

    @Column("last_interaction_at")
    private Instant lastInteractionAt;

    // Constructors
    public User() {}

    public User(Long telegramUserId, String firstName, String lastName, String username) {
        this.telegramUserId = telegramUserId;
        this.firstName = firstName;
        this.lastName = lastName;
        this.username = username;
        this.createdAt = Instant.now();
        this.lastInteractionAt = Instant.now();
    }

    // Getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getTelegramUserId() { return telegramUserId; }
    public void setTelegramUserId(Long telegramUserId) { this.telegramUserId = telegramUserId; }

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPreferredName() { return preferredName; }
    public void setPreferredName(String preferredName) { this.preferredName = preferredName; }

    public String getPreferredTitle() { return preferredTitle; }
    public void setPreferredTitle(String preferredTitle) { this.preferredTitle = preferredTitle; }

    public CommunicationStyle getCommunicationStyle() { return communicationStyle; }
    public void setCommunicationStyle(CommunicationStyle communicationStyle) { this.communicationStyle = communicationStyle; }

    public String getPersonalityTraits() { return personalityTraits; }
    public void setPersonalityTraits(String personalityTraits) { this.personalityTraits = personalityTraits; }

    public String getRelationshipContext() { return relationshipContext; }
    public void setRelationshipContext(String relationshipContext) { this.relationshipContext = relationshipContext; }

    public String getLanguagePreference() { return languagePreference; }
    public void setLanguagePreference(String languagePreference) { this.languagePreference = languagePreference; }

    public ResponseLength getResponseLength() { return responseLength; }
    public void setResponseLength(ResponseLength responseLength) { this.responseLength = responseLength; }

    public boolean isAiEnabled() { return aiEnabled; }
    public void setAiEnabled(boolean aiEnabled) { this.aiEnabled = aiEnabled; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Instant getLastInteractionAt() { return lastInteractionAt; }
    public void setLastInteractionAt(Instant lastInteractionAt) { this.lastInteractionAt = lastInteractionAt; }

    // Helper methods
    public String getDisplayName() {
        if (preferredName != null && !preferredName.isBlank()) {
            return preferredName;
        }
        if (firstName != null && !firstName.isBlank()) {
            return firstName;
        }
        if (username != null && !username.isBlank()) {
            return "@" + username;
        }
        return "User#" + telegramUserId;
    }

    public String getFullDisplayName() {
        StringBuilder name = new StringBuilder();
        if (preferredTitle != null && !preferredTitle.isBlank()) {
            name.append(preferredTitle).append(" ");
        }
        name.append(getDisplayName());
        return name.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        User user = (User) o;
        return Objects.equals(telegramUserId, user.telegramUserId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(telegramUserId);
    }

    @Override
    public String toString() {
        return "User{" +
                "id=" + id +
                ", telegramUserId=" + telegramUserId +
                ", displayName='" + getDisplayName() + '\'' +
                ", communicationStyle=" + communicationStyle +
                ", aiEnabled=" + aiEnabled +
                '}' ;
    }
}