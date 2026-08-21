package com.example.telegramuserbot.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to verify command parsing works correctly
 */
public class CommandParsingTest {

    @Test
    void testCommandSplitting() {
        // Test the command that was failing
        String commandText = "/delete_my_messages 1000000001 24";
        String[] parts = commandText.trim().split("\\s+");
        
        // Should have 3 parts: command, channelId, hours
        assertEquals(3, parts.length);
        assertEquals("/delete_my_messages", parts[0]);
        assertEquals("1000000001", parts[1]);
        assertEquals("24", parts[2]);
        
        // Test validation
        assertDoesNotThrow(() -> {
            CommandParsingUtils.validateArgumentCount(parts, 3, "Test usage");
        });
        
        // Test parsing
        long channelId = CommandParsingUtils.parseLong(parts[1], "Invalid channelId");
        int hours = CommandParsingUtils.parseInteger(parts[2], "Invalid hours");
        
        assertEquals(1000000001L, channelId);
        assertEquals(24, hours);
    }
    
    @Test
    void testCommandSplittingWithNegativeChannelId() {
        // Test with negative channel ID (typical for Telegram groups/channels)
        String commandText = "/delete_my_messages -1001234567890 72";
        String[] parts = commandText.trim().split("\\s+");
        
        assertEquals(3, parts.length);
        assertEquals("/delete_my_messages", parts[0]);
        assertEquals("-1001234567890", parts[1]);
        assertEquals("72", parts[2]);
        
        long channelId = CommandParsingUtils.parseLong(parts[1], "Invalid channelId");
        int hours = CommandParsingUtils.parseInteger(parts[2], "Invalid hours");
        
        assertEquals(-1001234567890L, channelId);
        assertEquals(72, hours);
    }
    
    @Test
    void testInsufficientArguments() {
        String commandText = "/delete_my_messages 1000000001";
        String[] parts = commandText.trim().split("\\s+");
        
        assertEquals(2, parts.length);
        
        assertThrows(IllegalArgumentException.class, () -> {
            CommandParsingUtils.validateArgumentCount(parts, 3, "Need channelId and hours");
        });
    }
}