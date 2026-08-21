package com.example.telegramuserbot.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelNameNormalizerTest {

    // AC-016: null safety
    @Test
    void normalizeShouldReturnEmptyAndInvalidForNullInput() {
        ChannelNameNormalizer.ChannelNameResult result = ChannelNameNormalizer.normalize(null);
        assertThat(result.bareName()).isEmpty();
        assertThat(result.valid()).isFalse();
    }

    // AC-017: empty string
    @Test
    void normalizeShouldReturnEmptyAndInvalidForEmptyInput() {
        ChannelNameNormalizer.ChannelNameResult result = ChannelNameNormalizer.normalize("");
        assertThat(result.bareName()).isEmpty();
        assertThat(result.valid()).isFalse();
    }

    // AC-018: blank (whitespace-only) string
    @Test
    void normalizeShouldReturnEmptyAndInvalidForBlankInput() {
        ChannelNameNormalizer.ChannelNameResult result = ChannelNameNormalizer.normalize("   ");
        assertThat(result.bareName()).isEmpty();
        assertThat(result.valid()).isFalse();
    }

    // AC-001: strip leading @
    @Test
    void normalizeShouldStripLeadingAt() {
        ChannelNameNormalizer.ChannelNameResult result = ChannelNameNormalizer.normalize("@durov");
        assertThat(result.bareName()).isEqualTo("durov");
        assertThat(result.valid()).isTrue();
    }

    // AC-002: strip https://t.me/ prefix
    @Test
    void normalizeShouldStripHttpsTmePrefix() {
        ChannelNameNormalizer.ChannelNameResult result = ChannelNameNormalizer.normalize("https://t.me/durov");
        assertThat(result.bareName()).isEqualTo("durov");
        assertThat(result.valid()).isTrue();
    }

    // AC-003: strip bare t.me/ prefix
    @Test
    void normalizeShouldStripBareTmePrefix() {
        ChannelNameNormalizer.ChannelNameResult result = ChannelNameNormalizer.normalize("t.me/durov");
        assertThat(result.bareName()).isEqualTo("durov");
        assertThat(result.valid()).isTrue();
    }

    // AC-004: strip http://t.me/ prefix
    @Test
    void normalizeShouldStripHttpTmePrefix() {
        ChannelNameNormalizer.ChannelNameResult result = ChannelNameNormalizer.normalize("http://t.me/durov");
        assertThat(result.bareName()).isEqualTo("durov");
        assertThat(result.valid()).isTrue();
    }

    // AC-005: no prefix, return as-is
    @Test
    void normalizeShouldReturnInputAsIsWhenNoPrefix() {
        ChannelNameNormalizer.ChannelNameResult result = ChannelNameNormalizer.normalize("durov");
        assertThat(result.bareName()).isEqualTo("durov");
        assertThat(result.valid()).isTrue();
    }

    // AC-006: case-insensitive prefix matching
    @Test
    void normalizeShouldMatchPrefixCaseInsensitively() {
        ChannelNameNormalizer.ChannelNameResult result = ChannelNameNormalizer.normalize("HTTPS://T.ME/durov");
        assertThat(result.bareName()).isEqualTo("durov");
        assertThat(result.valid()).isTrue();
    }

    @Test
    void normalizeShouldMatchHttpPrefixCaseInsensitively() {
        ChannelNameNormalizer.ChannelNameResult result = ChannelNameNormalizer.normalize("HTTP://T.ME/durov");
        assertThat(result.bareName()).isEqualTo("durov");
        assertThat(result.valid()).isTrue();
    }

    @Test
    void normalizeShouldMatchBareTmePrefixCaseInsensitively() {
        ChannelNameNormalizer.ChannelNameResult result = ChannelNameNormalizer.normalize("T.ME/durov");
        assertThat(result.bareName()).isEqualTo("durov");
        assertThat(result.valid()).isTrue();
    }

    // AC-007: strip query string after URL
    @Test
    void normalizeShouldStripQueryString() {
        ChannelNameNormalizer.ChannelNameResult result = ChannelNameNormalizer.normalize("https://t.me/durov?start=abc");
        assertThat(result.bareName()).isEqualTo("durov");
        assertThat(result.valid()).isTrue();
    }

    // AC-008: strip path fragment after URL
    @Test
    void normalizeShouldStripPathFragmentAfterUrl() {
        ChannelNameNormalizer.ChannelNameResult result = ChannelNameNormalizer.normalize("https://t.me/joinchat/abc123");
        assertThat(result.bareName()).isEqualTo("joinchat");
        // "joinchat" itself is a valid name (8 chars, alpha, starts with letter)
        assertThat(result.valid()).isTrue();
    }

    // AC-010: valid name returns valid=true
    @Test
    void normalizeShouldReturnValidTrueForValidName() {
        ChannelNameNormalizer.ChannelNameResult result = ChannelNameNormalizer.normalize("durov");
        assertThat(result.valid()).isTrue();
    }

    // AC-011: too short (< 5 chars) returns valid=false
    @Test
    void normalizeShouldReturnValidFalseForTooShortName() {
        ChannelNameNormalizer.ChannelNameResult result = ChannelNameNormalizer.normalize("abc");
        assertThat(result.bareName()).isEqualTo("abc");
        assertThat(result.valid()).isFalse();
    }

    // AC-012: too long (> 32 chars) returns valid=false
    @Test
    void normalizeShouldReturnValidFalseForTooLongName() {
        String longName = "a".repeat(33);
        ChannelNameNormalizer.ChannelNameResult result = ChannelNameNormalizer.normalize(longName);
        assertThat(result.bareName()).isEqualTo(longName);
        assertThat(result.valid()).isFalse();
    }

    // AC-013: leading digit returns valid=false
    @Test
    void normalizeShouldReturnValidFalseForLeadingDigit() {
        ChannelNameNormalizer.ChannelNameResult result = ChannelNameNormalizer.normalize("1durov");
        assertThat(result.bareName()).isEqualTo("1durov");
        assertThat(result.valid()).isFalse();
    }

    // AC-014: hyphen (disallowed char) returns valid=false
    @Test
    void normalizeShouldReturnValidFalseForHyphen() {
        ChannelNameNormalizer.ChannelNameResult result = ChannelNameNormalizer.normalize("du-rov");
        assertThat(result.bareName()).isEqualTo("du-rov");
        assertThat(result.valid()).isFalse();
    }

    // AC-015: underscore (allowed) returns valid=true
    @Test
    void normalizeShouldReturnValidTrueForUnderscore() {
        ChannelNameNormalizer.ChannelNameResult result = ChannelNameNormalizer.normalize("du_rov");
        assertThat(result.bareName()).isEqualTo("du_rov");
        assertThat(result.valid()).isTrue();
    }

    // FR-023: preserve original case
    @Test
    void normalizeShouldPreserveOriginalCase() {
        ChannelNameNormalizer.ChannelNameResult result = ChannelNameNormalizer.normalize("https://t.me/My_Channel");
        assertThat(result.bareName()).isEqualTo("My_Channel");
        assertThat(result.valid()).isTrue();
    }

    // FR-008: trim leading/trailing whitespace after prefix stripping
    @Test
    void normalizeShouldTrimWhitespaceAroundBareName() {
        ChannelNameNormalizer.ChannelNameResult result = ChannelNameNormalizer.normalize("  durov  ");
        assertThat(result.bareName()).isEqualTo("durov");
        assertThat(result.valid()).isTrue();
    }

    // Combination: @ then URL prefix
    @Test
    void normalizeShouldStripAtThenUrlPrefix() {
        ChannelNameNormalizer.ChannelNameResult result = ChannelNameNormalizer.normalize("@https://t.me/durov");
        assertThat(result.bareName()).isEqualTo("durov");
        assertThat(result.valid()).isTrue();
    }

    // Combination: @ then bare t.me/
    @Test
    void normalizeShouldStripAtThenBareTme() {
        ChannelNameNormalizer.ChannelNameResult result = ChannelNameNormalizer.normalize("@t.me/durov");
        assertThat(result.bareName()).isEqualTo("durov");
        assertThat(result.valid()).isTrue();
    }

    // Bare t.me/ with query string
    @Test
    void normalizeShouldStripBareTmeWithQueryString() {
        ChannelNameNormalizer.ChannelNameResult result = ChannelNameNormalizer.normalize("t.me/durov?start=abc");
        assertThat(result.bareName()).isEqualTo("durov");
        assertThat(result.valid()).isTrue();
    }

    // Bare name with trailing slash (path fragment on bare name after t.me/)
    @Test
    void normalizeShouldStripBareTmeWithPathFragment() {
        ChannelNameNormalizer.ChannelNameResult result = ChannelNameNormalizer.normalize("t.me/joinchat/abc123");
        assertThat(result.bareName()).isEqualTo("joinchat");
        assertThat(result.valid()).isTrue();
    }

    // Exactly 5 chars (minimum valid length)
    @Test
    void normalizeShouldReturnValidTrueForMinimumLengthName() {
        ChannelNameNormalizer.ChannelNameResult result = ChannelNameNormalizer.normalize("abcde");
        assertThat(result.valid()).isTrue();
    }

    // Exactly 32 chars (maximum valid length)
    @Test
    void normalizeShouldReturnValidTrueForMaximumLengthName() {
        String name = "a".repeat(32);
        ChannelNameNormalizer.ChannelNameResult result = ChannelNameNormalizer.normalize(name);
        assertThat(result.valid()).isTrue();
    }

    // Invalid character: digit in middle is OK, but special chars are not
    @Test
    void normalizeShouldReturnValidFalseForNameWithSpecialCharacters() {
        ChannelNameNormalizer.ChannelNameResult result = ChannelNameNormalizer.normalize("durov!!!");
        assertThat(result.valid()).isFalse();
    }

    // All-underscore name should fail because it starts with underscore (which is OK per spec)
    // Actually FR-010(c): first character is not an ASCII digit. Underscore is fine as first char.
    @Test
    void normalizeShouldReturnValidTrueForNameStartingWithUnderscore() {
        // Spec only forbids leading digit, not leading underscore
        ChannelNameNormalizer.ChannelNameResult result = ChannelNameNormalizer.normalize("_durov");
        assertThat(result.valid()).isTrue();
    }

    // Long URL with query string and path
    @Test
    void normalizeShouldHandleComplexUrl() {
        ChannelNameNormalizer.ChannelNameResult result = ChannelNameNormalizer.normalize("https://t.me/MyChannel/extra?query=value&foo=bar");
        assertThat(result.bareName()).isEqualTo("MyChannel");
        assertThat(result.valid()).isTrue();
    }

    // At-prefix with query string (no URL)
    @Test
    void normalizeShouldStripAtThenQueryString() {
        ChannelNameNormalizer.ChannelNameResult result = ChannelNameNormalizer.normalize("@durov?start=abc");
        assertThat(result.bareName()).isEqualTo("durov");
        assertThat(result.valid()).isTrue();
    }

    // Whitespace-only after stripping prefix
    @Test
    void normalizeShouldReturnEmptyAndInvalidWhenPrefixOnly() {
        ChannelNameNormalizer.ChannelNameResult result = ChannelNameNormalizer.normalize("https://t.me/");
        assertThat(result.bareName()).isEmpty();
        assertThat(result.valid()).isFalse();
    }

    // FR-003: t.me/ without http:// or https:// (confirmed by failing https:// first)
    @Test
    void normalizeShouldNotStripTmePrefixIfPrecededByHttp() {
        // "http://t.me/durov" should match FR-004, not FR-003
        ChannelNameNormalizer.ChannelNameResult result = ChannelNameNormalizer.normalize("http://t.me/durov");
        assertThat(result.bareName()).isEqualTo("durov");
        assertThat(result.valid()).isTrue();
    }
}
