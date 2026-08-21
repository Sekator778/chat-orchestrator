package com.example.telegramuserbot.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Provides the current bot instance identifier used to partition data
 * when multiple Telegram accounts run the same application.
 */
@Component
public final class BotInstanceProvider {

    private static final Logger log = LoggerFactory.getLogger(BotInstanceProvider.class);

    private final String instanceId;
    private final List<String> instanceIds;

    public BotInstanceProvider(@Value("${bot.persona-ids:}") String personaIdsRaw) {
        this.instanceIds = normalize(personaIdsRaw);
        if (this.instanceIds.isEmpty()) {
            throw new IllegalStateException("Property 'bot.persona-ids' must contain at least one bot id (comma-separated or YAML list)");
        }
        this.instanceId = this.instanceIds.get(0);
        log.info("Bot personas configured: instanceIds={}, primaryInstanceId={}", this.instanceIds, this.instanceId);
    }

    public String getInstanceId() {
        return instanceId;
    }

    public List<String> getInstanceIds() {
        return instanceIds;
    }

    private List<String> normalize(String raw) {
        List<String> result = new ArrayList<>();
        if (raw != null) {
            String cleaned = raw.replace("[", "").replace("]", "");
            String[] parts = cleaned.split(",");
            for (String part : parts) {
                String id = part.trim();
                if (!id.isEmpty()) {
                    result.add(id);
                }
            }
        }
        return result;
    }
}
