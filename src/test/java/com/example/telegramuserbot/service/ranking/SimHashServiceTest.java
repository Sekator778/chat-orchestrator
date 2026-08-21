package com.example.telegramuserbot.service.ranking;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SimHashService content hashing and similarity detection.
 */
class SimHashServiceTest {

    private SimHashService service;

    @BeforeEach
    void setUp() {
        service = new SimHashService();
    }

    @Test
    void hashReturnsSixteenCharacterHexString() {
        String hash = service.hash("This is a test message");
        assertNotNull(hash);
        assertEquals(16, hash.length());
        assertTrue(hash.matches("[0-9a-f]+"), "Hash should be hexadecimal");
    }

    @Test
    void hashReturnsZeroesForNullOrEmptyInput() {
        assertEquals("0000000000000000", service.hash(null));
        assertEquals("0000000000000000", service.hash(""));
        assertEquals("0000000000000000", service.hash("   "));
    }

    @Test
    void identicalTextsProduceSameHash() {
        String text = "Breaking news: Major event happening now";
        String hash1 = service.hash(text);
        String hash2 = service.hash(text);
        assertEquals(hash1, hash2);
    }

    @Test
    void similarTextsHaveSmallHammingDistance() {
        String text1 = "Breaking news: Major earthquake in California today";
        String text2 = "Breaking news: Major earthquake strikes California today";
        String hash1 = service.hash(text1);
        String hash2 = service.hash(text2);
        int distance = service.distance(hash1, hash2);
        assertTrue(distance <= 10, "Similar texts should have distance <= 10, got " + distance);
    }

    @Test
    void differentTextsHaveLargeHammingDistance() {
        String text1 = "Breaking news about politics and elections";
        String text2 = "Recipe for chocolate cake with vanilla frosting";
        String hash1 = service.hash(text1);
        String hash2 = service.hash(text2);
        int distance = service.distance(hash1, hash2);
        assertTrue(distance > 10, "Different texts should have distance > 10, got " + distance);
    }

    @Test
    void similarMethodReturnsTrueForSimilarContent() {
        String text1 = "Stock market reaches all time high today";
        String text2 = "Stock market reaches all time high today now";
        String hash1 = service.hash(text1);
        String hash2 = service.hash(text2);
        int distance = service.distance(hash1, hash2);
        assertTrue(service.similar(hash1, hash2, 12), "Distance was " + distance + ", expected similar with threshold 12");
    }

    @Test
    void similarMethodReturnsFalseForDifferentContent() {
        String text1 = "Weather forecast for tomorrow: sunny skies";
        String text2 = "Football team wins championship game in overtime";
        String hash1 = service.hash(text1);
        String hash2 = service.hash(text2);
        assertFalse(service.similar(hash1, hash2, 8));
    }

    @Test
    void distanceReturnsZeroForSameHash() {
        String hash = service.hash("Test message content");
        assertEquals(0, service.distance(hash, hash));
    }

    @Test
    void distanceHandlesNullAndEmptyHashes() {
        assertEquals(64, service.distance(null, "abcd1234abcd1234"));
        assertEquals(64, service.distance("abcd1234abcd1234", null));
        assertEquals(64, service.distance(null, null));
        assertEquals(64, service.distance("", "abcd1234abcd1234"));
    }

    @Test
    void similarityReturnsOneForIdenticalHashes() {
        String hash = service.hash("Test content");
        assertEquals(1.0, service.similarity(hash, hash), 0.001);
    }

    @Test
    void similarityReturnsValueBetweenZeroAndOne() {
        String hash1 = service.hash("First test message");
        String hash2 = service.hash("Second different message");
        double similarity = service.similarity(hash1, hash2);
        assertTrue(similarity >= 0.0 && similarity <= 1.0);
    }

    @Test
    void hashIsCaseInsensitive() {
        String text1 = "Breaking News Today";
        String text2 = "breaking news today";
        String hash1 = service.hash(text1);
        String hash2 = service.hash(text2);
        assertTrue(service.distance(hash1, hash2) <= 5, "Case difference should result in similar hashes");
    }

    @Test
    void hashHandlesUnicodeContent() {
        String text = "Новости мира: важные события";
        String hash = service.hash(text);
        assertNotNull(hash);
        assertEquals(16, hash.length());
        assertTrue(hash.matches("[0-9a-f]+"));
    }
}
