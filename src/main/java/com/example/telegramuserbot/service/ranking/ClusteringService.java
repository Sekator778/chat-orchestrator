package com.example.telegramuserbot.service.ranking;

import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Service for clustering similar messages across channels.
 * Uses SimHash-based similarity detection to group related news.
 */
public interface ClusteringService {

    /**
     * Clusters unclustered messages from the past time window.
     *
     * @param window Time window to look back for unclustered messages
     * @return Mono with count of messages processed
     */
    Mono<Integer> clusterRecentMessages(Duration window);

    /**
     * Clusters a specific message by finding similar existing messages.
     *
     * @param messageId Database ID of the message
     * @return Mono with cluster ID assigned (or existing)
     */
    Mono<String> clusterMessage(Long messageId);

    /**
     * Recalculates primary message for each cluster in time window.
     *
     * @param window Time window to consider
     * @return Mono with count of clusters updated
     */
    Mono<Integer> recalculatePrimaryMessages(Duration window);

    /**
     * Designates a primary for every "headless" cluster (one with no primary member),
     * regardless of cluster age. Closes the permanent-orphan tail that the window-bounded
     * {@link #recalculatePrimaryMessages(Duration)} cannot reach once a cluster ages out of
     * its window. Intended to run inside the hourly clustering job (which reliably fires),
     * not on a separate timer that deploy churn can truncate.
     *
     * @return Mono with count of clusters healed
     */
    Mono<Integer> healHeadlessClusters();
}
