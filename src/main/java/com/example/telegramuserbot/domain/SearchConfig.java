package com.example.telegramuserbot.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.Objects;

/**
 * Configuration entity for search functionality per chat
 */
@Table("search_configs")
public class SearchConfig {

    @Id
    private Long id;

    @Column("chat_id")
    private Long chatId;

    @Column("search_enabled")
    private boolean searchEnabled = false;

    @Column("auto_search_enabled")
    private boolean autoSearchEnabled = false;

    @Column("search_provider")
    private SearchProvider searchProvider = SearchProvider.GOOGLE;

    @Column("max_results")
    private Integer maxResults = 5;

    @Column("cache_duration_minutes")
    private Integer cacheDurationMinutes = 60;

    @Column("rate_limit_per_hour")
    private Integer rateLimitPerHour = 30;

    @Column("include_attribution")
    private boolean includeAttribution = true;

    @Column("relevance_threshold")
    private Double relevanceThreshold = 0.6;

    @Column("search_triggers")
    private String searchTriggers; // JSON array of trigger patterns

    // Constructors
    public SearchConfig() {}

    public SearchConfig(Long chatId) {
        this.chatId = chatId;
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getChatId() {
        return chatId;
    }

    public void setChatId(Long chatId) {
        this.chatId = chatId;
    }

    public boolean isSearchEnabled() {
        return searchEnabled;
    }

    public void setSearchEnabled(boolean searchEnabled) {
        this.searchEnabled = searchEnabled;
    }

    public boolean isAutoSearchEnabled() {
        return autoSearchEnabled;
    }

    public void setAutoSearchEnabled(boolean autoSearchEnabled) {
        this.autoSearchEnabled = autoSearchEnabled;
    }

    public SearchProvider getSearchProvider() {
        return searchProvider;
    }

    public void setSearchProvider(SearchProvider searchProvider) {
        this.searchProvider = searchProvider;
    }

    public Integer getMaxResults() {
        return maxResults;
    }

    public void setMaxResults(Integer maxResults) {
        this.maxResults = maxResults;
    }

    public Integer getCacheDurationMinutes() {
        return cacheDurationMinutes;
    }

    public void setCacheDurationMinutes(Integer cacheDurationMinutes) {
        this.cacheDurationMinutes = cacheDurationMinutes;
    }

    public Integer getRateLimitPerHour() {
        return rateLimitPerHour;
    }

    public void setRateLimitPerHour(Integer rateLimitPerHour) {
        this.rateLimitPerHour = rateLimitPerHour;
    }

    public boolean isIncludeAttribution() {
        return includeAttribution;
    }

    public void setIncludeAttribution(boolean includeAttribution) {
        this.includeAttribution = includeAttribution;
    }

    public Double getRelevanceThreshold() {
        return relevanceThreshold;
    }

    public void setRelevanceThreshold(Double relevanceThreshold) {
        this.relevanceThreshold = relevanceThreshold;
    }

    public String getSearchTriggers() {
        return searchTriggers;
    }

    public void setSearchTriggers(String searchTriggers) {
        this.searchTriggers = searchTriggers;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SearchConfig that = (SearchConfig) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}