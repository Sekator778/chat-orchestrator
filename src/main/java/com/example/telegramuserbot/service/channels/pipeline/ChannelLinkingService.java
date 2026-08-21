package com.example.telegramuserbot.service.channels.pipeline;

import com.example.telegramuserbot.domain.ChatConfig;
import reactor.core.publisher.Mono;

/**
 * Phase 2: Channel Linking Service.
 * Resolves channel relationships (primary_channel_id, discussion chats).
 */
public interface ChannelLinkingService {

    /**
     * Processes a single channel through Phase 2 (Linking).
     *
     * @param config ChatConfig in INGESTED phase
     * @return Mono emitting true if processing succeeded, false otherwise
     */
    Mono<Boolean> processChannel(ChatConfig config);
}
