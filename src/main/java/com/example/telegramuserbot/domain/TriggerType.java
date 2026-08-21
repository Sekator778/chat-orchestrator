package com.example.telegramuserbot.domain;

public enum TriggerType {
    KEYWORD_MATCH("keyword_match", "Triggered by specific keywords in messages"),
    MENTION_ONLY("mention_only", "Only when bot is mentioned or replied to"),
    TIME_BASED("time_based", "Triggered after a time delay"),
    RANDOM("random", "Random probability-based triggering"),
    MESSAGE_COUNT("message_count", "Triggered after N messages in chat"),
    USER_JOIN("user_join", "When new users join the chat"),
    MEDIA_SHARED("media_shared", "When media files are shared"),
    QUESTION_DETECTED("question_detected", "When questions are detected in messages"),
    SENTIMENT_BASED("sentiment_based", "Based on message sentiment analysis"),
    CONTINUOUS("continuous", "Responds to all messages (with rate limits)"),
    SCHEDULED("scheduled", "Scheduled responses at specific times"),
    CONTEXT_AWARE("context_aware", "Based on conversation context analysis"),
    NEGATIVE_REACTION("negative_reaction", "Triggered by user aggression or negative mood");

    private final String code;
    private final String description;

    TriggerType(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static TriggerType fromCode(String code) {
        if (code == null) return KEYWORD_MATCH;
        
        for (TriggerType type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        return KEYWORD_MATCH;
    }
}