package com.example.telegramuserbot.service.integration;

import com.example.telegramuserbot.integration.BaseIntegrationTest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.postgresql.codec.Json;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.Parameter;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = EventsAlertsIntegrationTest.TestApplication.class)
final class EventsAlertsIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private DatabaseClient databaseClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void spikeEventProducesAlertAndIsDeduplicated() throws Exception {
        insertChannel(1001L, "alpha_news", "Alpha News", 0.4, 150_000L);
        insertChannel(1002L, "beta_whales", "Beta Whales", 0.5, 90_000L);
        insertChannel(1003L, "gamma_trader", "Gamma Trader", 0.3, 50_000L);
        insertChannel(1004L, "delta_signals", "Delta Signals", 0.2, 45_000L);

        // Baseline activity (older than detection window)
        insertMessage(5001L, 1001L, hoursAgo(2), 0.25, "BTC market recap", new String[]{"btc"});
        insertMessage(5002L, 1002L, hoursAgo(1), 0.22, "BTC daily digest", new String[]{"btc"});

        // Spike within window
        insertMessage(6001L, 1001L, minutesAgo(10), 0.82, "BTC listing rumor on major exchange", new String[]{"btc"});
        insertMessage(6002L, 1002L, minutesAgo(9), 0.78, "Traders expect BTC listing", new String[]{"btc"});
        insertMessage(6003L, 1003L, minutesAgo(8), 0.74, "BTC volume spikes ahead of listing", new String[]{"btc"});
        insertMessage(6004L, 1004L, minutesAgo(7), 0.71, "Signals indicate BTC listing tomorrow", new String[]{"btc"});
        insertMessage(6005L, 1001L, minutesAgo(6), 0.69, "BTC flows surge after listing hints", new String[]{"btc"});
        insertMessage(6006L, 1002L, minutesAgo(5), 0.68, "BTC whales accumulate on listing talk", new String[]{"btc"});
        insertMessage(6007L, 1003L, minutesAgo(4), 0.66, "BTC traders confirm listing chatter", new String[]{"btc"});
        insertMessage(6008L, 1004L, minutesAgo(3), 0.65, "BTC listing narrative gains traction", new String[]{"btc"});

        Long totalMessages = databaseClient.sql("SELECT COUNT(*) AS cnt FROM bot.messages")
                .map(row -> ((Number) row.get("cnt")).longValue())
                .one()
                .block();
        assertThat(totalMessages).isNotNull().isEqualTo(10L);

        Long windowMessages = databaseClient.sql("SELECT COUNT(*) AS cnt FROM bot.messages WHERE date >= now() - interval '15 minutes'")
                .map(row -> ((Number) row.get("cnt")).longValue())
                .one()
                .block();
        assertThat(windowMessages).isNotNull().isEqualTo(8L);

        List<Map<String, Object>> keywordRows = databaseClient.sql("SELECT matched_keywords FROM bot.messages ORDER BY message_id")
                .fetch()
                .all()
                .collectList()
                .block();
        assertThat(keywordRows)
                .as("matched_keywords should be populated for BTC messages")
                .isNotNull()
                .allMatch(row -> row.get("matched_keywords") != null);

