package com.example.telegramuserbot.controller;

import com.example.telegramuserbot.config.ConditionalOnHttpEnabled;
import com.example.telegramuserbot.domain.Channel;
import com.example.telegramuserbot.repository.ChannelRepository;
import com.example.telegramuserbot.service.startup.ChatPopulationService;
import com.example.telegramuserbot.service.messagesync.ChannelMessageSynchronizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;

/**
 * REST controller for monitoring and managing startup chat synchronization.
 * Provides endpoints to check progress, manually trigger sync, and get chat discovery info.
 */
@RestController
@ConditionalOnHttpEnabled
@RequestMapping("/api/startup-sync")
public class StartupSyncController {

    private static final Logger uiLog = LoggerFactory.getLogger("frontend.ui");
    private final ChannelMessageSynchronizationService channelMessageSynchronizationService;
    private final ChatPopulationService chatPopulationService;
    private final ChannelRepository channelRepository;
    private static final int DISCOVERY_LOOKBACK_DAYS = 5;
    private static final int DISCOVERY_LIMIT = 200;

    public StartupSyncController(
            ChannelMessageSynchronizationService channelMessageSynchronizationService,
            ChatPopulationService chatPopulationService,
            ChannelRepository channelRepository) {
        this.channelMessageSynchronizationService = channelMessageSynchronizationService;
        this.chatPopulationService = chatPopulationService;
        this.channelRepository = channelRepository;
    }
    
    /**
     * Get current synchronization progress.
     */
    @GetMapping("/progress")
    public Mono<ResponseEntity<ChannelMessageSynchronizationService.MessageSyncSummary>> getProgress() {
        return channelMessageSynchronizationService.getLastSummary()
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.noContent().build());
    }
    
    /**
     * Manually trigger startup synchronization.
     * Useful for re-running sync after startup or when new chats are detected.
     */
    @PostMapping("/trigger")
    public Mono<ResponseEntity<ChannelMessageSynchronizationService.MessageSyncSummary>> triggerSync() {
        uiLog.info("UI:startupSyncTrigger");
        return channelMessageSynchronizationService.synchronizeAutoSyncChannels()
                .timeout(Duration.ofMinutes(30))
                .map(ResponseEntity::ok)
                .onErrorReturn(ResponseEntity.status(500).build());
    }
    
    /**
     * Cancel ongoing synchronization.
     */
    @PostMapping("/cancel")
    public Mono<ResponseEntity<String>> cancelSync() {
        uiLog.info("UI:startupSyncCancel");
        return Mono.just(ResponseEntity.ok("Auto synchronization runs in short batches and does not support cancellation."));
    }
    
    /**
     * Discover available chats without triggering synchronization.
     * Useful for checking what chats the bot can access.
     */
    @GetMapping(value = "/discover-chats", produces = MediaType.APPLICATION_NDJSON_VALUE)
    public Flux<ChannelView> discoverChats() {
        uiLog.info("UI:discoverChats lookbackDays={} limit={}", DISCOVERY_LOOKBACK_DAYS, DISCOVERY_LIMIT);
        return channelRepository.findChannelsNeedingIngestion(DISCOVERY_LOOKBACK_DAYS, DISCOVERY_LIMIT)
                .timeout(Duration.ofMinutes(5))
                .map(ChannelView::fromChannel);
    }
    
    /**
     * Get information about a specific chat.
     */
    @GetMapping("/chat/{chatId}")
    public Mono<ResponseEntity<ChannelView>> getChatInfo(@PathVariable Long chatId) {
        uiLog.info("UI:getChatInfo chatId={}", chatId);
        return channelRepository.findByChatId(chatId)
                .map(ChannelView::fromChannel)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }
    
    /**
     * Check if a specific chat needs synchronization.
     */
    @GetMapping("/chat/{chatId}/needs-sync")
    public Mono<ResponseEntity<Boolean>> checkIfChatNeedsSync(@PathVariable Long chatId) {
        return channelMessageSynchronizationService.isChannelMarkedForSync(chatId)
                .map(ResponseEntity::ok);
    }
    
    /**
     * Manually populate channels from message history.
     */
    @PostMapping("/populate-channels")
    public Mono<ResponseEntity<String>> populateChannels() {
        return chatPopulationService.populateChannelsFromMessages()
                .map(count -> ResponseEntity.ok("Populated " + count + " channels from message history"))
                .onErrorReturn(ResponseEntity.status(500).body("Failed to populate channels"));
    }
    
    /**
     * Populate a specific channel by chat ID.
     */
    @PostMapping("/populate-channel/{chatId}")
    public Mono<ResponseEntity<String>> populateChannel(@PathVariable Long chatId) {
        return chatPopulationService.populateChannel(chatId)
                .map(channel -> ResponseEntity.ok("Successfully populated channel: " + channel.getTitle() + " (ID: " + channel.getChatId() + ")"))
                .switchIfEmpty(Mono.just(ResponseEntity.notFound().build()))
                .onErrorReturn(ResponseEntity.status(500).body("Failed to populate channel"));
    }
    
    private record ChannelView(Long chatId, String title, String joinStatus, String muteStatus, Instant lastSeen) {
        static ChannelView fromChannel(Channel channel) {
            return new ChannelView(
                    channel.getChatId(),
                    channel.getTitle(),
                    channel.getJoinStatus(),
                    channel.getMuteStatus(),
                    channel.getLastSeen()
            );
        }
    }
}
