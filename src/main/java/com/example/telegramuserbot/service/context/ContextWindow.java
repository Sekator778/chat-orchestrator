package com.example.telegramuserbot.service.context;

import com.example.telegramuserbot.domain.MessageEntity;

import java.util.List;

public record ContextWindow(List<MessageEntity> contextMessages, MessageEntity triggeringMessage) {
    public static ContextWindow empty() {
        return new ContextWindow(List.of(), null);
    }
}
