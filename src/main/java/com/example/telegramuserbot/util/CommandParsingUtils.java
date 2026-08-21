package com.example.telegramuserbot.util;

public final class CommandParsingUtils {
    
    private CommandParsingUtils() {
        // Utility class - prevent instantiation
    }
    
    public static long parseChannelId(String[] parts, String commandName) {
        if (parts.length < 2) {
            throw new IllegalArgumentException("Потрібно вказати <channelId>. Приклад: " + commandName + " -1001234567890");
        }
        // Using original TDLib channel ID directly - no normalization needed
        return parseLong(parts[1], "Невірний формат channelId.");
    }
    
    public static long parseLong(String value, String errorMessage) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(errorMessage + " Значення: '" + value + "'");
        }
    }
    
    public static Integer parseInteger(String value, String errorMessage) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(errorMessage + " Значення: '" + value + "'");
        }
    }
    
    public static void validateArgumentCount(String[] parts, int expectedMin, String usage) {
        if (parts.length < expectedMin) {
            throw new IllegalArgumentException(usage);
        }
    }
    
    public static void validateArgumentCount(String[] parts, int expectedMin, int expectedMax, String usage) {
        if (parts.length < expectedMin || parts.length > expectedMax) {
            throw new IllegalArgumentException(usage);
        }
    }

    public static boolean parseToggleValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Очікується значення 'on' або 'off'.");
        }
        return switch (value.trim().toLowerCase()) {
            case "on", "true", "1", "yes", "enable", "enabled" -> true;
            case "off", "false", "0", "no", "disable", "disabled" -> false;
            default -> throw new IllegalArgumentException("Невірний формат: очікується 'on' або 'off', отримано '" + value + "'");
        };
    }

    public static Double parseDouble(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Невірний формат числа: '" + value + "'");
        }
    }
}
