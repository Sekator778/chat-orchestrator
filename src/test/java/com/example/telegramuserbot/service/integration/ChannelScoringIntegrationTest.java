package com.example.telegramuserbot.service.integration;

import com.example.telegramuserbot.integration.BaseIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = ChannelScoringIntegrationTest.TestApplication.class)
final class ChannelScoringIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private DatabaseClient databaseClient;

    @Test
    void recalculatesChannelScoresBasedOnInfluenceRelevanceAndActivity() {
        seedSampleChannels();

        databaseClient.sql("SELECT tgscan.fn_refresh_all(:window, :half_life, :limit)")
                .bind("window", 14)
                .bind("half_life", 12.0)
                .bind("limit", 500)
                .fetch()
                .rowsUpdated()
                .block();

        ChannelMetrics metricsA = loadMetrics(1L);
        ChannelMetrics metricsB = loadMetrics(2L);
        ChannelMetrics metricsC = loadMetrics(3L);

        assertThat(metricsA.scoreInfluence()).isGreaterThan(metricsC.scoreInfluence());
        assertThat(metricsB.scoreRelevance()).isGreaterThan(metricsA.scoreRelevance());
        assertThat(metricsC.scoreActivity()).isGreaterThan(metricsA.scoreActivity());

        assertThat(metricsA.channelScore()).isBetween(0.0, 1.0);
        assertThat(metricsB.channelScore()).isBetween(0.0, 1.0);
        assertThat(metricsC.channelScore()).isBetween(0.0, 1.0);

        assertThat(metricsC.channelScore()).isGreaterThan(0.0);
    }

    @Test
    void channelDebugViewSummarisesLatestMetrics() {
        seedSampleChannels();

        databaseClient.sql("SELECT tgscan.fn_refresh_all(:window, :half_life, :limit)")
                .bind("window", 14)
                .bind("half_life", 12.0)
                .bind("limit", 500)
                .fetch()
                .rowsUpdated()
                .block();

        databaseClient.sql("REFRESH MATERIALIZED VIEW tgscan.v_channel_debug")
                .fetch()
                .rowsUpdated()
                .block();

        Map<String, Object> row = databaseClient.sql("""
                        SELECT channel_score,
                               score_influence,
                               score_relevance,
                               score_activity,
                               raw_keyword_score,
                               weight,
                               msgs_14d,
                               avg_views_14d,
                               avg_fwd_14d
                          FROM tgscan.v_channel_debug
                         WHERE id = :channel_id
                        """)
                .bind("channel_id", 1L)
                .fetch()
                .one()
                .block();

        assertThat(row).isNotNull();
        assertThat(((Number) row.get("channel_score")).doubleValue()).isBetween(0.0, 1.0);
        assertThat(((Number) row.get("score_influence")).doubleValue()).isGreaterThan(0.0);
        assertThat(((Number) row.get("raw_keyword_score")).doubleValue()).isEqualTo(6.0);
        assertThat(((Number) row.get("msgs_14d")).intValue()).isGreaterThan(0);
    }

    private void seedSampleChannels() {
        insertChannel(1L, "chanA", "Channel A", 6.0, 0.4, 100_000L);
        insertChannel(2L, "chanB", "Channel B", 8.0, 0.5, 80_000L);
        insertChannel(3L, "chanC", "Channel C", 0.0, 0.6, 50_000L);

        // Channel A: high views, low keyword diversity
        insertMessage(101L, 1L, hoursAgo(6), 15_000, 120, new String[]{"alpha"});
        insertMessage(102L, 1L, hoursAgo(30), 12_000, 60, new String[]{});

        // Channel B: moderate views, rich keyword coverage
        insertMessage(201L, 2L, hoursAgo(8), 6_000, 80, new String[]{"btc", "eth", "sol"});
        insertMessage(202L, 2L, hoursAgo(20), 5_500, 65, new String[]{"btc", "ltc"});
        insertMessage(203L, 2L, hoursAgo(32), 4_800, 40, new String[]{"eth", "dot", "ada"});

        // Channel C: lower views but very active stream
        insertMessage(301L, 3L, hoursAgo(2), 1_200, 15, new String[]{"flow"});
        insertMessage(302L, 3L, hoursAgo(6), 900, 12, new String[]{"flow"});
        insertMessage(303L, 3L, hoursAgo(10), 850, 10, new String[]{"flow"});
        insertMessage(304L, 3L, hoursAgo(14), 800, 9, new String[]{"flow"});
        insertMessage(305L, 3L, hoursAgo(18), 780, 9, new String[]{"flow"});
        insertMessage(306L, 3L, hoursAgo(26), 720, 8, new String[]{"flow"});
    }

    private void insertChannel(long id, String username, String title, double rawKeywordScore, double weight, long subscribers) {
        databaseClient.sql("""
                        INSERT INTO tgscan.channels (id, username, title, raw_keyword_score, weight, subscribers, last_seen)
                        VALUES (:id, :username, :title, :raw_keyword_score, :weight, :subscribers, now())
                        """)
                .bind("id", id)
                .bind("username", username)
                .bind("title", title)
                .bind("raw_keyword_score", rawKeywordScore)
                .bind("weight", weight)
                .bind("subscribers", subscribers)
                .fetch()
                .rowsUpdated()
                .block();
    }

    private void insertMessage(long msgId, long channelId, Instant postedAt, int views, int forwards, String[] keywords) {
        databaseClient.sql("""
                        INSERT INTO tgscan.messages
                        (msg_id, channel_id, posted_at, text, matched_keywords, views, forwards)
                        VALUES (:msg_id, :channel_id, :posted_at, :text, :matched_keywords, :views, :forwards)
                        """)
                .bind("msg_id", msgId)
                .bind("channel_id", channelId)
                .bind("posted_at", postedAt)
                .bind("text", "sample message " + msgId)
                .bind("matched_keywords", keywords)
                .bind("views", views)
                .bind("forwards", forwards)
                .fetch()
                .rowsUpdated()
                .block();
    }

    private ChannelMetrics loadMetrics(long channelId) {
        Map<String, Object> row = databaseClient.sql("""
                        SELECT channel_score,
                               score_influence,
                               score_relevance,
                               score_activity
                          FROM tgscan.channels
                         WHERE id = :channel_id
                        """)
                .bind("channel_id", channelId)
                .fetch()
                .one()
                .block();

        assertThat(row)
                .as("Channel %s metrics must be present", channelId)
                .isNotNull();

        return new ChannelMetrics(
                ((Number) row.get("channel_score")).doubleValue(),
                ((Number) row.get("score_influence")).doubleValue(),
                ((Number) row.get("score_relevance")).doubleValue(),
                ((Number) row.get("score_activity")).doubleValue()
        );
    }

    private Instant hoursAgo(int hours) {
        return Instant.now().minus(Duration.ofHours(hours));
    }

    private record ChannelMetrics(double channelScore,
                                  double scoreInfluence,
                                  double scoreRelevance,
                                  double scoreActivity) {
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication { }
}
