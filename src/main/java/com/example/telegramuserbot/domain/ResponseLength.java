package com.example.telegramuserbot.domain;

public enum ResponseLength {
    TINY("tiny", "Ultra-short replies (2-3 words)"),
    SHORT("short", "Brief and concise responses (1-2 sentences)"),
    MEDIUM("medium", "Balanced responses (2-4 sentences)"), 
    LONG("long", "Detailed responses (4-6 sentences)"),
    DETAILED("detailed", "Comprehensive and thorough responses");

    private final String code;
    private final String description;

    ResponseLength(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static ResponseLength fromCode(String code) {
        if (code == null) return MEDIUM;
        
        for (ResponseLength length : values()) {
            if (length.code.equalsIgnoreCase(code)) {
                return length;
            }
        }
        return MEDIUM; // default
    }
}
