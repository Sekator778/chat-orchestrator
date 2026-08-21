package com.example.telegramuserbot.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Request DTO for a quick scan of a chat's message history by Telegram chat ID.
 * Unlike the full sync, this bypasses sync-enabled configuration checks.
 * Trivial messages (3 words or fewer) are automatically filtered out.
 *
 * <p>If {@code syncDepthDays} is null the scan fetches the channel's full history
 * back to the very first message (no date filter applied).</p>
 */
public record QuickScanRequestDto(

        @NotNull(message = "Chat ID is required")
        Long chatId,

        @Min(value = 1, message = "Sync depth must be at least 1 day")
        Integer syncDepthDays
) {}
