package com.example.telegramuserbot.service.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Implementation of SearchRateLimitService using in-memory storage
 * For production, consider using Redis or database-backed storage
 */
@Service
public class SearchRateLimitServiceImpl implements SearchRateLimitService {
    
    private static final Logger log = LoggerFactory.getLogger(SearchRateLimitServiceImpl.class);
    
    // In-memory rate limit tracking
    // Key: chatId, Value: RateLimitInfo
    private final ConcurrentHashMap<Long, RateLimitInfo> rateLimitData = new ConcurrentHashMap<>();
    
    @Override
    public Mono<Boolean> checkRateLimit(Long chatId, Integer rateLimit) {
        return Mono.fromCallable(() -> {
            if (chatId == null || rateLimit == null || rateLimit <= 0) {
                return true; // No rate limiting
            }
            
            RateLimitInfo info = rateLimitData.computeIfAbsent(chatId, k -> new RateLimitInfo());
            
            Instant now = Instant.now();
            Instant currentHour = now.truncatedTo(ChronoUnit.HOURS);
            
            // Reset counter if we're in a new hour
            if (!currentHour.equals(info.currentHour)) {
                info.currentHour = currentHour;
                info.requestCount.set(0);
                log.debug("Rate limit reset for chat {} at hour {}", chatId, currentHour);
            }
            
            int currentCount = info.requestCount.get();
            boolean allowed = currentCount < rateLimit;
            
            if (!allowed) {
                log.debug("Rate limit exceeded for chat {}: {}/{} requests", chatId, currentCount, rateLimit);
            }
            
            return allowed;
        })
        .subscribeOn(Schedulers.boundedElastic());
    }
    
    @Override
    public Mono<Void> recordSearchRequest(Long chatId) {
        return Mono.fromRunnable(() -> {
            if (chatId == null) {
                return;
            }
            
            RateLimitInfo info = rateLimitData.computeIfAbsent(chatId, k -> new RateLimitInfo());
            
            Instant now = Instant.now();
            Instant currentHour = now.truncatedTo(ChronoUnit.HOURS);
            
            // Reset counter if we're in a new hour
            if (!currentHour.equals(info.currentHour)) {
                info.currentHour = currentHour;
                info.requestCount.set(0);
            }
            
            int newCount = info.requestCount.incrementAndGet();
            log.debug("Recorded search request for chat {}: {} requests this hour", chatId, newCount);
        })
        .subscribeOn(Schedulers.boundedElastic())
        .then();
    }
    
    @Override
    public Mono<Integer> getRemainingQuota(Long chatId) {
        return Mono.fromCallable(() -> {
            if (chatId == null) {
                return Integer.MAX_VALUE; // No limit
            }
            
            RateLimitInfo info = rateLimitData.get(chatId);
            if (info == null) {
                return Integer.MAX_VALUE; // No requests recorded yet
            }
            
            Instant now = Instant.now();
            Instant currentHour = now.truncatedTo(ChronoUnit.HOURS);
            
            // If we're in a new hour, reset counter
            if (!currentHour.equals(info.currentHour)) {
                return Integer.MAX_VALUE; // New hour, full quota available
            }
            
            // For simplicity, assume default rate limit of 30 per hour
            // In practice, this should be retrieved from configuration
            int defaultRateLimit = 30;
            return Math.max(0, defaultRateLimit - info.requestCount.get());
        })
        .subscribeOn(Schedulers.boundedElastic());
    }
    
    @Override
    public Mono<Integer> resetRateLimits() {
        return Mono.fromCallable(() -> {
            log.info("Performing rate limit reset for all chats");
            
            Instant currentHour = Instant.now().truncatedTo(ChronoUnit.HOURS);
            int resetCount = 0;
            
            for (RateLimitInfo info : rateLimitData.values()) {
                if (!currentHour.equals(info.currentHour)) {
                    info.currentHour = currentHour;
                    info.requestCount.set(0);
                    resetCount++;
                }
            }
            
            log.info("Reset rate limits for {} chats", resetCount);
            return resetCount;
        })
        .subscribeOn(Schedulers.boundedElastic());
    }
    
    @Override
    public Mono<RateLimitStatsDto> getRateLimitStatistics() {
        return Mono.fromCallable(() -> {
            Instant currentHour = Instant.now().truncatedTo(ChronoUnit.HOURS);
            
            int totalChatsTracked = rateLimitData.size();
            int chatsAtLimit = 0;
            long totalRequestsThisHour = 0;
            int activeChatsThisHour = 0;
            
            // Assume default rate limit for calculation
            int defaultRateLimit = 30;
            
            for (RateLimitInfo info : rateLimitData.values()) {
                if (currentHour.equals(info.currentHour)) {
                    int requests = info.requestCount.get();
                    totalRequestsThisHour += requests;
                    activeChatsThisHour++;
                    
                    if (requests >= defaultRateLimit) {
                        chatsAtLimit++;
                    }
                }
            }
            
            double averageRequestsPerChat = activeChatsThisHour > 0 ? 
                    (double) totalRequestsThisHour / activeChatsThisHour : 0.0;
            
            return new RateLimitStatsDto(totalChatsTracked, chatsAtLimit, 
                    totalRequestsThisHour, averageRequestsPerChat);
        })
        .subscribeOn(Schedulers.boundedElastic());
    }
    
    /**
     * Internal class to track rate limit information for each chat
     */
    private static class RateLimitInfo {
        private volatile Instant currentHour;
        private final AtomicInteger requestCount;
        
        public RateLimitInfo() {
            this.currentHour = Instant.now().truncatedTo(ChronoUnit.HOURS);
            this.requestCount = new AtomicInteger(0);
        }
    }
    
    /**
     * Cleanup method to remove old rate limit data
     * Should be called periodically to prevent memory leaks
     */
    public void cleanupOldData() {
        Instant cutoff = Instant.now().minus(24, ChronoUnit.HOURS);
        
        rateLimitData.entrySet().removeIf(entry -> {
            RateLimitInfo info = entry.getValue();
            return info.currentHour.isBefore(cutoff);
        });
        
        log.debug("Cleaned up old rate limit data. Current entries: {}", rateLimitData.size());
    }
}