package com.example.telegramuserbot.service.humanization;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for monitoring anti-detection system effectiveness and providing metrics.
 */
@Service
public class AntiDetectionMonitoringService {
    
    private static final Logger log = LoggerFactory.getLogger(AntiDetectionMonitoringService.class);
    
    // Metrics tracking
    private final AtomicLong totalMessagesProcessed = new AtomicLong(0);
    private final AtomicLong aiPatternsDetected = new AtomicLong(0);
    private final AtomicLong emergencyMeasuresApplied = new AtomicLong(0);
    private final AtomicLong botDetectionQuestions = new AtomicLong(0);
    private final AtomicLong successfulDeflections = new AtomicLong(0);
    
    // Per-user tracking
    private final Map<Long, UserAntiDetectionMetrics> userMetrics = new ConcurrentHashMap<>();
    
    // System health tracking
    private final Map<String, Long> patternDetectionCounts = new ConcurrentHashMap<>();
    private final Map<String, Long> responseVariationUsage = new ConcurrentHashMap<>();
    
    /**
     * Record a message being processed
     */
    public void recordMessageProcessed(Long userId, String messageType) {
        totalMessagesProcessed.incrementAndGet();
        
        UserAntiDetectionMetrics metrics = userMetrics.computeIfAbsent(userId, 
                k -> new UserAntiDetectionMetrics());
        metrics.recordMessageProcessed(messageType);
        
        log.debug("Recorded message processed for user {}: {}", userId, messageType);
    }
    
    /**
     * Record AI pattern detection
     */
    public void recordAiPatternDetected(Long userId, String pattern, String originalResponse) {
        aiPatternsDetected.incrementAndGet();
        patternDetectionCounts.merge(pattern, 1L, Long::sum);
        
        UserAntiDetectionMetrics metrics = userMetrics.computeIfAbsent(userId, 
                k -> new UserAntiDetectionMetrics());
        metrics.recordAiPatternDetected(pattern);
        
        log.warn("AI pattern detected for user {}: {} in response: {}", 
                userId, pattern, originalResponse.substring(0, Math.min(100, originalResponse.length())));
    }
    
    /**
     * Record emergency measures application
     */
    public void recordEmergencyMeasuresApplied(Long userId, String reason) {
        emergencyMeasuresApplied.incrementAndGet();
        
        UserAntiDetectionMetrics metrics = userMetrics.computeIfAbsent(userId, 
                k -> new UserAntiDetectionMetrics());
        metrics.recordEmergencyMeasuresApplied(reason);
        
        log.warn("Emergency measures applied for user {}: {}", userId, reason);
    }
    
    /**
     * Record bot detection question
     */
    public void recordBotDetectionQuestion(Long userId, String question) {
        botDetectionQuestions.incrementAndGet();
        
        UserAntiDetectionMetrics metrics = userMetrics.computeIfAbsent(userId, 
                k -> new UserAntiDetectionMetrics());
        metrics.recordBotDetectionQuestion(question);
        
        log.info("Bot detection question from user {}: {}", userId, question);
    }
    
    /**
     * Record successful deflection
     */
    public void recordSuccessfulDeflection(Long userId, String deflectionType) {
        successfulDeflections.incrementAndGet();
        
        UserAntiDetectionMetrics metrics = userMetrics.computeIfAbsent(userId, 
                k -> new UserAntiDetectionMetrics());
        metrics.recordSuccessfulDeflection(deflectionType);
        
        log.info("Successful deflection for user {}: {}", userId, deflectionType);
    }
    
    /**
     * Record response variation usage
     */
    public void recordResponseVariationUsage(String intentType, String variationId) {
        String key = intentType + ":" + variationId;
        responseVariationUsage.merge(key, 1L, Long::sum);
    }
    
    /**
     * Get overall system metrics
     */
    public SystemMetrics getSystemMetrics() {
        long total = totalMessagesProcessed.get();
        long aiDetected = aiPatternsDetected.get();
        long emergency = emergencyMeasuresApplied.get();
        long botQuestions = botDetectionQuestions.get();
        long deflections = successfulDeflections.get();
        
        double aiDetectionRate = total > 0 ? (double) aiDetected / total : 0.0;
        double emergencyRate = total > 0 ? (double) emergency / total : 0.0;
        double deflectionSuccessRate = botQuestions > 0 ? (double) deflections / botQuestions : 0.0;
        
        return new SystemMetrics(
                total,
                aiDetected,
                emergency,
                botQuestions,
                deflections,
                aiDetectionRate,
                emergencyRate,
                deflectionSuccessRate,
                LocalDateTime.now()
        );
    }
    
