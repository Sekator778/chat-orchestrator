package com.example.telegramuserbot.service.persistence;

import com.example.telegramuserbot.domain.ChatConfig;
import com.example.telegramuserbot.integration.BaseIntegrationTest;
import com.example.telegramuserbot.repository.ChatConfigRepository;
import com.example.telegramuserbot.repository.ChatMessageStatsRepository;
import com.example.telegramuserbot.service.cache.SyncEnabledChatsCache;
import it.tdlight.jni.TdApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration-level regression test that ensures the human message statistics
 * stay in sync with the actual rows stored in {@code bot.messages}, even when
 * synchronization replays historical messages.
 */
final class MessagePersistenceServiceIntegrationTest extends BaseIntegrationTest {

    private static final long CHAT_ID = -1001234567890L;

    @Autowired
    private MessagePersistenceService messagePersistenceService;

    @Autowired
    private ChatMessageStatsRepository chatMessageStatsRepository;

    @Autowired
    private ChatConfigRepository chatConfigRepository;

    @Autowired
    private SyncEnabledChatsCache syncEnabledChatsCache;

    @Autowired
    private DatabaseClient databaseClient;

    @BeforeEach
    void prepareSyncEnabledChat() {
        syncEnabledChatsCache.invalidateAll();

        insertTestChannel(CHAT_ID, "Stats consistency", 1.0, 10_000L)
                .then(setupChatConfig(CHAT_ID))
                .block();

        syncEnabledChatsCache.invalidate(CHAT_ID);
    }

    @Test
    void humanMessageStatsMatchUniqueHumanMessages() {
        persist(incomingMessage(101L, 2001L, "Alpha"));
        persist(incomingMessage(102L, 2002L, "Beta"));
        persist(incomingMessage(103L, 2003L, "Gamma"));

        assertCountsAligned(3);

        // Synchronization replays the same payloads (including edits)
        persist(incomingMessage(101L, 2001L, "Alpha (replay)"));
        persist(incomingMessage(102L, 2002L, "Beta (edited)"));
        persist(incomingMessage(103L, 2003L, "Gamma"));

        assertCountsAligned(3);

        // Non-human messages should never affect the stats
        persist(outgoingMessage(201L, "Bot status update"));
        persist(serviceMessage(301L));

        assertCountsAligned(3);
    }

    private void persist(TdApi.Message message) {
        messagePersistenceService.persistMessage(CHAT_ID, message).block();
    }

	    private Mono<Long> setupChatConfig(long chatId) {
	        ChatConfig config = new ChatConfig();
	        config.setChannelId(chatId);
	        config.setEnabled(true);
	        config.setSyncEnabled(true);
	        return chatConfigRepository.save(config).map(ChatConfig::getId);
	    }

    private void assertCountsAligned(long expected) {
        long humanRows = actualHumanMessageCount();
        long statsValue = chatMessageStatsRepository.findCountByChatId(CHAT_ID)
                .defaultIfEmpty(0L)
                .block();

        assertThat(humanRows)
                .as("Stored human rows should equal %s", expected)
                .isEqualTo(expected);
        assertThat(statsValue)
                .as("Aggregated chat_message_stats should equal %s", expected)
                .isEqualTo(expected);
    }

    private long actualHumanMessageCount() {
        return databaseClient.sql("""
                        SELECT COUNT(*) AS cnt
                          FROM bot.messages
                         WHERE chat_id = :chatId
                           AND is_outgoing = false
                           AND sender_id IS NOT NULL
                           AND sender_id > 0
                        """)
                .bind("chatId", CHAT_ID)
                .map((row, metadata) -> row.get("cnt", Long.class))
                .one()
                .blockOptional()
                .orElse(0L);
    }

    private TdApi.Message incomingMessage(long messageId, long senderId, String text) {
        TdApi.Message message = new TdApi.Message();
        message.id = messageId;
        message.chatId = CHAT_ID;
        message.date = (int) Instant.now().getEpochSecond();
        message.editDate = 0;
        message.isOutgoing = false;

        TdApi.MessageSenderUser sender = new TdApi.MessageSenderUser();
        sender.userId = senderId;
        message.senderId = sender;

        TdApi.FormattedText formattedText = new TdApi.FormattedText(text, null);
        message.content = new TdApi.MessageText(formattedText, null, null);

        return message;
    }

    private TdApi.Message outgoingMessage(long messageId, String text) {
        TdApi.Message message = incomingMessage(messageId, 9999L, text);
        message.isOutgoing = true;
        return message;
    }

    private TdApi.Message serviceMessage(long messageId) {
        TdApi.Message message = incomingMessage(messageId, 0L, "service");
        TdApi.MessageSenderChat senderChat = new TdApi.MessageSenderChat();
        senderChat.chatId = CHAT_ID;
        message.senderId = senderChat;
        return message;
    }
}
