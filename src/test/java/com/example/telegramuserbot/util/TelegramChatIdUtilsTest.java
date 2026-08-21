package com.example.telegramuserbot.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TelegramChatIdUtilsTest {

    // FR-002: normalizeChatId(null) must return null
    @Test
    void normalizeChatIdShouldReturnNullForNullInput() {
        assertThat(TelegramChatIdUtils.normalizeChatId((Long) null)).isNull();
    }

    // FR-003 / AC-003.1: supergroup id with -100 prefix → stripped result
    @Test
    void normalizeChatIdShouldStripSupergroupPrefix() {
        assertThat(TelegramChatIdUtils.normalizeChatId(-1001234567890L)).isEqualTo(1234567890L);
    }

    // FR-003 / AC-003.2: second supergroup id confirms prefix stripping is general
    @Test
    void normalizeChatIdShouldStripSupergroupPrefixForAnotherValue() {
        assertThat(TelegramChatIdUtils.normalizeChatId(-100999999999L)).isEqualTo(999999999L);
    }

    // FR-004 / AC-004.1: exactly "-100" (length == prefix length) must be returned unchanged
    @Test
    void normalizeChatIdShouldReturnUnchangedWhenValueIsExactlyMinus100() {
        assertThat(TelegramChatIdUtils.normalizeChatId(-100L)).isEqualTo(-100L);
    }

    // FR-005 / AC-005.1: positive id must be returned unchanged
    @Test
    void normalizeChatIdShouldReturnUnchangedForPositiveId() {
        assertThat(TelegramChatIdUtils.normalizeChatId(1234567890L)).isEqualTo(1234567890L);
    }

    // FR-005 / AC-005.2: negative non-supergroup id must be returned unchanged
    @Test
    void normalizeChatIdShouldReturnUnchangedForNegativeNonSupergroupId() {
        assertThat(TelegramChatIdUtils.normalizeChatId(-1234567L)).isEqualTo(-1234567L);
    }

    // FR-006: a large supergroup id near Long range still strips the -100 prefix.
    // (The NumberFormatException fallback in normalizeChatId is defensive and
    // unreachable for any valid Long — the post-prefix substring is always shorter
    // than the input and therefore in range — so we exercise the strip path at the
    // boundary instead of an unconstructible overflow.)
    @Test
    void normalizeChatIdShouldStripPrefixForLargeSupergroupId() {
        assertThat(TelegramChatIdUtils.normalizeChatId(-1001000000000000000L))
                .isEqualTo(1000000000000000L);
    }

    // FR-007 / AC-007.1: primitive normalizeChatId overload delegates to boxed overload
    @Test
    void normalizeChatIdPrimitiveOverloadShouldDelegateToBoxedOverload() {
        long primitiveResult = TelegramChatIdUtils.normalizeChatId(-1001234567890L);
        Long boxedResult = TelegramChatIdUtils.normalizeChatId(Long.valueOf(-1001234567890L));
        assertThat(primitiveResult).isEqualTo(boxedResult);
    }

    // FR-008 / AC-008.1: ensureSupergroupPrefix(null) must return null
    @Test
    void ensureSupergroupPrefixShouldReturnNullForNullInput() {
        assertThat(TelegramChatIdUtils.ensureSupergroupPrefix((Long) null)).isNull();
    }

    // FR-009 / AC-009.1: id already starting with -100 must be returned unchanged
    @Test
    void ensureSupergroupPrefixShouldReturnUnchangedWhenAlreadyPrefixed() {
        assertThat(TelegramChatIdUtils.ensureSupergroupPrefix(-1001234567890L)).isEqualTo(-1001234567890L);
    }

    // FR-010 / AC-010.1: positive id must get -100 prepended
    @Test
    void ensureSupergroupPrefixShouldPrependPrefixToPositiveId() {
        assertThat(TelegramChatIdUtils.ensureSupergroupPrefix(1234567890L)).isEqualTo(-1001234567890L);
    }

    // FR-011 / AC-011.1: negative non-supergroup id must be returned unchanged
    @Test
    void ensureSupergroupPrefixShouldReturnUnchangedForNegativeNonSupergroupId() {
        assertThat(TelegramChatIdUtils.ensureSupergroupPrefix(-1234567L)).isEqualTo(-1234567L);
    }

    // FR-012 / AC-012.1: primitive ensureSupergroupPrefix overload delegates to boxed overload
    @Test
    void ensureSupergroupPrefixPrimitiveOverloadShouldDelegateToBoxedOverload() {
        long primitiveResult = TelegramChatIdUtils.ensureSupergroupPrefix(1234567890L);
        Long boxedResult = TelegramChatIdUtils.ensureSupergroupPrefix(Long.valueOf(1234567890L));
        assertThat(primitiveResult).isEqualTo(boxedResult);
    }

    // FR-013 / AC-013.1: round-trip invariant — normalize(ensurePrefix(normalize(s))) == normalize(s)
    @Test
    void roundTripInvariantShouldHoldForSupergroupId() {
        Long normalized = TelegramChatIdUtils.normalizeChatId(-1001234567890L);
        Long roundTripped = TelegramChatIdUtils.normalizeChatId(
                TelegramChatIdUtils.ensureSupergroupPrefix(normalized));
        assertThat(roundTripped).isEqualTo(normalized);
    }
}
