package com.example.telegramuserbot.util;

/**
 * Utility methods for converting Telegram chat identifiers between the TDLib format
 * (supergroups/channels use the "-100" prefix) and the canonical format we store in the database.
 */
public final class TelegramChatIdUtils {

    private static final String SUPERGROUP_PREFIX = "-100";

    private TelegramChatIdUtils() {
    }

    /**
     * Normalizes a chat ID so that supergroup/channel identifiers are stored without the "-100" prefix.
     *
     * @param chatId raw chat ID (may include the "-100" prefix)
     * @return canonical chat ID without the prefix, or {@code null} if the input was null
     */
    public static Long normalizeChatId(Long chatId) {
        if (chatId == null) {
            return null;
        }

        String value = chatId.toString();
        if (value.startsWith(SUPERGROUP_PREFIX) && value.length() > SUPERGROUP_PREFIX.length()) {
            try {
                return Long.parseLong(value.substring(SUPERGROUP_PREFIX.length()));
            } catch (NumberFormatException ex) {
                return chatId;
            }
        }
        return chatId;
    }

    /**
     * Normalizes a primitive chat ID.
     */
    public static long normalizeChatId(long chatId) {
        return normalizeChatId(Long.valueOf(chatId));
    }

    /**
     * Ensures the chat ID uses the "-100" prefix that TDLib expects for supergroups/channels.
     * If the ID already contains the prefix, it is returned as-is. For non-supergroup IDs the input is returned untouched.
     *
     * @param chatId canonical chat ID
     * @return TDLib-ready chat ID with "-100" prefix when applicable
     */
    public static Long ensureSupergroupPrefix(Long chatId) {
        if (chatId == null) {
            return null;
        }

        String value = chatId.toString();
        if (value.startsWith(SUPERGROUP_PREFIX)) {
            return chatId;
        }

        if (chatId > 0) {
            try {
                return Long.parseLong(SUPERGROUP_PREFIX + chatId);
            } catch (NumberFormatException ex) {
                return chatId;
            }
        }

        return chatId;
    }

    public static long ensureSupergroupPrefix(long chatId) {
        return ensureSupergroupPrefix(Long.valueOf(chatId));
    }
}
