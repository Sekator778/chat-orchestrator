package com.example.telegramuserbot.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DTO containing message context for humanization decisions
 */
public record MessageContextDto(
        Long chatId,
        Long messageId,
        String originalUserMessage,
        List<String> recentMessages,
        LocalDateTime timestamp,
        String conversationTopic,
        boolean isGroupChat,
        int messagePosition, // Position in conversation (1st, 2nd, etc.)
        boolean isFollowUpQuestion,
        String detectedEmotion,
        boolean containsQuestionWords,
        boolean mentionsBotDirectly,
        double suspicionLevel // 0.0-1.0 how suspicious the message seems
) {
    
    /**
     * Create basic context from minimal information
     */
    public static MessageContextDto basic(Long chatId, String userMessage) {
        return new MessageContextDto(
                chatId,
                null,
                userMessage,
                List.of(),
                LocalDateTime.now(),
                null,
                false,
                1,
                false,
                "neutral",
                userMessage.contains("?"),
                false,
                0.0
        );
    }
    
    /**
     * Create context with conversation history
     */
    public static MessageContextDto withHistory(
            Long chatId,
            String userMessage,
            List<String> recentMessages,
            boolean isGroupChat) {
        return new MessageContextDto(
                chatId,
                null,
                userMessage,
                recentMessages,
                LocalDateTime.now(),
                null,
                isGroupChat,
                recentMessages.size() + 1,
                false,
                "neutral",
                userMessage.contains("?"),
                containsBotMention(userMessage),
                calculateSuspicionLevel(userMessage)
        );
    }
    
    /**
     * Create context with full analysis
     */
    public static MessageContextDto full(
            Long chatId,
            Long messageId,
            String userMessage,
            List<String> recentMessages,
            String topic,
            boolean isGroupChat,
            String emotion,
            boolean isFollowUp) {
        return new MessageContextDto(
                chatId,
                messageId,
                userMessage,
                recentMessages,
                LocalDateTime.now(),
                topic,
                isGroupChat,
                recentMessages.size() + 1,
                isFollowUp,
                emotion,
                userMessage.contains("?"),
                containsBotMention(userMessage),
                calculateSuspicionLevel(userMessage)
        );
    }
    
    /**
     * Check if message contains bot detection keywords
     */
    public boolean containsBotDetectionKeywords() {
        String lowerMessage = originalUserMessage.toLowerCase();
        return lowerMessage.contains("are you a bot") ||
               lowerMessage.contains("are you ai") ||
               lowerMessage.contains("are you artificial") ||
               lowerMessage.contains("are you real") ||
               lowerMessage.contains("are you human") ||
               lowerMessage.contains("what are you") ||
               lowerMessage.contains("chatgpt") ||
               lowerMessage.contains("gpt") ||
               lowerMessage.contains("artificial intelligence") ||
               // Ukrainian bot detection phrases
               lowerMessage.contains("ти бот") ||
               lowerMessage.contains("ви бот") ||
               lowerMessage.contains("ты бот") ||
               lowerMessage.contains("вы бот") ||
               lowerMessage.contains("хто ти") ||
               lowerMessage.contains("ті хто") ||
               lowerMessage.contains("що ти") ||
               lowerMessage.contains("кто ты") ||
               lowerMessage.contains("что ты") ||
               lowerMessage.matches(".*\\bбот\\?.*") ||
               lowerMessage.contains("штучний інтелект") ||
               lowerMessage.contains("искусственный интеллект") ||
               lowerMessage.contains("ші") || // AI abbreviation in Ukrainian
               lowerMessage.contains("ai") ||
               (lowerMessage.contains("ти") && lowerMessage.contains("?")) &&
               (lowerMessage.contains("бот") || lowerMessage.contains("програма") || lowerMessage.contains("робот"));
    }
    
    /**
     * Check if this is likely a test or probing message
     */
    public boolean isLikelyProbing() {
        return containsBotDetectionKeywords() || 
               suspicionLevel > 0.7 ||
               (containsQuestionWords && mentionsBotDirectly);
    }
    
    /**
     * Get conversation depth (how many messages deep)
     */
    public int getConversationDepth() {
        return messagePosition;
    }
    
    /**
     * Create a copy with elevated suspicion level for anti-detection
     */
    public MessageContextDto withElevatedSuspicion(boolean elevated) {
        if (!elevated) {
            return this;
        }
        
        double newSuspicionLevel = Math.min(1.0, this.suspicionLevel + 0.5);
        return new MessageContextDto(
                this.chatId,
                this.messageId,
                this.originalUserMessage,
                this.recentMessages,
                this.timestamp,
                this.conversationTopic,
                this.isGroupChat,
                this.messagePosition,
                this.isFollowUpQuestion,
                this.detectedEmotion,
                this.containsQuestionWords,
                this.mentionsBotDirectly,
                newSuspicionLevel
        );
    }
    
    /**
     * Check if user mentioned bot directly
     */
    private static boolean containsBotMention(String message) {
        String lower = message.toLowerCase();
        return lower.contains("@") || // Direct mention
               lower.contains("bot") ||
               lower.contains("you") ||
               lower.contains("ти") ||  // Ukrainian "you"
               lower.contains("ви");    // Ukrainian formal "you"
    }
    
    /**
     * Calculate suspicion level based on message content
     */
    private static double calculateSuspicionLevel(String message) {
        String lower = message.toLowerCase();
        double suspicion = 0.0;
        
        // Bot detection keywords increase suspicion
        if (lower.contains("bot") || lower.contains("бот") || lower.contains("робот")) suspicion += 0.3;
        if (lower.contains("ai") || lower.contains("artificial") || lower.contains("штучний") || lower.contains("ші")) suspicion += 0.4;
        if (lower.contains("real") || lower.contains("human") || lower.contains("людина") || lower.contains("справжній")) suspicion += 0.2;
        if (lower.contains("test") || lower.contains("тест") || lower.contains("перевірка")) suspicion += 0.2;
        if (lower.contains("prove") || lower.contains("доведи") || lower.contains("докажи")) suspicion += 0.3;
        
        // Question patterns
        if (lower.contains("are you") || lower.contains("ти є") || lower.contains("ти")) suspicion += 0.3;
        if (lower.contains("what are") || lower.contains("що ти") || lower.contains("хто ти")) suspicion += 0.2;
        
        // Direct bot questions
        if (lower.matches(".*\\bбот\\?.*") || lower.equals("ти бот?")) suspicion += 0.8;
        
        return Math.min(1.0, suspicion);
    }
}