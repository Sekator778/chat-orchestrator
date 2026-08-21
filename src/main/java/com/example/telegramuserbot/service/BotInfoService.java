package com.example.telegramuserbot.service;

import com.example.telegramuserbot.service.TelegramClientManager;
import com.example.telegramuserbot.telegram.TelegramClientFacade;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Service to manage bot information and provide access to bot's user ID.
 * This service is separate from TelegramListenerService to avoid circular dependencies.
 * <p>
 * NOTE: This holds runtime Telegram account info (id, first name) fetched via GetMe.
 * It is not related to the persona/legend for LLM responses stored in bot_personas.
 */
@Service
public class BotInfoService {

    private static final Logger log = LoggerFactory.getLogger(BotInfoService.class);

    private final TelegramClientManager telegramClientManager;
    private volatile Long botUserId = null;
    private volatile String botFirstName = null;

    public BotInfoService(TelegramClientManager telegramClientManager) {
        this.telegramClientManager = telegramClientManager;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initializeBotInfo() {
        log.info("BotInfoService: Fetching bot's own user information...");

        TelegramClientFacade client = telegramClientManager.getAnyClient();
        if (client == null) {
            log.warn("BotInfoService: No Telegram client available, skipping GetMe");
            return;
        }

        Mono.<TdApi.User>create(sink ->
                client.send(new TdApi.GetMe(), result -> {
                    if (result.isError()) {
                        sink.error(new RuntimeException("Failed to get bot info: " + result.getError().message));
                    } else {
                        sink.success(result.get());
                    }
                })
        ).subscribe(
                me -> {
                    this.botUserId = me.id;
                    this.botFirstName = me.firstName;
                    log.info("BotInfoService: Successfully fetched bot info. UserID: {}, FirstName: {}",
                            this.botUserId, this.botFirstName);
                },
                error -> log.error("!!! FAILED to get bot's own info in BotInfoService (GetMe): {}", error.getMessage(), error)
        );
    }

    /**
     * Get the bot's user ID (after initialization).
     * @return Bot's user ID or null if not initialized yet
     */
    public Long getBotUserId() {
        return botUserId;
    }

    /**
     * Get the bot's first name (after initialization).
     * @return Bot's first name or null if not initialized yet
     */
    public String getBotFirstName() {
        return botFirstName;
    }

    /**
     * Check if bot info has been initialized.
     * @return true if bot info is available
     */
    public boolean isInitialized() {
        return botUserId != null;
    }
}
