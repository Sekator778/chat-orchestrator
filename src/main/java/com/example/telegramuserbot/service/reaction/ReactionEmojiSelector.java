package com.example.telegramuserbot.service.reaction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Service for selecting a reaction emoji using weighted random sampling.
 * Picks from the configured emoji pool, respecting each emoji's weight.
 */
@Service
public final class ReactionEmojiSelector {

    private static final Logger log = LoggerFactory.getLogger(ReactionEmojiSelector.class);

    private final ReactionProperties properties;

    /**
     * Constructs the selector with reaction configuration.
     *
     * @param properties the reaction system configuration
     */
    public ReactionEmojiSelector(ReactionProperties properties) {
        this.properties = properties;
    }

    /**
     * Selects a single emoji from the pool using weighted random sampling.
     * Falls back to a thumbs-up emoji if the pool is empty or all weights are zero.
     *
     * @return the selected emoji string
     */
    public String select() {
        List<ReactionProperties.EmojiWeight> pool = properties.emojiPool();
        if (pool == null || pool.isEmpty()) {
            log.warn("Emoji pool is empty, using default thumbs-up emoji");
            return "\uD83D\uDC4D";
        }
        int total = pool.stream().mapToInt(ReactionProperties.EmojiWeight::weight).sum();
        if (total <= 0) {
            log.warn("Emoji pool has zero total weight, returning first emoji");
            return pool.get(0).emoji();
        }
        int roll = ThreadLocalRandom.current().nextInt(total);
        int cumulative = 0;
        for (ReactionProperties.EmojiWeight entry : pool) {
            cumulative += entry.weight();
            if (roll < cumulative) {
                return entry.emoji();
            }
        }
        return pool.get(pool.size() - 1).emoji();
    }
}
