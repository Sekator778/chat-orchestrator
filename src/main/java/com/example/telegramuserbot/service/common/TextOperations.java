package com.example.telegramuserbot.service.common;

/**
 * Provides text manipulation utilities for JSON escaping, HTML escaping, and truncation.
 *
 * <p>This service consolidates all text utility methods scattered across the codebase,
 * eliminating ~80+ lines of duplicated code across 12 service files.
 *
 * <p>Usage example:
 * <pre>{@code
 * @Autowired
 * private TextOperations textOps;
 *
 * String json = textOps.escapeJson(unsafeText);
 * String html = textOps.escapeHtml(unsafeText);
 * String truncated = textOps.truncate(longText, 100);
 * String logged = textOps.truncateForLog(content, 80);
 * }</pre>
 *
 * @see com.example.telegramuserbot.service.orchestration.PromptJsonSerializer
 * @see com.example.telegramuserbot.service.publishing.TelegramPostRenderer
 */
public interface TextOperations {

    /**
     * Default ellipsis suffix used for truncation.
     */
    String ELLIPSIS = "...";

    /**
     * Default suffix used for logging truncation.
     */
    String LOG_TRUNCATION_SUFFIX = " ...[truncated]";

    /**
     * Telegram maximum message length.
     */
    int TELEGRAM_MESSAGE_LIMIT = 4096;

    /**
     * Escapes text for safe inclusion in JSON strings.
     *
     * <p>Handles the following characters:
     * <ul>
     *   <li>Backslash (\) becomes \\</li>
     *   <li>Double quote (") becomes \"</li>
     *   <li>Newline (\n) becomes \\n</li>
     *   <li>Carriage return (\r) becomes \\r</li>
     * </ul>
     *
     * @param text the text to escape, may be null
     * @return escaped text safe for JSON, empty string if input is null
     */
    String escapeJson(String text);

    /**
     * Escapes HTML special characters for Telegram HTML format.
     *
     * <p>Handles the following characters:
     * <ul>
     *   <li>Ampersand (&amp;) becomes &amp;amp;</li>
     *   <li>Less than (&lt;) becomes &amp;lt;</li>
     *   <li>Greater than (&gt;) becomes &amp;gt;</li>
     * </ul>
     *
     * @param text the text to escape, may be null
     * @return escaped text safe for HTML, empty string if input is null
     */
    String escapeHtml(String text);

    /**
     * Truncates text to maximum length with ellipsis suffix.
     *
     * <p>If text exceeds maxLength, it will be cut to (maxLength - 3)
     * characters and "..." will be appended.
     *
     * @param text the text to truncate, may be null
     * @param maxLength the maximum length including ellipsis
     * @return truncated text with "...", or original text if within limit, null if input is null
     */
    String truncate(String text, int maxLength);

    /**
     * Truncates text with a custom suffix.
     *
     * <p>If text exceeds limit, it will be cut to (limit - suffix.length())
     * characters and the suffix will be appended.
     *
     * @param text the text to truncate, may be null
     * @param limit the maximum length including suffix
     * @param suffix the suffix to append if truncated
     * @return truncated text with suffix, or original text if within limit, empty string if input is null
     */
    String truncateWithSuffix(String text, int limit, String suffix);

    /**
     * Truncates text for logging with standard log truncation suffix.
     *
     * <p>Uses " ...[truncated]" as suffix. Suitable for debug/info logging.
     *
     * @param text the text to truncate, may be null
     * @param maxLength the maximum length including suffix
     * @return truncated text with suffix, or original text if within limit, empty string if input is null
     */
    String truncateForLog(String text, int maxLength);

    /**
     * Truncates text for logging with whitespace normalization.
     *
     * <p>First normalizes multiple whitespace to single space, then truncates.
     * Useful for compact log output.
     *
     * @param text the text to truncate, may be null
     * @param maxLength the maximum length including ellipsis
     * @return normalized and truncated text, empty string if input is null
     */
    String truncateForLogNormalized(String text, int maxLength);

    /**
     * Truncates text to Telegram message limit (4096 characters).
     *
     * <p>Logs a warning if truncation occurs.
     *
     * @param text the text to truncate, may be null
     * @return truncated text with "...", or original text if within limit, null if input is null
     */
    String truncateTelegramMessage(String text);

    /**
     * Truncates text for payload logging with character count in truncation message.
     *
     * <p>Format: "...text...\n...[truncated X chars]" where X is the number
     * of characters removed.
     *
     * @param text the text to truncate, may be null
     * @param limit the maximum length before truncation info
     * @return truncated text with char count, or original text if within limit, null if input is null
     */
    String truncateForPayloadLogging(String text, int limit);
}
