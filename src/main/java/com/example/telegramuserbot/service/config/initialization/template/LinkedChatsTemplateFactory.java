package com.example.telegramuserbot.service.config.initialization.template;

import org.springframework.stereotype.Component;

/**
 * Factory for creating standard linked chats configuration templates.
 * Templates are loaded from YAML files in {@code resources/templates/linked-chats/}.
 *
 * <p>This factory provides convenience methods for accessing commonly used templates.
 * All templates are loaded from external YAML files rather than being hardcoded.
 *
 * <h2>Available Templates</h2>
 * <ul>
 *   <li>{@code minimal-reaction} - Simple short reactions with light emotion (8 msg/day)</li>
 *   <li>{@code low-engagement-followup} - Neutral follow-up after human engagement (5 msg/day)</li>
 * </ul>
 */
@Component
public final class LinkedChatsTemplateFactory {

    private final LinkedChatsTemplateLoader loader;

    public LinkedChatsTemplateFactory(LinkedChatsTemplateLoader loader) {
        this.loader = loader;
    }

    /**
     * Loads minimal reaction template from YAML file (standalone chats).
     * Simple short reactions with light emotion (2 messages/day max).
     * Located in templates/standalone/ folder.
     *
     * @return Minimal reaction template for standalone chats
     */
    public LinkedChatsTemplate minimalReactionTemplate() {
        return loader.load("standalone/minimal-reaction");
    }

    /**
     * Loads minimal reaction template for linked chats (channel + discussion pair).
     * Located in templates/linked-chats/ folder.
     *
     * @return Minimal reaction template for linked chats
     */
    public LinkedChatsTemplate linkedChatsMinimalReactionTemplate() {
        return loader.load("linked-chats/minimal-reaction");
    }

    /**
     * Loads low-engagement follow-up template from YAML file (discussion groups).
     * Neutral follow-up reactions after human engagement (5 messages/day max).
     * Located in templates/discussion/ folder.
     *
     * @return Low engagement follow-up template for discussion groups
     */
    public LinkedChatsTemplate lowEngagementFollowupTemplate() {
        return loader.load("discussion/low-engagement-followup");
    }

    /**
     * Loads custom template by name from YAML file.
     *
     * @param templateName Template name without .yaml extension
     * @return Loaded template
     * @throws IllegalArgumentException if template not found
     */
    public LinkedChatsTemplate loadTemplate(String templateName) {
        return loader.load(templateName);
    }

    /**
     * Checks if template exists.
     *
     * @param templateName Template name without .yaml extension
     * @return true if template file exists
     */
    public boolean templateExists(String templateName) {
        return loader.exists(templateName);
    }
}
