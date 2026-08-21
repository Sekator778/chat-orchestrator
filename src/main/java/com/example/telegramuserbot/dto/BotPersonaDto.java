package com.example.telegramuserbot.dto;

import com.example.telegramuserbot.domain.BotPersona;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record BotPersonaDto(
        Long id,
        String language,
        String name,
        String description,
        List<String> behavior,
        List<String> traits,
        List<String> limitations,
        Map<String, Object> metadata,
        Instant updatedAt
) {
    public static BotPersonaDto fromEntity(BotPersona entity, Map<String, Object> metadata) {
        return new BotPersonaDto(
                entity.getId(),
                entity.getLanguage(),
                entity.getName(),
                entity.getDescription(),
                splitLines(entity.getBehavior()),
                splitComma(entity.getTraits()),
                splitLines(entity.getLimitations()),
                metadata,
                entity.getUpdatedAt()
        );
    }

    private static List<String> splitLines(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return java.util.Arrays.stream(raw.split("[\\r?\\n,]"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static List<String> splitComma(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return java.util.Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
