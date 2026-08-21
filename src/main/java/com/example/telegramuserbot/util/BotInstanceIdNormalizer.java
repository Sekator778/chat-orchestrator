package com.example.telegramuserbot.util;

import java.util.ArrayList;
import java.util.List;

public final class BotInstanceIdNormalizer {

    private BotInstanceIdNormalizer() {
    }

    public static List<String> splitCandidates(String raw) {
        if (raw == null) {
            return List.of();
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return List.of();
        }

        String cleaned = stripEnclosing(trimmed, '{', '}');
        cleaned = stripEnclosing(cleaned, '[', ']');
        cleaned = cleaned.trim();
        if (cleaned.isEmpty()) {
            return List.of();
        }

        String[] parts = cleaned.split(",");
        List<String> result = new ArrayList<>(parts.length);
        for (String part : parts) {
            String candidate = unquote(part.trim());
            if (!candidate.isBlank()) {
                result.add(candidate);
            }
        }
        return result;
    }

    public static String normalizeSingleOrDefault(String raw, String defaultBotId) {
        if (raw == null) {
            return defaultBotId;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || "default-bot".equalsIgnoreCase(trimmed)) {
            return defaultBotId;
        }

        List<String> candidates = splitCandidates(trimmed);
        if (!candidates.isEmpty()) {
            return candidates.get(0);
        }
        return trimmed;
    }

    private static String stripEnclosing(String value, char start, char end) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.charAt(0) == start && trimmed.charAt(trimmed.length() - 1) == end) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String unquote(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() >= 2) {
            char first = trimmed.charAt(0);
            char last = trimmed.charAt(trimmed.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return trimmed.substring(1, trimmed.length() - 1).trim();
            }
        }
        return trimmed;
    }
}

