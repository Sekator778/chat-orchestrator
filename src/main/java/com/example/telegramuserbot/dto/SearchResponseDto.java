package com.example.telegramuserbot.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * DTO for search responses
 */
public class SearchResponseDto {
    
    private String query;
    
    @JsonProperty("normalized_query")
    private String normalizedQuery;
    
    @JsonProperty("search_provider")
    private String searchProvider;
    
    @JsonProperty("total_results")
    private Long totalResults;
    
    private List<SearchItemDto> results;
    
    @JsonProperty("search_time_ms")
    private Long searchTimeMs;
    
    @JsonProperty("relevance_score")
    private Double relevanceScore;
    
    @JsonProperty("from_cache")
    private boolean fromCache;
    
    @JsonProperty("created_at")
    private Instant createdAt;
    
    @JsonProperty("summary")
    private String summary;
    
    @JsonProperty("attribution")
    private String attribution;
    
    // Constructors
    public SearchResponseDto() {}
    
    public SearchResponseDto(String query, List<SearchItemDto> results) {
        this.query = query;
        this.results = results;
        this.createdAt = Instant.now();
    }
    
    // Getters and Setters
    public String getQuery() {
        return query;
    }
    
    public void setQuery(String query) {
        this.query = query;
    }
    
    public String getNormalizedQuery() {
        return normalizedQuery;
    }
    
    public void setNormalizedQuery(String normalizedQuery) {
        this.normalizedQuery = normalizedQuery;
    }
    
    public String getSearchProvider() {
        return searchProvider;
    }
    
    public void setSearchProvider(String searchProvider) {
        this.searchProvider = searchProvider;
    }
    
    public Long getTotalResults() {
        return totalResults;
    }
    
    public void setTotalResults(Long totalResults) {
        this.totalResults = totalResults;
    }
    
    public List<SearchItemDto> getResults() {
        return results;
    }
    
    public void setResults(List<SearchItemDto> results) {
        this.results = results;
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
    
    public boolean isFromCache() {
        return fromCache;
    }
    
    public void setFromCache(boolean fromCache) {
        this.fromCache = fromCache;
    }
    
    public Instant getCreatedAt() {
        return createdAt;
    }
    
    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
    
    public String getSummary() {
        return summary;
    }
    
    public void setSummary(String summary) {
        this.summary = summary;
    }
    
    public String getAttribution() {
        return attribution;
    }
    
    public void setAttribution(String attribution) {
        this.attribution = attribution;
    }
    
    /**
     * Individual search result item
     */
    public static class SearchItemDto {
        private String title;
        private String url;
        private String snippet;
        
        @JsonProperty("display_url")
        private String displayUrl;
        
        @JsonProperty("relevance_score")
        private Double relevanceScore;
        
        @JsonProperty("content_type")
        private String contentType;
        
        @JsonProperty("published_date")
        private String publishedDate;

        /** Full article body when fetched via Tavily advanced + include_raw_content (else null). */
        @JsonProperty("raw_content")
        private String rawContent;

        // Constructors
        public SearchItemDto() {}
        
        public SearchItemDto(String title, String url, String snippet) {
            this.title = title;
            this.url = url;
            this.snippet = snippet;
        }
        
        // Getters and Setters
        public String getTitle() {
            return title;
        }
        
        public void setTitle(String title) {
            this.title = title;
        }
        
        public String getUrl() {
            return url;
        }
        
        public void setUrl(String url) {
            this.url = url;
        }
        
        public String getSnippet() {
            return snippet;
        }
        
        public void setSnippet(String snippet) {
            this.snippet = snippet;
        }
        
        public String getDisplayUrl() {
            return displayUrl;
        }
        
        public void setDisplayUrl(String displayUrl) {
            this.displayUrl = displayUrl;
        }
        
        public Double getRelevanceScore() {
            return relevanceScore;
        }
        
        public void setRelevanceScore(Double relevanceScore) {
            this.relevanceScore = relevanceScore;
        }
        
        public String getContentType() {
            return contentType;
        }
        
        public void setContentType(String contentType) {
            this.contentType = contentType;
        }
        
        public String getPublishedDate() {
            return publishedDate;
        }
        
        public void setPublishedDate(String publishedDate) {
            this.publishedDate = publishedDate;
        }

        public String getRawContent() {
            return rawContent;
        }

        public void setRawContent(String rawContent) {
            this.rawContent = rawContent;
        }
    }
}