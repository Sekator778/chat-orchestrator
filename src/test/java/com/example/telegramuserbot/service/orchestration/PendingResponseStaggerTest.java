package com.example.telegramuserbot.service.orchestration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * A queued reply is spread around its configured delay so two personas answering
 * the same message do not surface in the same second. The spread window is half
 * the delay — which is zero for a one-second delay, and the arithmetic was
 * written as if it never could be: {@code nextLong(0, 0)} and a {@code % 0}
 * both waiting behind it.
 * <p>
 * That threw from inside the reply pipeline, not from the enqueue call, so it
 * landed outside the coordinator's own {@code onErrorReturn} and took the whole
 * reply down with it. A chat configured with a one-second pending delay simply
 * stopped answering.
 */
class PendingResponseStaggerTest {

    @ParameterizedTest
    @DisplayName("every delay a config can hold produces a stagger instead of throwing")
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 7, 10, 30, 60, 300, 3600})
    void noDelayValueThrows(int delaySeconds) {
        assertThatCode(() -> PendingResponseCoordinator.staggerSeconds(delaySeconds, "persona-alpha"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a one-second delay is the case that used to throw")
    void oneSecondDelay() {
        assertThatCode(() -> PendingResponseCoordinator.staggerSeconds(1, "persona-alpha"))
                .as("nextLong(0, 0) rejects an empty range, and delaySeconds/2 is zero right behind it")
                .doesNotThrowAnyException();

        assertThat(PendingResponseCoordinator.staggerSeconds(1, "persona-alpha"))
                .as("there is no room to spread within one second")
                .isZero();
    }

    @ParameterizedTest
    @DisplayName("the reply never lands before it was asked for, nor a full delay late")
    @ValueSource(ints = {2, 3, 5, 10, 60, 300})
    void staggerStaysInsideItsWindow(int delaySeconds) {
        for (int i = 0; i < 500; i++) {
            long stagger = PendingResponseCoordinator.staggerSeconds(delaySeconds, "persona-" + i);

            assertThat(delaySeconds + stagger)
                    .as("a queued reply must not become eligible in the past")
                    .isPositive();
            assertThat(stagger)
                    .as("the spread is bounded by the delay it is spreading")
                    .isBetween((long) -delaySeconds, (long) delaySeconds);
        }
    }

    @Test
    @DisplayName("a persona id hashing to Integer.MIN_VALUE does not push the reply backwards")
    void extremeHashCodeStaysNonNegative() {
        // "polygenelubricants" is the textbook String whose hashCode is Integer.MIN_VALUE,
        // where Math.abs returns the same negative number.
        String pathological = "polygenelubricants";
        assertThat(pathological.hashCode()).isEqualTo(Integer.MIN_VALUE);

        for (int i = 0; i < 200; i++) {
            long stagger = PendingResponseCoordinator.staggerSeconds(10, pathological);
            assertThat(10 + stagger).isPositive();
        }
    }

    @Test
    @DisplayName("a missing persona id degrades to no offset rather than an NPE")
    void nullBotInstanceId() {
        assertThatCode(() -> PendingResponseCoordinator.staggerSeconds(10, null))
                .doesNotThrowAnyException();
    }
}
