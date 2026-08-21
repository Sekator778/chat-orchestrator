package com.example.telegramuserbot.dto;

import java.util.Map;

/**
 * DTO for search configuration statistics
 */
public class SearchConfigStatsDto {
    private long totalConfigurations;
    private long searchEnabledCount;
    private long autoSearchEnabledCount;
    private java.util.Map<String, Long> providerDistribution;
    private double averageMaxResults;
    private double averageCacheDuration;
    private double averageRateLimit;

    public SearchConfigStatsDto() {}

    public SearchConfigStatsDto(long totalConfigurations, long searchEnabledCount,
                              long autoSearchEnabledCount, java.util.Map<String, Long> providerDistribution,
                              double averageMaxResults, double averageCacheDuration, double averageRateLimit) {
        this.totalConfigurations = totalConfigurations;
        this.searchEnabledCount = searchEnabledCount;
        this.autoSearchEnabledCount = autoSearchEnabledCount;
        this.providerDistribution = providerDistribution;
        this.averageMaxResults = averageMaxResults;
        this.averageCacheDuration = averageCacheDuration;
        this.averageRateLimit = averageRateLimit;
    }

    // Getters and Setters
    public long getTotalConfigurations() {
        return totalConfigurations;
    }

    public void setTotalConfigurations(long totalConfigurations) {
        this.totalConfigurations = totalConfigurations;
    }

    public long getSearchEnabledCount() {
        return searchEnabledCount;
    }

    public void setSearchEnabledCount(long searchEnabledCount) {
        this.searchEnabledCount = searchEnabledCount;
    }

    public long getAutoSearchEnabledCount() {
        return autoSearchEnabledCount;
    }

    public void setAutoSearchEnabledCount(long autoSearchEnabledCount) {
        this.autoSearchEnabledCount = autoSearchEnabledCount;
    }

    public java.util.Map<String, Long> getProviderDistribution() {
        return providerDistribution;
    }

    public void setProviderDistribution(java.util.Map<String, Long> providerDistribution) {
        this.providerDistribution = providerDistribution;
    }

    public double getAverageMaxResults() {
        return averageMaxResults;
    }

    public void setAverageMaxResults(double averageMaxResults) {
        this.averageMaxResults = averageMaxResults;
    }

    public double getAverageCacheDuration() {
        return averageCacheDuration;
    }

    public void setAverageCacheDuration(double averageCacheDuration) {
        this.averageCacheDuration = averageCacheDuration;
    }

    public double getAverageRateLimit() {
        return averageRateLimit;
    }

    public void setAverageRateLimit(double averageRateLimit) {
        this.averageRateLimit = averageRateLimit;
    }
}
