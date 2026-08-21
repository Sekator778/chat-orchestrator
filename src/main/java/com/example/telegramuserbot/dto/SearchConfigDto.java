package com.example.telegramuserbot.dto;

import com.example.telegramuserbot.domain.SearchProvider;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;

import java.util.List;

/**
 * DTO for search configuration
 */
public class SearchConfigDto {
    
    private Long id;
    
    @NotNull(message = "Chat ID cannot be null")
    @JsonProperty("chat_id")
    private Long chatId;
    
    @JsonProperty("search_enabled")
    private boolean searchEnabled = false;
    
    @JsonProperty("auto_search_enabled")
    private boolean autoSearchEnabled = false;
    
    @JsonProperty("search_provider")
    private SearchProvider searchProvider = SearchProvider.GOOGLE;
    
    @Min(value = 1, message = "Max results must be at least 1")
    @Max(value = 20, message = "Max results cannot exceed 20")
    @JsonProperty("max_results")
    private Integer maxResults = 5;
    
    @Min(value = 1, message = "Cache duration must be at least 1 minute")
    @Max(value = 1440, message = "Cache duration cannot exceed 24 hours")
    @JsonProperty("cache_duration_minutes")
    private Integer cacheDurationMinutes = 60;
    
    @Min(value = 1, message = "Rate limit must be at least 1 per hour")
    @Max(value = 1000, message = "Rate limit cannot exceed 1000 per hour")
    @JsonProperty("rate_limit_per_hour")
    private Integer rateLimitPerHour = 30;
    
    @JsonProperty("include_attribution")
    private boolean includeAttribution = true;
    
    @DecimalMin(value = "0.0", message = "Relevance threshold must be between 0.0 and 1.0")
    @DecimalMax(value = "1.0", message = "Relevance threshold must be between 0.0 and 1.0")
    @JsonProperty("relevance_threshold")
    private Double relevanceThreshold = 0.6;
    
    @JsonProperty("search_triggers")
    private List<String> searchTriggers;
    
    // Constructors
    public SearchConfigDto() {}
    
    public SearchConfigDto(Long chatId) {
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
    
    public List<String> getSearchTriggers() {
        return searchTriggers;
    }
    
    public void setSearchTriggers(List<String> searchTriggers) {
        this.searchTriggers = searchTriggers;
    }
}