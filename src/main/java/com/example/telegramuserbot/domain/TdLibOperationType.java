package com.example.telegramuserbot.domain;

/**
 * Types of TDLib operations that require coordination and locking.
 * These operations modify TDLib internal state and must be serialized
 * to prevent state corruption.
 */
public enum TdLibOperationType {

    /**
     * Full chat discovery operation including GetChats and LoadChats
     * for both main and archive chat lists.
     */
    CHAT_DISCOVERY,

    /**
     * LoadChats operation for main chat list.
     * Triggers loading of more chats from Telegram servers.
     */
    LOAD_CHATS_MAIN,

    /**
     * LoadChats operation for archive chat list.
     * Triggers loading of more archived chats from Telegram servers.
     */
    LOAD_CHATS_ARCHIVE,

    /**
     * Message synchronization operation for a specific chat.
     * Loads message history from Telegram servers.
     */
    MESSAGE_SYNC,

    /**
     * Channel synchronization scheduler operation.
     * Periodic refresh of channel list.
     */
    CHANNEL_SYNC_SCHEDULED,

    /**
     * General TDLib health check operation.
     */
    HEALTH_CHECK
}
