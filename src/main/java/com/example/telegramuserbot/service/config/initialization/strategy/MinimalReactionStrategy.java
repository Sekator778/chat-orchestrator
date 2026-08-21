package com.example.telegramuserbot.service.config.initialization.strategy;

import com.example.telegramuserbot.domain.Channel;
import com.example.telegramuserbot.repository.ChannelRepository;
import com.example.telegramuserbot.service.config.initialization.template.LinkedChatsTemplate;
import com.example.telegramuserbot.service.config.initialization.template.LinkedChatsTemplateFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Strategy for applying minimal reaction configuration to Russian-language channels.
 * This strategy applies conservative AI response settings designed for low-volume,
 * short message interactions.
 *
 * <h2>Conditions</h2>
 * <ul>
 *   <li>Channel language must be Russian (detected from title, description, or sample messages)</li>
 *   <li>Both channel and discussion must exist in database</li>
 * </ul>
 *
 * <h2>Template Configuration</h2>
 * <ul>
 *   <li>Channel: Disabled bot responses, sync enabled</li>
 *   <li>Discussion: Minimal AI reactions (1 short message per day)</li>
 *   <li>Language: Russian (ru)</li>
 *   <li>Rate limits: Very conservative</li>
 * </ul>
 */
@Component
public final class MinimalReactionStrategy implements TemplateApplicationStrategy {

    private static final Logger log = LoggerFactory.getLogger(MinimalReactionStrategy.class);
    private static final Duration QUERY_TIMEOUT = Duration.ofSeconds(5);

    private final ChannelRepository channelRepository;
    private final LinkedChatsTemplateFactory templateFactory;

    public MinimalReactionStrategy(ChannelRepository channelRepository,
                                   LinkedChatsTemplateFactory templateFactory) {
        this.channelRepository = channelRepository;
        this.templateFactory = templateFactory;
    }

    @Override
    public Mono<Boolean> shouldApply(Channel channel, Channel discussion) {
        if (channel == null || discussion == null) {
            log.debug("Strategy {}: Channel or discussion is null, skipping", name());
            return Mono.just(false);
        }

        log.debug("Strategy {}: Evaluating channel {} (title: {}) and discussion {}",
                name(), channel.getChatId(), channel.getTitle(), discussion.getChatId());

        return isRussianLanguage(channel)
                .doOnNext(isRussian -> {
                    if (isRussian) {
                        log.info("Strategy {}: Matched for channel {} (Russian language detected)",
                                name(), channel.getChatId());
                    } else {
                        log.debug("Strategy {}: Not matched for channel {} (non-Russian language)",
                                name(), channel.getChatId());
                    }
                })
                .timeout(QUERY_TIMEOUT)
                .onErrorResume(error -> {
                    log.warn("Strategy {}: Error checking conditions for channel {}: {}",
                            name(), channel.getChatId(), error.getMessage());
                    return Mono.just(false);
                });
    }

    @Override
    public LinkedChatsTemplate template() {
        return templateFactory.linkedChatsMinimalReactionTemplate();
    }

    @Override
    public String name() {
        return "minimal-reaction-russian";
    }

    @Override
    public int priority() {
        return 100; // High priority for Russian language channels
    }

    @Override
    public String description() {
        return "Minimal reaction configuration for Russian-language channel-discussion pairs";
    }

    /**
     * Detects if channel uses Russian language based on available metadata.
     * Checks title, description, and sample messages for Cyrillic characters.
     *
     * @param channel Channel to check
     * @return Mono emitting true if Russian language detected
     */
    private Mono<Boolean> isRussianLanguage(Channel channel) {
        return Mono.fromCallable(() -> {
            // Check title
            if (containsCyrillic(channel.getTitle())) {
                log.debug("Russian detected in channel {} title: {}", channel.getChatId(), channel.getTitle());
                return true;
            }

            // Check description
            if (containsCyrillic(channel.getDescription())) {
                log.debug("Russian detected in channel {} description", channel.getChatId());
                return true;
            }

            // Check sample message
            if (containsCyrillic(channel.getSampleMessage())) {
                log.debug("Russian detected in channel {} sample message", channel.getChatId());
                return true;
            }

            // Check username (less reliable but can help)
            if (containsCyrillic(channel.getUsername())) {
                log.debug("Russian detected in channel {} username", channel.getChatId());
                return true;
            }

            log.debug("No Russian language detected in channel {} metadata", channel.getChatId());
            return false;
        });
    }

    /**
     * Checks if text contains Cyrillic characters (Russian alphabet).
     *
     * @param text Text to check
     * @return true if Cyrillic characters found
     */
    private boolean containsCyrillic(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }

        // Check for Cyrillic Unicode range (U+0400 to U+04FF)
        for (char c : text.toCharArray()) {
            if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CYRILLIC) {
                return true;
            }
        }

        return false;
    }
}
