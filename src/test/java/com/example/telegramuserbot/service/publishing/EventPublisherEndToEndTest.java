package com.example.telegramuserbot.service.publishing;

import com.example.telegramuserbot.domain.Event;
import com.example.telegramuserbot.domain.PostSubscription;
import com.example.telegramuserbot.integration.BaseIntegrationTest;
import com.example.telegramuserbot.repository.EventRepository;
import com.example.telegramuserbot.repository.PostSubscriptionRepository;
import com.example.telegramuserbot.repository.PostedRepository;
import it.tdlight.jni.TdApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * End-to-end integration test for Event Publishing pipeline.
 *
 * Test Flow:
 * 1. Create ready event (already detected and processed by watcher)
 * 2. Create subscription that matches the event
 * 3. Publisher finds event, matches subscription, renders post
 * 4. Mock Telegram send (no real network calls in tests)
 * 5. Verify audit trail in posted table
 * 6. Verify event transitioned to 'published' status
 */
@SpringBootTest(classes = EventPublisherEndToEndTest.TestApplication.class)
@TestPropertySource(properties = {
    "events.publisher.enabled=true",
    "events.publisher.poll-interval-ms=1000",
    "events.publisher.batch-size=100"
})
final class EventPublisherEndToEndTest extends BaseIntegrationTest {

    @Autowired
    private DatabaseClient databaseClient;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private PostSubscriptionRepository subscriptionRepository;

    @Autowired
    private PostedRepository postedRepository;

    @Autowired
    private EventPublisherService publisher;

    @MockBean
    private TelegramMessageSender telegramSender;

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({
        EventPublisherService.class,
        EventPublisherProperties.class,
        TelegramPostRenderer.class
    })
    @EnableR2dbcRepositories(basePackages = "com.example.telegramuserbot.repository")
    static class TestApplication { }

    @BeforeEach
    void setup() {
        // Configure mock Telegram sender to simulate successful sends
        when(telegramSender.send(anyLong(), anyString())).thenAnswer(invocation -> {
            Long chatId = invocation.getArgument(0);

            TdApi.Message sentMessage = new TdApi.Message();
            sentMessage.id = 999L;
            sentMessage.chatId = chatId;
            sentMessage.date = (int) (System.currentTimeMillis() / 1000);

            return Mono.just(sentMessage);
        });
    }

    @Test
    void publishesEventToMatchingSubscription() throws Exception {
        // ==================== ARRANGE ====================

        // Step 1: Create a ready event (as if EventWatcher already processed it)
        Long eventId = createReadyEvent(
            "btc",
            "FOMO/LISTING",
            "high",
            0.85,
            "Major exchange listing BTC tomorrow - sources confirm",
            10,
            4
        );

        System.out.println("\n=== Created ready event: id=" + eventId + " ===");

        // Step 2: Create subscription that matches this event
        Long subscriptionId = createSubscription(
            777L,               // chat_id (test Telegram chat)
            "btc|eth",          // topic pattern (regex)
            new String[]{"FOMO/LISTING", "SPIKE"},
            "medium",           // min_severity
            "RICH",             // template
            1200                // dedupe_ttl_sec
        );

        System.out.println("=== Created subscription: id=" + subscriptionId + ", chatId=777 ===");

        // ==================== ACT ====================

        // Step 3: Run publisher (this is what scheduler does every 5 seconds)
        Integer postsPublished = publisher.process()
            .block(Duration.ofSeconds(5));

        System.out.println("=== EventPublisher sent " + postsPublished + " post(s) ===");

        // ==================== ASSERT ====================

        // Verify publisher processed exactly one event
        assertThat(postsPublished)
            .as("Publisher should send one post")
            .isEqualTo(1);

        // Verify Telegram send was called with correct chat
        ArgumentCaptor<Long> chatIdCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        verify(telegramSender, times(1)).send(chatIdCaptor.capture(), textCaptor.capture());

        assertThat(chatIdCaptor.getValue()).isEqualTo(777L);

        // Verify message content contains key event details
        String messageText = textCaptor.getValue();
        assertThat(messageText)
            .contains("BTC")
            .contains("FOMO/LISTING");

        System.out.println("=== Telegram message preview: " + messageText.substring(0, Math.min(100, messageText.length())) + "... ===");

        // Verify posted audit record was created
        StepVerifier.create(
                postedRepository.findAll().collectList()
            )
            .assertNext(posts -> {
                assertThat(posts).hasSize(1);

                var post = posts.get(0);
                assertThat(post.eventId()).isEqualTo(eventId);
                assertThat(post.subscriptionId()).isEqualTo(subscriptionId);
                assertThat(post.chatId()).isEqualTo(777L);
                assertThat(post.messageId()).isEqualTo(999L);  // From mock
                assertThat(post.templateCode()).isEqualTo("RICH");
                assertThat(post.status()).isEqualTo("sent");
                assertThat(post.postedAt()).isNotNull();

                System.out.println("=== Posted audit record verified: " + post + " ===");
            })
            .verifyComplete();

        // Verify event status transitioned to 'published'
        StepVerifier.create(
                eventRepository.findById(eventId)
            )
            .assertNext(event -> {
                assertThat(event.status()).isEqualTo("published");
                assertThat(event.processedAt()).isNotNull();

                System.out.println("=== Event status transitioned: new → ready → published ===");
            })
            .verifyComplete();
    }

