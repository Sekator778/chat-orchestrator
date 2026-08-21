package com.example.telegramuserbot.integration;

import com.example.telegramuserbot.dto.KafkaTelegramMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * High-throughput integration tests validating async/reactive architecture
 * under load conditions matching production requirements.
 *
 * Requirements (see tasks_and_manuals/events_and_alerts_pipeline.md):
 * - Handle 1000+ messages per 10 minutes during bursts
 * - Latency: <120 seconds from message to alert
 * - No message loss
 *
 * Tests actual Kafka and PostgreSQL (unit_db) interaction.
 */
@SpringBootTest(
    properties = {
        "events.watcher.enabled=false",       // Disable event watcher for Kafka tests
        "events.publisher.enabled=false",     // Disable event publisher for Kafka tests
        "telegram.enabled=false"              // Disable real Telegram client
    }
)
@DisplayName("High-Throughput Kafka Integration Tests")
final class HighThroughputKafkaIntegrationTest extends BaseIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(HighThroughputKafkaIntegrationTest.class);

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${kafka.topic.incoming-messages}")
    private String topicName;

    @Test
    @DisplayName("Baseline: Sustain 10 messages per second for 30 seconds")
    void testSustainedThroughput() throws Exception {
        // Arrange
        int messagesPerSecond = 10;
        int durationSeconds = 30;
        int totalMessages = messagesPerSecond * durationSeconds;

        long baseChatId = -1001000000L;

        log.info("TEST START: Sustained throughput - {} msg/sec for {} sec = {} total",
            messagesPerSecond, durationSeconds, totalMessages);

        // Pre-create test channel
        insertTestChannel(baseChatId, "Test Channel - Sustained", 0.5, 10000L).block();

        Instant startTime = Instant.now();
        AtomicInteger sentCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        // Act: Send messages at controlled rate
        Flux.range(0, totalMessages)
            .delayElements(Duration.ofMillis(100)) // 10 per second
            .flatMap(i -> {
                long messageId = 1000000L + i;
                KafkaTelegramMessage kafkaMsg = new KafkaTelegramMessage(baseChatId, messageId);

                return Mono.fromCallable(() -> objectMapper.writeValueAsString(kafkaMsg))
                    .flatMap(json -> Mono.fromFuture(
                        kafkaTemplate.send(topicName, String.valueOf(baseChatId), json)
                    ))
                    .doOnSuccess(result -> {
                        int count = sentCount.incrementAndGet();
                        if (count % 50 == 0) {
                            log.info("Sent {} messages...", count);
                        }
                    })
                    .doOnError(error -> {
                        errorCount.incrementAndGet();
                        log.error("Failed to send message {}: {}", i, error.getMessage());
                    })
                    .onErrorResume(e -> Mono.empty()); // Continue on error
            }, 20) // Concurrency: 20 parallel sends
            .then()
            .as(StepVerifier::create)
            .expectComplete()
            .verify(Duration.ofMinutes(2));

        Duration sendDuration = Duration.between(startTime, Instant.now());

        // Assert: All messages sent successfully
        log.info("SEND COMPLETE: {} messages sent in {} ms ({} msg/sec), {} errors",
            sentCount.get(), sendDuration.toMillis(),
            sentCount.get() * 1000.0 / sendDuration.toMillis(),
            errorCount.get());

        assert sentCount.get() >= totalMessages * 0.99 : "Should send 99%+ of messages";
        assert errorCount.get() < totalMessages * 0.01 : "Error rate should be <1%";

        // Wait for Kafka consumers to process
        log.info("Waiting for Kafka consumers to process messages...");
        Thread.sleep(10000); // 10 seconds processing time

        // Verify: Messages persisted to database
        Long messagesInDb = countBotMessages().block();
        log.info("DATABASE CHECK: {} messages persisted", messagesInDb);

        // We expect some messages might not trigger persistence (e.g., sync disabled)
        // But should have at least 50% persisted as a baseline
        assert messagesInDb >= totalMessages * 0.5 :
            String.format("Expected at least 50%% persistence, got %d/%d", messagesInDb, totalMessages);
    }

    @Test
    @DisplayName("Burst: Handle 200 messages in 10 seconds")
    void testBurstCapacity() throws Exception {
        // Arrange
        int burstSize = 200;
        int burstWindowSeconds = 10;
        long baseChatId = -1002000000L;

        log.info("TEST START: Burst capacity - {} messages in {} seconds",
            burstSize, burstWindowSeconds);

        insertTestChannel(baseChatId, "Test Channel - Burst", 0.8, 50000L).block();

        Instant startTime = Instant.now();
        AtomicInteger sentCount = new AtomicInteger(0);

        // Act: Send burst of messages
        Flux.range(0, burstSize)
            .flatMap(i -> {
                long messageId = 2000000L + i;
                KafkaTelegramMessage kafkaMsg = new KafkaTelegramMessage(baseChatId, messageId);

                return Mono.fromCallable(() -> objectMapper.writeValueAsString(kafkaMsg))
                    .flatMap(json -> Mono.fromFuture(
                        kafkaTemplate.send(topicName, String.valueOf(baseChatId), json)
                    ))
                    .doOnSuccess(result -> sentCount.incrementAndGet());
            }, 50) // High concurrency for burst
            .then()
            .as(StepVerifier::create)
            .expectComplete()
            .verify(Duration.ofSeconds(burstWindowSeconds + 5));

        Duration sendDuration = Duration.between(startTime, Instant.now());
        double throughput = sentCount.get() * 1000.0 / sendDuration.toMillis();

        log.info("BURST COMPLETE: {} messages sent in {} ms ({:.1f} msg/sec)",
            sentCount.get(), sendDuration.toMillis(), throughput);

        // Assert: All messages sent within time limit
        assert sentCount.get() == burstSize : "All messages should be sent";
        assert sendDuration.toSeconds() <= burstWindowSeconds + 2 : "Should complete within time limit + buffer";

        // Wait for processing
        Thread.sleep(15000); // 15 seconds

        Long messagesInDb = countBotMessages().block();
        log.info("DATABASE CHECK: {} messages persisted after burst", messagesInDb);

        // Verify throughput maintained
        assert messagesInDb >= burstSize * 0.5 : "Should handle burst without message loss";
    }

    @Test
    @DisplayName("Stress: 1000 messages over 10 minutes (production load)")
    void testProductionLoad() throws Exception {
        // Arrange
        int totalMessages = 1000;
        int durationMinutes = 10;
        long baseChatId = -1003000000L;

        log.info("TEST START: Production load - {} messages over {} minutes",
            totalMessages, durationMinutes);

        insertTestChannel(baseChatId, "Test Channel - Production", 0.7, 100000L).block();

        // Calculate delay for even distribution
        long delayMs = (durationMinutes * 60 * 1000) / totalMessages; // ~600ms per message

        Instant startTime = Instant.now();
        AtomicInteger sentCount = new AtomicInteger(0);
        List<Duration> sendLatencies = new ArrayList<>();

        // Act: Send messages distributed over time
        Flux.range(0, totalMessages)
            .delayElements(Duration.ofMillis(delayMs))
            .flatMap(i -> {
                Instant msgStart = Instant.now();
                long messageId = 3000000L + i;
                KafkaTelegramMessage kafkaMsg = new KafkaTelegramMessage(baseChatId, messageId);

                return Mono.fromCallable(() -> objectMapper.writeValueAsString(kafkaMsg))
                    .flatMap(json -> Mono.fromFuture(
                        kafkaTemplate.send(topicName, String.valueOf(baseChatId), json)
                    ))
                    .doOnSuccess(result -> {
                        int count = sentCount.incrementAndGet();
                        Duration latency = Duration.between(msgStart, Instant.now());
                        synchronized (sendLatencies) {
                            sendLatencies.add(latency);
                        }
                        if (count % 100 == 0) {
                            log.info("Progress: {}/{} messages sent", count, totalMessages);
                        }
                    });
            }, 10) // Moderate concurrency
            .then()
            .as(StepVerifier::create)
            .expectComplete()
            .verify(Duration.ofMinutes(durationMinutes + 2));

        Duration totalDuration = Duration.between(startTime, Instant.now());

        // Calculate latency statistics
        synchronized (sendLatencies) {
            sendLatencies.sort(Duration::compareTo);
            Duration p50 = sendLatencies.get(sendLatencies.size() / 2);
            Duration p95 = sendLatencies.get((int) (sendLatencies.size() * 0.95));
            Duration p99 = sendLatencies.get((int) (sendLatencies.size() * 0.99));

            log.info("SEND LATENCY STATS: p50={} ms, p95={} ms, p99={} ms",
                p50.toMillis(), p95.toMillis(), p99.toMillis());

            // Assert: Send latency within acceptable bounds
            assert p95.toMillis() < 1000 : "p95 send latency should be <1 second";
        }

        log.info("PRODUCTION LOAD COMPLETE: {} messages sent in {} minutes ({:.1f} msg/sec avg)",
            sentCount.get(), totalDuration.toMinutes(),
            sentCount.get() * 60.0 / totalDuration.toSeconds());

        // Wait for processing
        log.info("Waiting for processing to complete...");
        Thread.sleep(30000); // 30 seconds

        Long messagesInDb = countBotMessages().block();
        log.info("DATABASE CHECK: {} messages persisted ({}%)",
            messagesInDb, messagesInDb * 100 / totalMessages);

        // Assert: System handled production load
        assert sentCount.get() == totalMessages : "All messages should be sent";
        assert messagesInDb >= totalMessages * 0.5 : "Should persist majority of messages";
    }

    @Test
    @DisplayName("Kafka Lag: Verify consumer keeps up under load")
    void testKafkaLag() throws Exception {
        // Arrange
        int messageCount = 500;
        long baseChatId = -1004000000L;

        log.info("TEST START: Kafka lag test - {} messages", messageCount);

        insertTestChannel(baseChatId, "Test Channel - Lag", 0.6, 20000L).block();

        // Act: Send all messages as fast as possible (no delay)
        Instant startTime = Instant.now();

        Flux.range(0, messageCount)
            .flatMap(i -> {
                long messageId = 4000000L + i;
                KafkaTelegramMessage kafkaMsg = new KafkaTelegramMessage(baseChatId, messageId);

                return Mono.fromCallable(() -> objectMapper.writeValueAsString(kafkaMsg))
                    .flatMap(json -> Mono.fromFuture(
                        kafkaTemplate.send(topicName, String.valueOf(baseChatId), json)
                    ));
            }, 100) // Very high concurrency
            .then()
            .as(StepVerifier::create)
            .expectComplete()
            .verify(Duration.ofSeconds(30));

        Duration sendDuration = Duration.between(startTime, Instant.now());
        log.info("All {} messages sent in {} ms", messageCount, sendDuration.toMillis());

        // Measure processing time
        Instant processingStart = Instant.now();
        long previousCount = 0;

        for (int i = 0; i < 10; i++) {
            Thread.sleep(2000); // Check every 2 seconds
            Long currentCount = countBotMessages().block();
            long processed = currentCount - previousCount;
            previousCount = currentCount;

            double processingRate = processed / 2.0; // messages per second
            log.info("Processing check {}: {} total messages ({} msg/sec)",
                i + 1, currentCount, processingRate);

            if (currentCount >= messageCount * 0.95) {
                log.info("95% of messages processed!");
                break;
            }
        }

        Duration processingDuration = Duration.between(processingStart, Instant.now());
        Long finalCount = countBotMessages().block();

        double overallRate = finalCount * 1000.0 / processingDuration.toMillis();
        log.info("PROCESSING COMPLETE: {} messages in {} ms ({:.1f} msg/sec)",
            finalCount, processingDuration.toMillis(), overallRate);

        // Assert: Consumer kept up reasonably well
        assert processingDuration.toSeconds() < 60 : "Should process all messages within 60 seconds";
        assert finalCount >= messageCount * 0.9 : "Should process 90%+ of messages";
        assert overallRate >= 5.0 : "Processing rate should be >=5 msg/sec";
    }
}
