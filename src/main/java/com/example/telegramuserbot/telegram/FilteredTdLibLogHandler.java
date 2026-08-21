package com.example.telegramuserbot.telegram;

import it.tdlight.tdnative.NativeClient.LogMessageHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Custom log handler for TDLib that filters verbose messages
 * and routes them to appropriate SLF4J log levels.
 *
 * <p>Includes metrics to monitor filtering overhead.</p>
 */
public final class FilteredTdLibLogHandler implements LogMessageHandler {

    private static final Logger log = LoggerFactory.getLogger("tdlib");

    // Metrics for monitoring - static so they persist across instances
    private static final AtomicLong totalMessages = new AtomicLong(0);
    private static final AtomicLong filteredMessages = new AtomicLong(0);
    private static final AtomicLong dialogDateWarnings = new AtomicLong(0);
    private static volatile long lastReportTime = System.currentTimeMillis();
    private static final long REPORT_INTERVAL_MS = 60_000; // Report every minute

    @Override
    public void onLogMessage(int verbosityLevel, String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        totalMessages.incrementAndGet();
        if (shouldFilter(message)) {
            filteredMessages.incrementAndGet();
            if (message.contains("dialog date")) {
                dialogDateWarnings.incrementAndGet();
            }
            maybeReportMetrics();
            return;
        }
        switch (verbosityLevel) {
            case 0 -> log.error("[TDLib] {}", message);
            case 1 -> log.warn("[TDLib] {}", message);
            case 2 -> log.info("[TDLib] {}", message);
            default -> log.debug("[TDLib] {}", message);
        }
    }

    private void maybeReportMetrics() {
        long now = System.currentTimeMillis();
        if (now - lastReportTime > REPORT_INTERVAL_MS) {
            lastReportTime = now;
            long total = totalMessages.get();
            long filtered = filteredMessages.get();
            long dialogWarnings = dialogDateWarnings.get();
            if (dialogWarnings > 0) {
                log.info("[TDLib-Metrics] total={}, filtered={}, dialogDateWarnings={} (if growing rapidly, investigate)",
                        total, filtered, dialogWarnings);
            }
        }
    }

    /**
     * Returns current metrics for external monitoring (e.g., actuator endpoint).
     */
    public static TdLibLogMetrics getMetrics() {
        return new TdLibLogMetrics(
                totalMessages.get(),
                filteredMessages.get(),
                dialogDateWarnings.get()
        );
    }

    /**
     * Resets metrics counters. Useful for testing or periodic reset.
     */
    public static void resetMetrics() {
        totalMessages.set(0);
        filteredMessages.set(0);
        dialogDateWarnings.set(0);
        lastReportTime = System.currentTimeMillis();
    }

    public record TdLibLogMetrics(long totalMessages, long filteredMessages, long dialogDateWarnings) {
        public double filterRatio() {
            return totalMessages > 0 ? (double) filteredMessages / totalMessages : 0.0;
        }
    }

    private boolean shouldFilter(String message) {
        return message.contains("Emojis database is not ready")
                || message.contains("updateAuthorizationState")
                || message.contains("updateConnectionState")
                || message.contains("setTdlibParameters")
                || message.contains("connection state")
                || message.contains("polling for updates")
                || message.contains("update is received")
                || message.contains("Update is ignored")
                || message.contains("Receive update")
                || message.startsWith("Check")
                // Multi-client TDLib warnings - harmless during concurrent client initialization
                // TDLib internally auto-syncs chats on authorization, causing pagination state
                // collisions when multiple clients run in the same process. Not a code bug.
                || message.contains("dialog date didn't increase")
                || message.contains("Last server dialog date");
    }
}
