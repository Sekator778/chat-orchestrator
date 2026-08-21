package com.example.telegramuserbot.dto.digest;

/**
 * Request for generating a test digest.
 * Allows customization of test parameters.
 *
 * @param lookbackHours hours of messages to look back (default: persona setting)
 * @param maxMessages maximum messages to include (default: persona setting)
 * @param preview if true, generates preview without persisting (default: true)
 */
public record DigestTestRequest(
        Integer lookbackHours,
        Integer maxMessages,
        Boolean preview
) {

    /**
     * Creates a default test request.
     *
     * @return default test request
     */
    public static DigestTestRequest defaults() {
        return new DigestTestRequest(null, null, true);
    }

    /**
     * Gets whether this is a preview-only request.
     *
     * @return true if preview mode
     */
    public boolean isPreview() {
        return preview == null || preview;
    }

    /**
     * Gets the lookback hours or default.
     *
     * @param defaultValue default if not specified
     * @return lookback hours
     */
    public int lookbackHoursOr(int defaultValue) {
        return lookbackHours != null && lookbackHours > 0 ? lookbackHours : defaultValue;
    }

    /**
     * Gets the max messages or default.
     *
     * @param defaultValue default if not specified
     * @return max messages
     */
    public int maxMessagesOr(int defaultValue) {
        return maxMessages != null && maxMessages > 0 ? maxMessages : defaultValue;
    }
}
