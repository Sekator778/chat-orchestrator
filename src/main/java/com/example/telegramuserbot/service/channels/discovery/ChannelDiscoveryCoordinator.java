package com.example.telegramuserbot.service.channels.discovery;

import com.example.telegramuserbot.domain.Channel;
import com.example.telegramuserbot.domain.TdLibOperationType;
import com.example.telegramuserbot.service.ChannelService;
import com.example.telegramuserbot.service.TdLibOperationLockService;
import com.example.telegramuserbot.service.startup.ChatDiscoveryService;
import com.example.telegramuserbot.repository.ChatConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Coordinates discovery of chats where the bot is already present and ensures
 * that corresponding Channel/ChatConfig rows exist in the database.
 *
 * <p>This coordinator uses distributed locking via {@link TdLibOperationLockService}
 * to prevent concurrent discovery operations across multiple bot instances.</p>
 */
@Service
public class ChannelDiscoveryCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ChannelDiscoveryCoordinator.class);

    /**
     * Timeout for the entire discovery operation (10 minutes).
     */
    private static final Duration DISCOVERY_TIMEOUT = Duration.ofMinutes(10);

    private final ChatDiscoveryService chatDiscoveryService;
    private final ChannelService channelService;
    private final ChatConfigRepository chatConfigRepository;
    private final TdLibOperationLockService lockService;

    public ChannelDiscoveryCoordinator(ChatDiscoveryService chatDiscoveryService,
                                       ChannelService channelService,
                                       ChatConfigRepository chatConfigRepository,
                                       TdLibOperationLockService lockService) {
        this.chatDiscoveryService = chatDiscoveryService;
        this.channelService = channelService;
        this.chatConfigRepository = chatConfigRepository;
        this.lockService = lockService;
    }

    /**
     * Discovers available chats through TDLib and persists any missing channel/config records.
     *
     * <p>This method acquires a distributed lock before starting discovery to prevent
     * concurrent discovery operations across multiple bot instances. If another instance
     * is already running discovery, this method will return an empty summary.</p>
     *
     * @return summary of discovery results
     */
    public Mono<DiscoverySummary> discoverAndPopulateChats() {
        log.info("🔍 CHANNEL DISCOVERY: Attempting to acquire distributed lock for discovery");
        return lockService.tryAcquireLock(TdLibOperationType.CHAT_DISCOVERY, null, DISCOVERY_TIMEOUT)
                .flatMap(lock -> executeDiscovery()
                        .doFinally(signal -> lockService.releaseLock(lock).subscribe()))
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("🔍 CHANNEL DISCOVERY: Another discovery operation is in progress, skipping");
                    return Mono.just(new DiscoverySummary(0, 0, Duration.ZERO));
                }));
    }

    /**
     * Executes the actual discovery logic (internal method).
     */
    private Mono<DiscoverySummary> executeDiscovery() {
        Instant start = Instant.now();
        AtomicInteger processed = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        log.info("🔍 CHANNEL DISCOVERY: Lock acquired, starting TDLib chat discovery run");
        return chatDiscoveryService.discoverAvailableChats()
                .collectList()
                .flatMapMany(chatInfos -> filterAlreadyConfigured(chatInfos)
                        .flatMapMany(filtered -> reactor.core.publisher.Flux.fromIterable(filtered)))
                .flatMap(chatInfo -> channelService.findOrCreateChannelAndConfig(chatInfo)
                        .doOnSuccess(channel -> processed.incrementAndGet())
                        .onErrorResume(error -> {
                            failures.incrementAndGet();
                            log.warn("🔍 CHANNEL DISCOVERY: Failed to ensure channel {} ({}): {}",
                                    chatInfo.chatId(), chatInfo.title(), error.getMessage());
                            return Mono.empty();
                        }),
                        4)
                .then(Mono.fromSupplier(() -> {
                    DiscoverySummary summary = new DiscoverySummary(
                            processed.get(),
                            failures.get(),
                            Duration.between(start, Instant.now())
                    );
                    log.info("🔍 CHANNEL DISCOVERY: Completed (processed={}, failed={}, duration={})",
                            summary.channelsProcessed(), summary.failures(), summary.duration());
                    return summary;
                }));
    }

    private Mono<List<ChatDiscoveryService.ChatInfo>> filterAlreadyConfigured(List<ChatDiscoveryService.ChatInfo> chatInfos) {
        if (chatInfos.isEmpty()) {
            return Mono.just(chatInfos);
        }
        Set<Long> ids = chatInfos.stream().map(ChatDiscoveryService.ChatInfo::chatId).collect(Collectors.toSet());
        return chatConfigRepository.findExistingChatIds(ids)
                .collect(Collectors.toSet())
                .map(existing -> {
                    List<ChatDiscoveryService.ChatInfo> filtered = chatInfos.stream()
                            .filter(info -> !existing.contains(info.chatId()))
                            .toList();
                    log.info("🔍 CHANNEL DISCOVERY: total={}, alreadyConfigured={}, toProcess={}",
                            chatInfos.size(), existing.size(), filtered.size());
                    if (!existing.isEmpty()) {
                        log.debug("🔍 CHANNEL DISCOVERY: filtered chatIds={}", existing);
                    }
                    return filtered;
                });
    }

    public record DiscoverySummary(
            int channelsProcessed,
            int failures,
            Duration duration
    ) {}
}
