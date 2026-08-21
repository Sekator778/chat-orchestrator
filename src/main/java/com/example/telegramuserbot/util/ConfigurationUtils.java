package com.example.telegramuserbot.util;

import com.example.telegramuserbot.dto.ChatConfigDto;

public final class ConfigurationUtils {
    
    private ConfigurationUtils() {
        // Utility class - prevent instantiation
    }
    
    public static ChatConfigDto createDefaultConfig(long chatId) {
        return new ChatConfigDto(
            null,
            chatId,
            "",
            "",
            false,
            false,
            null,
            false,
            null,
            null,
            null,
            10,
            false,
            false,
            null,
            null
        );
    }
    
    public static ChatConfigDto copyConfigWithPrompt(ChatConfigDto original, String newPrompt) {
        return new ChatConfigDto(
            original.id(),
            original.channelId(),
            original.channelTitle(),
            newPrompt,
            original.enabled(),
            original.multiStageEnabled(),
            original.defaultSyncDepthDays(),
            original.autoSyncEnabled(),
            original.language(),
            original.primaryChannelId(),
            original.primaryChannelCheckedAt(),
            original.contextWindowSize(),
            original.respondToForwardedBotMessages(),
            original.syncEnabled(),
            original.maxTokens(),
            original.temperature()
        );
    }
    
    public static ChatConfigDto copyConfigWithEnabled(ChatConfigDto original, boolean enabled) {
        return new ChatConfigDto(
            original.id(),
            original.channelId(),
            original.channelTitle(),
            original.promptTemplate(),
            enabled,
            original.multiStageEnabled(),
            original.defaultSyncDepthDays() != null ? original.defaultSyncDepthDays() : (enabled ? 100 : null), // Set default 100 days when enabling
            original.autoSyncEnabled(),
            original.language(),
            original.primaryChannelId(),
            original.primaryChannelCheckedAt(),
            original.contextWindowSize(),
            original.respondToForwardedBotMessages(),
            enabled || original.syncEnabled(), // Ensure sync stays on once enabled
            original.maxTokens(),
            original.temperature()
        );
    }
    
    public static String formatLimitDisplay(Integer limit) {
        return limit == null ? "Без ліміту" : String.valueOf(limit);
    }
    
    public static String formatLimitStatusMessage(Integer limit) {
        return limit == null ? "знято (без ліміту)" : "встановлено на " + limit;
    }
}
