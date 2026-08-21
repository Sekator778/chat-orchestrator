package com.example.telegramuserbot.service.llm.conversation;

import com.example.telegramuserbot.domain.MediaKind;
import com.example.telegramuserbot.domain.MessageEntity;
import com.example.telegramuserbot.service.llm.dto.ApiMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Service that formats conversation messages for LLM API calls.
 *
 * Converts MessageEntity sequences into normalized ApiMessage lists suitable for
 * LLM consumption, handling speaker identification, role assignment, and message merging.
 */
@Service
public final class LlmConversationFormatter implements ConversationFormatter {

    public static final String SELF_LABEL = "ME";

    // A self (assistant) turn this short or shorter is a one-word filler ("Ок.", "Да", "👍").
    // Feeding the persona's OWN fillers back into context makes the LLM echo them, poisoning
    // every later reply. Human short turns are NOT filtered — they are real context to answer.
    private static final int SELF_FILLER_MAX_CHARS = 4;

    @Override
    public FormatResult format(List<MessageEntity> contextMessages,
                               MessageEntity triggeringMessage,
                               String botInstanceId,
                               Long selfTelegramUserId) {
        return format(contextMessages, triggeringMessage, botInstanceId, selfTelegramUserId, false);
    }

    @Override
    public FormatResult format(List<MessageEntity> contextMessages,
                               MessageEntity triggeringMessage,
                               String botInstanceId,
                               Long selfTelegramUserId,
                               boolean includeMediaPlaceholders) {
        List<MessageEntity> sequence = new ArrayList<>();
        if (contextMessages != null) {
            sequence.addAll(contextMessages);
        }
        if (triggeringMessage != null) {
            sequence.add(triggeringMessage);
        }
        LinkedHashMap<Long, Integer> participantIndexBySenderId = new LinkedHashMap<>();
        List<LlmSpeakerContext.Participant> participants = new ArrayList<>();
        List<ApiMessage> raw = new ArrayList<>();
        for (MessageEntity message : sequence) {
            if (message == null) {
                continue;
            }
            if (shouldFilterServiceMessage(message)) {
                continue;
            }
            // Media-only posts (e.g. a chart photo) carry their text in caption, not content.
            // Fall back to caption so a captioned forward becomes a real user turn instead of
            // being dropped as blank — which previously starved the LLM into a "Ок." filler.
            String textContent = message.getContent() != null && !message.getContent().isBlank()
                    ? message.getContent()
                    : (message.getCaption() != null ? message.getCaption() : "");
            String mediaPlaceholder = includeMediaPlaceholders ? generateMediaPlaceholder(message) : "";
            String combinedContent = combineContentAndPlaceholder(textContent, mediaPlaceholder);
            if (combinedContent.isBlank()) {
                continue;
            }
            Speaker speaker = resolveSpeaker(message, selfTelegramUserId, participantIndexBySenderId, participants);
            String role = speaker.isSelf() ? "assistant" : "user";
            // Drop the persona's OWN degenerate one-word fillers from context so the LLM can't
            // echo them into another "Ок.". Only self turns — human short turns stay.
            if (speaker.isSelf() && combinedContent.strip().length() <= SELF_FILLER_MAX_CHARS) {
                continue;
            }
            String content = formatContentWithText(combinedContent, speaker, message);
            raw.add(new ApiMessage(role, content));
        }
        List<ApiMessage> normalized = dropLeadingAssistant(raw);
        List<ApiMessage> merged = mergeConsecutiveRoles(normalized);
        LlmSpeakerContext speakerContext = new LlmSpeakerContext(
                botInstanceId,
                selfTelegramUserId,
                List.copyOf(participants)
        );
        return new FormatResult(List.copyOf(merged), speakerContext);
    }

    private boolean shouldFilterServiceMessage(MessageEntity message) {
        if (message.getMessageType() == null) {
            return false;
        }
        return !message.getMessageType().isIncludedInLlmConversation();
    }

