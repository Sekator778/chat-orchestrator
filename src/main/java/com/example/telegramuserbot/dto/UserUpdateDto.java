package com.example.telegramuserbot.dto;

import com.example.telegramuserbot.domain.CommunicationStyle;
import com.example.telegramuserbot.domain.ResponseLength;

public record UserUpdateDto(
        String preferredName,
        String preferredTitle,
        CommunicationStyle communicationStyle,
        String personalityTraits,
        String relationshipContext,
        String languagePreference,
        ResponseLength responseLength,
        Boolean aiEnabled
) {
    // Validation helper
    public boolean hasUpdates() {
        return preferredName != null || 
               preferredTitle != null || 
               communicationStyle != null ||
               personalityTraits != null ||
               relationshipContext != null || 
               languagePreference != null ||
               responseLength != null ||
               aiEnabled != null;
    }
}