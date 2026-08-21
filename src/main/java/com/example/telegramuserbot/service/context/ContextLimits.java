package com.example.telegramuserbot.service.context;

import com.example.telegramuserbot.domain.ContextSettings;

import java.time.Duration;
import java.time.Instant;

public record ContextLimits(int maxMessages, Instant cutoff, Instant upperBound, int maxTokens) {

    private static final int DEFAULT_MAX_MESSAGES = 10;
    private static final int DEFAULT_MAX_TOKENS = 2000;
    private static final int DEFAULT_MAX_HOURS = 24;

    public static ContextLimits fromSettings(ContextSettings settings, Instant triggerTime) {
        int maxMessages = DEFAULT_MAX_MESSAGES;
        int maxTokens = DEFAULT_MAX_TOKENS;
        int maxHours = DEFAULT_MAX_HOURS;

        if (settings != null) {
            Integer historyCount = settings.getHistoryMessageCount();
            if (historyCount != null && historyCount > 0) {
                maxMessages = historyCount;
            }
            Integer tokens = settings.getMaxContextTokens();
            if (tokens != null && tokens > 0) {
                maxTokens = tokens;
            }
            Integer hours = settings.getHistoryTimeWindowHours();
            if (hours != null && hours > 0) {
                maxHours = hours;
            }
        }

        Instant upperBound = triggerTime != null ? triggerTime : Instant.now();
        Instant cutoff = upperBound.minus(Duration.ofHours(maxHours));
        return new ContextLimits(maxMessages, cutoff, upperBound, maxTokens);
    }
}
