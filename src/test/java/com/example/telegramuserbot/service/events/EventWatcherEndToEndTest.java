package com.example.telegramuserbot.service.events;

import com.example.telegramuserbot.integration.BaseIntegrationTest;
import com.example.telegramuserbot.repository.EventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.Parameter;
import org.springframework.test.context.TestPropertySource;
import reactor.test.StepVerifier;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test for the complete Event detection and processing pipeline.
 *
 * Test Flow:
 * 1. Create realistic test channels (as if monitoring real Telegram channels)
 * 2. Insert baseline messages (normal activity)
 * 3. Simulate message spike (breaking news scenario)
 * 4. Let fn_detect_events() naturally discover the event
 * 5. EventWatcher processes and transitions event to 'ready'
 * 6. Verify complete pipeline from messages → events → alerts
 */
@SpringBootTest(classes = EventWatcherEndToEndTest.TestApplication.class)
@TestPropertySource(properties = {
    "events.watcher.enabled=true",
    "events.watcher.min-confidence=0.5",
    "events.watcher.min-severity=low",
    "events.watcher.batch-size=100"
})
final class EventWatcherEndToEndTest extends BaseIntegrationTest {

    @Autowired
    private DatabaseClient databaseClient;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private EventWatcherService watcher;

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({EventWatcherService.class, EventWatcherProperties.class})
    @EnableR2dbcRepositories(basePackages = "com.example.telegramuserbot.repository")
    static class TestApplication { }

