package com.example.telegramuserbot.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.Objects;

/**
 * Entity to store cached search results
 */
@Table("search_results")
public class SearchResult {

    @Id
    private Long id;

    @Column("query_hash")
    private String queryHash; // SHA-256 hash of normalized query

    @Column("original_query")
    private String originalQuery;

    @Column("normalized_query")
    private String normalizedQuery;

    @Column("search_provider")
    private SearchProvider searchProvider;

    @Column("results_json")
    private String resultsJson; // JSON array of search results

    @Column("total_results")
    private Long totalResults;

    @Column("search_time_ms")
    private Long searchTimeMs;

    @Column("relevance_score")
    private Double relevanceScore;

    @Column("created_at")
    private Instant createdAt = Instant.now();

    @Column("expires_at")
    private Instant expiresAt;

    @Column("access_count")
    private int accessCount = 0;

    @Column("last_accessed_at")
    private Instant lastAccessedAt;

    // Constructors
    public SearchResult() {}

    public SearchResult(String queryHash, String originalQuery, String normalizedQuery,
                       SearchProvider searchProvider, String resultsJson) {
        this.queryHash = queryHash;
        this.originalQuery = originalQuery;
        this.normalizedQuery = normalizedQuery;
        this.searchProvider = searchProvider;
        this.resultsJson = resultsJson;
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getQueryHash() {
        return queryHash;
    }

    public void setQueryHash(String queryHash) {
        this.queryHash = queryHash;
    }

    public String getOriginalQuery() {
        return originalQuery;
    }

    public void setOriginalQuery(String originalQuery) {
        this.originalQuery = originalQuery;
    }

    public String getNormalizedQuery() {
        return normalizedQuery;
    }

    public void setNormalizedQuery(String normalizedQuery) {
        this.normalizedQuery = normalizedQuery;
    }

    public SearchProvider getSearchProvider() {
        return searchProvider;
    }

    public void setSearchProvider(SearchProvider searchProvider) {
        this.searchProvider = searchProvider;
    }

    public String getResultsJson() {
        return resultsJson;
    }

    public void setResultsJson(String resultsJson) {
        this.resultsJson = resultsJson;
    }

    public Long getTotalResults() {
        return totalResults;
    }

    public void setTotalResults(Long totalResults) {
        this.totalResults = totalResults;
    }

    public Long getSearchTimeMs() {
        return searchTimeMs;
    }

    public void setSearchTimeMs(Long searchTimeMs) {
        this.searchTimeMs = searchTimeMs;
    }

    public Double getRelevanceScore() {
        return relevanceScore;
    }

    public void setRelevanceScore(Double relevanceScore) {
        this.relevanceScore = relevanceScore;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public int getAccessCount() {
        return accessCount;
    }

    public void setAccessCount(int accessCount) {
        this.accessCount = accessCount;
    }

    public Instant getLastAccessedAt() {
        return lastAccessedAt;
    }

    public void setLastAccessedAt(Instant lastAccessedAt) {
        this.lastAccessedAt = lastAccessedAt;
    }

    /**
     * Increment access count and update last accessed time
     */
    public void recordAccess() {
        this.accessCount++;
        this.lastAccessedAt = Instant.now();
    }

    /**
     * Check if the cached result has expired
     */
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SearchResult that = (SearchResult) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}