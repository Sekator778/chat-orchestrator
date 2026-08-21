package com.example.telegramuserbot.domain;

public enum ResponseTone {
    NEUTRAL("neutral", "Balanced and objective tone"),
    FRIENDLY("friendly", "Warm and approachable"),
    FORMAL("formal", "Professional and respectful"),
    CASUAL("casual", "Relaxed and informal"),
    ENTHUSIASTIC("enthusiastic", "Energetic and positive"),
    CALM("calm", "Peaceful and soothing"),
    CONFIDENT("confident", "Assured and authoritative"),
    HUMBLE("humble", "Modest and unassuming"),
    PLAYFUL("playful", "Light-hearted and fun"),
    SERIOUS("serious", "Focused and earnest"),
    SUPPORTIVE("supportive", "Encouraging and helpful"),
    DIPLOMATIC("diplomatic", "Tactful and balanced"),
    EMPATHETIC("empathetic", "Understanding and emotionally aware");

    private final String code;
    private final String description;

    ResponseTone(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    public static ResponseTone fromCode(String code) {
        if (code == null) return NEUTRAL;
        
        for (ResponseTone tone : values()) {
            if (tone.code.equalsIgnoreCase(code)) {
                return tone;
            }
        }
        return NEUTRAL;
    }
}