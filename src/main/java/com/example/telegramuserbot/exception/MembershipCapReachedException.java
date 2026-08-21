package com.example.telegramuserbot.exception;

/**
 * Thrown when a TDLib {@code JoinChat} call is rejected because the collector
 * account has reached Telegram's channel-membership cap (CHANNELS_TOO_MUCH).
 *
 * <p>This exception is intentionally <em>not</em> caught by the generic
 * "mark-processed" error handler so the pending candidate is NOT discarded —
 * it will be retried on the next join sweep once headroom has returned
 * (channels have been left and the at-cap flag is cleared).
 */
public class MembershipCapReachedException extends RuntimeException {

    private final String botId;
    private final long chatId;

    public MembershipCapReachedException(String botId, long chatId, String tdlibMessage) {
        super("Collector botId=" + botId + " at membership cap (CHANNELS_TOO_MUCH) for chatId=" + chatId
                + ": " + tdlibMessage);
        this.botId = botId;
        this.chatId = chatId;
    }

    public String getBotId() {
        return botId;
    }

    public long getChatId() {
        return chatId;
    }
}
