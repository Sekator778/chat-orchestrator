package com.example.telegramuserbot.service;

import com.example.telegramuserbot.config.BotInstanceProvider;
import com.example.telegramuserbot.dto.BotHealthResponse;
import com.example.telegramuserbot.dto.BotStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a health snapshot of all configured bot instances by comparing the
 * bots declared in {@code bot.persona-ids} against the TDLib clients that
 * actually initialized at startup. An initialized client means the bot
 * reached {@code AuthorizationStateReady}.
 */
@Service
public class BotHealthService {

    private static final Logger log = LoggerFactory.getLogger(BotHealthService.class);

    private static final String STATUS_UP = "UP";
    private static final String STATUS_DEGRADED = "DEGRADED";
    private static final String STATUS_DOWN = "DOWN";

    private final BotInstanceProvider botInstanceProvider;
    private final TelegramClientManager telegramClientManager;

    public BotHealthService(BotInstanceProvider botInstanceProvider,
                            TelegramClientManager telegramClientManager) {
        this.botInstanceProvider = botInstanceProvider;
        this.telegramClientManager = telegramClientManager;
    }

    /**
     * Returns the current health snapshot for every configured bot instance.
     */
    public BotHealthResponse getBotHealth() {
        List<String> configured = botInstanceProvider.getInstanceIds();
        String primaryId = botInstanceProvider.getInstanceId();

        List<BotStatus> bots = new ArrayList<>(configured.size());
        int initializedCount = 0;
        for (String botId : configured) {
            boolean initialized = telegramClientManager.getClient(botId) != null;
            if (initialized) {
                initializedCount++;
            }
            bots.add(new BotStatus(
                    botId,
                    botId.equals(primaryId),
                    initialized,
                    initialized ? STATUS_UP : STATUS_DOWN
            ));
        }

        String overall = overallStatus(configured.size(), initializedCount);
        boolean pendingSecondary = telegramClientManager.hasPendingSecondaryClients();
        log.debug("Bot health: status={}, configured={}, initialized={}, pendingSecondary={}",
                overall, configured.size(), initializedCount, pendingSecondary);

        return new BotHealthResponse(
                overall,
                configured.size(),
                initializedCount,
                pendingSecondary,
                bots
        );
    }

    private static String overallStatus(int configuredCount, int initializedCount) {
        if (initializedCount == 0) {
            return STATUS_DOWN;
        }
        if (initializedCount < configuredCount) {
            return STATUS_DEGRADED;
        }
        return STATUS_UP;
    }
}
