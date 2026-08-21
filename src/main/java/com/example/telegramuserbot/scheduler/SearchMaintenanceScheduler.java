package com.example.telegramuserbot.scheduler;

import com.example.telegramuserbot.service.search.SearchRateLimitService;
import com.example.telegramuserbot.service.search.SearchRateLimitServiceImpl;
import com.example.telegramuserbot.service.search.SearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled tasks for search functionality maintenance
 */
@Component
public class SearchMaintenanceScheduler {
    
    private static final Logger log = LoggerFactory.getLogger(SearchMaintenanceScheduler.class);
    
    private final SearchService searchService;
    private final SearchRateLimitService rateLimitService;
    private final SearchRateLimitServiceImpl rateLimitServiceImpl;
    
    public SearchMaintenanceScheduler(SearchService searchService, 
                                    SearchRateLimitService rateLimitService,
                                    SearchRateLimitServiceImpl rateLimitServiceImpl) {
        this.searchService = searchService;
        this.rateLimitService = rateLimitService;
        this.rateLimitServiceImpl = rateLimitServiceImpl;
    }
    
    /**
     * Clear expired search cache entries every hour
     */
    @Scheduled(fixedRate = 3600000) // 1 hour
    public void clearExpiredSearchCache() {
        log.debug("Starting scheduled search cache cleanup");
        
        searchService.clearExpiredCache()
                .doOnSuccess(deletedCount -> {
                    if (deletedCount > 0) {
                        log.info("Cleared {} expired search cache entries", deletedCount);
                    } else {
                        log.debug("No expired search cache entries to clear");
                    }
                })
                .doOnError(error -> log.error("Error during search cache cleanup", error))
                .subscribe();
    }
    
    /**
     * Reset rate limits every hour
     */
    @Scheduled(cron = "0 0 * * * *") // At the start of every hour
    public void resetRateLimits() {
        log.debug("Starting scheduled rate limit reset");
        
        rateLimitService.resetRateLimits()
                .doOnSuccess(resetCount -> {
                    if (resetCount > 0) {
                        log.info("Reset rate limits for {} chats", resetCount);
                    } else {
                        log.debug("No rate limits to reset");
                    }
                })
                .doOnError(error -> log.error("Error during rate limit reset", error))
                .subscribe();
    }
    
    /**
     * Clean up old rate limit data every 4 hours
     */
    @Scheduled(fixedRate = 14400000) // 4 hours
    public void cleanupOldRateLimitData() {
        log.debug("Starting scheduled rate limit data cleanup");
        
        try {
            rateLimitServiceImpl.cleanupOldData();
            log.debug("Completed rate limit data cleanup");
        } catch (Exception e) {
            log.error("Error during rate limit data cleanup", e);
        }
    }
    
    /**
     * Log search statistics every 6 hours
     */
    @Scheduled(fixedRate = 21600000) // 6 hours
    public void logSearchStatistics() {
        log.debug("Gathering search statistics");
        
        searchService.getSearchStatistics()
                .doOnSuccess(stats -> {
                    String hitRatePercent = String.format("%.2f", stats.getCacheHitRate() * 100);
                    String avgSearchTime = String.format("%.2f", stats.getAverageSearchTime());
                    log.info("Search Statistics - Total: {}, Cache Hits: {}, Cache Misses: {}, " +
                            "Hit Rate: {}%, Avg Search Time: {}ms, Active Configs: {}",
                            stats.getTotalSearches(),
                            stats.getCacheHits(),
                            stats.getCacheMisses(),
                            hitRatePercent,
                            avgSearchTime,
                            stats.getActiveConfigurations());
                })
                .doOnError(error -> log.error("Error gathering search statistics", error))
                .subscribe();
        
        rateLimitService.getRateLimitStatistics()
                .doOnSuccess(stats -> {
                    String avgRequestsPerChat = String.format("%.2f", stats.getAverageRequestsPerChat());
                    log.info("Rate Limit Statistics - Tracked Chats: {}, At Limit: {}, " +
                            "Total Requests This Hour: {}, Avg Requests Per Chat: {}",
                            stats.getTotalChatsTracked(),
                            stats.getChatsAtLimit(),
                            stats.getTotalRequestsThisHour(),
                            avgRequestsPerChat);
                })
                .doOnError(error -> log.error("Error gathering rate limit statistics", error))
                .subscribe();
    }
}
