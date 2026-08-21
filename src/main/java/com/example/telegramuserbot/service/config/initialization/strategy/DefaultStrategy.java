package com.example.telegramuserbot.service.config.initialization.strategy;

import com.example.telegramuserbot.domain.Channel;
import com.example.telegramuserbot.service.config.initialization.template.LinkedChatsTemplate;
import com.example.telegramuserbot.service.config.initialization.template.LinkedChatsTemplateFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Fallback strategy that applies when no other strategy matches.
 * Provides minimal conservative configuration for any linked chat pair.
 *
 * <p>This strategy always returns false by default, serving as an example
 * for future implementations. To enable it, change shouldApply to return true.
 *
 * <h2>Use Cases</h2>
 * <ul>
 *   <li>Fallback configuration when language detection fails</li>
 *   <li>Default settings for non-Russian channels</li>
 *   <li>Testing new channel setups</li>
 * </ul>
 */
@Component
public final class DefaultStrategy implements TemplateApplicationStrategy {

    private static final Logger log = LoggerFactory.getLogger(DefaultStrategy.class);
    private final LinkedChatsTemplateFactory templateFactory;

    public DefaultStrategy(LinkedChatsTemplateFactory templateFactory) {
        this.templateFactory = templateFactory;
    }

    @Override
    public Mono<Boolean> shouldApply(Channel channel, Channel discussion) {
        // Enabled as fallback for channels that don't match language-specific strategies
        log.debug("Strategy {}: Default fallback strategy (enabled)", name());
        return Mono.just(true);
    }

    @Override
    public LinkedChatsTemplate template() {
        return templateFactory.linkedChatsMinimalReactionTemplate();
    }

    @Override
    public String name() {
        return "default-fallback";
    }

    @Override
    public int priority() {
        return -100; // Lowest priority - fallback only
    }

    @Override
    public String description() {
        return "Default fallback configuration (disabled by default)";
    }
}
