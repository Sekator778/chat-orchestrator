package com.example.telegramuserbot.domain;

public enum ResponseStyle {
    ADAPTIVE("adaptive", "Adapts style based on conversation context"),
    INFORMATIVE("informative", "Focuses on providing information and facts"),
    CONVERSATIONAL("conversational", "Natural, flowing conversation style"),
    CONCISE("concise", "Brief and to-the-point responses"),
    DETAILED("detailed", "Comprehensive and thorough explanations"),
    CREATIVE("creative", "Original and imaginative responses"),
    ANALYTICAL("analytical", "Structured and logical responses"),
    EMPATHETIC("empathetic", "Understanding and emotionally aware"),
    INSTRUCTIONAL("instructional", "Teaching and guiding responses"),
    STORYTELLING("storytelling", "Narrative and engaging style");

    private final String code;
    private final String description;

    ResponseStyle(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static ResponseStyle fromCode(String code) {
        if (code == null) return ADAPTIVE;
        
        for (ResponseStyle style : values()) {
            if (style.code.equalsIgnoreCase(code)) {
                return style;
            }
        }
        return ADAPTIVE;
    }
}