    private String generateMediaPlaceholder(MessageEntity message) {
        if (message.getMediaType() == null || message.getMediaType() == MediaKind.UNKNOWN) {
            return "";
        }
        return switch (message.getMediaType()) {
            case PHOTO -> "[Фото]";
            case VIDEO -> "[Відео]";
            case VOICE -> "[Голосове повідомлення]";
            case AUDIO -> "[Аудіо]";
            case DOCUMENT -> "[Документ]";
            case STICKER -> "[Стікер]";
            case ANIMATION -> "[Анімація]";
            case VIDEO_NOTE -> "[Відео-повідомлення]";
            default -> "";
        };
    }

    private String combineContentAndPlaceholder(String text, String placeholder) {
        boolean hasText = text != null && !text.isBlank();
        boolean hasPlaceholder = placeholder != null && !placeholder.isBlank();
        if (hasText && hasPlaceholder) {
            return text + "\n" + placeholder;
        } else if (hasText) {
            return text;
        } else if (hasPlaceholder) {
            return placeholder;
        }
        return "";
    }

    private String formatContentWithText(String combinedContent, Speaker speaker, MessageEntity message) {
        String hint = speakerHint(message);
        String prefix = speaker.label();
        if (hint != null && !hint.isBlank()) {
            prefix = prefix + " (" + hint + ")";
        }
        return prefix + ": " + combinedContent.strip();
    }

    private List<ApiMessage> dropLeadingAssistant(List<ApiMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        int idx = 0;
        while (idx < messages.size() && "assistant".equals(messages.get(idx).role())) {
            idx++;
        }
        return idx == 0 ? messages : new ArrayList<>(messages.subList(idx, messages.size()));
    }

    private List<ApiMessage> mergeConsecutiveRoles(List<ApiMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<ApiMessage> merged = new ArrayList<>();
        ApiMessage last = null;
        for (ApiMessage msg : messages) {
            if (msg == null || msg.content() == null || msg.content().isBlank()) {
                continue;
            }
            if (last == null || !Objects.equals(last.role(), msg.role())) {
                merged.add(msg);
                last = msg;
                continue;
            }
            ApiMessage combined = new ApiMessage(last.role(), last.content() + "\n" + msg.content());
            merged.set(merged.size() - 1, combined);
            last = combined;
        }
        return merged;
    }

    private Speaker resolveSpeaker(MessageEntity message,
                                   Long selfTelegramUserId,
                                   LinkedHashMap<Long, Integer> participantIndexBySenderId,
                                   List<LlmSpeakerContext.Participant> participantsOut) {
        boolean outgoing = message.isOutgoing();
        Long senderId = message.getSenderId();
        boolean isSelf = false;
        if (selfTelegramUserId != null && selfTelegramUserId != 0) {
            if (senderId != null) {
                isSelf = selfTelegramUserId.equals(senderId);
            } else {
                isSelf = outgoing;
            }
        } else {
            isSelf = outgoing;
        }
        if (isSelf) {
            return new Speaker(true, SELF_LABEL, senderId);
        }
        if (senderId == null) {
            return new Speaker(false, "UNKNOWN", null);
        }
        Integer idx = participantIndexBySenderId.get(senderId);
        if (idx == null) {
            idx = participantIndexBySenderId.size() + 1;
            participantIndexBySenderId.put(senderId, idx);
            participantsOut.add(new LlmSpeakerContext.Participant(
                    "P" + idx,
                    senderId,
                    safeString(message.getSenderUsername()),
                    safeString(message.getSenderFirstName()),
                    safeString(message.getSenderLastName()),
                    safeString(message.getSenderName())
            ));
        }
        return new Speaker(false, "P" + idx, senderId);
    }

    private String speakerHint(MessageEntity message) {
        String username = safeString(message.getSenderUsername());
        if (username != null && !username.isBlank()) {
            return username.startsWith("@") ? username : "@" + username;
        }
        String first = safeString(message.getSenderFirstName());
        String last = safeString(message.getSenderLastName());
        String full = (first == null ? "" : first.strip()) + (last == null || last.isBlank() ? "" : " " + last.strip());
        if (!full.isBlank()) {
            return full;
        }
        String name = safeString(message.getSenderName());
        if (name != null && !name.isBlank()) {
            return name;
        }
        return null;
    }

    private String safeString(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private record Speaker(boolean isSelf, String label, Long senderId) { }
}
