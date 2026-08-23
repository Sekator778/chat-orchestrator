package com.example.telegramuserbot.service.telegram;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A send that fails decides whether the chat is muted forever, so the direction
 * of a wrong answer matters. Muting on an unrecognized error is unrecoverable —
 * nothing in the application ever un-mutes a chat — while not muting on a real
 * access error costs one wasted send per incoming message. These tests pin the
 * classifier to the safe direction, and specifically to the case that motivated
 * it: the owner's emergency stop must not blacklist the chats it silences.
 */
class SendFailureClassifierTest {

    /** The exact shape the reply path sees: the facade's error, re-wrapped by the sender. */
    private static Throwable asSendFailure(String telegramMessage) {
        return new IOException("Failed to send Telegram message: " + telegramMessage);
    }

    @Test
    @DisplayName("the kill switch does not mute the chats it silences")
    void killSwitchIsNotAnAccessError() {
        Throwable error = asSendFailure("Outbound kill switch is ACTIVE — SendMessage suppressed");

        assertThat(SendFailureClassifier.isPermanentAccessError(error))
                .as("flipping the emergency stop used to blacklist every chat a persona answered")
                .isFalse();
        assertThat(SendFailureClassifier.isTransientSendError(error)).isTrue();
    }

    @Test
    @DisplayName("flood wait and a missing client stay transient")
    void knownTransientErrors() {
        assertThat(SendFailureClassifier.isPermanentAccessError(
                asSendFailure("FLOOD_WAIT backoff active for botId=persona-1"))).isFalse();
        assertThat(SendFailureClassifier.isPermanentAccessError(
                new IllegalStateException("No telegram client for botId persona-1"))).isFalse();
    }

    @Test
    @DisplayName("an unrecognized failure is not treated as denied access")
    void unknownErrorsDoNotMute() {
        assertThat(SendFailureClassifier.isPermanentAccessError(
                asSendFailure("Internal Server Error"))).isFalse();
        assertThat(SendFailureClassifier.isPermanentAccessError(
                new RuntimeException())).isFalse();
        assertThat(SendFailureClassifier.isPermanentAccessError(null)).isFalse();

        // ...and it is reported as unclassified rather than quietly filed as transient.
        assertThat(SendFailureClassifier.isTransientSendError(
                asSendFailure("Internal Server Error"))).isFalse();
    }

    @Test
    @DisplayName("real access errors still mute the chat")
    void genuineAccessErrorsArePermanent() {
        for (String telegramError : new String[]{
                "CHAT_WRITE_FORBIDDEN",
                "USER_BANNED_IN_CHANNEL",
                "CHANNEL_PRIVATE",
                "CHAT_ADMIN_REQUIRED",
                "USER_IS_BLOCKED",
                "PEER_ID_INVALID",
                "Have no write access to the chat"}) {
            assertThat(SendFailureClassifier.isPermanentAccessError(asSendFailure(telegramError)))
                    .as("%s means the account really cannot post here", telegramError)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("the reason is found however deeply it is wrapped")
    void causeChainIsWalkedInFull() {
        Throwable deep = new IllegalStateException("dispatch failed",
                new RuntimeException("send stage",
                        asSendFailure("CHAT_WRITE_FORBIDDEN")));

        assertThat(SendFailureClassifier.isPermanentAccessError(deep))
                .as("the previous one-level lookup missed anything wrapped twice")
                .isTrue();
        assertThat(SendFailureClassifier.extractMessage(deep)).contains("CHAT_WRITE_FORBIDDEN");
    }

    @Test
    @DisplayName("a self-referencing cause chain terminates")
    void selfReferencingCauseDoesNotHang() {
        Exception loop = new Exception("boom") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        assertThat(SendFailureClassifier.extractMessage(loop)).isEqualTo("boom");
    }
}
