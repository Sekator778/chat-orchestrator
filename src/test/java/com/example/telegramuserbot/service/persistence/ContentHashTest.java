package com.example.telegramuserbot.service.persistence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test: the fingerprint must stay byte-compatible with the original
 * Python scanner (" ".join(text.split()).lower() → sha1 hex), or dedup against
 * historically hashed rows silently breaks.
 */
class ContentHashTest {

    @Test
    void matchesPythonScannerFingerprint() {
        // sha1("hello world") — canonical vector
        assertThat(ContentHash.of("  Hello \n\t WORLD  "))
                .isEqualTo("2aae6c35c94fcfb415dbe95f408b9ce91ee846ed");
    }

    @Test
    void blankAndNullProduceNull() {
        assertThat(ContentHash.of(null)).isNull();
        assertThat(ContentHash.of("   \n ")).isNull();
    }
}
