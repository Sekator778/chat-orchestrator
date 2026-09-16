package com.example.telegramuserbot.service.testing;

import com.example.telegramuserbot.service.config.AppSettingsService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The chats a developer is allowed to drive from the test endpoints.
 *
 * <p>Verifying that a persona still sends and receives has to happen against real
 * Telegram, and real Telegram means a real chat. These are the chats set aside for
 * that: nothing outside this list can be driven by {@code /api/admin/test-chat},
 * so a typo in a chat id cannot put a test message into a live conversation.
 *
 * <p>Single source of truth is {@code bot.app_settings.test.chats} (comma-separated
 * chat ids), re-read through the settings cache so the list can change without a
 * restart.
 */
@Service
public class TestChatRegistry {

    public static final String SETTING_NAME = "test.chats";

    private final AppSettingsService appSettings;

    public TestChatRegistry(AppSettingsService appSettings) {
        this.appSettings = appSettings;
    }

    /** Chat ids currently designated as test chats, in configured order. */
    public List<Long> chatIds() {
        String raw = appSettings.getString(SETTING_NAME, "");
        Set<Long> ids = new LinkedHashSet<>();
        if (raw != null && !raw.isBlank()) {
            for (String token : raw.split(",")) {
                String trimmed = token.strip();
                if (trimmed.isEmpty()) {
                    continue;
                }
                try {
                    ids.add(Long.parseLong(trimmed));
                } catch (NumberFormatException ignored) {
                    // A malformed id is simply not a test chat — never widen the list on bad input.
                }
            }
        }
        return new ArrayList<>(ids);
    }

    public boolean isTestChat(Long chatId) {
        return chatId != null && chatIds().contains(chatId);
    }
}
