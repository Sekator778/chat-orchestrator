package com.example.telegramuserbot.service.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Deduplication fingerprint for message text: collapse whitespace, lowercase,
 * SHA-1 hex. Byte-for-byte compatible with the original Python scanner
 * (Telegram_scaner extract.py), so hashes computed by either side match.
 * SHA-1 is fine here — it is a dedup key, not a security primitive.
 */
public final class ContentHash {

    private ContentHash() {
    }

    public static String of(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalized = String.join(" ", text.trim().split("\\s+")).toLowerCase(Locale.ROOT);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }
}
