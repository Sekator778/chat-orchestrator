package com.example.telegramuserbot.service.telegram;

import java.util.List;

/**
 * Decides what a failed outbound send means for the chat it was aimed at.
 * <p>
 * The reply path used to answer that question with a denylist: everything that
 * was not a FLOOD_WAIT or a missing client was treated as "this chat has denied
 * us access" and written to {@code bot.problematic_chats}, which mutes the chat
 * permanently — there is no un-mark path. That reading is wrong for every
 * failure mode nobody thought of in advance, and one of them is our own
 * emergency stop: while the outbound kill switch is active the client facade
 * completes each send with a synthetic error, so flipping the switch quietly
 * blacklisted every chat a persona tried to answer during the window, and
 * flipping it back did not bring them back.
 * <p>
 * So the question is asked the other way round here: a chat is muted only for
 * an error that actually names an access problem. Anything unrecognized is
 * logged and retried on the next message — the cost of a wrong "transient" is
 * one wasted send, the cost of a wrong "permanent" is a chat that never speaks
 * again.
 */
public final class SendFailureClassifier {

    /**
     * Telegram/TDLib errors that mean the account genuinely cannot post here.
     * Matched case-insensitively against the whole cause chain.
     */
    private static final List<String> PERMANENT_ACCESS_ERRORS = List.of(
            "CHAT_WRITE_FORBIDDEN",
            "USER_BANNED_IN_CHANNEL",
            "CHANNEL_PRIVATE",
            "CHAT_ADMIN_REQUIRED",
            "USER_IS_BLOCKED",
            "PEER_ID_INVALID",
            "CHAT_RESTRICTED",
            "CHAT_SEND_PLAIN_FORBIDDEN",
            "USER_PRIVACY_RESTRICTED",
            "CHAT_NOT_FOUND",
            "have no write access to the chat",
            "not enough rights to send text messages"
    );

    /**
     * Failures that self-recover on their own schedule. Kept separate from
     * "not permanent" only so the log can say which one it was.
     */
    private static final List<String> TRANSIENT_ERRORS = List.of(
            "FLOOD_WAIT",
            "No telegram client",
            "Outbound kill switch is ACTIVE",
            "Too Many Requests"
    );

    private SendFailureClassifier() {
    }

    /** True only for errors that name a real, standing access problem. */
    public static boolean isPermanentAccessError(Throwable error) {
        return matches(error, PERMANENT_ACCESS_ERRORS);
    }

    /** True for known self-recovering failures; unknown errors are neither. */
    public static boolean isTransientSendError(Throwable error) {
        return matches(error, TRANSIENT_ERRORS);
    }

    /**
     * Joins every message in the cause chain. The previous version looked one
     * level deep, so a reason wrapped twice — the common shape here, since the
     * send error is re-wrapped as an IOException — was invisible to the match.
     */
    public static String extractMessage(Throwable error) {
        StringBuilder chain = new StringBuilder();
        Throwable current = error;
        int depth = 0;
        while (current != null && depth < 10) {
            if (current.getMessage() != null) {
                if (chain.length() > 0) {
                    chain.append(" | ");
                }
                chain.append(current.getMessage());
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
            depth++;
        }
        return chain.length() == 0 ? null : chain.toString();
    }

    private static boolean matches(Throwable error, List<String> needles) {
        String message = extractMessage(error);
        if (message == null) {
            return false;
        }
        String lowered = message.toLowerCase();
        return needles.stream().anyMatch(needle -> lowered.contains(needle.toLowerCase()));
    }
}
