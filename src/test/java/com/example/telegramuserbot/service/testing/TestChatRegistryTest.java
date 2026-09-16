package com.example.telegramuserbot.service.testing;

import com.example.telegramuserbot.service.config.AppSettingsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestChatRegistryTest {

    private TestChatRegistry registryFor(String setting) {
        AppSettingsService settings = mock(AppSettingsService.class);
        when(settings.getString(eq(TestChatRegistry.SETTING_NAME), anyString())).thenReturn(setting);
        return new TestChatRegistry(settings);
    }

    @Test
    @DisplayName("configured ids are test chats, everything else is not")
    void parsesConfiguredIds() {
        TestChatRegistry registry = registryFor("-4964162923, -1003869517196");

        assertThat(registry.chatIds()).containsExactly(-4964162923L, -1003869517196L);
        assertThat(registry.isTestChat(-4964162923L)).isTrue();
        assertThat(registry.isTestChat(-1002858683612L)).isFalse();
        assertThat(registry.isTestChat(null)).isFalse();
    }

    @Test
    @DisplayName("an unset or malformed list never widens into a live chat")
    void badInputNeverWidensTheList() {
        assertThat(registryFor("").chatIds()).isEmpty();
        assertThat(registryFor("   ").isTestChat(-4964162923L)).isFalse();
        // A typo must drop that entry only, not turn the guard off.
        TestChatRegistry partial = registryFor("-4964162923, not-a-number, ,-1003869517196");
        assertThat(partial.chatIds()).containsExactly(-4964162923L, -1003869517196L);
    }
}
