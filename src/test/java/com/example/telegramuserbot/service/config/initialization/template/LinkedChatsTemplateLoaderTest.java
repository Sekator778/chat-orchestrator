package com.example.telegramuserbot.service.config.initialization.template;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for YAML template loading functionality.
 * Verifies that templates can be loaded from classpath resources.
 */
final class LinkedChatsTemplateLoaderTest {

    @Test
    void loads_minimal_reaction_template_successfully() {
        LinkedChatsTemplateLoader loader = new LinkedChatsTemplateLoader();

        LinkedChatsTemplate template = loader.load("linked-chats/minimal-reaction");

        assertNotNull(template, "Template should not be null");
        assertNotNull(template.channel(), "Channel template should not be null");
        assertNotNull(template.discussion(), "Discussion template should not be null");
        assertEquals("linked-chats-minimal-reaction-template", template.templateName());

        // Verify channel settings
        assertFalse(template.channel().enabled(), "Channel should be disabled");
        assertEquals("ru", template.channel().language(), "Language should be Russian");

        // Verify discussion settings
        assertTrue(template.discussion().enabled(), "Discussion should be enabled");
        assertEquals(2, template.discussion().maxDailyMessages(), "Max daily messages should be 2");
        assertEquals(0.35, template.discussion().temperature(), 0.001, "Temperature should be 0.35");
    }

    @Test
    void loads_low_engagement_followup_template_successfully() {
        LinkedChatsTemplateLoader loader = new LinkedChatsTemplateLoader();

        LinkedChatsTemplate template = loader.load("discussion/low-engagement-followup");

        assertNotNull(template, "Template should not be null");
        assertNotNull(template.channel(), "Channel template should not be null");
        assertNotNull(template.discussion(), "Discussion template should not be null");

        // Verify channel settings
        assertFalse(template.channel().enabled(), "Channel should be disabled");
        assertEquals("ru", template.channel().language(), "Language should be Russian");

        // Verify discussion settings
        assertTrue(template.discussion().enabled(), "Discussion should be enabled");
        assertEquals(5, template.discussion().maxDailyMessages(), "Max daily messages should be 5");
        assertEquals(0.18, template.discussion().temperature(), 0.001, "Temperature should be 0.18");

        // Verify rate limits
        assertEquals(5, template.discussion().rateLimits().maxMessagesPerDay(),
            "Rate limit should be 5 messages per day");
    }

    @Test
    void caches_loaded_templates() {
        LinkedChatsTemplateLoader loader = new LinkedChatsTemplateLoader();

        LinkedChatsTemplate first = loader.load("linked-chats/minimal-reaction");
        LinkedChatsTemplate second = loader.load("linked-chats/minimal-reaction");

        assertSame(first, second, "Should return same cached instance");
    }

    @Test
    void checks_template_existence() {
        LinkedChatsTemplateLoader loader = new LinkedChatsTemplateLoader();

        assertTrue(loader.exists("linked-chats/minimal-reaction"), "linked-chats/minimal-reaction template should exist");
        assertTrue(loader.exists("standalone/minimal-reaction"), "standalone/minimal-reaction template should exist");
        assertTrue(loader.exists("discussion/low-engagement-followup"), "discussion/low-engagement-followup template should exist");
        assertFalse(loader.exists("non-existent"), "non-existent template should not exist");
    }

    @Test
    void throws_exception_for_missing_template() {
        LinkedChatsTemplateLoader loader = new LinkedChatsTemplateLoader();

        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> loader.load("non-existent"),
            "Should throw exception for missing template"
        );

        String message = exception.getMessage();
        assertTrue(message.contains("not found") || message.contains("does not exist") || message.contains("Failed to"),
            "Exception message should indicate failure, but was: " + message);
    }
}
