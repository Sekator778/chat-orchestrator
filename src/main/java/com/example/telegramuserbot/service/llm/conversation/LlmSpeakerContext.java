package com.example.telegramuserbot.service.llm.conversation;

import java.util.List;

public record LlmSpeakerContext(
        String botInstanceId,
        Long selfTelegramUserId,
        List<Participant> participants
) {
    public record Participant(
            String label,
            Long senderId,
            String username,
            String firstName,
            String lastName,
            String name
    ) { }
}

