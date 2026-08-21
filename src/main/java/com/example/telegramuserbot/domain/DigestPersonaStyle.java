package com.example.telegramuserbot.domain;

/**
 * Defines the writing style for digest generation personas.
 * Each style influences the tone and format of generated content.
 */
public enum DigestPersonaStyle {
    /**
     * Professional, analytical tone suitable for business news.
     */
    PROFESSIONAL,
    /**
     * Ironic, witty commentary on events.
     */
    IRONIC,
    /**
     * Urgent, concise breaking news style.
     */
    BREAKING_NEWS,
    /**
     * Technical, detailed analysis for specialized topics.
     */
    TECHNICAL,
    /**
     * Custom style defined by user-provided system prompt.
     */
    CUSTOM
}
