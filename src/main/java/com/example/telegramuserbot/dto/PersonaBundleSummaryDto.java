package com.example.telegramuserbot.dto;

import com.example.telegramuserbot.domain.BotPersona;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public record PersonaBundleSummaryDto(
        String botId,
        List<String> languages,
        String previewName,
        String previewDescription,
        Instant updatedAt
) {
    public static PersonaBundleSummaryDto from(String botId, List<BotPersona> personas) {
        if (personas == null || personas.isEmpty()) {
            return new PersonaBundleSummaryDto(botId, List.of(), null, null, null);
        }
        personas.sort(Comparator.comparing(BotPersona::getLanguage));
        BotPersona primary = personas.stream()
                .filter(p -> "base".equalsIgnoreCase(p.getLanguage()) || "ru".equalsIgnoreCase(p.getLanguage()))
                .findFirst()
                .orElse(personas.get(0));
        Instant updated = personas.stream()
                .map(BotPersona::getUpdatedAt)
                .filter(i -> i != null)
                .max(Instant::compareTo)
                .orElse(null);
        List<String> langs = personas.stream()
                .map(BotPersona::getLanguage)
                .map(lang -> lang == null ? "base" : lang)
                .collect(Collectors.toList());
        return new PersonaBundleSummaryDto(
                botId,
                langs,
                primary.getName(),
                primary.getDescription(),
                updated
        );
    }
}
