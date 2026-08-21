package com.example.telegramuserbot.config;

/**
 * Removed: the @Primary TelegramClientFacade bean that previously bound every
 * @Autowired TelegramClientFacade injection to account #1 at startup.
 *
 * All former injection sites now receive TelegramClientManager directly and call
 * telegramClientManager.getAnyClient() at point-of-use.
 *
 * The SmokeTelegramClientConfig (telegram.client.enabled=false) still registers a
 * @Primary NoOpTelegramClientFacade for the smoke/test profile; that path is unaffected.
 */
public final class TelegramClientConfig {
    private TelegramClientConfig() {}
}
