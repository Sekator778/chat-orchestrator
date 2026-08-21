package com.example.telegramuserbot.telegram;

/**
 * Listener for TDLib client lifecycle events.
 * Implementations are auto-discovered by {@link com.example.telegramuserbot.service.TelegramClientManager}
 * and called for every client on initialization (primary and secondary alike).
 *
 * <p>Every client is treated equally: the same {@link #onClientReady} method is called
 * regardless of whether the client is primary or secondary.</p>
 */
public interface TelegramClientLifecycleListener {

    /**
     * Called when a TDLib client has been fully initialized and is ready for use.
     *
     * @param botId  the bot instance identifier (e.g. "2000000001")
     * @param client the initialized TDLib client facade
     */
    void onClientReady(String botId, TelegramClientFacade client);
}
