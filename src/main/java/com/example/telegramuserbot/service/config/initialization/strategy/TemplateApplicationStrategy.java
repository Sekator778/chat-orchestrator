package com.example.telegramuserbot.service.config.initialization.strategy;

import com.example.telegramuserbot.domain.Channel;
import com.example.telegramuserbot.service.config.initialization.template.LinkedChatsTemplate;
import reactor.core.publisher.Mono;

/**
 * Strategy interface for determining when to apply configuration template to linked chats.
 * Implementations define conditions under which a specific template should be used.
 *
 * <p>This allows flexible configuration rules based on:
 * <ul>
 *   <li>Channel language</li>
 *   <li>Channel size (subscriber count)</li>
 *   <li>Channel metadata</li>
 *   <li>Custom business logic</li>
 * </ul>
 *
 * <h2>Example Implementation</h2>
 * <pre>{@code
 * public class RussianMinimalReactionStrategy implements TemplateApplicationStrategy {
 *     public Mono<Boolean> shouldApply(Channel channel, Channel discussion) {
 *         return Mono.just(isRussianLanguage(channel));
 *     }
 *
 *     public LinkedChatsTemplate template() {
 *         return LinkedChatsTemplateFactory.linkedChatsMinimalReactionTemplate();
 *     }
 *
 *     public String name() {
 *         return "russian-minimal-reaction";
 *     }
 * }
 * }</pre>
 */
public interface TemplateApplicationStrategy {

    /**
     * Determines if this strategy should apply configuration to given channel-discussion pair.
     *
     * @param channel    Primary channel information
     * @param discussion Discussion group information
     * @return Mono emitting true if template should be applied
     */
    Mono<Boolean> shouldApply(Channel channel, Channel discussion);

    /**
     * Returns configuration template to apply.
     *
     * @return Configuration template
     */
    LinkedChatsTemplate template();

    /**
     * Returns unique strategy identifier for logging and monitoring.
     *
     * @return Strategy name
     */
    String name();

    /**
     * Returns strategy priority (higher values processed first).
     * Default priority is 0.
     *
     * @return Priority value
     */
    default int priority() {
        return 0;
    }

    /**
     * Returns human-readable description of strategy.
     *
     * @return Strategy description
     */
    default String description() {
        return "Configuration template application strategy";
    }
}