    /**
     * Get user-specific metrics
     */
    public UserAntiDetectionMetrics getUserMetrics(Long userId) {
        return userMetrics.getOrDefault(userId, new UserAntiDetectionMetrics());
    }
    
    /**
     * Get pattern detection statistics
     */
    public Map<String, Long> getPatternDetectionStats() {
        return new ConcurrentHashMap<>(patternDetectionCounts);
    }
    
    /**
     * Get response variation usage statistics
     */
    public Map<String, Long> getResponseVariationStats() {
        return new ConcurrentHashMap<>(responseVariationUsage);
    }
    
    /**
     * Check if system is healthy
     */
    public boolean isSystemHealthy() {
        SystemMetrics metrics = getSystemMetrics();
        
        // System is healthy if:
        // 1. AI detection rate is reasonable (not too high, not too low)
        // 2. Emergency measures are not applied too frequently
        // 3. Deflection success rate is high
        
        boolean healthy = true;
        
        if (metrics.aiDetectionRate() > 0.3) {
            log.warn("High AI detection rate: {}%", metrics.aiDetectionRate() * 100);
            healthy = false;
        }
        
        if (metrics.emergencyRate() > 0.1) {
            log.warn("High emergency measures rate: {}%", metrics.emergencyRate() * 100);
            healthy = false;
        }
        
        if (metrics.deflectionSuccessRate() < 0.8) {
            log.warn("Low deflection success rate: {}%", metrics.deflectionSuccessRate() * 100);
            healthy = false;
        }
        
        return healthy;
    }
    
    /**
     * Reset metrics (for testing or maintenance)
     */
    public void resetMetrics() {
        totalMessagesProcessed.set(0);
        aiPatternsDetected.set(0);
        emergencyMeasuresApplied.set(0);
        botDetectionQuestions.set(0);
        successfulDeflections.set(0);
        userMetrics.clear();
        patternDetectionCounts.clear();
        responseVariationUsage.clear();
        
        log.info("Anti-detection metrics reset");
    }

    /**
     * Reset all metrics (alias for resetMetrics)
     */
    public void resetAllMetrics() {
        resetMetrics();
    }

    /**
     * Get system health status with detailed information
     */
    public SystemHealth getSystemHealth() {
        SystemMetrics metrics = getSystemMetrics();
        boolean isHealthy = isSystemHealthy();
        
        List<String> issues = new ArrayList<>();
        if (metrics.aiDetectionRate() > 0.3) {
            issues.add("High AI detection rate: " + String.format("%.1f%%", metrics.aiDetectionRate() * 100));
        }
        if (metrics.emergencyRate() > 0.1) {
            issues.add("High emergency measures rate: " + String.format("%.1f%%", metrics.emergencyRate() * 100));
        }
        if (metrics.deflectionSuccessRate() < 0.8) {
            issues.add("Low deflection success rate: " + String.format("%.1f%%", metrics.deflectionSuccessRate() * 100));
        }
        
        return new SystemHealth(
                isHealthy ? "HEALTHY" : "WARNING",
                metrics.aiDetectionRate(),
                metrics.deflectionSuccessRate(),
                calculateAverageSuspicionLevel(),
                calculateActiveThreats(),
                issues.isEmpty() ? null : String.join("; ", issues),
                LocalDateTime.now()
        );
    }

    /**
     * Calculate average suspicion level across all users
     */
    private double calculateAverageSuspicionLevel() {
        if (userMetrics.isEmpty()) {
            return 0.0;
        }
        
        double totalSuspicion = userMetrics.values().stream()
                .mapToDouble(UserAntiDetectionMetrics::getAiDetectionRate)
                .sum();
        
        return totalSuspicion / userMetrics.size();
    }

    /**
     * Calculate number of active threats (users with high suspicion)
     */
    private int calculateActiveThreats() {
        return (int) userMetrics.values().stream()
                .filter(metrics -> metrics.getAiDetectionRate() > 0.5)
                .count();
    }