    @Test
    void detectsAndProcessesRealMessageSpikeEndToEnd() throws Exception {
        // ==================== ARRANGE ====================

        // Step 1: Create realistic Telegram channels we're "monitoring"
        createChannel(1001L, "crypto_insider", "Crypto Insider News", 0.75, 250_000L);
        createChannel(1002L, "blockchain_daily", "Blockchain Daily", 0.68, 180_000L);
        createChannel(1003L, "defi_alerts", "DeFi Alert System", 0.82, 320_000L);
        createChannel(1004L, "whale_watch", "Whale Watcher", 0.71, 150_000L);

        System.out.println("\n=== Created 4 test channels with high ratings ===");

        // Step 2: Insert baseline activity (normal, older messages)
        // This establishes "normal" traffic pattern for baseline comparison
        insertMessage(5001L, 1001L, hoursAgo(3), 0.45, "BTC daily market analysis", new String[]{"btc"});
        insertMessage(5002L, 1002L, hoursAgo(2), 0.42, "ETH price movements", new String[]{"eth"});
        insertMessage(5003L, 1003L, hoursAgo(2), 0.38, "BTC technical review", new String[]{"btc"});
        insertMessage(5004L, 1004L, hoursAgo(1), 0.41, "BTC whale activity report", new String[]{"btc"});

        System.out.println("=== Inserted baseline messages (2-3 hours ago) ===");

        // Step 3: Simulate BREAKING NEWS - Message spike in last 15 minutes
        // Scenario: Major BTC listing rumor spreads across all monitored channels
        insertMessage(6001L, 1001L, minutesAgo(12), 0.85, "BREAKING: Major exchange listing BTC tomorrow - sources confirm", new String[]{"btc"});
        insertMessage(6002L, 1002L, minutesAgo(11), 0.88, "BTC listing confirmed by multiple insiders - huge volume incoming", new String[]{"btc"});
        insertMessage(6003L, 1003L, minutesAgo(10), 0.82, "BTC LISTING ALERT: Exchange announcement expected within 24h", new String[]{"btc"});
        insertMessage(6004L, 1004L, minutesAgo(9), 0.87, "Whales accumulating BTC ahead of major listing event", new String[]{"btc"});
        insertMessage(6005L, 1001L, minutesAgo(8), 0.83, "BTC listing rumors gaining traction - check exchange announcements", new String[]{"btc"});
        insertMessage(6006L, 1002L, minutesAgo(7), 0.86, "Multiple sources: BTC listing imminent on top-tier exchange", new String[]{"btc"});
        insertMessage(6007L, 1003L, minutesAgo(6), 0.84, "BTC UPGRADE: Listing + new trading pairs confirmed", new String[]{"btc"});
        insertMessage(6008L, 1004L, minutesAgo(5), 0.81, "BTC listing narrative driving massive whale flows", new String[]{"btc"});
        insertMessage(6009L, 1001L, minutesAgo(4), 0.79, "Official BTC listing announcement coming within hours", new String[]{"btc"});
        insertMessage(6010L, 1002L, minutesAgo(3), 0.80, "BTC IPO-style listing event - traders prepare positions", new String[]{"btc"});

        System.out.println("=== Inserted spike: 10 messages in 15-min window (4 channels) ===");

        // ==================== ACT ====================

        // Step 4: Let the system naturally detect the event
        // This is what would happen automatically via scheduler in production
        int eventsDetected;
        try (Connection connection = openJdbcConnection();
             PreparedStatement detectStmt = connection.prepareStatement("SELECT tgscan.fn_detect_events(?, ?)")) {
            detectStmt.setInt(1, 15);      // 15-minute window
            detectStmt.setDouble(2, 0.5);  // min confidence 0.5
            try (ResultSet rs = detectStmt.executeQuery()) {
                rs.next();
                eventsDetected = rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to invoke tgscan.fn_detect_events", e);
        }

        System.out.println("=== fn_detect_events() detected " + eventsDetected + " event(s) ===");

        // Step 5: EventWatcher processes the detected event
        // This is what would happen via EventWatcherScheduler every 30 seconds
        Integer eventsProcessed = watcher.process()
            .block(Duration.ofSeconds(5));

        System.out.println("=== EventWatcher processed " + eventsProcessed + " event(s) ===");

        // ==================== ASSERT ====================

        // Verify event was detected
        assertThat(eventsDetected)
            .as("fn_detect_events should detect the BTC listing spike")
            .isEqualTo(1);

        // Verify EventWatcher processed it
        assertThat(eventsProcessed)
            .as("EventWatcher should process the detected event")
            .isEqualTo(1);

        // Verify event details in database
        StepVerifier.create(
                eventRepository.findAll()
                    .collectList()
            )
            .assertNext(events -> {
                assertThat(events).hasSize(1);

                var event = events.get(0);

                // Event classification
                assertThat(event.topic()).isEqualTo("btc");
                assertThat(event.eventType()).isEqualTo("FOMO/LISTING");
                assertThat(event.severity()).isEqualTo("high");

                // Event metrics
                assertThat(event.messageCount()).isEqualTo(10);
                assertThat(event.uniqueSources()).isEqualTo(4);
                assertThat(event.confidence()).isGreaterThan(0.7);
                assertThat(event.spikeRatio()).isGreaterThan(2.0);

                // Status lifecycle
                assertThat(event.status()).isEqualTo("ready");
                assertThat(event.processedAt()).isNotNull();

                // Content validation
                assertThat(event.rootCause()).containsIgnoringCase("listing");

                System.out.println("\n=== EVENT DETAILS ===");
                System.out.println("ID: " + event.id());
                System.out.println("Type: " + event.eventType());
                System.out.println("Topic: " + event.topic());
                System.out.println("Severity: " + event.severity());
                System.out.println("Confidence: " + String.format("%.2f", event.confidence()));
                System.out.println("Messages: " + event.messageCount());
                System.out.println("Sources: " + event.uniqueSources());
                System.out.println("Spike Ratio: " + String.format("%.2f", event.spikeRatio()));
                System.out.println("Status: " + event.status());
                System.out.println("Root Cause: " + event.rootCause());
            })
            .expectComplete()
            .verify(Duration.ofSeconds(5));

        // Verify statistics
        StepVerifier.create(watcher.statistics())
            .assertNext(stats -> {
                assertThat(stats.readyCount()).isEqualTo(1L);
                assertThat(stats.newCount()).isZero();
                System.out.println("\n=== PIPELINE STATISTICS ===");
                System.out.println(stats);
            })
            .expectComplete()
            .verify(Duration.ofSeconds(5));

        System.out.println("\n=== ✅ END-TO-END TEST PASSED ===");
        System.out.println("Pipeline validated: messages → event detection → event processing → ready for alerts");
    }

    @Test
    void ignoresLowVolumeActivityThatDoesNotMeetThresholds() throws Exception {
        // Arrange: Create channels and insert minimal activity
        createChannel(2001L, "small_channel", "Small Channel", 0.2, 5_000L);
        createChannel(2002L, "tiny_updates", "Tiny Updates", 0.15, 2_000L);

        // Only 2 messages - below minimum threshold of 3
        insertMessage(7001L, 2001L, minutesAgo(10), 0.35, "ADA price update", new String[]{"ada"});
        insertMessage(7002L, 2002L, minutesAgo(8), 0.32, "ADA market recap", new String[]{"ada"});

        // Act: Attempt event detection
        int eventsDetected;
        try (Connection connection = openJdbcConnection();
             PreparedStatement detectStmt = connection.prepareStatement("SELECT tgscan.fn_detect_events(?, ?)")) {
            detectStmt.setInt(1, 15);
            detectStmt.setDouble(2, 0.5);
            try (ResultSet rs = detectStmt.executeQuery()) {
                rs.next();
                eventsDetected = rs.getInt(1);
            }
        }

        Integer eventsProcessed = watcher.process().block(Duration.ofSeconds(5));

        // Assert: No events should be detected or processed
        assertThat(eventsDetected).isZero();
        assertThat(eventsProcessed).isZero();

        StepVerifier.create(eventRepository.count())
            .assertNext(count -> assertThat(count).isZero())
            .expectComplete()
            .verify(Duration.ofSeconds(5));
    }

    @Test
    void detectsPanicEventFromNegativeNewsSpike() throws Exception {
        // Arrange: Create channels
        createChannel(3001L, "market_panic", "Market Panic Alerts", 0.65, 100_000L);
        createChannel(3002L, "fud_monitor", "FUD Monitor", 0.58, 80_000L);
        createChannel(3003L, "crisis_news", "Crisis News", 0.72, 120_000L);

        // Insert panic-inducing messages with low importance (high panic ratio)
        insertMessage(8001L, 3001L, minutesAgo(12), 0.15, "ETH DUMP incoming - panic selling detected", new String[]{"eth"});
        insertMessage(8002L, 3002L, minutesAgo(11), 0.18, "ETH default risk - major concerns raised", new String[]{"eth"});
        insertMessage(8003L, 3003L, minutesAgo(10), 0.12, "SANCTIONS on ETH network causing panic", new String[]{"eth"});
        insertMessage(8004L, 3001L, minutesAgo(9), 0.20, "ETH panic spreads across markets - massive dump", new String[]{"eth"});
        insertMessage(8005L, 3002L, minutesAgo(8), 0.16, "ETH FUD intensifies - default fears grow", new String[]{"eth"});

        // Act
        int eventsDetected;
        try (Connection connection = openJdbcConnection();
             PreparedStatement detectStmt = connection.prepareStatement("SELECT tgscan.fn_detect_events(?, ?)")) {
            detectStmt.setInt(1, 15);
            detectStmt.setDouble(2, 0.45);  // Lower threshold for panic events
            try (ResultSet rs = detectStmt.executeQuery()) {
                rs.next();
                eventsDetected = rs.getInt(1);
            }
        }

        Integer eventsProcessed = watcher.process().block(Duration.ofSeconds(5));

        // Assert: Should detect FUD/PANIC event
        assertThat(eventsDetected).isEqualTo(1);
        assertThat(eventsProcessed).isEqualTo(1);

        StepVerifier.create(eventRepository.findAll().collectList())
            .assertNext(events -> {
                assertThat(events).hasSize(1);
                var event = events.get(0);

                assertThat(event.topic()).isEqualTo("eth");
                assertThat(event.eventType()).isEqualTo("FUD/PANIC");
                assertThat(event.panicRatio()).isGreaterThan(0.5);
                assertThat(event.status()).isEqualTo("ready");

                System.out.println("\n=== PANIC EVENT DETECTED ===");
                System.out.println("Type: " + event.eventType());
                System.out.println("Panic Ratio: " + String.format("%.2f", event.panicRatio()));
            })
            .expectComplete()
            .verify(Duration.ofSeconds(5));
    }

    // ==================== HELPER METHODS ====================

    private void createChannel(long id, String username, String title, double rawKeywordScore, long subscribers) {
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
}
