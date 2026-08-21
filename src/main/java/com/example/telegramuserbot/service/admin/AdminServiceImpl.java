package com.example.telegramuserbot.service.admin;

import com.example.telegramuserbot.config.BotInstanceProvider;
import com.example.telegramuserbot.domain.Channel;
import com.example.telegramuserbot.domain.ChatConfig;
import com.example.telegramuserbot.dto.ChannelDto;
import com.example.telegramuserbot.dto.ChatConfigDto;
import com.example.telegramuserbot.dto.SyncRequestDto;
import com.example.telegramuserbot.exception.ResourceNotFoundException;
import com.example.telegramuserbot.repository.ChannelRepository;
import com.example.telegramuserbot.repository.ChatConfigRepository;
import com.example.telegramuserbot.service.TelegramClientManager;
import com.example.telegramuserbot.service.messagesync.SyncOrchestrationService;
import com.example.telegramuserbot.telegram.TelegramClientFacade;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Locale;

@Service
public class AdminServiceImpl implements AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminServiceImpl.class);

    private final ChannelRepository channelRepository;
    private final ChatConfigRepository chatConfigRepository;
    private final TelegramClientManager telegramClientManager;
    private final SyncOrchestrationService syncOrchestrationService;
    private final BotInstanceProvider botInstanceProvider;

    public AdminServiceImpl(ChannelRepository channelRepository,
                            ChatConfigRepository chatConfigRepository,
                            TelegramClientManager telegramClientManager,
                            SyncOrchestrationService syncOrchestrationService,
                            BotInstanceProvider botInstanceProvider) {
        this.channelRepository = channelRepository;
        this.chatConfigRepository = chatConfigRepository;
        this.telegramClientManager = telegramClientManager;
        this.syncOrchestrationService = syncOrchestrationService;
        this.botInstanceProvider = botInstanceProvider;
    }

    @Override
    public Mono<Void> logoutAndDeleteSession() {
        log.warn("!!! Initiating Telegram Logout via Admin API !!!");
        TelegramClientFacade telegramClient = telegramClientManager.getAnyClient();
        if (telegramClient == null) {
            return Mono.error(new IllegalStateException("No Telegram client available"));
        }
        return Mono.create(sink ->
                telegramClient.send(new TdApi.LogOut(), result -> {
                    if (result.isError()) {
                        log.error("!!! Telegram Logout FAILED: {}", result.getError());
                        sink.error(new RuntimeException("Telegram Logout Failed: " + result.getError().message));
                    } else {
                        log.info("--- Telegram Logout SUCCESS ---");
                        sink.success();
                    }
                })
        );
    }

    @Override
    public Flux<ChannelDto> findAllChannels() {
        log.debug("Fetching all channels for admin API");
        return channelRepository.findAllForInstance().map(this::mapToChannelDto);
    }

    @Override
    public Mono<ChatConfigDto> getConfig(Long channelId) {
        log.debug("Fetching chat config for channelId: {}", channelId);
        Mono<Channel> channelMono = channelRepository.findByChatId(channelId)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Channel not found for ID: " + channelId)));
        Mono<ChatConfig> configMono = chatConfigRepository.findByChannelChatId(channelId)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("ChatConfig not found for channel ID: " + channelId)));

        return Mono.zip(configMono, channelMono)
                .map(tuple -> mapToChatConfigDto(tuple.getT1(), tuple.getT2()));
    }

    @Override
    public Mono<ChatConfigDto> saveConfig(Long channelId, ChatConfigDto dto) {
        log.info("Saving/Updating chat config for channelId: {}", channelId);

        return channelRepository.findByChatId(channelId)
                .switchIfEmpty(Mono.error(new ResourceNotFoundException("Channel not found for chatId: " + channelId)))
                .flatMap(channel -> chatConfigRepository.findByChannelChatId(channelId)
                        .defaultIfEmpty(createNewChatConfig(channel))
                        .flatMap(config -> {
                            boolean wasDisabled = !config.isEnabled();
                            boolean willBeEnabled = dto.enabled();

                            updateConfigFromDto(config, dto);

                            return chatConfigRepository.save(config)
                                    .doOnSuccess(savedConfig -> {
                                        if (wasDisabled && willBeEnabled) {
                                            triggerAutoSync(channelId);
                                        }
                                    })
                                    .map(savedConfig -> mapToChatConfigDto(savedConfig, channel));
                        }));
    }

    private ChatConfig createNewChatConfig(Channel channel) {
        log.info("Creating new ChatConfig for channelId: {}.", channel.getChatId());
        ChatConfig newConfig = new ChatConfig();
        newConfig.setChannelId(channel.getChatId());
        newConfig.setEnabled(false);
        newConfig.setPromptTemplate("");
        return newConfig;
    }

    private void updateConfigFromDto(ChatConfig config, ChatConfigDto dto) {
        config.setPromptTemplate(dto.promptTemplate());
        config.setEnabled(dto.enabled());
        config.setSyncEnabled(dto.syncEnabled());
        config.setMaxTokens(dto.maxTokens());
        config.setTemperature(dto.temperature());
        if (dto.language() != null) config.setLanguage(normalizeLanguage(dto.language()));
        if (dto.primaryChannelId() != null) config.setPrimaryChannelId(dto.primaryChannelId());
        if (dto.contextWindowSize() != null) config.setContextWindowSize(Math.max(1, dto.contextWindowSize()));
        if (dto.respondToForwardedBotMessages() != null) config.setRespondToForwardedBotMessages(dto.respondToForwardedBotMessages());
        if (dto.defaultSyncDepthDays() != null) config.setDefaultSyncDepthDays(dto.defaultSyncDepthDays());
        if (dto.autoSyncEnabled() != null) config.setAutoSyncEnabled(dto.autoSyncEnabled());
    }

    private void triggerAutoSync(long channelId) {
        log.info("LLM enabled for channel {}. Triggering automatic 100-day history sync.", channelId);
        SyncRequestDto syncRequest = new SyncRequestDto(channelId, 100, false);
        syncOrchestrationService.initiateSync(syncRequest, null)
                .doOnSuccess(job -> log.info("Auto-sync initiated for channel {} with job ID: {}", channelId, job.id()))
                .doOnError(error -> log.warn("Failed to initiate auto-sync for channel {}: {}", channelId, error.getMessage()))
                .subscribe();
    }

    private ChannelDto mapToChannelDto(Channel channel) {
        return new ChannelDto(channel.getChatId(), channel.getTitle(), channel.isChannel());
    }

    private ChatConfigDto mapToChatConfigDto(ChatConfig config, Channel channel) {
        return new ChatConfigDto(
                config.getId(),
                channel.getChatId(),
                channel.getTitle(),
                config.getPromptTemplate(),
                config.isEnabled(),
                config.isMultiStageEnabled(),
                config.getDefaultSyncDepthDays(),
                config.getAutoSyncEnabled(),
                config.getLanguage(),
                config.getPrimaryChannelId(),
                config.getPrimaryChannelCheckedAt(),
                config.getContextWindowSize(),
                config.isRespondToForwardedBotMessages(),
                config.isSyncEnabled(),
                config.getMaxTokens(),
                config.getTemperature()
        );
    }

    private String normalizeLanguage(String language) {
        if (language == null) return null;
        String trimmed = language.trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
    }
}
