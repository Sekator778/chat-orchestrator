package com.example.telegramuserbot.dto;

import java.time.Instant;

public record ChatConfigDto(
        Long id,
        Long channelId,
        String channelTitle, // Added channelTitle to DTO
        String promptTemplate,
        boolean enabled,
        boolean multiStageEnabled,
        Integer defaultSyncDepthDays,
        Boolean autoSyncEnabled,
        String language,
        Long primaryChannelId,
        Instant primaryChannelCheckedAt,
        Integer contextWindowSize,
        Boolean respondToForwardedBotMessages,
        boolean syncEnabled,
        Integer maxTokens,
        Double temperature
) {}
