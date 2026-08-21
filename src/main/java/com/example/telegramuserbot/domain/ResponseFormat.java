package com.example.telegramuserbot.domain;

public enum ResponseFormat {
    TEXT("text", "Plain text responses"),
    MARKDOWN("markdown", "Markdown formatted responses"),
    HTML("html", "HTML formatted responses"),
    JSON("json", "Structured JSON responses"),
    CODE("code", "Code-formatted responses with syntax highlighting");

    private final String code;
    private final String description;

    ResponseFormat(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static ResponseFormat fromCode(String code) {
        if (code == null) return TEXT;
        
        for (ResponseFormat format : values()) {
            if (format.code.equalsIgnoreCase(code)) {
                return format;
            }
        }
        return TEXT;
    }
}