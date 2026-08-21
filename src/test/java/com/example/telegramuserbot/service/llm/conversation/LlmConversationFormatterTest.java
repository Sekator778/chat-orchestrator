package com.example.telegramuserbot.service.llm.conversation;

import com.example.telegramuserbot.domain.MediaKind;
import com.example.telegramuserbot.domain.MessageEntity;
import com.example.telegramuserbot.domain.MessageType;
import com.example.telegramuserbot.service.llm.dto.ApiMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for LlmConversationFormatter service.
 */
class LlmConversationFormatterTest {

    private LlmConversationFormatter formatter;

    @BeforeEach
    void setUp() {
        formatter = new LlmConversationFormatter();
    }

    @Test
    void shouldReturnEmptyResultWhenNoMessagesProvided() {
        ConversationFormatter.FormatResult result = formatter.format(null, null, "bot-1", 123L);
        assertThat(result.messages()).isEmpty();
        assertThat(result.speakerContext().participants()).isEmpty();
    }

    @Test
    void shouldReturnEmptyResultWhenEmptyContextAndNullTriggering() {
        ConversationFormatter.FormatResult result = formatter.format(List.of(), null, "bot-1", 123L);
        assertThat(result.messages()).isEmpty();
    }

    @Test
    void shouldFormatSingleTriggeringMessageFromOtherUser() {
        MessageEntity trigger = createMessage(1L, 456L, "Hello there", false, "john_doe", "John", "Doe");
        ConversationFormatter.FormatResult result = formatter.format(null, trigger, "bot-1", 123L);
        assertThat(result.messages()).hasSize(1);
        ApiMessage msg = result.messages().get(0);
        assertThat(msg.role()).isEqualTo("user");
        assertThat(msg.content()).contains("P1");
        assertThat(msg.content()).contains("Hello there");
        assertThat(result.speakerContext().participants()).hasSize(1);
        assertThat(result.speakerContext().participants().get(0).label()).isEqualTo("P1");
        assertThat(result.speakerContext().participants().get(0).senderId()).isEqualTo(456L);
    }

    @Test
    void shouldFormatOwnMessageAsAssistantRoleWhenNotLeading() {
        MessageEntity otherMsg = createMessage(1L, 456L, "User question", false, "jane", null, null);
        MessageEntity ownMsg = createMessage(2L, 123L, "My response", true, null, null, null);
        ConversationFormatter.FormatResult result = formatter.format(List.of(otherMsg), ownMsg, "bot-1", 123L);
        assertThat(result.messages()).hasSize(2);
        assertThat(result.messages().get(0).role()).isEqualTo("user");
        assertThat(result.messages().get(1).role()).isEqualTo("assistant");
        assertThat(result.messages().get(1).content()).contains("ME");
    }

    @Test
    void shouldDropLeadingAssistantMessages() {
        MessageEntity own1 = createMessage(1L, 123L, "First own", true, null, null, null);
        MessageEntity own2 = createMessage(2L, 123L, "Second own", true, null, null, null);
        MessageEntity other = createMessage(3L, 456L, "User msg", false, "user1", null, null);
        ConversationFormatter.FormatResult result = formatter.format(List.of(own1, own2), other, "bot-1", 123L);
        assertThat(result.messages()).hasSize(1);
        assertThat(result.messages().get(0).role()).isEqualTo("user");
    }

    @Test
    void shouldMergeConsecutiveMessagesWithSameRole() {
        MessageEntity user1 = createMessage(1L, 456L, "First message", false, "user1", null, null);
        MessageEntity user2 = createMessage(2L, 456L, "Second message", false, "user1", null, null);
        ConversationFormatter.FormatResult result = formatter.format(List.of(user1), user2, "bot-1", 123L);
        assertThat(result.messages()).hasSize(1);
        String content = result.messages().get(0).content();
        assertThat(content).contains("First message");
        assertThat(content).contains("Second message");
    }

    @Test
    void shouldAssignDifferentLabelsToMultipleParticipants() {
        MessageEntity user1Msg = createMessage(1L, 456L, "User 1 says hi", false, "user1", null, null);
        MessageEntity user2Msg = createMessage(2L, 789L, "User 2 responds", false, "user2", null, null);
        ConversationFormatter.FormatResult result = formatter.format(List.of(user1Msg), user2Msg, "bot-1", 123L);
        assertThat(result.speakerContext().participants()).hasSize(2);
        assertThat(result.speakerContext().participants().get(0).label()).isEqualTo("P1");
        assertThat(result.speakerContext().participants().get(1).label()).isEqualTo("P2");
    }

