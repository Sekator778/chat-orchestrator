package com.example.telegramuserbot.service;

import com.example.telegramuserbot.config.TelegramAccountProperties;
import com.example.telegramuserbot.config.TelegramAccountProperties.Account;
import com.example.telegramuserbot.config.BotInstanceProvider;
import com.example.telegramuserbot.repository.TelegramAccountRepository;
import com.example.telegramuserbot.service.auth.TelegramAuthCodeService;
import com.example.telegramuserbot.service.config.AppSettingsService;
import com.example.telegramuserbot.service.safety.OutboundKillSwitch;
import com.example.telegramuserbot.telegram.FloodWaitGuard;
import com.example.telegramuserbot.telegram.FloodWaitTelegramClientFacade;
import com.example.telegramuserbot.telegram.KillSwitchTelegramClientFacade;
import com.example.telegramuserbot.telegram.TdLightTelegramClient;
import com.example.telegramuserbot.telegram.TelegramClientFacade;
import com.example.telegramuserbot.telegram.TelegramClientLifecycleListener;
import it.tdlight.Init;
import it.tdlight.Log;
import com.example.telegramuserbot.telegram.FilteredTdLibLogHandler;
import it.tdlight.client.APIToken;
import it.tdlight.client.AuthenticationSupplier;
import it.tdlight.client.SimpleTelegramClient;
import it.tdlight.client.SimpleTelegramClientBuilder;
import it.tdlight.client.SimpleTelegramClientFactory;
import it.tdlight.client.TDLibSettings;
import it.tdlight.jni.TdApi;
import it.tdlight.util.UnsupportedNativeLibraryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class TelegramClientManager {

    private static final Logger log = LoggerFactory.getLogger(TelegramClientManager.class);

    private final TelegramAccountProperties properties;
    private final BotInstanceProvider botInstanceProvider;
    private final OutboundKillSwitch outboundKillSwitch;
    private final TelegramAuthCodeService telegramAuthCodeService;
    private final boolean headlessAuthEnabled;
    private final String tdlibFilesDirectory;
    private final TelegramAccountRepository telegramAccountRepository;
    private final AppSettingsService appSettingsService;
    private final Map<String, TelegramClientFacade> clients = new ConcurrentHashMap<>();
    private final List<Account> pendingAccounts = new CopyOnWriteArrayList<>();

    @Autowired(required = false)
    @Lazy
    private List<TelegramClientLifecycleListener> lifecycleListeners = Collections.emptyList();

    public TelegramClientManager(TelegramAccountProperties properties,
                                 BotInstanceProvider botInstanceProvider,
                                 OutboundKillSwitch outboundKillSwitch,
                                 TelegramAuthCodeService telegramAuthCodeService,
                                 @Value("${telegram.auth.headless.enabled:false}") boolean headlessAuthEnabled,
                                 @Value("${tdlib.filesDirectory:}") String tdlibFilesDirectory,
                                 TelegramAccountRepository telegramAccountRepository,
                                 AppSettingsService appSettingsService) {
        this.properties = properties;
        this.botInstanceProvider = botInstanceProvider;
        this.outboundKillSwitch = outboundKillSwitch;
        this.telegramAuthCodeService = telegramAuthCodeService;
        this.headlessAuthEnabled = headlessAuthEnabled;
        this.tdlibFilesDirectory = tdlibFilesDirectory;
        this.telegramAccountRepository = telegramAccountRepository;
        this.appSettingsService = appSettingsService;
    }

    /**
     * Wraps a client with the safety decorators: flood-wait backoff (inner) so
     * a parked account stops sending, then the owner kill switch (outer) so the
     * emergency stop always wins.
     */
    private TelegramClientFacade wrapWithKillSwitch(TelegramClientFacade client, String botId) {
        FloodWaitTelegramClientFacade floodGuarded =
                new FloodWaitTelegramClientFacade(client, new FloodWaitGuard(botId), botId);
        return new KillSwitchTelegramClientFacade(floodGuarded, outboundKillSwitch, botId);
    }

    /**
     * Polls bot.auth_codes for the owner-submitted code/password (off the TDLib
     * update thread) and feeds it to the waiting login.
     */
    private void submitHeadlessSecret(java.util.concurrent.atomic.AtomicReference<SimpleTelegramClient> clientRef,
                                      String botId, String kind) {
        Thread.ofVirtual().name("auth-" + kind.toLowerCase() + "-" + botId).start(() -> {
            log.info("Headless login: botId={} awaiting {} via admin API", botId, kind);
            String value = telegramAuthCodeService.awaitValue(botId, kind,
                    java.time.Duration.ofMinutes(4), java.time.Duration.ofSeconds(3));
            SimpleTelegramClient client = clientRef.get();
            if (value == null || client == null) {
                log.warn("Headless login: no {} provided for botId={} in time", kind, botId);
                return;
            }
            if (TelegramAuthCodeService.KIND_PASSWORD.equals(kind)) {
                client.send(new TdApi.CheckAuthenticationPassword(value), r -> logAuthResult(botId, kind, r));
            } else {
                client.send(new TdApi.CheckAuthenticationCode(value), r -> logAuthResult(botId, kind, r));
            }
        });
    }

    private void logAuthResult(String botId, String kind, it.tdlight.client.Result<TdApi.Ok> result) {
        if (result.isError()) {
            log.error("Headless login: {} rejected for botId={}: {}", kind, botId, result.getError().message);
        } else {
            log.info("Headless login: {} accepted for botId={}", kind, botId);
        }
    }

    @PostConstruct
    public void init() throws UnsupportedNativeLibraryException {
        Init.init();
        Log.setLogMessageHandler(1, new FilteredTdLibLogHandler());
        if (properties.getAccounts() == null || properties.getAccounts().isEmpty()) {
            log.warn("No telegram accounts configured under telegram.accounts");
            return;
        }

        log.info("Telegram accounts configured: {}", properties.getAccounts().stream()
                .map(Account::getBotId)
                .filter(id -> id != null && !id.isBlank())
                .toList());
        log.info("Primary bot instance requested (bot.persona-ids[0]) = {}", botInstanceProvider.getInstanceId());

        String primaryBotIdFromConfig = botInstanceProvider.getInstanceId();
        for (Account acc : properties.getAccounts()) {
            if (acc.getBotId() == null || acc.getApiId() == null || acc.getApiHash() == null) {
                log.warn("Skipping telegram account with missing fields: {}", acc);
                continue;
            }
            boolean isPrimary = acc.getBotId().equals(primaryBotIdFromConfig);
            if (isPrimary) {
                try {
                    TelegramClientFacade client = wrapWithKillSwitch(buildClient(acc), acc.getBotId());
                    clients.put(acc.getBotId(), client);
                    log.info("PRIMARY Telegram client initialized for botId={}", acc.getBotId());
                } catch (Exception e) {
                    log.error("Failed to initialize PRIMARY Telegram client for botId={}: {}", acc.getBotId(), e.getMessage(), e);
                }
            } else {
                pendingAccounts.add(acc);
                log.info("Secondary account botId={} will be initialized after startup completes", acc.getBotId());
            }
        }

        String primaryBotId = botInstanceProvider.getInstanceId();
        if (primaryBotId != null && !primaryBotId.isBlank() && !clients.containsKey(primaryBotId)) {
            log.warn("Primary bot instance {} has no initialized Telegram client; available botIds={}",
                    primaryBotId, clients.keySet());
        } else {
            log.info("Telegram clients initialized: botIds={}", clients.keySet());
        }

        if (!pendingAccounts.isEmpty()) {
            initializeSecondaryClients();
        }
    }

    /**
     * Notifies lifecycle listeners for ALL initialized clients after all Spring beans are ready.
     * During {@code @PostConstruct}, beans may not be fully wired (circular reference risk),
     * so listener notification is deferred entirely to this event.
     *
     * <p>Ordered {@link Ordered#LOWEST_PRECEDENCE} so it runs AFTER
     * {@code AppSettingsService}'s blocking snapshot load (which is {@link Ordered#HIGHEST_PRECEDENCE}).
     * This guarantees that {@code onClientReady} consumers (e.g. the collector channel registry)
     * read fully-loaded {@code bot.app_settings} values, not startup fallbacks.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void onApplicationReady() {
        log.info("ApplicationReady: notifying {} lifecycle listener(s) for {} client(s): {}",
            lifecycleListeners.size(), clients.size(), clients.keySet());
        for (Map.Entry<String, TelegramClientFacade> entry : clients.entrySet()) {
            notifyListeners(entry.getKey(), entry.getValue());
        }
        log.info("=== BOT PERSONAS INITIALIZED ({}) ===", clients.size());
        for (Map.Entry<String, TelegramClientFacade> entry : clients.entrySet()) {
            String botId = entry.getKey();
            boolean isPrimary = botId.equals(botInstanceProvider.getInstanceId());
            properties.getAccounts().stream()
                    .filter(a -> botId.equals(a.getBotId()))
                    .findFirst()
                    .ifPresentOrElse(acc -> log.info("  [{}] {} | {} | phone={} {}",
                            botId,
                            acc.getName() != null ? acc.getName() : "—",
                            isPrimary ? "PRIMARY" : "secondary",
                            acc.getPhoneNumber(),
                            java.time.LocalDateTime.now()),
                            () -> log.info("  [{}] {} (no account config found)", botId, isPrimary ? "PRIMARY" : "secondary"));
        }
        log.info("==============================");
    }

    public TelegramClientFacade getClient(String botId) {
        return clients.get(botId);
    }

    public TelegramClientFacade getAnyClient() {
        String primaryBotId = botInstanceProvider.getInstanceId();
        if (primaryBotId != null && !primaryBotId.isBlank()) {
            TelegramClientFacade primaryClient = clients.get(primaryBotId);
            if (primaryClient != null) {
                return primaryClient;
            }
        }
        return clients.values().stream().findFirst().orElse(null);
    }

    public List<String> getAllBotIds() {
        return new java.util.ArrayList<>(clients.keySet());
    }

    public int getClientCount() {
        return clients.size();
    }

    /**
     * Builds secondary TDLib clients and triggers LoadChats for each.
     * Does NOT notify lifecycle listeners — all listener notification is deferred
     * to {@link #onApplicationReady()} to avoid circular reference issues during startup.
     */
    public void initializeSecondaryClients() {
        if (pendingAccounts.isEmpty()) {
            log.info("No secondary accounts to initialize");
            return;
        }
        log.info("Initializing {} secondary Telegram client(s)...", pendingAccounts.size());
        for (Account acc : pendingAccounts) {
            try {
                log.info("Initializing secondary client for botId={}...", acc.getBotId());
                TelegramClientFacade client = wrapWithKillSwitch(buildClient(acc), acc.getBotId());
                clients.put(acc.getBotId(), client);
                log.info("Secondary Telegram client initialized for botId={}", acc.getBotId());
                triggerLoadChats(client, acc.getBotId());
            } catch (Exception e) {
                log.error("Failed to initialize secondary Telegram client for botId={}: {}", acc.getBotId(), e.getMessage(), e);
            }
        }
        pendingAccounts.clear();
        log.info("All Telegram clients initialized: botIds={}", clients.keySet());
    }

    /**
     * Triggers LoadChats for the given client so TDLib subscribes to chat updates
     * and starts caching incoming messages.
     * Secondary clients do not receive UpdateNewMessage until LoadChats is called at least once.
     * Without chat subscription, AddMessageReaction fails because the message is not in local cache.
     *
     * @param client the TDLib client to load chats for
     * @param botId  bot identifier for logging
     */
    private void triggerLoadChats(TelegramClientFacade client, String botId) {
        client.send(new TdApi.LoadChats(new TdApi.ChatListMain(), 100), result -> {
            if (result.isError()) {
                String msg = result.getError().message;
                if (msg != null && msg.contains("404")) {
                    log.info("LoadChats complete for secondary botId={} (all chats loaded)", botId);
                } else {
                    log.warn("LoadChats failed for secondary botId={}: {}", botId, msg);
                }
            } else {
                log.info("LoadChats triggered for secondary botId={}", botId);
            }
        });
    }

    private void notifyListeners(String botId, TelegramClientFacade client) {
        log.info("Notifying {} lifecycle listener(s) for botId={}", lifecycleListeners.size(), botId);
        for (TelegramClientLifecycleListener listener : lifecycleListeners) {
            try {
                listener.onClientReady(botId, client);
            } catch (Exception e) {
                log.error("Lifecycle listener {} failed for botId={}: {}",
                    listener.getClass().getSimpleName(), botId, e.getMessage(), e);
            }
        }
    }

    public boolean hasPendingSecondaryClients() {
        return !pendingAccounts.isEmpty();
    }

    /**
     * Builds a TelegramClientFacade for a single account.
     * Each client gets its own SimpleTelegramClientFactory to ensure complete TDLib state isolation.
     * This prevents "dialog date didn't increase" errors when initializing multiple accounts.
     *
     * <p>When {@code tdlib.role-aware-cache.enabled} is {@code true} (default), reply-persona
     * clients (non-collector accounts) are built with a lightweight TDLib profile: message-database
     * and file-database are disabled (controlled by {@code tdlib.reply-persona.use-message-database}
     * and {@code tdlib.reply-persona.use-file-database}, both defaulting to {@code false}).
     * The chat-info database is always kept on so TDLib can resolve chats and deliver live updates.
     * Collector clients retain full caching.
     */
    private TelegramClientFacade buildClient(Account acc) throws Exception {
        Path sessionRoot = Path.of(acc.getSessionsDirectory() != null ? acc.getSessionsDirectory() : "./tdlib-sessions/" + acc.getBotId());
        Path dbPath = sessionRoot.resolve("data");
        Path filesPath;
        if (acc.getFilesDirectory() != null && !acc.getFilesDirectory().isBlank()) {
            filesPath = Path.of(acc.getFilesDirectory());
        } else if (properties.getSharedFilesDirectory() != null && !properties.getSharedFilesDirectory().isBlank()) {
            filesPath = Path.of(properties.getSharedFilesDirectory());
        } else if (tdlibFilesDirectory != null && !tdlibFilesDirectory.isBlank()) {
            filesPath = Path.of(tdlibFilesDirectory);
        } else {
            filesPath = sessionRoot.resolve("files");
        }

        TDLibSettings settings = TDLibSettings.create(new APIToken(acc.getApiId(), acc.getApiHash()));
        settings.setDatabaseDirectoryPath(dbPath);
        settings.setDownloadedFilesDirectoryPath(filesPath);
        settings.setDeviceModel("SpringUserBot");
        settings.setSystemLanguageCode("en");
        settings.setApplicationVersion("1.0.0");

        // Role-aware TDLib cache: replica-personas skip on-disk message/file caches to
        // avoid N-times duplication of chat data across independent TDLib sessions.
        if (appSettingsService.getBoolean("tdlib.role-aware-cache.enabled", true)) {
            boolean isCollector = Boolean.TRUE.equals(
                    telegramAccountRepository.isCollector(acc.getBotId()).block());
            if (!isCollector) {
                boolean useMessageDb = appSettingsService.getBoolean(
                        "tdlib.reply-persona.use-message-database", false);
                boolean useFileDb = appSettingsService.getBoolean(
                        "tdlib.reply-persona.use-file-database", false);
                settings.setMessageDatabaseEnabled(useMessageDb);
                settings.setFileDatabaseEnabled(useFileDb);
                log.info("Role-aware TDLib cache: botId={} is a reply-persona — " +
                                "messageDb={} fileDb={} chatInfoDb=true",
                        acc.getBotId(), useMessageDb, useFileDb);
            } else {
                log.info("Role-aware TDLib cache: botId={} is the collector — full caching retained",
                        acc.getBotId());
            }
        }

        log.info("Creating isolated SimpleTelegramClientFactory for botId={}", acc.getBotId());
        SimpleTelegramClientFactory factory = new SimpleTelegramClientFactory();
        SimpleTelegramClientBuilder clientBuilder = factory.builder(settings);
        CountDownLatch authLatch = new CountDownLatch(1);
        var authState = new java.util.concurrent.atomic.AtomicReference<TdApi.AuthorizationState>();
        clientBuilder.addUpdateHandler(TdApi.UpdateAuthorizationState.class, update -> {
            TdApi.AuthorizationState st = update.authorizationState;
            log.info("TDLib auth state for botId={}: {}", acc.getBotId(), st.getClass().getSimpleName());
            if (st instanceof TdApi.AuthorizationStateReady || st instanceof TdApi.AuthorizationStateClosed || st instanceof TdApi.AuthorizationStateClosing) {
                authState.set(st);
                authLatch.countDown();
            }
        });

        boolean headless = headlessAuthEnabled
                && acc.getPhoneNumber() != null && !acc.getPhoneNumber().isBlank();
        var clientRef = new java.util.concurrent.atomic.AtomicReference<SimpleTelegramClient>();
        if (headless) {
            // Headless login: TDLib sends the phone from config; the verification
            // code / 2FA password are fed by the owner through the admin API and
            // polled from bot.auth_codes. Existing authorized sessions go straight
            // to Ready and never enter these states, so they are untouched.
            clientBuilder.addUpdateHandler(TdApi.UpdateAuthorizationState.class, update -> {
                TdApi.AuthorizationState st = update.authorizationState;
                if (st instanceof TdApi.AuthorizationStateWaitCode) {
                    submitHeadlessSecret(clientRef, acc.getBotId(), TelegramAuthCodeService.KIND_CODE);
                } else if (st instanceof TdApi.AuthorizationStateWaitPassword) {
                    submitHeadlessSecret(clientRef, acc.getBotId(), TelegramAuthCodeService.KIND_PASSWORD);
                }
            });
        }

        AuthenticationSupplier<?> authenticationData = headless
                ? AuthenticationSupplier.user(acc.getPhoneNumber())
                : AuthenticationSupplier.consoleLogin();
        SimpleTelegramClient client = clientBuilder.build(authenticationData);
        clientRef.set(client);

        boolean authorized = authLatch.await(5, TimeUnit.MINUTES);
        if (!authorized) {
            client.close();
            throw new IllegalStateException("Authorization timeout for botId=" + acc.getBotId());
        }
        TdApi.AuthorizationState finalState = authState.get();
        if (!(finalState instanceof TdApi.AuthorizationStateReady)) {
            log.error("TDLib client for botId={} ended in state {} instead of AuthorizationStateReady — session may be corrupted",
                    acc.getBotId(), finalState.getClass().getSimpleName());
            client.close();
            throw new IllegalStateException("Client for botId=" + acc.getBotId() + " is not authorized: " + finalState.getClass().getSimpleName());
        }
        return new TdLightTelegramClient(client);
    }

    // Additional configuration bean for allowed command chat ID
    @Bean
    public Long allowedCommandChatId(@Value("${telegram.allowedCommandChatId:1000000001}") Long chatId) {
        log.info("Allowed command chat ID configured: {}", chatId);
        return chatId;
    }

    // Створюємо пул потоків для фонових завдань, таких як завантаження медіа
    // Розмір пулу можна налаштувати (наприклад, 5 потоків)
    @Bean(name = "mediaTaskExecutor")
    public ExecutorService mediaTaskExecutor() {
        // Використовуємо кешований пул або фіксований, залежно від очікуваного навантаження
        // return Executors.newCachedThreadPool();
        return Executors.newFixedThreadPool(5, r -> {
            Thread t = new Thread(r);
            t.setName("media-downloader-" + t.threadId());
            t.setDaemon(true); // Робимо потоки демонами, щоб не блокували завершення JVM
            return t;
        });
    }
}