        int inserted;
        long eventsAfterDetect;
        try (Connection connection = openJdbcConnection();
             PreparedStatement detectStmt = connection.prepareStatement("SELECT tgscan.fn_detect_events(?, ?)");
             PreparedStatement countStmt = connection.prepareStatement("SELECT COUNT(*) FROM tgscan.events")) {
            detectStmt.setInt(1, 15);
            detectStmt.setDouble(2, 0.5);
            try (ResultSet rs = detectStmt.executeQuery()) {
                rs.next();
                inserted = rs.getInt(1);
            }

            try (ResultSet countRs = countStmt.executeQuery()) {
                countRs.next();
                eventsAfterDetect = countRs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to invoke tgscan.fn_detect_events", e);
        }

        assertThat(eventsAfterDetect).as("Expected 1 event to be created").isEqualTo(1L);
        assertThat(inserted).as("fn_detect_events should return 1").isEqualTo(1);

        Map<String, Object> eventRow = databaseClient.sql("SELECT * FROM tgscan.events")
                .fetch()
                .one()
                .block();

        assertThat(eventRow).isNotNull();
        long eventId = ((Number) eventRow.get("id")).longValue();
        assertThat(eventRow.get("topic")).isEqualTo("btc");
        assertThat(((Number) eventRow.get("message_count")).intValue()).isEqualTo(8);
        assertThat(((Number) eventRow.get("unique_sources")).intValue()).isGreaterThanOrEqualTo(4);
        assertThat(((Number) eventRow.get("confidence")).doubleValue()).isGreaterThan(0.7);
        assertThat(eventRow.get("event_type")).isEqualTo("FOMO/LISTING");
        assertThat(eventRow.get("severity")).isEqualTo("high");

        List<Map<String, Object>> topSources = readJsonArray(eventRow.get("top_sources"));
        List<Map<String, Object>> evidence = readJsonArray(eventRow.get("evidence"));

        assertThat(topSources).isNotEmpty();
        assertThat(evidence).hasSizeGreaterThanOrEqualTo(5);
        assertThat(eventRow.get("root_cause").toString()).contains("listing");

        int alertsCreated;
        try (Connection connection2 = openJdbcConnection();
             PreparedStatement emitStmt = connection2.prepareStatement("SELECT tgscan.fn_emit_alerts(?)")) {
            emitStmt.setInt(1, 5);
            try (ResultSet rs = emitStmt.executeQuery()) {
                rs.next();
                alertsCreated = rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to invoke tgscan.fn_emit_alerts", e);
        }

        assertThat(alertsCreated).isEqualTo(1);

        Map<String, Object> alertRow = databaseClient.sql("SELECT * FROM tgscan.alerts")
                .fetch()
                .one()
                .block();
        assertThat(alertRow).isNotNull();
        assertThat(((Number) alertRow.get("event_id")).longValue()).isEqualTo(eventId);
        assertThat(alertRow.get("template").toString()).contains("btc").contains("msgs");
        assertThat(alertRow.get("priority")).isEqualTo("high");

        // Deduplication: second pass should create no additional events or alerts
        Integer duplicated = databaseClient.sql("SELECT tgscan.fn_detect_events(:window, :min_conf)")
                .bind("window", 15)
                .bind("min_conf", 0.5)
                .map(row -> ((Number) row.get("fn_detect_events")).intValue())
                .one()
                .block();
        assertThat(duplicated).isNotNull().isZero();

        int alertsDup;
        try (Connection connection3 = openJdbcConnection();
             PreparedStatement emitStmt2 = connection3.prepareStatement("SELECT tgscan.fn_emit_alerts(?)")) {
            emitStmt2.setInt(1, 5);
            try (ResultSet rs = emitStmt2.executeQuery()) {
                rs.next();
                alertsDup = rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to invoke tgscan.fn_emit_alerts (dedup check)", e);
        }
        assertThat(alertsDup).isZero();

        Long alertCount = databaseClient.sql("SELECT COUNT(*) AS cnt FROM tgscan.alerts")
                .map(row -> ((Number) row.get("cnt")).longValue())
                .one()
                .block();
        assertThat(alertCount).isEqualTo(1L);
    }

    @Test
    void fudPanicEventIsDetectedWithHighPanicRatio() throws Exception {
        insertChannel(2001L, "panic_news", "Panic News", 0.4, 100_000L);
        insertChannel(2002L, "fud_channel", "FUD Channel", 0.3, 80_000L);
        insertChannel(2003L, "dump_alert", "Dump Alert", 0.5, 120_000L);

        // Messages with panic keywords and low importance scores
        insertMessage(7001L, 2001L, minutesAgo(12), 0.15, "ETH dump incoming panic sell", new String[]{"eth"});
        insertMessage(7002L, 2002L, minutesAgo(11), 0.18, "ETH panic in markets default risk", new String[]{"eth"});
        insertMessage(7003L, 2003L, minutesAgo(10), 0.12, "ETH sanctions causing dump", new String[]{"eth"});
        insertMessage(7004L, 2001L, minutesAgo(9), 0.20, "Major ETH panic selling pressure", new String[]{"eth"});
        insertMessage(7005L, 2002L, minutesAgo(8), 0.16, "ETH FUD spreading fast", new String[]{"eth"});

        int inserted;
        try (Connection connection = openJdbcConnection();
             PreparedStatement detectStmt = connection.prepareStatement("SELECT tgscan.fn_detect_events(?, ?)")) {
            detectStmt.setInt(1, 15);
            detectStmt.setDouble(2, 0.45);
            try (ResultSet rs = detectStmt.executeQuery()) {
                rs.next();
                inserted = rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to invoke tgscan.fn_detect_events", e);
        }

        assertThat(inserted).isEqualTo(1);

        Map<String, Object> eventRow = databaseClient.sql("SELECT * FROM tgscan.events")
                .fetch()
                .one()
                .block();

        assertThat(eventRow).isNotNull();
        assertThat(eventRow.get("topic")).isEqualTo("eth");
        assertThat(eventRow.get("event_type")).isEqualTo("FUD/PANIC");
        assertThat(((Number) eventRow.get("panic_ratio")).doubleValue()).isGreaterThan(0.5);
        assertThat(((Number) eventRow.get("message_count")).intValue()).isEqualTo(5);
    }

    @Test
    void spikeEventIsDetectedWithoutSpecialTriggers() throws Exception {
        insertChannel(3001L, "crypto_updates", "Crypto Updates", 0.3, 50_000L);
        insertChannel(3002L, "blockchain_news", "Blockchain News", 0.4, 60_000L);

        // Spike without listing/panic keywords
        insertMessage(8001L, 3001L, minutesAgo(8), 0.65, "SOL price movement detected", new String[]{"sol"});
        insertMessage(8002L, 3002L, minutesAgo(7), 0.68, "SOL trading volume increasing", new String[]{"sol"});
        insertMessage(8003L, 3001L, minutesAgo(6), 0.70, "SOL whale activity noted", new String[]{"sol"});
        insertMessage(8004L, 3002L, minutesAgo(5), 0.72, "SOL market update", new String[]{"sol"});

        int inserted;
        try (Connection connection = openJdbcConnection();
             PreparedStatement detectStmt = connection.prepareStatement("SELECT tgscan.fn_detect_events(?, ?)")) {
            detectStmt.setInt(1, 15);
            detectStmt.setDouble(2, 0.45);
            try (ResultSet rs = detectStmt.executeQuery()) {
                rs.next();
                inserted = rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to invoke tgscan.fn_detect_events", e);
        }

        assertThat(inserted).isEqualTo(1);

        Map<String, Object> eventRow = databaseClient.sql("SELECT * FROM tgscan.events")
                .fetch()
                .one()
                .block();

        assertThat(eventRow).isNotNull();
        assertThat(eventRow.get("topic")).isEqualTo("sol");
        assertThat(eventRow.get("event_type")).isEqualTo("SPIKE");
        assertThat(((Number) eventRow.get("panic_ratio")).doubleValue()).isLessThan(0.5);
    }

    @Test
    void lowConfidenceEventsAreFilteredOut() throws Exception {
        insertChannel(4001L, "low_volume", "Low Volume Channel", 0.1, 1_000L);

        // Only 2 messages - below minimum threshold of 3
        insertMessage(9001L, 4001L, minutesAgo(5), 0.25, "ADA mention", new String[]{"ada"});
        insertMessage(9002L, 4001L, minutesAgo(4), 0.28, "ADA update", new String[]{"ada"});

        int inserted;
        try (Connection connection = openJdbcConnection();
             PreparedStatement detectStmt = connection.prepareStatement("SELECT tgscan.fn_detect_events(?, ?)")) {
            detectStmt.setInt(1, 15);
            detectStmt.setDouble(2, 0.5);
            try (ResultSet rs = detectStmt.executeQuery()) {
                rs.next();
                inserted = rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to invoke tgscan.fn_detect_events", e);
        }

        assertThat(inserted).isZero();

        Long eventCount = databaseClient.sql("SELECT COUNT(*) AS cnt FROM tgscan.events")
                .map(row -> ((Number) row.get("cnt")).longValue())
                .one()
                .block();
        assertThat(eventCount).isZero();
    }

    @Test
    void baselineComparisonDetectsSpikeAboveHistoricalAverage() throws Exception {
        insertChannel(5001L, "trending_channel", "Trending Channel", 0.5, 200_000L);

        // Historical baseline (older messages)
        insertMessage(10001L, 5001L, hoursAgo(3), 0.45, "DOT price check", new String[]{"dot"});
        insertMessage(10002L, 5001L, hoursAgo(2), 0.48, "DOT market review", new String[]{"dot"});

        // Recent spike (8 messages vs 2 historical)
        insertMessage(10003L, 5001L, minutesAgo(10), 0.75, "DOT breaking news", new String[]{"dot"});
        insertMessage(10004L, 5001L, minutesAgo(9), 0.78, "DOT major announcement", new String[]{"dot"});
        insertMessage(10005L, 5001L, minutesAgo(8), 0.80, "DOT partnership confirmed", new String[]{"dot"});
        insertMessage(10006L, 5001L, minutesAgo(7), 0.82, "DOT upgrade released", new String[]{"dot"});
        insertMessage(10007L, 5001L, minutesAgo(6), 0.79, "DOT ecosystem growth", new String[]{"dot"});
        insertMessage(10008L, 5001L, minutesAgo(5), 0.77, "DOT developer activity", new String[]{"dot"});
        insertMessage(10009L, 5001L, minutesAgo(4), 0.76, "DOT adoption increasing", new String[]{"dot"});
        insertMessage(10010L, 5001L, minutesAgo(3), 0.74, "DOT validators expanding", new String[]{"dot"});

        int inserted;
        try (Connection connection = openJdbcConnection();
             PreparedStatement detectStmt = connection.prepareStatement("SELECT tgscan.fn_detect_events(?, ?)")) {
            detectStmt.setInt(1, 15);
            detectStmt.setDouble(2, 0.5);
            try (ResultSet rs = detectStmt.executeQuery()) {
                rs.next();
                inserted = rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to invoke tgscan.fn_detect_events", e);
        }

        assertThat(inserted).isEqualTo(1);

        Map<String, Object> eventRow = databaseClient.sql("SELECT * FROM tgscan.events")
                .fetch()
                .one()
                .block();

        assertThat(eventRow).isNotNull();
        assertThat(eventRow.get("topic")).isEqualTo("dot");
        assertThat(((Number) eventRow.get("spike_ratio")).doubleValue()).isGreaterThan(2.0);
        assertThat(((Number) eventRow.get("message_count")).intValue()).isEqualTo(8);
    }

    private void insertChannel(long id, String username, String title, double rawKeywordScore, long subscribers) {
        databaseClient.sql("""
                        INSERT INTO tgscan.channels
                        (id, username, title, raw_keyword_score, subscribers, last_seen)
                        VALUES (:id, :username, :title, :raw_keyword_score, :subscribers, now())
                        """)
                .bind("id", id)
                .bind("username", username)
                .bind("title", title)
                .bind("raw_keyword_score", rawKeywordScore)
                .bind("subscribers", subscribers)
                .fetch()
                .rowsUpdated()
                .block();
    }

    private void insertMessage(long msgId, long chatId, Instant postedAt, double importance, String text, String[] keywords) {
        databaseClient.sql("""
                        INSERT INTO bot.messages
                        (chat_id, message_id, telegram_message_id, content, date, matched_keywords, views, forwards, importance, message_type, is_outgoing, created_at)
                        VALUES (:chat_id, :message_id, :telegram_message_id, :content, :date, :matched_keywords, :views, :forwards, :importance, 'USER_MESSAGE', FALSE, NOW())
                        """)
                .bind("chat_id", chatId)
                .bind("message_id", msgId)
                .bind("telegram_message_id", msgId)
                .bind("content", text)
                .bind("date", postedAt)
                .bind("matched_keywords", Parameter.fromOrEmpty(keywords, String[].class))
                .bind("views", 10_000)
                .bind("forwards", 25)
                .bind("importance", importance)
                .fetch()
                .rowsUpdated()
                .block();
    }

    private Instant hoursAgo(int hours) {
        return Instant.now().minus(Duration.ofHours(hours));
    }

    private Instant minutesAgo(int minutes) {
        return Instant.now().minus(Duration.ofMinutes(minutes));
    }

    private List<Map<String, Object>> readJsonArray(Object value) throws Exception {
        if (value == null) {
            return List.of();
        }
        String json;
        if (value instanceof Json jsonValue) {
            json = jsonValue.asString();
        } else {
            json = value.toString();
        }
        if (json == null || json.isEmpty()) {
            return List.of();
        }
        return objectMapper.readValue(json, new TypeReference<>() {});
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication { }
}