    /**
     * System health record
     */
    public record SystemHealth(
            String status,
            double detectionEffectiveness,
            double deflectionEffectiveness,
            double averageSuspicionLevel,
            int activeThreats,
            String recommendations,
            LocalDateTime lastUpdated
    ) {}
    
    /**
     * System metrics record
     */
    public record SystemMetrics(
            long totalMessagesProcessed,
            long aiPatternsDetected,
            long emergencyMeasuresApplied,
            long botDetectionQuestions,
            long successfulDeflections,
            double aiDetectionRate,
            double emergencyRate,
            double deflectionSuccessRate,
            LocalDateTime timestamp
    ) {}
    
    /**
     * User-specific anti-detection metrics
     */
    public static class UserAntiDetectionMetrics {
        private final AtomicLong messagesProcessed = new AtomicLong(0);
        private final AtomicLong aiPatternsDetected = new AtomicLong(0);
        private final AtomicLong emergencyMeasuresApplied = new AtomicLong(0);
        private final AtomicLong botDetectionQuestions = new AtomicLong(0);
        private final AtomicLong successfulDeflections = new AtomicLong(0);
        private final Map<String, Long> messageTypes = new ConcurrentHashMap<>();
        private final Map<String, Long> detectedPatterns = new ConcurrentHashMap<>();
        private final Map<String, Long> emergencyReasons = new ConcurrentHashMap<>();
        private final Map<String, Long> deflectionTypes = new ConcurrentHashMap<>();
        private LocalDateTime lastActivity = LocalDateTime.now();
        
        public void recordMessageProcessed(String messageType) {
            messagesProcessed.incrementAndGet();
            messageTypes.merge(messageType, 1L, Long::sum);
            lastActivity = LocalDateTime.now();
        }
        
        public void recordAiPatternDetected(String pattern) {
            aiPatternsDetected.incrementAndGet();
            detectedPatterns.merge(pattern, 1L, Long::sum);
            lastActivity = LocalDateTime.now();
        }
        
        public void recordEmergencyMeasuresApplied(String reason) {
            emergencyMeasuresApplied.incrementAndGet();
            emergencyReasons.merge(reason, 1L, Long::sum);
            lastActivity = LocalDateTime.now();
        }
        
        public void recordBotDetectionQuestion(String question) {
            botDetectionQuestions.incrementAndGet();
            lastActivity = LocalDateTime.now();
        }
        
        public void recordSuccessfulDeflection(String deflectionType) {
            successfulDeflections.incrementAndGet();
            deflectionTypes.merge(deflectionType, 1L, Long::sum);
            lastActivity = LocalDateTime.now();
        }
        
        // Getters
        public long getMessagesProcessed() { return messagesProcessed.get(); }
        public long getAiPatternsDetected() { return aiPatternsDetected.get(); }
        public long getEmergencyMeasuresApplied() { return emergencyMeasuresApplied.get(); }
        public long getBotDetectionQuestions() { return botDetectionQuestions.get(); }
        public long getSuccessfulDeflections() { return successfulDeflections.get(); }
        public Map<String, Long> getMessageTypes() { return new ConcurrentHashMap<>(messageTypes); }
        public Map<String, Long> getDetectedPatterns() { return new ConcurrentHashMap<>(detectedPatterns); }
        public Map<String, Long> getEmergencyReasons() { return new ConcurrentHashMap<>(emergencyReasons); }
        public Map<String, Long> getDeflectionTypes() { return new ConcurrentHashMap<>(deflectionTypes); }
        public LocalDateTime getLastActivity() { return lastActivity; }
        
        public double getAiDetectionRate() {
            long total = messagesProcessed.get();
            return total > 0 ? (double) aiPatternsDetected.get() / total : 0.0;
        }
        
        public double getDeflectionSuccessRate() {
            long questions = botDetectionQuestions.get();
            return questions > 0 ? (double) successfulDeflections.get() / questions : 0.0;
        }

        public double getCurrentSuspicionLevel() {
            return getAiDetectionRate();
        }

        public double getDetectionRate() {
            return getAiDetectionRate();
        }

        public double getDeflectionRate() {
            return getDeflectionSuccessRate();
        }

        public LocalDateTime getLastMessageTime() {
            return lastActivity;
        }

        public LocalDateTime getLastThreatTime() {
            // For simplicity, return last activity time
            // In a real implementation, you might track threat times separately
            return lastActivity;
        }

        public long getMessageCount() {
            return messagesProcessed.get();
        }
    }
} 