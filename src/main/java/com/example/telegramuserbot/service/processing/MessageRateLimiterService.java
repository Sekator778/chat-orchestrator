package com.example.telegramuserbot.service.processing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Service for tracking and enforcing rate limits for message processing
 * Uses in-memory counters for real-time rate limiting
 */
@Service
public class MessageRateLimiterService {
    
    private static final Logger log = LoggerFactory.getLogger(MessageRateLimiterService.class);
    
    // In-memory storage for rate limiting counters
    private final ConcurrentHashMap<String, AtomicInteger> dailyCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> hourlyCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> minuteCounters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> burstCounters = new ConcurrentHashMap<>();
    
    // Timestamp tracking for counter resets
    private final ConcurrentHashMap<String, LocalDateTime> dailyResetTimes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LocalDateTime> hourlyResetTimes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LocalDateTime> minuteResetTimes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LocalDateTime> burstResetTimes = new ConcurrentHashMap<>();
    
    /**
     * Gets current daily usage for a chat
     */
    public int getCurrentDailyUsage(long chatId) {
        String key = "daily_" + chatId;
        resetCounterIfNeeded(key, dailyCounters, dailyResetTimes, 24 * 60); // 24 hours in minutes
        return dailyCounters.getOrDefault(key, new AtomicInteger(0)).get();
    }
    
    /**
     * Gets current hourly usage for a chat
     */
    public int getCurrentHourlyUsage(long chatId) {
        String key = "hourly_" + chatId;
        resetCounterIfNeeded(key, hourlyCounters, hourlyResetTimes, 60); // 60 minutes
        return hourlyCounters.getOrDefault(key, new AtomicInteger(0)).get();
    }
    
    /**
     * Gets current minute usage for a chat
     */
    public int getCurrentMinuteUsage(long chatId) {
        String key = "minute_" + chatId;
        resetCounterIfNeeded(key, minuteCounters, minuteResetTimes, 1); // 1 minute
        return minuteCounters.getOrDefault(key, new AtomicInteger(0)).get();
    }
    
    /**
     * Gets current burst window usage for a chat
     */
    public int getBurstWindowUsage(long chatId, int windowSeconds) {
        String key = "burst_" + chatId + "_" + windowSeconds;
        resetCounterIfNeeded(key, burstCounters, burstResetTimes, windowSeconds / 60.0); // Convert to minutes
        return burstCounters.getOrDefault(key, new AtomicInteger(0)).get();
    }
    
    /**
     * Increments usage counters for a chat after successful message processing
     */
    public void incrementUsageCounters(long chatId, int burstWindowSeconds) {
        LocalDateTime now = LocalDateTime.now();
        
        // Increment daily counter
        String dailyKey = "daily_" + chatId;
        dailyCounters.computeIfAbsent(dailyKey, k -> new AtomicInteger(0)).incrementAndGet();
        dailyResetTimes.putIfAbsent(dailyKey, now);
        
        // Increment hourly counter
        String hourlyKey = "hourly_" + chatId;
        hourlyCounters.computeIfAbsent(hourlyKey, k -> new AtomicInteger(0)).incrementAndGet();
        hourlyResetTimes.putIfAbsent(hourlyKey, now);
        
        // Increment minute counter
        String minuteKey = "minute_" + chatId;
        minuteCounters.computeIfAbsent(minuteKey, k -> new AtomicInteger(0)).incrementAndGet();
        minuteResetTimes.putIfAbsent(minuteKey, now);
        
        // Increment burst counter if configured
        if (burstWindowSeconds > 0) {
            String burstKey = "burst_" + chatId + "_" + burstWindowSeconds;
            burstCounters.computeIfAbsent(burstKey, k -> new AtomicInteger(0)).incrementAndGet();
            burstResetTimes.putIfAbsent(burstKey, now);
        }
        
        log.debug("[Chat {}] Rate limit counters incremented. Daily: {}, Hourly: {}, Minute: {}", 
                chatId, 
                dailyCounters.get(dailyKey).get(),
                hourlyCounters.get(hourlyKey).get(),
                minuteCounters.get(minuteKey).get());
    }
    
