package com.example.telegramuserbot.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BotInstanceIdNormalizerTest {

    @Test
    void splitCandidatesShouldParsePostgresArrayStyle() {
        assertThat(BotInstanceIdNormalizer.splitCandidates("{2000000001, 2000000002}"))
                .containsExactly("2000000001", "2000000002");
    }

    @Test
    void normalizeSingleOrDefaultShouldPickFirstCandidate() {
        assertThat(BotInstanceIdNormalizer.normalizeSingleOrDefault("{a,b}", "fallback"))
                .isEqualTo("a");
    }

    @Test
    void normalizeSingleOrDefaultShouldUseDefaultForDefaultBot() {
        assertThat(BotInstanceIdNormalizer.normalizeSingleOrDefault("default-bot", "2000000001"))
                .isEqualTo("2000000001");
    }
}

