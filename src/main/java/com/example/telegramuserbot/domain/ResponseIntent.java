package com.example.telegramuserbot.domain;

/**
 * Represents different types of response intents for variation templates.
 * Used to categorize responses and ensure appropriate humanization.
 */
public enum ResponseIntent {
    
    // Common response types
    GREETING("greeting", "Greeting responses"),
    ACKNOWLEDGMENT("acknowledgment", "Acknowledgment responses"),
    CLARIFICATION("clarification", "Clarification requests"),
    INFORMATION("information", "Information providing responses"),
    OPINION("opinion", "Opinion expressions"),
    TRANSITION("transition", "Topic transition responses"),
    
    // Anti-detection specific
    BOT_DENIAL("bot_denial", "Responses to 'Are you a bot?' questions"),
    DEFLECTION("deflection", "Deflecting suspicious questions"),
    CASUAL_CHAT("casual_chat", "Casual conversation responses"),
    
    // Emotional responses
    EMPATHY("empathy", "Empathetic responses"),
    HUMOR("humor", "Humorous responses"),
    CONFUSION("confusion", "Confusion expressions"),
    ENTHUSIASM("enthusiasm", "Enthusiastic responses"),
    
    // Functional responses
    ERROR_HANDLING("error_handling", "Error explanation responses"),
    WAIT_REQUEST("wait_request", "Request for patience"),
    THINKING("thinking", "Thinking/processing indicators"),
    
    // Conversation management
    TOPIC_CHANGE("topic_change", "Topic change responses"),
    MEMORY_REFERENCE("memory_reference", "References to past conversations"),
    FOLLOW_UP("follow_up", "Follow-up questions"),
    
    // Default
    GENERAL("general", "General purpose responses");
    
    private final String code;
    private final String description;
    
    ResponseIntent(String code, String description) {
        this.code = code;
        this.description = description;
    }
    
    public String getCode() {
        return code;
    }
    
    public String getDescription() {
        return description;
    }
    
    /**
     * Get ResponseIntent from code string
     */
    public static ResponseIntent fromCode(String code) {
        for (ResponseIntent intent : values()) {
            if (intent.code.equals(code)) {
                return intent;
            }
        }
        return GENERAL;
    }
    
    /**
     * Check if this intent is anti-detection related
     */
    public boolean isAntiDetection() {
        return this == BOT_DENIAL || this == DEFLECTION;
    }
    
    /**
     * Check if this intent is emotional
     */
    public boolean isEmotional() {
        return this == EMPATHY || this == HUMOR || this == CONFUSION || this == ENTHUSIASM;
    }
}