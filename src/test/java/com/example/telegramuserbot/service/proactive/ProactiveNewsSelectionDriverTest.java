package com.example.telegramuserbot.service.proactive;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The selection log is the only window into why a persona posted what it posted.
 * It used to label every non-positive-cosine winner "value-only (no vector)",
 * which conflated two different problems: an item Qdrant has never seen, and an
 * item that is embedded but off-topic for this persona. The first is a pipeline
 * gap worth chasing; the second is ranking working as designed.
 */
class ProactiveNewsSelectionDriverTest {

    @Test
    @DisplayName("no vector at all is reported as such")
    void noVector() {
        assertThat(ProactiveNewsPostingService.describeSelectionDriver(false, 0.0))
                .isEqualTo("value-only (no vector)");
    }

    @Test
    @DisplayName("a positive cosine means cosine actually drove the pick")
    void positiveCosine() {
        assertThat(ProactiveNewsPostingService.describeSelectionDriver(true, 0.42))
                .isEqualTo("cosine+value");
    }

    @Test
    @DisplayName("an embedded but off-topic winner names its score instead of claiming no vector")
    void negativeCosineIsNotReportedAsMissingVector() {
        String driver = ProactiveNewsPostingService.describeSelectionDriver(true, -0.31);

        assertThat(driver).doesNotContain("no vector");
        assertThat(driver).contains("off-topic vector");
        assertThat(driver).contains("-0.31");
    }

    @Test
    @DisplayName("exactly zero counts as off-topic, not as missing")
    void zeroCosineWithVector() {
        assertThat(ProactiveNewsPostingService.describeSelectionDriver(true, 0.0))
                .contains("off-topic vector");
    }
}
