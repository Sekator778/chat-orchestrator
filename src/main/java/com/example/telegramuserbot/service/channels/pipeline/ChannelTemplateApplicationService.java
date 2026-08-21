package com.example.telegramuserbot.service.channels.pipeline;

import com.example.telegramuserbot.domain.ChatConfig;
import reactor.core.publisher.Mono;

/**
 * Phase 3: Channel Template Application Service.
 * Applies configuration templates to channels based on characteristics and strategy.
 */
public interface ChannelTemplateApplicationService {

    /**
     * Processes a single channel through Phase 3 (Template Application).
     *
     * @param config ChatConfig in LINKED phase
     * @return Mono emitting true if processing succeeded, false otherwise
     */
    Mono<Boolean> processChannel(ChatConfig config);
}
