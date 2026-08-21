package com.example.telegramuserbot.service.ranking;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * SimHash implementation for content deduplication and similarity detection.
 * Based on Charikar's SimHash algorithm for finding near-duplicates.
 */
@Service
public final class SimHashService {

    private static final Logger log = LoggerFactory.getLogger(SimHashService.class);
    private static final int HASH_BITS = 64;
    private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;
    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+");
    private static final Pattern HTML_PATTERN = Pattern.compile("<[^>]*>");
    private static final Pattern EMOJI_PATTERN = Pattern.compile("[\\p{So}\\p{Cn}]");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
    private static final Pattern WORD_SPLIT_PATTERN = Pattern.compile("[\\s\\p{Punct}]+");
    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
            "i", "me", "my", "myself", "we", "our", "ours", "ourselves", "you", "your", "yours",
            "he", "him", "his", "she", "her", "hers", "it", "its", "they", "them", "their",
            "what", "which", "who", "whom", "this", "that", "these", "those", "am", "is", "are",
            "was", "were", "be", "been", "being", "have", "has", "had", "having", "do", "does",
            "did", "doing", "a", "an", "the", "and", "but", "if", "or", "because", "as", "until",
            "while", "of", "at", "by", "for", "with", "about", "against", "between", "into",
            "through", "during", "before", "after", "above", "below", "to", "from", "up", "down",
            "in", "out", "on", "off", "over", "under", "again", "further", "then", "once",
            "и", "в", "на", "с", "по", "для", "что", "как", "это", "был", "быть",
            "из", "о", "от", "к", "до", "не", "за", "при", "так", "также", "или",
            "та", "те", "ті", "той", "та", "це", "є", "був", "була", "були", "бути",
            "з", "у", "на", "по", "до", "від", "для", "як", "що", "але", "та", "і"
    ));

    /**
     * Calculates 64-bit SimHash for given text.
     *
     * @param text Input text
     * @return 16-character hex string (64-bit hash)
     */
    public String hash(String text) {
        if (text == null || text.isBlank()) {
            return "0".repeat(16);
        }
        String cleaned = preprocessText(text);
        String[] tokens = tokenize(cleaned);
        int[] weights = new int[HASH_BITS];
        for (String token : tokens) {
            if (token.isEmpty() || STOP_WORDS.contains(token.toLowerCase())) {
                continue;
            }
            long tokenHash = hashToken(token);
            for (int i = 0; i < HASH_BITS; i++) {
                if (((tokenHash >> i) & 1) == 1) {
                    weights[i]++;
                } else {
                    weights[i]--;
                }
            }
        }
        long simHash = 0;
        for (int i = 0; i < HASH_BITS; i++) {
            if (weights[i] > 0) {
                simHash |= (1L << i);
            }
        }
        return String.format("%016x", simHash);
    }

    /**
     * Calculates Hamming distance between two hashes.
     *
     * @param hash1 First hash
     * @param hash2 Second hash
     * @return Hamming distance (0-64)
     */
    public int distance(String hash1, String hash2) {
        if (hash1 == null || hash2 == null || hash1.length() != 16 || hash2.length() != 16) {
            return HASH_BITS;
        }
        try {
            long h1 = Long.parseUnsignedLong(hash1, 16);
            long h2 = Long.parseUnsignedLong(hash2, 16);
            return Long.bitCount(h1 ^ h2);
        } catch (NumberFormatException e) {
            log.warn("Invalid hash format: {} or {}", hash1, hash2);
            return HASH_BITS;
        }
    }

    /**
     * Checks if two texts are similar based on Hamming distance threshold.
     *
     * @param hash1     First hash
     * @param hash2     Second hash
     * @param threshold Maximum Hamming distance (default: 8 for similar)
     * @return true if similar
     */
    public boolean similar(String hash1, String hash2, int threshold) {
        return distance(hash1, hash2) <= threshold;
    }

    /**
     * Checks if two texts are similar using default threshold of 8.
     */
    public boolean similar(String hash1, String hash2) {
        return similar(hash1, hash2, 8);
    }

    /**
     * Calculates similarity score between two hashes as percentage.
     *
     * @return Similarity from 0.0 (completely different) to 1.0 (identical)
     */
    public double similarity(String hash1, String hash2) {
        int dist = distance(hash1, hash2);
        return 1.0 - (dist / (double) HASH_BITS);
    }

    private String preprocessText(String text) {
        String result = URL_PATTERN.matcher(text).replaceAll("");
        result = HTML_PATTERN.matcher(result).replaceAll("");
        result = EMOJI_PATTERN.matcher(result).replaceAll("");
        result = WHITESPACE_PATTERN.matcher(result).replaceAll(" ");
        return result.trim();
    }

    private String[] tokenize(String text) {
        return WORD_SPLIT_PATTERN.split(text.toLowerCase());
    }

    private long hashToken(String token) {
        long hash = FNV_OFFSET_BASIS;
        for (byte b : token.getBytes(StandardCharsets.UTF_8)) {
            hash ^= (b & 0xff);
            hash *= FNV_PRIME;
        }
        return hash;
    }
}
