package com.example.telegramuserbot.domain;

public enum ActionType {
    IGNORE("ignore", "Ignore messages matching this restriction"),
    CUSTOM_RESPONSE("custom_response", "Send a custom response instead"),
    REDIRECT("redirect", "Redirect to another topic"),
    MODERATE("moderate", "Apply content moderation"),
    LOG_ONLY("log_only", "Log the attempt but don't respond"),
    ESCALATE("escalate", "Escalate to human moderator");

    private final String code;
    private final String description;

    ActionType(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static ActionType fromCode(String code) {
        if (code == null) return IGNORE;
        
        for (ActionType type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        return IGNORE;
    }
}