package com.example.telegramuserbot.domain;

public enum RestrictionType {
    FORBIDDEN("forbidden", "Topics that should never be discussed"),
    ALLOWED_ONLY("allowed_only", "Only these topics are allowed"),
    MODERATED("moderated", "Topics that require careful handling"),
    TIME_RESTRICTED("time_restricted", "Topics restricted to certain times"),
    USER_RESTRICTED("user_restricted", "Topics restricted to certain users"),
    CONTEXT_DEPENDENT("context_dependent", "Topics allowed only in specific contexts");

    private final String code;
    private final String description;

    RestrictionType(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static RestrictionType fromCode(String code) {
        if (code == null) return FORBIDDEN;
        
        for (RestrictionType type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        return FORBIDDEN;
    }
}