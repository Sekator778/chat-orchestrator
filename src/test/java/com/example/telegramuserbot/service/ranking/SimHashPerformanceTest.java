package com.example.telegramuserbot.service.ranking;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance tests for SimHashService.
 * Validates that hashing meets performance targets.
 */
class SimHashPerformanceTest {

    private static final int PERFORMANCE_TEST_SIZE = 1000;
    private static final long MAX_AVERAGE_MS = 10;
    private SimHashService service;

    @BeforeEach
    void setUp() {
        service = new SimHashService();
    }

    @Test
    void hashingThousandMessagesCompletesUnderTarget() {
        List<String> messages = generateRandomMessages(PERFORMANCE_TEST_SIZE);
        long startTime = System.currentTimeMillis();
        for (String message : messages) {
            service.hash(message);
        }
        long endTime = System.currentTimeMillis();
        long totalTime = endTime - startTime;
        double averageMs = (double) totalTime / PERFORMANCE_TEST_SIZE;
        assertTrue(averageMs < MAX_AVERAGE_MS,
                String.format("Average hash time %.2fms exceeds target %dms", averageMs, MAX_AVERAGE_MS));
    }

    @Test
    void distanceCalculationIsEfficient() {
        String hash1 = service.hash("Sample text for performance testing");
        String hash2 = service.hash("Different text for comparison testing");
        long startTime = System.nanoTime();
        for (int i = 0; i < 10000; i++) {
            service.distance(hash1, hash2);
        }
        long endTime = System.nanoTime();
        double averageNanos = (double) (endTime - startTime) / 10000;
        assertTrue(averageNanos < 10000, "Distance calculation too slow: " + averageNanos + "ns");
    }

    @Test
    void similarityCheckIsEfficient() {
        String hash1 = service.hash("Sample text for performance testing");
        String hash2 = service.hash("Sample text for performance test");
        long startTime = System.nanoTime();
        for (int i = 0; i < 10000; i++) {
            service.similar(hash1, hash2, 8);
        }
        long endTime = System.nanoTime();
        double averageNanos = (double) (endTime - startTime) / 10000;
        assertTrue(averageNanos < 10000, "Similarity check too slow: " + averageNanos + "ns");
    }

    @Test
    void hashingLongMessagesRemainsEfficient() {
        String longMessage = generateLongMessage(5000);
        long startTime = System.currentTimeMillis();
        for (int i = 0; i < 100; i++) {
            service.hash(longMessage);
        }
        long endTime = System.currentTimeMillis();
        double averageMs = (double) (endTime - startTime) / 100;
        assertTrue(averageMs < 50, "Long message hash time " + averageMs + "ms exceeds 50ms limit");
    }

    @Test
    void hashingShortMessagesIsVeryFast() {
        String shortMessage = "Short text";
        long startTime = System.nanoTime();
        for (int i = 0; i < 10000; i++) {
            service.hash(shortMessage);
        }
        long endTime = System.nanoTime();
        double averageNanos = (double) (endTime - startTime) / 10000;
        assertTrue(averageNanos < 1000000, "Short message hash too slow: " + averageNanos + "ns");
    }

    @Test
    void bulkSimilarityComparisonIsEfficient() {
        List<String> hashes = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            hashes.add(service.hash("Message number " + i + " with some content"));
        }
        long startTime = System.currentTimeMillis();
        int comparisons = 0;
        for (int i = 0; i < hashes.size(); i++) {
            for (int j = i + 1; j < hashes.size(); j++) {
                service.similar(hashes.get(i), hashes.get(j), 8);
                comparisons++;
            }
        }
        long endTime = System.currentTimeMillis();
        long totalTime = endTime - startTime;
        assertTrue(totalTime < 100, "Bulk comparison (" + comparisons + " pairs) took " + totalTime + "ms");
    }

    private List<String> generateRandomMessages(int count) {
        List<String> messages = new ArrayList<>();
        Random random = new Random(42);
        String[] words = {"breaking", "news", "today", "market", "economy", "politics", "technology",
                "science", "health", "sports", "weather", "update", "report", "analysis", "event",
                "development", "announcement", "statement", "policy", "decision"};
        for (int i = 0; i < count; i++) {
            StringBuilder sb = new StringBuilder();
            int wordCount = 10 + random.nextInt(40);
            for (int j = 0; j < wordCount; j++) {
                if (j > 0) sb.append(" ");
                sb.append(words[random.nextInt(words.length)]);
            }
            messages.add(sb.toString());
        }
        return messages;
    }

    private String generateLongMessage(int length) {
        StringBuilder sb = new StringBuilder();
        String sample = "This is a sample text that will be repeated to create a long message. ";
        while (sb.length() < length) {
            sb.append(sample);
        }
        return sb.substring(0, length);
    }
}
