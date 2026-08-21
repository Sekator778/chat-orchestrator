package com.example.telegramuserbot.service.safety;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test for outbound moderation: a self-identifying / denylisted reply
 * is suppressed (fail-closed to silence); normal replies pass.
 */
class OutboundReplyGuardTest {

    private static final String DEFAULT_DENYLIST =
            "я бот,я штучний інтелект,я ai,i am an ai,i'm an ai,i am a bot,i'm a bot,as an ai,language model";

    private final OutboundReplyGuard guard = new OutboundReplyGuard(DEFAULT_DENYLIST);

    @Test
    void suppressesSelfIdentifyingReply() {
        assertThat(guard.shouldSuppress("Честно говоря, я бот, но помогу")).isTrue();
        assertThat(guard.shouldSuppress("As an AI, I cannot do that")).isTrue();
        assertThat(guard.shouldSuppress("I'm just a large language model")).isTrue();
    }

    @Test
    void allowsNormalReply() {
        assertThat(guard.shouldSuppress("Думаю, нефть пойдёт вверх на этой неделе")).isFalse();
        assertThat(guard.shouldSuppress("Oil looks bullish this week")).isFalse();
    }

    @Test
    void blankIsNotTheGuardsConcern() {
        assertThat(guard.shouldSuppress(null)).isFalse();
        assertThat(guard.shouldSuppress("  ")).isFalse();
    }
}
