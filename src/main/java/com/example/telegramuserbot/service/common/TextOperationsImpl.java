package com.example.telegramuserbot.service.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Implementation of text manipulation utilities.
 *
 * <p>Consolidates JSON escaping, HTML escaping, and text truncation logic
 * previously duplicated across multiple services.
 *
 * <p>Thread-safe: all methods are stateless and can be called concurrently.
 */
@Service
public final class TextOperationsImpl implements TextOperations {

    private static final Logger LOG = LoggerFactory.getLogger(TextOperationsImpl.class);

    @Override
    public String escapeJson(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    @Override
    public String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    @Override
    public String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        if (maxLength <= ELLIPSIS.length()) {
            return text.length() <= maxLength ? text : text.substring(0, maxLength);
        }
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength - ELLIPSIS.length()) + ELLIPSIS;
    }

    @Override
    public String truncateWithSuffix(String text, int limit, String suffix) {
        if (text == null) {
            return "";
        }
        if (text.length() <= limit) {
            return text;
        }
        String effectiveSuffix = suffix != null ? suffix : "";
        int cutPoint = Math.max(0, limit - effectiveSuffix.length());
        return text.substring(0, cutPoint) + effectiveSuffix;
    }

    @Override
    public String truncateForLog(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (text.length() <= maxLength) {
            return text;
        }
        int cutPoint = Math.max(0, maxLength - LOG_TRUNCATION_SUFFIX.length());
        return text.substring(0, cutPoint) + LOG_TRUNCATION_SUFFIX;
    }

    @Override
    public String truncateForLogNormalized(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        int cutPoint = Math.max(0, maxLength - ELLIPSIS.length());
        return normalized.substring(0, cutPoint) + ELLIPSIS;
    }

    @Override
    public String truncateTelegramMessage(String text) {
        if (text == null) {
            return null;
        }
        if (text.length() <= TELEGRAM_MESSAGE_LIMIT) {
            return text;
        }
        LOG.warn("Truncating Telegram message from {} to {} characters", text.length(), TELEGRAM_MESSAGE_LIMIT);
        return text.substring(0, TELEGRAM_MESSAGE_LIMIT - ELLIPSIS.length()) + ELLIPSIS;
    }

    @Override
    public String truncateForPayloadLogging(String text, int limit) {
        if (text == null) {
            return null;
        }
        if (text.length() <= limit) {
            return text;
        }
        int charsRemoved = text.length() - limit;
        return text.substring(0, limit) + "\n...[truncated " + charsRemoved + " chars]";
    }
}