    /**
     * Calculates remaining quota based on the most restrictive limit
     */
    public int calculateRemainingQuota(long chatId, Integer maxDaily, Integer maxHourly, 
                                     Integer maxMinute, Integer maxBurst, int burstWindowSeconds) {
        
        int remainingQuota = Integer.MAX_VALUE;
        
        // Check daily limit
        if (maxDaily != null) {
            int dailyUsed = getCurrentDailyUsage(chatId);
            int dailyRemaining = Math.max(0, maxDaily - dailyUsed);
            remainingQuota = Math.min(remainingQuota, dailyRemaining);
        }
        
        // Check hourly limit
        if (maxHourly != null) {
            int hourlyUsed = getCurrentHourlyUsage(chatId);
            int hourlyRemaining = Math.max(0, maxHourly - hourlyUsed);
            remainingQuota = Math.min(remainingQuota, hourlyRemaining);
        }
        
        // Check minute limit
        if (maxMinute != null) {
            int minuteUsed = getCurrentMinuteUsage(chatId);
            int minuteRemaining = Math.max(0, maxMinute - minuteUsed);
            remainingQuota = Math.min(remainingQuota, minuteRemaining);
        }
        
        // Check burst limit
        if (maxBurst != null && burstWindowSeconds > 0) {
            int burstUsed = getBurstWindowUsage(chatId, burstWindowSeconds);
            int burstRemaining = Math.max(0, maxBurst - burstUsed);
            remainingQuota = Math.min(remainingQuota, burstRemaining);
        }
        
        return remainingQuota == Integer.MAX_VALUE ? Integer.MAX_VALUE : remainingQuota;
    }
    
    /**
     * Resets counters that have exceeded their time window
     */
    private void resetCounterIfNeeded(String key, ConcurrentHashMap<String, AtomicInteger> counters,
                                    ConcurrentHashMap<String, LocalDateTime> resetTimes, double windowMinutes) {
        
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime resetTime = resetTimes.get(key);
        
        if (resetTime == null) {
            // No reset time recorded, set it now
            resetTimes.put(key, now);
            return;
        }
        
        // Check if window has expired
        long minutesElapsed = java.time.Duration.between(resetTime, now).toMinutes();
        if (minutesElapsed >= windowMinutes) {
            // Reset counter and update reset time
            AtomicInteger counter = counters.get(key);
            if (counter != null) {
                int oldValue = counter.getAndSet(0);
                if (oldValue > 0) {
                    log.debug("Reset counter {} from {} (window: {} minutes elapsed: {})", 
                            key, oldValue, windowMinutes, minutesElapsed);
                }
            }
            resetTimes.put(key, now);
        }
    }
    
    /**
     * Clears all rate limiting data for a chat (useful for testing or admin operations)
     */
    public void clearRateLimitData(long chatId) {
        String[] prefixes = {"daily_", "hourly_", "minute_", "burst_"};
        
        for (String prefix : prefixes) {
            String keyPattern = prefix + chatId;
            
            // Remove counters
            dailyCounters.entrySet().removeIf(entry -> entry.getKey().startsWith(keyPattern));
            hourlyCounters.entrySet().removeIf(entry -> entry.getKey().startsWith(keyPattern));
            minuteCounters.entrySet().removeIf(entry -> entry.getKey().startsWith(keyPattern));
            burstCounters.entrySet().removeIf(entry -> entry.getKey().startsWith(keyPattern));
            
            // Remove reset times
            dailyResetTimes.entrySet().removeIf(entry -> entry.getKey().startsWith(keyPattern));
            hourlyResetTimes.entrySet().removeIf(entry -> entry.getKey().startsWith(keyPattern));
            minuteResetTimes.entrySet().removeIf(entry -> entry.getKey().startsWith(keyPattern));
            burstResetTimes.entrySet().removeIf(entry -> entry.getKey().startsWith(keyPattern));
        }
        
        log.info("[Chat {}] All rate limiting data cleared", chatId);
    }
    
    /**
     * Gets current rate limiting statistics for a chat
     */
    public RateLimitStats getRateLimitStats(long chatId, int burstWindowSeconds) {
        return new RateLimitStats(
                getCurrentDailyUsage(chatId),
                getCurrentHourlyUsage(chatId),
                getCurrentMinuteUsage(chatId),
                getBurstWindowUsage(chatId, burstWindowSeconds),
                burstWindowSeconds
        );
    }
    
    /**
     * Rate limiting statistics record
     */
    public record RateLimitStats(
            int dailyUsage,
            int hourlyUsage,
            int minuteUsage,
            int burstUsage,
            int burstWindowSeconds
    ) {
        @Override
        public String toString() {
            return String.format("RateLimitStats{daily=%d, hourly=%d, minute=%d, burst=%d/%ds}", 
                    dailyUsage, hourlyUsage, minuteUsage, burstUsage, burstWindowSeconds);
        }
    }
}