package com.example.telegramuserbot.service.command;

import com.example.telegramuserbot.domain.*;
import com.example.telegramuserbot.dto.*;
import com.example.telegramuserbot.exception.ResourceNotFoundException;
import com.example.telegramuserbot.repository.*;
import com.example.telegramuserbot.service.BotInfoService;
import com.example.telegramuserbot.service.ChannelService;
import com.example.telegramuserbot.service.MessageDeletionService;
import com.example.telegramuserbot.service.ShutdownService;
import com.example.telegramuserbot.service.UserService;
import com.example.telegramuserbot.service.config.ConfigurationService;
import com.example.telegramuserbot.service.search.SearchConfigurationService;
import com.example.telegramuserbot.service.messagesync.ChannelMessageSynchronizationService;
import com.example.telegramuserbot.service.messagesync.SyncOrchestrationService;
import com.example.telegramuserbot.util.CommandParsingUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class CommandServiceImpl implements CommandService {

    private static final Logger log = LoggerFactory.getLogger(CommandServiceImpl.class);
    private static final Map<String, HelpSection> HELP_SECTIONS = buildHelpSections();
    private static final int LIST_CHANNELS_LIMIT = 60;

    private final Long adminUserId;
    private final ConfigurationService configurationService;
    private final UserService userService;
    private final SyncOrchestrationService syncOrchestrationService;
    private final MessageRepository messageRepository;
    private final SearchConfigurationService searchConfigurationService;
    private final ChannelMessageSynchronizationService channelMessageSynchronizationService;
    private final MessageDeletionService messageDeletionService;
    private final BotInfoService botInfoService;
    private final ChannelRepository channelRepository;
    private final ChannelService channelService;
    private final ShutdownService shutdownService;

    public CommandServiceImpl(@org.springframework.beans.factory.annotation.Qualifier("allowedCommandChatId") Long allowedCommandChatId,
                              ConfigurationService configurationService, UserService userService,
                              SyncOrchestrationService syncOrchestrationService, MessageRepository messageRepository,
                              SearchConfigurationService searchConfigurationService,
                              ChannelMessageSynchronizationService channelMessageSynchronizationService,
                              MessageDeletionService messageDeletionService,
                              BotInfoService botInfoService, ChannelRepository channelRepository,
                              ChannelService channelService, ShutdownService shutdownService) {
        this.adminUserId = allowedCommandChatId;
        this.configurationService = configurationService;
        this.userService = userService;
        this.syncOrchestrationService = syncOrchestrationService;
        this.messageRepository = messageRepository;
        this.searchConfigurationService = searchConfigurationService;
        this.channelMessageSynchronizationService = channelMessageSynchronizationService;
        this.messageDeletionService = messageDeletionService;
        this.botInfoService = botInfoService;
        this.channelRepository = channelRepository;
        this.channelService = channelService;
        this.shutdownService = shutdownService;
    }

    @Override
    public Mono<Optional<String>> processCommand(long chatId, long messageId, String commandText) {
        return processCommand(chatId, messageId, null, commandText);
    }

    @Override
    public Mono<Optional<String>> processCommand(long chatId, long messageId, Long senderId, String commandText) {
        log.info("Processing command from chatId={}, messageId={}, senderId={}: {}",
                chatId, messageId, senderId, commandText);
        if (!commandText.startsWith("/")) {
            return Mono.just(Optional.empty());
        }

        if (!isPrivateAdminChat(chatId, senderId)) {
            log.debug("Command rejected: chatId={} is not private admin chat (adminUserId={})", chatId, adminUserId);
            return Mono.just(Optional.empty());
        }

        String[] parts = commandText.trim().split("\\s+");
        String command = parts[0].toLowerCase();

        Mono<String> responseMono = Mono.fromCallable(() -> parts)
                .flatMap(p -> switch (command) {
                    // Admin commands
                    case "/list_channels", "/list_channel" -> handleListChannels(p);
                    case "/get_config" -> handleGetConfig(p);
                    case "/enablellminteraction" -> handleToggleConfig(p, true, "/enableLLMInteraction");
                    case "/disablellminteraction" -> handleToggleConfig(p, false, "/disableLLMInteraction");
                    case "/enable_config" -> handleToggleConfig(p, true, "/enable_config"); // legacy alias
                    case "/disable_config" -> handleToggleConfig(p, false, "/disable_config"); // legacy alias
                    case "/set_prompt" -> handleSetPrompt(p);
                    case "/set_limit" -> handleSetLimit(p);
                    case "/set_channel_language" -> handleSetChannelLanguage(p);
                    case "/set_context_window" -> handleSetContextWindow(p);
                    case "/set_primary_channel" -> handleSetPrimaryChannel(p);
                    case "/set_forwarded_responses" -> handleSetForwardedResponses(p);
                    case "/set_max_tokens" -> handleSetMaxTokens(p);
                    case "/set_temperature" -> handleSetTemperature(p);
                    case "/set_length" -> handleSetChannelResponseLength(p);
                    case "/setup_channel" -> handleSetupChannel(p);
                    case "/stop" -> handleStopCommand();

                    // User personalization commands
                    case "/my_profile" -> handleMyProfile(senderId);
                    case "/set_name" -> handleSetName(senderId, p);
                    case "/set_title" -> handleSetTitle(senderId, p);
                    case "/set_style" -> handleSetStyle(senderId, p);
                    case "/set_language" -> handleSetLanguage(senderId, p);
                    case "/set_traits" -> handleSetTraits(senderId, p);
                    case "/set_context" -> handleSetContext(senderId, p);
                    case "/toggle_ai" -> handleToggleAi(senderId);

                    // Sync history commands
                    case "/sync_history" -> handleSyncHistory(senderId, p);
                    case "/sync_status" -> handleSyncStatus(p);
                    case "/sync_list" -> handleSyncList(p);
                    case "/sync_cancel" -> handleSyncCancel(senderId, p);
                    case "/sync_count" -> handleSyncCount(p);

                    // Message deletion
                    case "/delete_my_messages" -> handleDeleteMyMessages(senderId, p);

                    // Search commands
                    case "/search_config" -> handleSearchConfig(p);
                    case "/search_enable" -> handleSearchToggle(p, true);
                    case "/search_disable" -> handleSearchToggle(p, false);
                    case "/search_stats" -> handleSearchStats();

                    // Startup sync commands
                    case "/startup_progress" -> handleStartupProgress();
                    case "/startup_trigger" -> handleStartupTrigger();
                    case "/startup_cancel" -> handleStartupCancel();
                    case "/startup_errors" -> handleStartupErrors();
                    case "/discover_chats" -> handleDiscoverChats();

                    // Help
                    case "/help", "/start" -> handleHelpCommand(p);
                    default -> {
                        log.debug("Unknown command received: {}", command);
                        yield Mono.just("Невідома команда: " + command + "\\nСпробуйте /help");
                    }
                });

        return responseMono
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .onErrorResume(IllegalArgumentException.class, e -> {
                    log.warn("Invalid command arguments for '{}': {}", command, e.getMessage());
                    return Mono.just(Optional.of("Помилка аргументів: " + e.getMessage()));
                })
                .onErrorResume(Exception.class, e -> {
                    log.error("Error processing command '{}': {}", command, commandText, e);
                    return Mono.just(Optional.of("Виникла внутрішня помилка при обробці команди."));
                });
    }

    /**
     * Checks if command is from admin in their private chat with the bot.
     * In TDLib, private chat ID equals the user ID of the chat participant.
     * Commands are only allowed when both chatId and senderId match adminUserId.
     */
    private boolean isPrivateAdminChat(long chatId, Long senderId) {
        return senderId != null
                && adminUserId != null
                && adminUserId.equals(senderId)
                && adminUserId.equals(chatId);
    }

    private Mono<String> handleListChannels(String[] parts) {
        int page = 1;
        if (parts.length > 1) {
            page = CommandParsingUtils.parseInteger(parts[1], "Невірний номер сторінки.");
            if (page <= 0) {
                throw new IllegalArgumentException("Номер сторінки має бути більше нуля.");
            }
        }

        int offset = (page - 1) * LIST_CHANNELS_LIMIT;

        Mono<Long> totalMono = channelRepository.countForInstance();
        Mono<List<Channel>> pageMono = channelRepository.findAllOrderedForInstance()
                .skip(offset)
                .take(LIST_CHANNELS_LIMIT)
                .collectList();

        return Mono.zip(pageMono, totalMono, Mono.just(page))
                .map(tuple -> formatChannelPage(tuple.getT1(), tuple.getT2(), tuple.getT3()));
    }

    private String formatChannelPage(List<Channel> channels, long total, int page) {
        if (total == 0) {
            return "Список відомих каналів порожній.";
        }

        long totalPages = Math.max(1, (long) Math.ceil((double) total / LIST_CHANNELS_LIMIT));
        if (page > totalPages) {
            return String.format("Сторінка %d недоступна. Всього сторінок: %d.", page, totalPages);
        }

        if (channels.isEmpty()) {
            return String.format("На сторінці %d немає даних. Спробуйте сторінку %d.", page, totalPages);
        }

        String body = channels.stream()
                .map(channel -> {
                    String title = channel.getTitle();
                    if (title == null || title.isBlank()) {
                        title = "(без назви)";
                    }
                    return String.format("- ID: %d | Title: %s", channel.getChatId(), title);
                })
                .collect(Collectors.joining("\n"));

        return String.format("Відомі канали — сторінка %d з %d (усього %d):\n\n%s",
                page, totalPages, total, body);
    }

    private Mono<String> handleGetConfig(String[] parts) {
        return Mono.fromCallable(() -> CommandParsingUtils.parseChannelId(parts, "/get_config"))
                .flatMap(configurationService::getEnhancedConfig)
                .map(this::formatConfigResponse);
    }

	    private Mono<String> handleToggleConfig(String[] parts, boolean enable, String commandName) {
	        return Mono.fromCallable(() -> CommandParsingUtils.parseChannelId(parts, commandName))
	                .flatMap(channelId -> configurationService.updateBasicConfig(channelId, new ChatConfigUpdateDto(null, enable, null, null, null, null, null, null, null, null))
	                        .flatMap(config -> {
	                            if (enable) {
	                                return syncOrchestrationService.initiateSync(new SyncRequestDto(channelId, 100, false), adminUserId)
	                                        .thenReturn(String.format("✅ Обробка LLM для каналу %d успішно увімкнена.", channelId));
	                            }
                            return Mono.just(String.format("Обробка LLM для каналу %d успішно вимкнена.", channelId));
                        }));
    }

	    private Mono<String> handleSetPrompt(String[] parts) {
	        return Mono.fromCallable(() -> {
	            CommandParsingUtils.validateArgumentCount(parts, 3, "Usage: /set_prompt <channelId> <prompt_text>");
	            long channelId = CommandParsingUtils.parseLong(parts[1], "Invalid channelId format.");
	            String prompt = String.join(" ", Arrays.copyOfRange(parts, 2, parts.length));
	            return new ChatConfigUpdateDto(prompt, null, null, null, null, null, null, null, null, null);
	        }).flatMap(dto -> configurationService.updateBasicConfig(CommandParsingUtils.parseChannelId(parts, "/set_prompt"), dto))
	                .thenReturn(String.format("Системний промпт для каналу %d успішно встановлено.", CommandParsingUtils.parseChannelId(parts, "/set_prompt")));
	    }
    
	    private Mono<String> handleSetLimit(String[] parts) {
	        return Mono.fromCallable(() -> {
	            CommandParsingUtils.validateArgumentCount(parts, 2, 3, "Usage: /set_limit <channelId> [limit]");
	            long channelId = CommandParsingUtils.parseLong(parts[1], "Invalid channelId format.");
	            Integer limit = (parts.length == 3) ? CommandParsingUtils.parseInteger(parts[2], "Invalid limit format.") : null;
	            if (limit != null && limit < 0) throw new IllegalArgumentException("Limit cannot be negative.");
	            return new ChatConfigUpdateDto(null, null, limit, null, null, null, null, null, null, null);
	        }).flatMap(dto -> configurationService.updateBasicConfig(CommandParsingUtils.parseChannelId(parts, "/set_limit"), dto))
	                .thenReturn(String.format("Денний ліміт LLM повідомлень для каналу %d успішно змінено.", CommandParsingUtils.parseChannelId(parts, "/set_limit")));
	    }

	    private Mono<String> handleSetChannelLanguage(String[] parts) {
	        return Mono.fromCallable(() -> {
	            CommandParsingUtils.validateArgumentCount(parts, 3, "Usage: /set_channel_language <channelId> <language|default>");
	            String lang = parts[2].toLowerCase(Locale.ROOT);
	            if (!Arrays.asList("uk", "ru", "en", "auto", "default").contains(lang)) throw new IllegalArgumentException("Unsupported language.");
	            return new ChatConfigUpdateDto(null, null, null, null, null, lang.equals("default") ? null : lang, null, null, null, null);
	        }).flatMap(dto -> configurationService.updateBasicConfig(CommandParsingUtils.parseChannelId(parts, "/set_channel_language"), dto))
	                .thenReturn(String.format("Мову відповідей для каналу %d встановлено.", CommandParsingUtils.parseChannelId(parts, "/set_channel_language")));
	    }

	    private Mono<String> handleSetContextWindow(String[] parts) {
	        return Mono.fromCallable(() -> {
	            CommandParsingUtils.validateArgumentCount(parts, 3, "Usage: /set_context_window <channelId> <messages>");
	            int size = CommandParsingUtils.parseInteger(parts[2], "Invalid size format.");
	            if (size < 1 || size > 500) throw new IllegalArgumentException("Context window size must be between 1 and 500.");
	            return new ChatConfigUpdateDto(null, null, null, null, null, null, null, size, null, null);
	        }).flatMap(dto -> configurationService.updateBasicConfig(CommandParsingUtils.parseChannelId(parts, "/set_context_window"), dto))
	                .thenReturn(String.format("Контекстне вікно для каналу %d встановлено.", CommandParsingUtils.parseChannelId(parts, "/set_context_window")));
	    }

	    private Mono<String> handleSetPrimaryChannel(String[] parts) {
	        return Mono.fromCallable(() -> {
	            CommandParsingUtils.validateArgumentCount(parts, 3, "Usage: /set_primary_channel <channelId> <sourceId|none>");
	            String primaryInput = parts[2].trim();
	            Long primaryId = (!primaryInput.equalsIgnoreCase("none")) ? CommandParsingUtils.parseLong(primaryInput, "Invalid sourceId format.") : null;
	            return new ChatConfigUpdateDto(null, null, null, null, null, null, primaryId, null, null, null);
	        }).flatMap(dto -> configurationService.updateBasicConfig(CommandParsingUtils.parseChannelId(parts, "/set_primary_channel"), dto))
	                .thenReturn(String.format("Первинний канал для %d встановлено.", CommandParsingUtils.parseChannelId(parts, "/set_primary_channel")));
	    }

	    private Mono<String> handleSetForwardedResponses(String[] parts) {
	        return Mono.fromCallable(() -> {
	            CommandParsingUtils.validateArgumentCount(parts, 3, "Usage: /set_forwarded_responses <channelId> <on|off>");
	            boolean enabled = CommandParsingUtils.parseToggleValue(parts[2]);
	            return new ChatConfigUpdateDto(null, null, null, null, null, null, null, null, enabled, null);
	        }).flatMap(dto -> configurationService.updateBasicConfig(CommandParsingUtils.parseChannelId(parts, "/set_forwarded_responses"), dto))
	                .thenReturn(String.format("Відповіді на переслані бот-повідомлення для каналу %d змінено.", CommandParsingUtils.parseChannelId(parts, "/set_forwarded_responses")));
	    }

	    private Mono<String> handleSetMaxTokens(String[] parts) {
	        return Mono.fromCallable(() -> {
	            CommandParsingUtils.validateArgumentCount(parts, 3, "Usage: /set_max_tokens <channelId> <value|default>");
	            Integer tokens = parts[2].equalsIgnoreCase("default") ? null : CommandParsingUtils.parseInteger(parts[2], "Invalid token count format.");
	            return new ChatConfigUpdateDto(null, null, null, tokens, null, null, null, null, null, null);
	        }).flatMap(dto -> configurationService.updateBasicConfig(CommandParsingUtils.parseChannelId(parts, "/set_max_tokens"), dto))
	                .thenReturn(String.format("Max tokens для каналу %d змінено.", CommandParsingUtils.parseChannelId(parts, "/set_max_tokens")));
	    }

	    private Mono<String> handleSetTemperature(String[] parts) {
	        return Mono.fromCallable(() -> {
	            CommandParsingUtils.validateArgumentCount(parts, 3, "Usage: /set_temperature <channelId> <value|default>");
	            Double temp = parts[2].equalsIgnoreCase("default") ? null : CommandParsingUtils.parseDouble(parts[2]);
	            return new ChatConfigUpdateDto(null, null, null, null, temp, null, null, null, null, null);
	        }).flatMap(dto -> configurationService.updateBasicConfig(CommandParsingUtils.parseChannelId(parts, "/set_temperature"), dto))
	                .thenReturn(String.format("Temperature для каналу %d змінено.", CommandParsingUtils.parseChannelId(parts, "/set_temperature")));
	    }

    private Mono<String> handleSetupChannel(String[] parts) {
        return Mono.fromCallable(() -> {
                    CommandParsingUtils.validateArgumentCount(parts, 3, "Usage: /setup_channel <channelId> <on|off>");
                    long channelId = CommandParsingUtils.parseLong(parts[1], "Invalid channelId format.");
                    boolean syncEnabled = CommandParsingUtils.parseToggleValue(parts[2]);
                    return new SetupChannelCommand(channelId, syncEnabled);
                })
                .flatMap(cmd -> channelService.setupChannelConfiguration(cmd.channelId(), cmd.syncEnabled())
                        .flatMap(result -> {
                            String channelLabel = Optional.ofNullable(result.channel().getTitle()).orElse(String.valueOf(result.channel().getChatId()));
                            String action = cmd.syncEnabled() ? "увімкнено" : "вимкнено";
                            String baseMessage = String.format("Налаштування прослуховування для каналу '%s' (%d) %s.", channelLabel, result.channel().getChatId(), action);

                            if (cmd.syncEnabled()) {
                                return syncOrchestrationService.initiateSync(new SyncRequestDto(result.channel().getChatId(), 30, false), adminUserId)
                                        .thenReturn(baseMessage + " Розпочато синхронізацію глибиною 30 днів.");
                            }
                            return Mono.just(baseMessage + " Синхронізацію зупинено.");
                        }))
                .onErrorResume(ResourceNotFoundException.class, e -> Mono.just(e.getMessage()))
                .onErrorResume(IllegalStateException.class, e -> Mono.just(e.getMessage()));
    }

    private record SetupChannelCommand(long channelId, boolean syncEnabled) {}

    private Mono<String> handleSetChannelResponseLength(String[] parts) {
        CommandParsingUtils.validateArgumentCount(parts, 3, "Usage: /set_length <channelId> <tiny|short|medium|long|detailed>");
        long channelId = CommandParsingUtils.parseLong(parts[1], "Invalid channelId format.");
        String lengthCode = parts[2].toLowerCase(Locale.ROOT);
        ResponseLength length = ResponseLength.fromCode(lengthCode);
        if (!length.getCode().equalsIgnoreCase(lengthCode)) {
            return Mono.just("Непідтримувана довжина відповіді. Доступні: tiny, short, medium, long, detailed.");
        }

        return channelService.updateResponseLength(channelId, length)
                .map(result -> String.format(
                        "✅ Довжину відповідей для каналу %d встановлено на '%s'. Оновлено тригерів: %d.",
                        result.chatConfig().getChannelId(),
                        length.getDescription(),
                        result.updatedTriggerCount()))
                .onErrorResume(ResourceNotFoundException.class, e -> Mono.just(e.getMessage()))
                .onErrorResume(IllegalStateException.class, e -> Mono.just(e.getMessage()));
    }

    private Mono<String> handleStopCommand() {
        shutdownService.scheduleShutdown(Duration.ofSeconds(3));
        return Mono.just("🛑 Зупинка застосунку ініційована. Завершення роботи через 3 секунди...");
    }

    private Mono<String> handleMyProfile(Long senderId) {
        if (senderId == null) return Mono.just("Помилка: не вдалося визначити ваш ID користувача.");
        return userService.getUserByTelegramId(senderId)
                .switchIfEmpty(userService.getOrCreateUser(senderId, null, null, null))
                .map(this::formatUserProfile);
    }
    
    private Mono<String> handleSetName(Long senderId, String[] parts) {
        if (senderId == null) return Mono.just("Помилка: ID користувача не знайдено.");
        if (parts.length < 2) return Mono.just("Використання: /set_name <ім\'я>");
        String name = parts[1];
        return userService.updateUser(senderId, new UserUpdateDto(name, null, null, null, null, null, null, null))
                .thenReturn(String.format("✅ Встановлено ім\'я для звернення: %s", name));
    }

    private Mono<String> handleSetTitle(Long senderId, String[] parts) {
        if (senderId == null) return Mono.just("Помилка: ID користувача не знайдено.");
        if (parts.length < 2) return Mono.just("Використання: /set_title <титул>");
        String title = parts[1];
        return userService.updateUser(senderId, new UserUpdateDto(null, title, null, null, null, null, null, null))
                .thenReturn(String.format("✅ Встановлено титул: %s", title));
    }

    private Mono<String> handleSetStyle(Long senderId, String[] parts) {
        if (senderId == null) return Mono.just("Помилка: ID користувача не знайдено.");
        if (parts.length < 2) return Mono.just("Використання: /set_style <стиль>");
        return Mono.fromCallable(() -> CommunicationStyle.fromCode(parts[1].toLowerCase()))
                .flatMap(style -> userService.updateUser(senderId, new UserUpdateDto(null, null, style, null, null, null, null, null))
                .thenReturn(String.format("✅ Встановлено стиль спілкування: %s", style.getDescription())));
    }

    private Mono<String> handleSetLanguage(Long senderId, String[] parts) {
        if (senderId == null) return Mono.just("Помилка: ID користувача не знайдено.");
        if (parts.length < 2) return Mono.just("Використання: /set_language <мова>");
        String lang = parts[1].toLowerCase();
        if (!Arrays.asList("uk", "ru", "en", "auto").contains(lang)) return Mono.just("Непідтримувана мова.");
        return userService.updateUser(senderId, new UserUpdateDto(null, null, null, null, null, lang, null, null))
                .thenReturn(String.format("✅ Встановлено мову спілкування: %s", lang));
    }

    private Mono<String> handleSetTraits(Long senderId, String[] parts) {
        if (senderId == null) return Mono.just("Помилка: ID користувача не знайдено.");
        if (parts.length < 2) return Mono.just("Використання: /set_traits <риси>");
        String traits = String.join(" ", Arrays.copyOfRange(parts, 1, parts.length));
        return userService.updateUser(senderId, new UserUpdateDto(null, null, null, traits, null, null, null, null))
                .thenReturn(String.format("✅ Встановлено особливості особистості: %s", traits));
    }

    private Mono<String> handleSetContext(Long senderId, String[] parts) {
        if (senderId == null) return Mono.just("Помилка: ID користувача не знайдено.");
        if (parts.length < 2) return Mono.just("Використання: /set_context <контекст>");
        String context = String.join(" ", Arrays.copyOfRange(parts, 1, parts.length));
        return userService.updateUser(senderId, new UserUpdateDto(null, null, null, null, context, null, null, null))
                .thenReturn(String.format("✅ Встановлено контекст стосунків: %s", context));
    }

    private Mono<String> handleToggleAi(Long senderId) {
        if (senderId == null) return Mono.just("Помилка: ID користувача не знайдено.");
        return userService.getUserByTelegramId(senderId)
                .flatMap(user -> userService.updateUser(senderId, new UserUpdateDto(null, null, null, null, null, null, null, !user.isAiEnabled())))
                .map(updatedUser -> String.format("✅ AI відповіді %s", updatedUser.isAiEnabled() ? "увімкнено 🤖" : "вимкнено 🔇"));
    }

    private Mono<String> handleSyncHistory(Long senderId, String[] parts) {
        return Mono.fromCallable(() -> {
            CommandParsingUtils.validateArgumentCount(parts, 3, "Usage: /sync_history <channelId> <days>");
            long channelId = CommandParsingUtils.parseLong(parts[1], "Invalid channelId format.");
            int days = CommandParsingUtils.parseInteger(parts[2], "Invalid days format.");
            if (days <= 0 || days > 1095) throw new IllegalArgumentException("Days must be between 1 and 1095.");
            return new SyncRequestDto(channelId, days, false);
        }).flatMap(request -> syncOrchestrationService.initiateSync(request, senderId))
                .map(job -> String.format("✅ Синхронізацію розпочато! Job ID: %d", job.id()));
    }

    private Mono<String> handleSyncStatus(String[] parts) {
        return Mono.fromCallable(() -> CommandParsingUtils.parseLong(parts[1], "Invalid jobId format."))
                .flatMap(syncOrchestrationService::getSyncJobStatus)
                .map(this::formatSyncJobDto);
    }

    private Mono<String> handleSyncList(String[] parts) {
        return Mono.fromCallable(() -> CommandParsingUtils.parseLong(parts[1], "Invalid channelId format."))
                .flatMap(syncOrchestrationService::getChannelSyncHistory)
                .flatMapMany(Flux::fromIterable)
                .map(this::formatSyncJobDto)
                .collect(Collectors.joining("\n\n"))
                .map(result -> "📋 Історія синхронізацій:\n\n" + result)
                .defaultIfEmpty("📋 Історія синхронізацій порожня.");
    }

    private Mono<String> handleSyncCancel(Long senderId, String[] parts) {
        return Mono.fromCallable(() -> CommandParsingUtils.parseLong(parts[1], "Invalid jobId format."))
                .flatMap(jobId -> syncOrchestrationService.cancelSync(jobId, senderId))
                .map(job -> String.format("🚫 Синхронізацію #%d скасовано.", job.id()));
    }

    private Mono<String> handleSyncCount(String[] parts) {
        return Mono.fromCallable(() -> CommandParsingUtils.parseLong(parts[1], "Invalid channelId format."))
                .flatMap(messageRepository::countByChatId)
                .map(count -> String.format("📊 Кількість повідомлень у БД: %d", count));
    }

    private Mono<String> handleDeleteMyMessages(Long senderId, String[] parts) {
        return Mono.fromCallable(() -> {
            CommandParsingUtils.validateArgumentCount(parts, 3, "Usage: /delete_my_messages <channelId> <hours>");
            long channelId = CommandParsingUtils.parseLong(parts[1], "Invalid channelId format.");
            int hours = CommandParsingUtils.parseInteger(parts[2], "Invalid hours format.");
            if (hours <= 0 || hours > 168) throw new IllegalArgumentException("Hours must be between 1 and 168.");
            Long botUserId = botInfoService.getBotUserId();
            if (botUserId == null) throw new IllegalStateException("Bot not initialized");
            return new Object[]{channelId, hours, botUserId};
        }).flatMap(arr -> messageDeletionService.deleteMyMessagesFromChannel((long) arr[0], (int) arr[1], (long) arr[2]))
                .map(this::formatDeletionSummary);
    }

    private Mono<String> handleSearchConfig(String[] parts) {
        return Mono.fromCallable(() -> CommandParsingUtils.parseChannelId(parts, "/search_config"))
                .flatMap(searchConfigurationService::getSearchConfig)
                .map(this::formatSearchConfig);
    }

    private Mono<String> handleSearchToggle(String[] parts, boolean enable) {
        return Mono.fromCallable(() -> CommandParsingUtils.parseChannelId(parts, enable ? "/search_enable" : "/search_disable"))
                .flatMap(channelId -> searchConfigurationService.setSearchEnabled(channelId, enable))
                .map(config -> String.format("%s Пошук для каналу %d %s", enable ? "✅" : "❌", config.getChatId(), enable ? "увімкнено" : "вимкнено"));
    }

    private Mono<String> handleSearchStats() {
        return searchConfigurationService.getConfigurationStatistics()
                .map(this::formatSearchStats);
    }

    private Mono<String> handleStartupProgress() {
        return channelMessageSynchronizationService.getLastSummary()
                .map(this::formatMessageSyncSummary)
                .defaultIfEmpty("🔄 Автосинхронізація ще не запускалась.");
    }

    private Mono<String> handleStartupTrigger() {
        return channelMessageSynchronizationService.synchronizeAutoSyncChannels()
                .map(summary -> "🚀 Запущено автосинхронізацію!\n\n" + formatMessageSyncSummary(summary));
    }

    private Mono<String> handleStartupCancel() {
        return Mono.just("ℹ️ Автосинхронізація запускається пакетно і не потребує скасування.");
    }

    private Mono<String> handleStartupErrors() {
        return channelMessageSynchronizationService.getLastSummary()
                .map(summary -> {
                    if (summary.failedChatIds().isEmpty()) {
                        return "✅ Помилок під час останньої автосинхронізації не було.";
                    }
                    String failedList = summary.failedChatIds().stream()
                            .map(id -> "• " + id)
                            .collect(Collectors.joining("\n"));
                    return "⚠️ Не вдалося запустити синхронізацію для:\n" + failedList;
                })
                .defaultIfEmpty("❇️ Помилки ще не зафіксовані.");
    }

    private Mono<String> handleDiscoverChats() {
        return channelRepository.findChannelsNeedingIngestion(5, LIST_CHANNELS_LIMIT)
                .map(channel -> String.format("• %s (ID: %d) — status: %s",
                        channel.getTitle(), channel.getChatId(), channel.getJoinStatus()))
                .collect(Collectors.joining("\n"))
                .map(chats -> "🔍 Відкрито чатів:\n" + chats)
                .defaultIfEmpty("🔍 Нові чати не знайдено.");
    }

    private Mono<String> handleHelpCommand(String[] parts) {
        if (parts.length <= 1) {
            return Mono.just(buildHelpIndex());
        }

        String sectionKey = parts[1].toLowerCase(Locale.ROOT);
        HelpSection section = HELP_SECTIONS.get(sectionKey);
        if (section == null) {
            String available = HELP_SECTIONS.values().stream()
                    .map(s -> String.format("/help %s", s.key()))
                    .collect(Collectors.joining("\n"));
            return Mono.just(String.format("Невідомий розділ '%s'. Доступні розділи:\n%s", sectionKey, available));
        }

        return Mono.just(formatHelpSection(section));
    }

    private String buildHelpIndex() {
        String sections = HELP_SECTIONS.values().stream()
                .map(section -> String.format("%s\n  %s\n  ➡️ /help %s", section.title(), section.summary(), section.key()))
                .collect(Collectors.joining("\n\n"));

        return """
                🤖 Панель адміністратора — виберіть розділ

                📌 Поради:
                • <channelId> можна вказувати у Telegram-форматі (-1001234567890) або без префікса (1234567890)
                • [ ] позначає необов'язкові аргументи, | — синоніми команд

                """.strip() + "\n\n" + sections + "\n\nℹ️ Використовуйте /help <розділ>, щоб переглянути деталі.";
    }

    private String formatHelpSection(HelpSection section) {
        return String.format("""
                %s
                %s

                %s

                🔙 /help — повернення до списку розділів
                """.strip(), section.title(), section.summary(), section.details());
    }

    private static Map<String, HelpSection> buildHelpSections() {
        Map<String, HelpSection> sections = new LinkedHashMap<>();

        sections.put("channels", new HelpSection(
                "channels",
                "📡 Канали та LLM",
                "Керування конфігурацією каналів і відповіді LLM",
                """
/list_channels [page] — Показати відомі канали сторінками по 60 записів.
/get_config <channelId> — Отримати LLM-конфігурацію каналу.
/enableLLMInteraction <channelId> — Увімкнути LLM та запустити синхронізацію.
/disableLLMInteraction <channelId> — Вимкнути LLM для каналу.
/set_prompt <channelId> <текст> — Задати системний промпт.
/set_limit <channelId> [limit] — Змінити денний ліміт LLM (0 — без обмежень).
/set_channel_language <channelId> <uk|ru|en|auto|default> — Вказати мову відповідей.
/set_context_window <channelId> <messages> — Встановити розмір контекстного вікна.
/set_primary_channel <channelId> <sourceId|none> — Прив'язати або скинути первинний канал.
/set_forwarded_responses <channelId> <on|off> — Керувати відповідями на переслані бот-повідомлення.
/set_max_tokens <channelId> <value|default> — Перевизначити max tokens.
/set_temperature <channelId> <value|default> — Перевизначити temperature.
/set_length <channelId> <tiny|short|medium|long|detailed> — Встановити довжину відповідей (оновлює усі тригери).
/setup_channel <channelId> <on|off> — Швидко створити або оновити конфігурацію з авто-синхронізацією.
""".strip()
        ));

        sections.put("history", new HelpSection(
                "history",
                "📚 Історичні синхронізації",
                "Запуск і контроль пакетної синхронізації історії", 
                """
/sync_history <channelId> <days> — Запустити історичну синхронізацію.
/sync_status <jobId> — Перевірити статус конкретного завдання.
/sync_list <channelId> — Показати останні синхронізації каналу.
/sync_cancel <jobId> — Скасувати активну синхронізацію.
/sync_count <channelId> — Порахувати кількість повідомлень у БД.
""".strip()
        ));

        sections.put("autosync", new HelpSection(
                "autosync",
                "⚙️ Автоматичні синхронізації",
                "Налаштування регулярного оновлення каналів",
                """
/sync_config <channelId> — Переглянути параметри авто-синхронізації.
/sync_enable <channelId> — Увімкнути авто-синхронізацію.
/sync_disable <channelId> — Вимкнути авто-синхронізацію.
/sync_set_depth <channelId> <days> — Встановити глибину авто-синхронізації.
""".strip()
        ));

        sections.put("realtime", new HelpSection(
                "realtime",
                "📥 Поточний збір повідомлень",
                "Управління потоковою синхронізацією нових повідомлень",
                """
/enable_sync <channelId> — Увімкнути збір нових повідомлень.
/disable_sync <channelId> — Вимкнути збір нових повідомлень.
/check_sync [channelId] — Перевірити статус поточної синхронізації.
""".strip()
        ));

        sections.put("search", new HelpSection(
                "search",
                "🔍 Пошук",
                "Керування пошуковими тригерами та статистикою",
                """
/search_config <channelId> — Показати налаштування пошуку.
/search_enable <channelId> — Увімкнути автоматичний пошук.
/search_disable <channelId> — Вимкнути автоматичний пошук.
/search_stats — Переглянути статистику пошуку.
""".strip()
        ));

        sections.put("startup", new HelpSection(
                "startup",
                "🚀 Стартові операції",
                "Моніторинг initial sync та пошук нових чатів",
                """
/startup_progress — Переглянути прогрес стартової синхронізації.
/startup_trigger — Запустити стартову синхронізацію.
/startup_cancel — Скасувати стартову синхронізацію.
/startup_errors — Переглянути останні помилки стартової синхронізації.
/discover_chats — Просканувати доступні чати.
""".strip()
        ));

        sections.put("system", new HelpSection(
                "system",
                "🛡️ Системні операції",
                "Життєвий цикл застосунку та безпека",
                """
/stop — Акуратно завершити роботу застосунку (затримка 3 секунди).
""".strip()
        ));

        sections.put("users", new HelpSection(
                "users",
                "👥 Користувачі та модерація",
                "Керування користувачами та власними повідомленнями",
                """
/users_list — Показати всіх користувачів.
/user_info <userId> — Переглянути профіль користувача.
/delete_my_messages <channelId> <hours> — Видалити власні повідомлення за період.
""".strip()
        ));

        sections.put("persona", new HelpSection(
                "persona",
                "🧑‍🎨 Персональні налаштування",
                "Налаштування особистого стилю відповідей",
                """
/my_profile — Показати ваш профіль.
/set_name <ім'я> — Встановити ім'я для звернення.
/set_title <посада> — Задати посаду або роль.
/set_style <опис> — Налаштувати стиль відповідей.
/set_language <uk|ru|en> — Вказати мову відповідей.
/set_traits <список> — Задати риси характеру.
/set_context <текст> — Додати контекст для відповідей.
/toggle_ai — Увімкнути або вимкнути генерацію від AI для користувача.
""".strip()
        ));

        return sections;
    }

    private record HelpSection(String key, String title, String summary, String details) {}

    private String formatConfigResponse(EnhancedChatConfigDto config) {
        if (config == null) return "Конфігурація не знайдена.";
        String channelTitle = config.channelTitle() != null ? config.channelTitle() : "(без назви)";
        RateLimitsDto limits = config.rateLimits();
        Integer maxDaily = limits != null ? limits.maxMessagesPerDay() : null;
        int currentDaily = limits != null && limits.currentDailyMessages() != null ? limits.currentDailyMessages() : 0;

        String limitDisplay = maxDaily == null ? "без ліміту" : maxDaily.toString();
        String usageDisplay = maxDaily == null
                ? String.format("%d (без ліміту)", currentDaily)
                : String.format("%d / %d", currentDaily, maxDaily);
        String languageDisplay = config.language() != null && !config.language().isBlank()
                ? config.language()
                : "за замовчуванням (ru)";
        String contextWindowDisplay = config.contextWindowSize() != null
                ? config.contextWindowSize() + " повідомлень"
                : "за замовчуванням (10)";
        String primaryChannelDisplay = config.primaryChannelId() != null
                ? config.primaryChannelId().toString()
                : "(не задано)";
        String respondDisplay = config.respondToForwardedBotMessages() == null
                ? "не налаштовано"
                : (config.respondToForwardedBotMessages() ? "✅ так" : "❌ ні");
        String autoSyncDisplay = config.autoSyncEnabled() == null
                ? "не налаштовано"
                : (config.autoSyncEnabled() ? "✅ увімкнена" : "❌ вимкнена");
        String defaultDepthDisplay = config.defaultSyncDepthDays() != null
                ? config.defaultSyncDepthDays() + " днів"
                : "не задано (30 за замовчуванням)";
        String syncStatusDisplay = config.syncEnabled() ? "✅ увімкнена" : "❌ вимкнена";
        String maxTokensDisplay = config.maxTokens() != null
                ? config.maxTokens().toString()
                : "не задано (використовується глобальне значення)";
        String temperatureDisplay = config.temperature() != null
                ? String.format(Locale.US, "%.2f", config.temperature())
                : "не задано (використовується глобальне значення)";
	        String configIdDisplay = config.id() != null ? config.id().toString() : "(ще не створено)";
	        String llmStatusDisplay = config.enabled() ? "✅ увімкнено" : "❌ вимкнено";
	        String multiStageDisplay = config.multiStageEnabled() ? "✅ увімкнено" : "❌ вимкнено";
	
	        return String.format("""
	                        📦 Конфігурація каналу %d (%s)
	                        • ChatConfig ID: %s
	                        
	                        🧠 LLM:
	                        • Статус: %s
	                        • Багатоступенева генерація: %s
	                        • Денний ліміт: %s
	                        • Використано сьогодні: %s
	                        • Мова відповіді: %s
	                        • Контекстне вікно: %s
	                        • Первинний канал: %s
	                        • Відповідати на переслані бот-повідомлення: %s
                        
                        🔄 Синхронізація:
                        • Авто-синхронізація: %s
                        • Глибина синхронізації за замовчуванням: %s
                        • Синхронізація повідомлень: %s
                        
                        ⚙️ Параметри моделі:
                        • Max tokens: %s
                        • Temperature: %s
                        
                        📝 Системний промпт:
                        %s
                        """,
                config.channelId(),
	                channelTitle,
	                configIdDisplay,
	                llmStatusDisplay,
	                multiStageDisplay,
	                limitDisplay,
	                usageDisplay,
	                languageDisplay,
	                contextWindowDisplay,
                primaryChannelDisplay,
                respondDisplay,
                autoSyncDisplay,
                defaultDepthDisplay,
                syncStatusDisplay,
                maxTokensDisplay,
                temperatureDisplay,
                config.promptTemplate() != null && !config.promptTemplate().isBlank() ? config.promptTemplate() : "(не встановлено)"
        );
    }

    private String formatUserProfile(User user) {
        if (user == null) return "Користувача не знайдено.";
        return String.format("""
                        👤 Ваш профіль:
                        
                        🆔 Telegram ID: %d
                        📛 Ім'я для звернення: %s
                        🎖️ Титул: %s
                        🎭 Стиль спілкування: %s
                        📏 Довжина відповідей: %s
                        🌐 Мова: %s
                        🧠 Особливості: %s
                        🔗 Контекст стосунків: %s
                        🤖 AI увімкнено: %s
                        
                        📅 Створено: %s
                        ⏰ Остання взаємодія: %s
                        """,
                user.getTelegramUserId(),
                user.getDisplayName(),
                user.getPreferredTitle() != null ? user.getPreferredTitle() : "(не встановлено)",
                user.getCommunicationStyle().getDescription(),
                user.getResponseLength().getDescription(),
                user.getLanguagePreference(),
                user.getPersonalityTraits() != null ? user.getPersonalityTraits() : "(не встановлено)",
                user.getRelationshipContext() != null ? user.getRelationshipContext() : "(не встановлено)",
                user.isAiEnabled() ? "✅" : "❌",
                user.getCreatedAt().toString(),
                user.getLastInteractionAt().toString()
        );
    }

    private String formatSyncJobDto(SyncJobDto job) {
        if (job == null) return "Завдання не знайдено.";
        String statusEmoji = switch (job.status()) {
            case PENDING -> "⏳";
            case IN_PROGRESS -> "🔄";
            case COMPLETED -> "✅";
            case FAILED -> "❌";
            case CANCELLED -> "🚫";
        };

        String progressInfo = "";
        if (job.messagesTotal() != null && job.messagesProcessed() != null) {
            progressInfo = String.format("\n📊 Прогрес: %d/%d повідомлень (%s%%)",
                    job.messagesProcessed(),
                    job.messagesTotal(),
                    job.completionPercentage() != null ?
                            String.format("%.1f", job.completionPercentage()) : "невідомо"
            );
        } else if (job.messagesProcessed() != null) {
            progressInfo = String.format("\n📊 Оброблено: %d повідомлень", job.messagesProcessed());
        }

        String errorInfo = job.errorMessage() != null ?
                "\n❌ Помилка: " + job.errorMessage() : "";

        return String.format("""
                        %s Статус синхронізації #%d
                        
                        📺 Канал: %s (%d)
                        📅 Глибина: %d днів
                        🔄 Статус: %s%s%s
                        🕐 Створено: %s
                        %s%s
                        """,
                statusEmoji,
                job.id(),
                job.channelTitle() != null ? job.channelTitle() : "Невідомий",
                job.channelId(),
                job.syncDepthDays(),
                job.status().getDisplayName(),
                progressInfo,
                errorInfo,
                job.createdAt().toString(),
                job.startedAt() != null ? "\n▶️ Розпочато: " + job.startedAt().toString() : "",
                job.completedAt() != null ? "\n🏁 Завершено: " + job.completedAt().toString() : ""
        );
    }

    private String formatDeletionSummary(MessageDeletionService.DeletionSummary summary) {
        if (summary == null) return "Помилка отримання звіту.";
        return String.format("""
                        %s Видалення повідомлень завершено
                        
                        📊 Результат: %s
                        
                        📈 Статистика:
                        🔍 Знайдено в БД: %d повідомлень
                        
                        📱 Telegram API:
                        ✅ Видалено: %d
                        ❌ Не вдалося: %d
                        
                        💾 База даних:
                        🗑️ Очищено: %d записів
                        ❌ Помилки БД: %d
                        """,
                summary.isFullySuccessful() ? "✅" : (summary.hasPartialFailures() ? "⚠️" : "❌"),
                summary.isFullySuccessful() ? "Всі повідомлення успішно оброблено" : (summary.hasPartialFailures() ? "Частково виконано (деякі помилки)" : "Не вдалося обробити повідомлення"),
                summary.totalFound(),
                summary.telegramSuccessful(),
                summary.telegramFailed(),
                summary.databaseDeleted(),
                summary.databaseFailed()
        );
    }

    private String formatSearchConfig(SearchConfigDto config) {
        if (config == null) return "Налаштування пошуку не знайдено.";
        return String.format("""
                        🔍 Налаштування пошуку для каналу %d:
                        
                        ✅ Пошук увімкнено: %s
                        🔄 Автопошук: %s
                        🔎 Провайдер: %s
                        📊 Макс. результатів: %d
                        ⏱️ Кеш (хв): %d
                        🚦 Ліміт запитів: %d/хв
                        
                        🎯 Тригери пошуку: %s
                        """,
                config.getChatId(),
                config.isSearchEnabled() ? "✅" : "❌",
                config.isAutoSearchEnabled() ? "✅" : "❌",
                config.getSearchProvider(),
                config.getMaxResults(),
                config.getCacheDurationMinutes(),
                config.getRateLimitPerHour(),
                config.getSearchTriggers() != null && !config.getSearchTriggers().isEmpty() ? config.getSearchTriggers().size() + " патернів" : "не налаштовано"
        );
    }

    private String formatSearchStats(SearchConfigStatsDto stats) {
        if (stats == null) return "Статистика пошуку недоступна.";
        return String.format("""
                        📊 Статистика пошуку:
                        
                        📈 Всього конфігурацій: %d
                        ✅ Увімкнено пошук: %d
                        🔄 Автопошук: %d
                        ❌ Вимкнено: %d
                        
                        🔎 Провайдери:
                        • Google: %d
                        • Bing: %d
                        • DuckDuckGo: %d
                        • Інші: %d
                        """,
                stats.getTotalConfigurations(),
                stats.getSearchEnabledCount(),
                stats.getAutoSearchEnabledCount(),
                stats.getTotalConfigurations() - stats.getSearchEnabledCount(),
                stats.getProviderDistribution().getOrDefault("GOOGLE", 0L).intValue(),
                stats.getProviderDistribution().getOrDefault("BING", 0L).intValue(),
                stats.getProviderDistribution().getOrDefault("DUCKDUCKGO", 0L).intValue(),
                stats.getProviderDistribution().values().stream().mapToInt(Long::intValue).sum() -
                        stats.getProviderDistribution().getOrDefault("GOOGLE", 0L).intValue() -
                        stats.getProviderDistribution().getOrDefault("BING", 0L).intValue() -
                        stats.getProviderDistribution().getOrDefault("DUCKDUCKGO", 0L).intValue()
        );
    }

    private String formatMessageSyncSummary(ChannelMessageSynchronizationService.MessageSyncSummary summary) {
        if (summary == null) {
            return "Синхронізація ще не запускалась.";
        }
        return String.format("""
                        📚 Автосинхронізація повідомлень
                        
                        📌 Каналів із авто-синхом: %d
                        🚀 Запущено задач: %d
                        ✅ Успішно: %d
                        ⚠️ Помилки: %d
                        ⏱️ Тривалість: %s
                        """,
                summary.autoSyncChannels(),
                summary.syncJobsAttempted(),
                summary.syncJobsSucceeded(),
                summary.syncJobsFailed(),
                summary.duration());
    }
}