    @Test
    void shouldUseSelfLabelForOwnMessages() {
        MessageEntity ownMsg = createMessage(1L, 123L, "My message", true, null, null, null);
        MessageEntity otherMsg = createMessage(2L, 456L, "Their reply", false, "other", null, null);
        ConversationFormatter.FormatResult result = formatter.format(List.of(ownMsg, otherMsg), null, "bot-1", 123L);
        assertThat(result.messages()).hasSize(1);
    }

    @Test
    void shouldHandleNullSelfTelegramUserId() {
        MessageEntity trigger = createMessage(1L, 456L, "Test message", false, "testuser", null, null);
        ConversationFormatter.FormatResult result = formatter.format(null, trigger, "bot-1", null);
        assertThat(result.messages()).hasSize(1);
        assertThat(result.speakerContext().selfTelegramUserId()).isNull();
    }

    @Test
    void shouldPreserveBotInstanceIdInContext() {
        MessageEntity trigger = createMessage(1L, 456L, "Test", false, "user", null, null);
        ConversationFormatter.FormatResult result = formatter.format(null, trigger, "my-bot-instance", 123L);
        assertThat(result.speakerContext().botInstanceId()).isEqualTo("my-bot-instance");
    }

    @Test
    void shouldSkipMessagesWithBlankContent() {
        MessageEntity blankMsg = createMessage(1L, 456L, "   ", false, "user", null, null);
        MessageEntity validMsg = createMessage(2L, 789L, "Valid content", false, "user2", null, null);
        ConversationFormatter.FormatResult result = formatter.format(List.of(blankMsg), validMsg, "bot-1", 123L);
        assertThat(result.messages()).hasSize(1);
        assertThat(result.messages().get(0).content()).contains("Valid content");
    }

    @Test
    void shouldSkipMessagesWithNullContent() {
        MessageEntity nullContentMsg = createMessage(1L, 456L, null, false, "user", null, null);
        MessageEntity validMsg = createMessage(2L, 789L, "Valid", false, "user2", null, null);
        ConversationFormatter.FormatResult result = formatter.format(List.of(nullContentMsg), validMsg, "bot-1", 123L);
        assertThat(result.messages()).hasSize(1);
    }

    @Test
    void shouldIncludeUsernameInSpeakerHint() {
        MessageEntity msg = createMessage(1L, 456L, "Test", false, "john_doe", null, null);
        ConversationFormatter.FormatResult result = formatter.format(null, msg, "bot-1", 123L);
        String content = result.messages().get(0).content();
        assertThat(content).contains("@john_doe");
    }

    @Test
    void shouldIncludeFirstAndLastNameInHintWhenNoUsername() {
        MessageEntity msg = createMessage(1L, 456L, "Test", false, null, "John", "Doe");
        ConversationFormatter.FormatResult result = formatter.format(null, msg, "bot-1", 123L);
        String content = result.messages().get(0).content();
        assertThat(content).contains("John Doe");
    }

    @Test
    void shouldUseOutgoingFlagWhenSenderIdIsNull() {
        MessageEntity outgoing = createMessageWithNullSender(1L, "My outgoing", true);
        MessageEntity incoming = createMessageWithNullSender(2L, "Incoming", false);
        ConversationFormatter.FormatResult result = formatter.format(List.of(outgoing, incoming), null, "bot-1", null);
        assertThat(result.messages()).hasSize(1);
        assertThat(result.messages().get(0).content()).contains("UNKNOWN");
    }

    @Test
    void shouldReturnImmutableMessagesList() {
        MessageEntity msg = createMessage(1L, 456L, "Test", false, "user", null, null);
        ConversationFormatter.FormatResult result = formatter.format(null, msg, "bot-1", 123L);
        assertThat(result.messages()).isUnmodifiable();
    }

    @Test
    void shouldReturnImmutableParticipantsList() {
        MessageEntity msg = createMessage(1L, 456L, "Test", false, "user", null, null);
        ConversationFormatter.FormatResult result = formatter.format(null, msg, "bot-1", 123L);
        assertThat(result.speakerContext().participants()).isUnmodifiable();
    }

    private MessageEntity createMessage(Long messageId, Long senderId, String content,
                                         boolean outgoing, String username,
                                         String firstName, String lastName) {
        MessageEntity msg = new MessageEntity();
        msg.setMessageId(messageId);
        msg.setSenderId(senderId);
        msg.setContent(content);
        msg.setOutgoing(outgoing);
        msg.setSenderUsername(username);
        msg.setSenderFirstName(firstName);
        msg.setSenderLastName(lastName);
        return msg;
    }

