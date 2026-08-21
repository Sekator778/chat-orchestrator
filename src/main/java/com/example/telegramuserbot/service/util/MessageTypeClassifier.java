package com.example.telegramuserbot.service.util;

import com.example.telegramuserbot.domain.MessageEntity;
import com.example.telegramuserbot.domain.MessageType;
import org.springframework.stereotype.Service;

/**
 * Service for classifying message types based on content and context
 */
@Service
public class MessageTypeClassifier {

    /**
     * Classify a message based on its content and properties
     */
    public MessageType classifyMessage(MessageEntity message) {
        if (message.getContent() == null) {
            return MessageType.USER_MESSAGE;
        }

        String content = message.getContent().trim();
        
        // Check for profile messages
        if (isProfileMessage(content)) {
            return MessageType.PROFILE_MESSAGE;
        }
        
        // Check for command responses
        if (isCommandResponse(content)) {
            return MessageType.COMMAND_RESPONSE;
        }
        
        // Check for sync messages
        if (isSyncMessage(content)) {
            return MessageType.SYNC_MESSAGE;
        }
        
        // Check for system notifications
        if (isSystemNotification(content)) {
            return MessageType.SYSTEM_NOTIFICATION;
        }
        
        // Check for service messages (general)
        if (isServiceMessage(content)) {
            return MessageType.SERVICE_MESSAGE;
        }
        
        // Determine if it's AI response or user message based on isOutgoing
        if (message.isOutgoing()) {
            // Outgoing messages from our bot could be AI responses or service messages
            // If it's not classified as service type above, treat as AI response
            return MessageType.AI_RESPONSE;
        }
        
        // Default to user message for incoming messages
        return MessageType.USER_MESSAGE;
    }
    
    /**
     * Update existing message with classified type
     */
    public void classifyAndSetMessageType(MessageEntity message) {
        MessageType type = classifyMessage(message);
        message.setMessageType(type);
    }
    
    /**
     * Check if content represents a profile message
     */
    private boolean isProfileMessage(String content) {
        return content.contains("👤 ") && (content.contains("профіль") || content.contains("profile")) ||
               content.contains("🆔 telegram id:") || 
               content.contains("📛 ім'я для звернення:") ||
               content.startsWith("👤 Ваш профіль") || 
               content.startsWith("👤 ваш профіль");
    }
    
    /**
     * Check if content represents a command response
     */
    private boolean isCommandResponse(String content) {
        // Check for specific command response patterns
        if (content.contains("✅ Обробка LLM для каналу") && content.contains("успішно увімкнена")) {
            return true;
        }
        
        if (content.startsWith("Відомі канали:") || content.contains("- ID:") && content.contains("| Title:")) {
            return true;
        }
        
        if (content.contains("📚 Автоматично запущено синхронізацію")) {
            return true;
        }
        
        // Generic command response patterns
        if (content.startsWith("✅") || content.startsWith("❌") || content.startsWith("⚠️")) {
            return true;
        }
        
        return false;
    }
    
    /**
     * Check if content represents a sync message
     */
    private boolean isSyncMessage(String content) {
        return content.contains("синхронізацію") || 
               content.contains("sync") ||
               content.contains("Використовуйте /sync_list") ||
               content.contains("прогресу синхронізації");
    }
    
    /**
     * Check if content represents a system notification
     */
    private boolean isSystemNotification(String content) {
        String normalized = content.toLowerCase();

        if (content.startsWith("🚨") || normalized.contains("kafka processing error")) {
            return true;
        }

        return content.contains("🔔") ||
               normalized.contains("система") ||
               normalized.contains("повідомлення системи");
    }
    
    /**
     * Check if content represents a generic service message
     */
    private boolean isServiceMessage(String content) {
        // Check for emoji patterns that indicate service messages
        return content.contains("📊") || 
               content.contains("⚙️") || 
               content.contains("🔧") ||
               content.contains("📋") ||
               content.startsWith("/") && content.contains("команд");
    }
}
