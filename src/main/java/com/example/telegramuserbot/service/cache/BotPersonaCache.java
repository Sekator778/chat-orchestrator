package com.example.telegramuserbot.service.cache;

import com.example.telegramuserbot.domain.BotPersona;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class BotPersonaCache {
    private final Map<String, BotPersona> cache = new ConcurrentHashMap<>();

    public Optional<BotPersona> get(String botId, String language) {
        return Optional.ofNullable(cache.get(key(botId, language)));
    }

    public void put(BotPersona persona) {
        if (persona == null || persona.getBotId() == null || persona.getLanguage() == null) {
            return;
        }
        cache.put(key(persona.getBotId(), persona.getLanguage()), persona);
    }

    private String key(String botId, String language) {
        return botId + "|" + language;
    }
}
