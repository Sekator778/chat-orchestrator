package com.example.telegramuserbot.util;

/**
 * Normalises and validates Telegram channel username strings.
 * <p>
 * Strips common prefixes ({@code @}, {@code https://t.me/}, {@code http://t.me/},
 * {@code t.me/}) from a channel name input and validates the resulting bare name
 * against Telegram's public-channel username rules: 5–32 characters,
 * {@code [a-zA-Z0-9_]}, first character not a digit.
 * <p>
 * Thread-safe, stateless, no Spring dependency, no I/O.
 */
public final class ChannelNameNormalizer {

    private static final int MIN_LENGTH = 5;
    private static final int MAX_LENGTH = 32;

    private static final String HTTPS_TME = "https://t.me/";
    private static final String HTTP_TME = "http://t.me/";
    private static final String BARE_TME = "t.me/";

    private ChannelNameNormalizer() {
    }

    /**
     * Normalises a raw channel name input and returns the bare name together with a
     * validity flag.
     *
     * @param input raw channel name (may be {@code null}, blank, prefixed, or a URL)
     * @return result record containing {@code bareName} (never {@code null}) and
     *         {@code valid} flag
     */
    public static ChannelNameResult normalize(String input) {
        if (input == null || input.isBlank()) {
            return new ChannelNameResult("", false);
        }

        String candidate = input;

        // FR-001: strip leading @
        if (candidate.startsWith("@")) {
            candidate = candidate.substring(1);
        }

        // FR-002: strip https://t.me/ (case-insensitive)
        if (regionMatchesIgnoreCase(candidate, 0, HTTPS_TME)) {
            candidate = candidate.substring(HTTPS_TME.length());
        }
        // FR-004: strip http://t.me/ (case-insensitive)
        else if (regionMatchesIgnoreCase(candidate, 0, HTTP_TME)) {
            candidate = candidate.substring(HTTP_TME.length());
        }
        // FR-003: strip bare t.me/ (case-insensitive); guaranteed not preceded by http:// or https://
        else if (regionMatchesIgnoreCase(candidate, 0, BARE_TME)) {
            candidate = candidate.substring(BARE_TME.length());
        }
        // FR-005: no matching prefix — treat as-is (implicit)

        // FR-006: strip ? and everything after (query string)
        int queryIndex = candidate.indexOf('?');
        if (queryIndex != -1) {
            candidate = candidate.substring(0, queryIndex);
        }

        // FR-007: strip / and everything after (first path separator)
        int slashIndex = candidate.indexOf('/');
        if (slashIndex != -1) {
            candidate = candidate.substring(0, slashIndex);
        }

        // FR-008: trim leading and trailing whitespace
        candidate = candidate.trim();

        // FR-010: validate against Telegram username rules
        boolean valid = isValid(candidate);

        return new ChannelNameResult(candidate, valid);
    }

    /**
     * Checks whether {@code value} contains {@code prefix} at offset 0, ignoring case.
     */
    private static boolean regionMatchesIgnoreCase(String value, int toffset, String prefix) {
        return value.regionMatches(true, toffset, prefix, 0, prefix.length());
    }

    /**
     * Validates a bare channel name against Telegram's public-channel username rules.
     * <ul>
     *   <li>length between {@value #MIN_LENGTH} and {@value #MAX_LENGTH} characters inclusive</li>
     *   <li>every character is ASCII letter, digit, or underscore</li>
     *   <li>first character is not a digit</li>
     * </ul>
     */
    private static boolean isValid(String name) {
        int len = name.length();
        if (len < MIN_LENGTH || len > MAX_LENGTH) {
            return false;
        }
        for (int i = 0; i < len; i++) {
            char c = name.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_')) {
                return false;
            }
        }
        char first = name.charAt(0);
        if (first >= '0' && first <= '9') {
            return false;
        }
        return true;
    }

    /**
     * Result of a channel name normalisation.
     *
     * @param bareName the stripped bare channel name (never {@code null}, may be empty)
     * @param valid    {@code true} if the bare name satisfies Telegram username rules
     */
    public record ChannelNameResult(String bareName, boolean valid) {
    }
}
