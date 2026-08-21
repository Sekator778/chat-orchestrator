package com.example.telegramuserbot.dto;

import com.example.telegramuserbot.domain.SearchProvider;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;

/**
 * DTO for search requests
 */
public class SearchRequestDto {
    
    @NotBlank(message = "Query cannot be blank")
    @Size(max = 500, message = "Query cannot exceed 500 characters")
    private String query;
    
    @JsonProperty("chat_id")
    private Long chatId;
    
    @JsonProperty("search_provider")
    private SearchProvider searchProvider;
    
    @Min(value = 1, message = "Max results must be at least 1")
    @Max(value = 20, message = "Max results cannot exceed 20")
    @JsonProperty("max_results")
    private Integer maxResults;
    
    @JsonProperty("force_refresh")
    private boolean forceRefresh = false;
    
    @JsonProperty("include_attribution")
    private Boolean includeAttribution;
    
    @DecimalMin(value = "0.0", message = "Relevance threshold must be between 0.0 and 1.0")
    @DecimalMax(value = "1.0", message = "Relevance threshold must be between 0.0 and 1.0")
    @JsonProperty("relevance_threshold")
    private Double relevanceThreshold;
    
    // Constructors
    public SearchRequestDto() {}
    
    public SearchRequestDto(String query) {
        this.query = query;
    }
    
    public SearchRequestDto(String query, Long chatId) {
        this.query = query;
        this.chatId = chatId;
    }
    
    // Getters and Setters
    public String getQuery() {
        return query;
    }
    
    public void setQuery(String query) {
        this.query = query;
    }
    
    public Long getChatId() {
        return chatId;
    }
    
    public void setChatId(Long chatId) {
        this.chatId = chatId;
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
    
    public boolean isForceRefresh() {
        return forceRefresh;
    }
    
    public void setForceRefresh(boolean forceRefresh) {
        this.forceRefresh = forceRefresh;
    }
    
    public Boolean getIncludeAttribution() {
        return includeAttribution;
    }
    
    public void setIncludeAttribution(Boolean includeAttribution) {
        this.includeAttribution = includeAttribution;
    }
    
    public Double getRelevanceThreshold() {
        return relevanceThreshold;
    }
    
    public void setRelevanceThreshold(Double relevanceThreshold) {
        this.relevanceThreshold = relevanceThreshold;
    }
}