    private MessageEntity createMessageWithNullSender(Long messageId, String content, boolean outgoing) {
        MessageEntity msg = new MessageEntity();
        msg.setMessageId(messageId);
        msg.setSenderId(null);
        msg.setContent(content);
        msg.setOutgoing(outgoing);
        return msg;
    }

    @Test
    void shouldIncludePhotoPlaceholderWhenMediaPlaceholdersEnabled() {
        MessageEntity msg = createMessage(1L, 456L, "Check this out", false, "user", null, null);
        msg.setMediaType(MediaKind.PHOTO);
        ConversationFormatter.FormatResult result = formatter.format(null, msg, "bot-1", 123L, true);
        assertThat(result.messages()).hasSize(1);
        String content = result.messages().get(0).content();
        assertThat(content).contains("[Фото]");
        assertThat(content).contains("Check this out");
    }

    @Test
    void shouldIncludeVideoPlaceholderWhenEnabled() {
        MessageEntity msg = createMessage(1L, 456L, "Watch this", false, "user", null, null);
        msg.setMediaType(MediaKind.VIDEO);
        ConversationFormatter.FormatResult result = formatter.format(null, msg, "bot-1", 123L, true);
        String content = result.messages().get(0).content();
        assertThat(content).contains("[Відео]");
    }

    @Test
    void shouldIncludeVoicePlaceholderWhenEnabled() {
        MessageEntity msg = createMessage(1L, 456L, "", false, "user", null, null);
        msg.setMediaType(MediaKind.VOICE);
        ConversationFormatter.FormatResult result = formatter.format(null, msg, "bot-1", 123L, true);
        String content = result.messages().get(0).content();
        assertThat(content).contains("[Голосове повідомлення]");
    }

    @Test
    void shouldNotIncludeMediaPlaceholderWhenDisabled() {
        MessageEntity msg = createMessage(1L, 456L, "Check this out", false, "user", null, null);
        msg.setMediaType(MediaKind.PHOTO);
        ConversationFormatter.FormatResult result = formatter.format(null, msg, "bot-1", 123L, false);
        String content = result.messages().get(0).content();
        assertThat(content).doesNotContain("[Фото]");
        assertThat(content).contains("Check this out");
    }

    @Test
    void shouldDelegateToNonMediaMethodWhenCalledWithoutMediaFlag() {
        MessageEntity msg = createMessage(1L, 456L, "Test content", false, "user", null, null);
        msg.setMediaType(MediaKind.PHOTO);
        ConversationFormatter.FormatResult result = formatter.format(null, msg, "bot-1", 123L);
        String content = result.messages().get(0).content();
        assertThat(content).doesNotContain("[Фото]");
    }

    @Test
    void shouldFilterServiceMessagesFromConversation() {
        MessageEntity serviceMsg = createMessage(1L, 456L, "Config enabled", false, "user", null, null);
        serviceMsg.setMessageType(MessageType.SERVICE_MESSAGE);
        MessageEntity normalMsg = createMessage(2L, 789L, "Hello everyone", false, "user2", null, null);
        ConversationFormatter.FormatResult result = formatter.format(List.of(serviceMsg), normalMsg, "bot-1", 123L, true);
        assertThat(result.messages()).hasSize(1);
        assertThat(result.messages().get(0).content()).contains("Hello everyone");
        assertThat(result.messages().get(0).content()).doesNotContain("Config enabled");
    }

    @Test
    void shouldIncludeOnlyMediaPlaceholderWhenTextIsBlankButMediaPresent() {
        MessageEntity msg = createMessage(1L, 456L, "", false, "user", null, null);
        msg.setMediaType(MediaKind.STICKER);
        ConversationFormatter.FormatResult result = formatter.format(null, msg, "bot-1", 123L, true);
        assertThat(result.messages()).hasSize(1);
        assertThat(result.messages().get(0).content()).contains("[Стікер]");
    }

    @Test
    void shouldHandleUnknownMediaTypeGracefully() {
        MessageEntity msg = createMessage(1L, 456L, "Some text", false, "user", null, null);
        msg.setMediaType(MediaKind.UNKNOWN);
        ConversationFormatter.FormatResult result = formatter.format(null, msg, "bot-1", 123L, true);
        String content = result.messages().get(0).content();
        assertThat(content).contains("Some text");
        assertThat(content).doesNotContain("[");
    }
}
