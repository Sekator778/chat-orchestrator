package com.example.telegramuserbot.service.channels.pipeline;

import com.example.telegramuserbot.domain.Channel;
import reactor.core.publisher.Mono;

/**
 * Phase 1: Channel Ingestion Service.
 * Pulls candidates from {@code tgscan.channels}, ensures the bot is a member,
 * joins + mutes when needed, then materializes {@code bot.chat_configs} rows.
 *
 * Responsibilities:
 * - Join channel via TDLib if not already accessible
 * - Mute notifications to avoid spam
 * - Create/update ChatConfig entry and mark it as INGESTED
 * - Update ingestion attempt tracking fields on tgscan.channels
 */
public interface ChannelIngestionService {

    /**
     * Processes a single tgscan channel through Phase 1 (Ingestion).
     *
     * @param channel Channel candidate from tgscan.channels
     * @return Mono emitting true if processing succeeded, false otherwise
     */
    Mono<Boolean> processChannel(Channel channel);
}
