package com.example.telegramuserbot.domain;

public enum CommunicationStyle {
    FORMAL("formal", "Formal and respectful communication"),
    CASUAL("casual", "Casual and friendly communication"),
    PROFESSIONAL("professional", "Professional business-like communication"),
    FRIENDLY("friendly", "Warm and friendly communication"),
    HUMOROUS("humorous", "Playful and humorous communication"),
    DIRECT("direct", "Direct and to-the-point communication"),
    SUPPORTIVE("supportive", "Encouraging and supportive communication"),
    NEUTRAL("neutral", "Neutral and unbiased communication");

    private final String code;
    private final String description;

    CommunicationStyle(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static CommunicationStyle fromCode(String code) {
        if (code == null) return CASUAL;
        
        for (CommunicationStyle style : values()) {
            if (style.code.equalsIgnoreCase(code)) {
                return style;
            }
        }
        return CASUAL; // default
    }
}