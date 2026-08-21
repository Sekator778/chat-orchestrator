package com.example.telegramuserbot.dto;

/**
 * DTO for search statistics
 */
public class SearchStatsDto {
    private long totalSearches;
    private long cacheHits;
    private long cacheMisses;
    private double averageSearchTime;
    private long activeConfigurations;

    // Constructors
    public SearchStatsDto() {}

    public SearchStatsDto(long totalSearches, long cacheHits, long cacheMisses,
                          double averageSearchTime, long activeConfigurations) {
        this.totalSearches = totalSearches;
        this.cacheHits = cacheHits;
        this.cacheMisses = cacheMisses;
        this.averageSearchTime = averageSearchTime;
        this.activeConfigurations = activeConfigurations;
    }

    // Getters and Setters
    public long getTotalSearches() {
        return totalSearches;
    }

    public void setTotalSearches(long totalSearches) {
        this.totalSearches = totalSearches;
    }

    public long getCacheHits() {
        return cacheHits;
    }

    public void setCacheHits(long cacheHits) {
        this.cacheHits = cacheHits;
    }

    public long getCacheMisses() {
        return cacheMisses;
    }

    public void setCacheMisses(long cacheMisses) {
        this.cacheMisses = cacheMisses;
    }

    public double getAverageSearchTime() {
        return averageSearchTime;
    }

    public void setAverageSearchTime(double averageSearchTime) {
        this.averageSearchTime = averageSearchTime;
    }

    public long getActiveConfigurations() {
        return activeConfigurations;
    }

    public void setActiveConfigurations(long activeConfigurations) {
        this.activeConfigurations = activeConfigurations;
    }

    public double getCacheHitRate() {
        long total = cacheHits + cacheMisses;
        return total > 0 ? (double) cacheHits / total : 0.0;
    }
}