    @Test
    void respectsIdempotencyConstraint() throws Exception {
        // ==================== ARRANGE ====================

        Long eventId = createReadyEvent("eth", "SPIKE", "high", 0.75,
            "ETH price surge detected", 8, 3);

        Long subscriptionId = createSubscription(888L, "eth", new String[]{"SPIKE"},
            "low", "SHORT", 600);

        // ==================== ACT ====================

        // Publish once
        Integer firstRun = publisher.process().block(Duration.ofSeconds(5));
        assertThat(firstRun).isEqualTo(1);

        // Try publishing same event again
        reset(telegramSender);  // Clear mock interactions

        Integer secondRun = publisher.process().block(Duration.ofSeconds(5));

        // ==================== ASSERT ====================

        // Second run should skip due to idempotency (already posted this event+subscription)
        assertThat(secondRun)
            .as("Second publish should skip (idempotency)")
            .isEqualTo(0);

        // Verify Telegram was NOT called second time
        verify(telegramSender, never()).send(anyLong(), anyString());

        System.out.println("=== ✅ Idempotency verified: same event not re-posted ===");
    }

    @Test
    void respectsDeduplicationTtl() throws Exception {
        // ==================== ARRANGE ====================

        // Create first event and publish it
        Long event1 = createReadyEvent("btc", "SPIKE", "medium", 0.65,
            "BTC spike #1", 5, 2);

        Long subscriptionId = createSubscription(999L, "btc", new String[]{"SPIKE"},
            "low", "RICH", 300);  // 5 min TTL

        // ==================== ACT ====================

        // Publish first event
        Integer firstRun = publisher.process().block(Duration.ofSeconds(5));
        assertThat(firstRun).isEqualTo(1);  // Event1 published

        // Now create second event with same topic/type (after first was published)
        Long event2 = createReadyEvent("btc", "SPIKE", "medium", 0.70,
            "BTC spike #2", 6, 3);

        // Try publishing second event immediately (within TTL)
        reset(telegramSender);
        Integer secondRun = publisher.process().block(Duration.ofSeconds(5));

        // ==================== ASSERT ====================

        // Second event should be SKIPPED due to recent post of same topic+type
        assertThat(secondRun)
            .as("Second event should be suppressed by TTL deduplication")
            .isEqualTo(0);

        verify(telegramSender, never()).send(anyLong(), anyString());

        System.out.println("=== ✅ TTL deduplication verified: similar event suppressed ===");
    }

    // ==================== HELPERS ====================

    private Long createReadyEvent(String topic, String type, String severity,
                                   double confidence, String rootCause,
                                   int messageCount, int uniqueSources) {
        return databaseClient.sql("""
            INSERT INTO tgscan.events (
                topic, event_type, severity, spike_ratio, confidence,
                root_cause, message_count, unique_sources, evidence,
                window_start, window_end, status, created_at, updated_at
            )
            VALUES (
                :topic, :type, :severity, 2.5, :confidence,
                :rootCause, :messageCount, :uniqueSources, '[]'::jsonb,
                NOW() - INTERVAL '15 minutes', NOW(),
                'ready', NOW(), NOW()
            )
            RETURNING id
            """)
            .bind("topic", topic)
            .bind("type", type)
            .bind("severity", severity)
            .bind("confidence", confidence)
            .bind("rootCause", rootCause)
            .bind("messageCount", messageCount)
            .bind("uniqueSources", uniqueSources)
            .map(row -> row.get("id", Long.class))
            .one()
            .block();
    }

    private Long createSubscription(Long chatId, String topicPattern, String[] eventTypes,
                                     String minSeverity, String template, int dedupeTtl) {
        return databaseClient.sql("""
            INSERT INTO tgscan.post_subscriptions (
                chat_id, enabled, topic_pattern, event_types, min_severity,
                template_code, dedupe_ttl_sec, created_at, updated_at
            )
            VALUES (
                :chatId, TRUE, :topicPattern, :eventTypes, :minSeverity,
                :template, :dedupeTtl, NOW(), NOW()
            )
            RETURNING id
            """)
            .bind("chatId", chatId)
            .bind("topicPattern", topicPattern)
            .bind("eventTypes", eventTypes)
            .bind("minSeverity", minSeverity)
            .bind("template", template)
            .bind("dedupeTtl", dedupeTtl)
            .map(row -> row.get("id", Long.class))
            .one()
            .block();
    }
}
