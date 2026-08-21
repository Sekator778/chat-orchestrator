package com.example.telegramuserbot.dto;

import java.time.Instant;

public record ChannelOverviewDto(
        Long chatId,
        String title,
        String description,
        String joinStatus,
        String muteStatus,
        Instant lastSeen,
        Double channelScore,
        Long subscribers,
        boolean hasConfig,
        Long configChannelChatId,
        Boolean enabled,
        Boolean autoSyncEnabled,
        String language,
        Integer contextWindowSize,
        String processingPhase,
        Integer triggerCount,
        Integer restrictionCount
) {
}
