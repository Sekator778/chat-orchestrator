package com.example.telegramuserbot.exception;

/**
 * Indicates that synchronization was requested for a channel where sync has been disabled.
 */
public class SyncDisabledException extends RuntimeException {

    private final Long channelId;
    private final String channelTitle;

    public SyncDisabledException(Long channelId, String channelTitle) {
        super(buildMessage(channelId, channelTitle));
        this.channelId = channelId;
        this.channelTitle = channelTitle;
    }

    private static String buildMessage(Long channelId, String channelTitle) {
        return String.format("Message synchronization is disabled for channel %s (ID: %d).",
                channelTitle, channelId);
    }

    public Long getChannelId() {
        return channelId;
    }

    public String getChannelTitle() {
        return channelTitle;
    }
}
