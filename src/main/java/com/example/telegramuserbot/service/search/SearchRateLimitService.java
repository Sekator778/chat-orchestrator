package com.example.telegramuserbot.service.search;

import reactor.core.publisher.Mono;

/**
 * Service interface for managing search rate limits
 */
public interface SearchRateLimitService {
    
    /**
     * Checks if a search request is allowed based on rate limits
     * 
     * @param chatId The chat ID to check
     * @param rateLimit The rate limit per hour for this chat
     * @return Mono containing true if request is allowed, false otherwise
     */
    Mono<Boolean> checkRateLimit(Long chatId, Integer rateLimit);
    
    /**
     * Records a search request for rate limiting
     * 
     * @param chatId The chat ID that performed the search
     * @return Mono completing when the request is recorded
     */
    Mono<Void> recordSearchRequest(Long chatId);
    
    /**
     * Gets the remaining search quota for a chat
     * 
     * @param chatId The chat ID to check
     * @return Mono containing the remaining requests for the current hour
     */
    Mono<Integer> getRemainingQuota(Long chatId);
    
    /**
     * Resets rate limits for all chats (typically called hourly)
     * 
     * @return Mono containing the number of chats reset
     */
    Mono<Integer> resetRateLimits();
    
    /**
     * Gets current rate limit statistics
     * 
     * @return Mono containing rate limit statistics
     */
    Mono<RateLimitStatsDto> getRateLimitStatistics();
    
    /**
     * DTO for rate limit statistics
     */
    class RateLimitStatsDto {
        private int totalChatsTracked;
        private int chatsAtLimit;
        private long totalRequestsThisHour;
        private double averageRequestsPerChat;
        
        public RateLimitStatsDto() {}
        
        public RateLimitStatsDto(int totalChatsTracked, int chatsAtLimit, 
                               long totalRequestsThisHour, double averageRequestsPerChat) {
            this.totalChatsTracked = totalChatsTracked;
            this.chatsAtLimit = chatsAtLimit;
            this.totalRequestsThisHour = totalRequestsThisHour;
            this.averageRequestsPerChat = averageRequestsPerChat;
        }
        
        // Getters and Setters
        public int getTotalChatsTracked() {
            return totalChatsTracked;
        }
        
        public void setTotalChatsTracked(int totalChatsTracked) {
            this.totalChatsTracked = totalChatsTracked;
        }
        
        public int getChatsAtLimit() {
            return chatsAtLimit;
        }
        
        public void setChatsAtLimit(int chatsAtLimit) {
            this.chatsAtLimit = chatsAtLimit;
        }
        
        public long getTotalRequestsThisHour() {
            return totalRequestsThisHour;
        }
        
        public void setTotalRequestsThisHour(long totalRequestsThisHour) {
            this.totalRequestsThisHour = totalRequestsThisHour;
        }
        
        public double getAverageRequestsPerChat() {
            return averageRequestsPerChat;
        }
        
        public void setAverageRequestsPerChat(double averageRequestsPerChat) {
            this.averageRequestsPerChat = averageRequestsPerChat;
        }
    }
}