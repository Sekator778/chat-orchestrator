package com.example.telegramuserbot.service.ranking;

import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;

/**
 * Service for synthesizing news digests from clustered messages.
 * Uses AI to generate coherent summaries of related news.
 */
public interface NewsSynthesisService {

    /**
     * Generates a digest from top messages in a time window.
     *
     * @param window       Time window to consider
     * @param maxMessages  Maximum messages to include
     * @param targetLanguage Language for the digest (en, ru, uk)
     * @return Mono with generated digest text
     */
    Mono<String> generateDigest(Duration window, int maxMessages, String targetLanguage);

    /**
     * Generates a summary for a specific cluster of messages.
     *
     * @param clusterId      Cluster ID to summarize
     * @param targetLanguage Language for the summary
     * @return Mono with cluster summary
     */
    Mono<String> summarizeCluster(String clusterId, String targetLanguage);

    /**
     * Generates bullet points from a list of message contents.
     *
     * @param contents       List of message content strings
     * @param targetLanguage Target language for output
     * @return Mono with bullet point summary
     */
    Mono<String> generateBulletPoints(List<String> contents, String targetLanguage);
}
