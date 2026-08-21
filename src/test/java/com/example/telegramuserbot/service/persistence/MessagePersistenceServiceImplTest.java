package com.example.telegramuserbot.service.persistence;

import com.example.telegramuserbot.domain.ChatConfig;
import com.example.telegramuserbot.domain.MessageEntity;
import com.example.telegramuserbot.domain.MediaKind;
import com.example.telegramuserbot.repository.ChannelRepository;
import com.example.telegramuserbot.repository.ChatConfigRepository;
import com.example.telegramuserbot.repository.MessageRepository;
import com.example.telegramuserbot.repository.ChatMessageStatsRepository;
import com.example.telegramuserbot.service.MediaStorageService;
import com.example.telegramuserbot.service.ProblematicChatService;
import com.example.telegramuserbot.service.cache.SyncEnabledChatsCache;
import com.example.telegramuserbot.service.util.MessageTypeClassifier;
import it.tdlight.Init;
import it.tdlight.jni.TdApi;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MessagePersistenceServiceImplTest {

    private static final long ADMIN_CHAT_ID = 1000000001L;

    // TDLight native lib is loaded best-effort. Most tests use TdApi as plain data
    // POJOs (no native), but the duplicate-update path serializes via the NATIVE
    // TdApi.toString() (serializeMessage), so a test that hits it is skipped via
    // assumeTrue when the native is absent — e.g. Linux without the
    // linux_amd64_gnu_ssl3 classifier — instead of erroring the whole build.
    private static boolean tdLightNativeAvailable;

    @BeforeAll
    static void initTdLight() {
        try {
            Init.init();
            tdLightNativeAvailable = true;
        } catch (Throwable t) {  // UnsupportedNativeLibraryException etc.
            tdLightNativeAvailable = false;
        }
    }

    @Mock
    private MessageRepository messageRepository;
    @Mock
    private ChatConfigRepository chatConfigRepository;
    @Mock
    private ChannelRepository channelRepository;
    @Mock
    private MediaStorageService mediaStorageService;
    @Mock
    private ChatMessageStatsRepository chatMessageStatsRepository;
    @Mock
    private ProblematicChatService problematicChatService;
    @Mock
    private MessageEntityHydrator messageEntityHydrator;

    private ExecutorService executorService;
    private MessagePersistenceServiceImpl persistenceService;
    private SyncEnabledChatsCache syncEnabledChatsCache;

    @BeforeEach
    void setUp() {
        executorService = Executors.newSingleThreadExecutor();
        MessageTypeClassifier classifier = new MessageTypeClassifier();
        when(problematicChatService.shouldProcess(anyLong())).thenReturn(Mono.just(true));
        when(problematicChatService.listProblematicChatIds()).thenReturn(Mono.just(java.util.Set.of()));
        syncEnabledChatsCache = new SyncEnabledChatsCache(chatConfigRepository, problematicChatService);
        when(chatMessageStatsRepository.incrementHumanCount(anyLong())).thenReturn(Mono.just(1L));
        when(messageEntityHydrator.enrichSenderIfMissing(any(), any(), any())).thenReturn(Mono.just(false));
        when(messageEntityHydrator.create(anyLong(), any(TdApi.Message.class), any())).thenAnswer(invocation -> {
            Long chatId = invocation.getArgument(0);
            TdApi.Message msg = invocation.getArgument(1);
            if (msg == null) {
                return Mono.empty();
            }
            MessageEntity entity = new MessageEntity();
            entity.setChatId(chatId);
            entity.setTelegramMessageId(msg.id);
            entity.setOutgoing(msg.isOutgoing);
            entity.setDate(Instant.ofEpochSecond(msg.date));
            if (msg.content instanceof TdApi.MessageText textContent) {
                entity.setContent(textContent.text != null ? textContent.text.text : null);
            }
            if (msg.senderId instanceof TdApi.MessageSenderUser userSender) {
                entity.setUserId(userSender.userId);
            }
            return Mono.just(entity);
        });
        persistenceService = new MessagePersistenceServiceImpl(
                messageRepository,
                channelRepository,
                mediaStorageService,
                executorService,
                classifier,
                ADMIN_CHAT_ID,
                syncEnabledChatsCache,
                chatMessageStatsRepository,
                messageEntityHydrator
        );
    }

    @AfterEach
    void tearDown() {
        executorService.shutdownNow();
    }

    @Test
    void skipsAdminSystemNotificationsFromPersistence() {
        long messageId = 7084179456L;
        TdApi.Message message = createTextMessage(
                ADMIN_CHAT_ID,
                messageId,
                true,
                "🚨 KAFKA PROCESSING ERROR\nChat: -1003000000\nMessage: 3000232\nError: Retries exhausted: 6/6",
                7734573356L
        );

        when(chatConfigRepository.findByChannelChatId(ADMIN_CHAT_ID)).thenReturn(Mono.just(syncEnabledConfig(ADMIN_CHAT_ID, true)));

        StepVerifier.create(persistenceService.persistMessage(ADMIN_CHAT_ID, message))
                .verifyComplete();

        verify(messageRepository, never()).save(any(MessageEntity.class));
    }

    @Test
    void incrementsHumanCountOnlyForNewMessages() {
        long chatId = 12345L;
        long messageId = 42L;
        TdApi.Message message = createTextMessage(
                chatId,
                messageId,
                false,
                "Hello there!",
                999L
        );

        when(chatConfigRepository.findByChannelChatId(chatId)).thenReturn(Mono.just(syncEnabledConfig(chatId, true)));
        when(messageRepository.save(any(MessageEntity.class))).thenAnswer(invocation -> {
            MessageEntity entity = invocation.getArgument(0, MessageEntity.class);
            entity.setId(1L);
            return Mono.just(entity);
        });

        StepVerifier.create(persistenceService.persistMessage(chatId, message))
                .expectNextMatches(entity -> entity.getMessageId().equals(messageId))
                .verifyComplete();

        verify(chatMessageStatsRepository).incrementHumanCount(chatId);
    }

    @Test
    void doesNotIncrementHumanCountForDuplicateMessages() {
        Assumptions.assumeTrue(tdLightNativeAvailable,
                "Requires the TDLight native lib — the duplicate-update path serializes via the native TdApi.toString()");
        long chatId = 123123L;
        long messageId = 300L;
        TdApi.Message message = createTextMessage(
                chatId,
                messageId,
                false,
                "Existing message",
                555L
        );

        when(chatConfigRepository.findByChannelChatId(chatId)).thenReturn(Mono.just(syncEnabledConfig(chatId, true)));

        AtomicBoolean firstCall = new AtomicBoolean(true);
        when(messageRepository.save(any(MessageEntity.class))).thenAnswer(invocation -> {
            if (firstCall.getAndSet(false)) {
                return Mono.error(new DuplicateKeyException("duplicate"));
            }
            MessageEntity entity = invocation.getArgument(0, MessageEntity.class);
            return Mono.just(entity);
        });

        MessageEntity existing = new MessageEntity();
        existing.setId(99L);
        existing.setChatId(chatId);
        existing.setMessageId(messageId);
        existing.setTelegramMessageId(messageId);
        existing.setOutgoing(false);
        existing.setSenderId(555L);
        existing.setContent("Existing message");
        existing.setDate(Instant.ofEpochSecond(message.date));
        existing.setMediaType(MediaKind.UNKNOWN);
        existing.setRawMessageDump("{\"id\":" + messageId + ",\"chatId\":" + chatId + "}");

        when(messageRepository.findByChatIdAndMessageId(chatId, messageId)).thenReturn(Mono.just(existing));

        StepVerifier.create(persistenceService.persistMessage(chatId, message))
                .expectNextMatches(entity -> entity.getId().equals(existing.getId()))
                .verifyComplete();

        verify(chatMessageStatsRepository, never()).incrementHumanCount(chatId);
        verify(messageRepository, times(1)).findByChatIdAndMessageId(chatId, messageId);
    }

    @Test
    void persistsRegularMessages() {
        long chatId = 12345L;
        long messageId = 42L;
        TdApi.Message message = createTextMessage(
                chatId,
                messageId,
                false,
                "Hello there!",
                999L
        );

        when(chatConfigRepository.findByChannelChatId(chatId)).thenReturn(Mono.just(syncEnabledConfig(chatId, true)));
        when(messageRepository.save(any(MessageEntity.class))).thenAnswer(invocation -> {
            MessageEntity entity = invocation.getArgument(0, MessageEntity.class);
            entity.setId(1L);
            return Mono.just(entity);
        });

        StepVerifier.create(persistenceService.persistMessage(chatId, message))
                .expectNextMatches(entity -> entity.getMessageId().equals(messageId))
                .verifyComplete();

        verify(messageRepository).save(any(MessageEntity.class));
    }

    @Test
    void skipsMessagesWhenSyncDisabled() {
        long chatId = 22222L;
        long messageId = 99L;
        TdApi.Message message = createTextMessage(
                chatId,
                messageId,
                false,
                "Should not be persisted",
                321L
        );

        when(chatConfigRepository.findByChannelChatId(chatId)).thenReturn(Mono.just(syncEnabledConfig(chatId, false)));

        StepVerifier.create(persistenceService.persistMessage(chatId, message))
                .verifyComplete();

        verify(messageRepository, never()).save(any(MessageEntity.class));
    }

    private TdApi.Message createTextMessage(long chatId, long messageId, boolean outgoing, String text, long senderUserId) {
        TdApi.Message message = new TdApi.Message();
        message.id = messageId;
        message.chatId = chatId;
        message.isOutgoing = outgoing;
        message.date = (int) Instant.now().getEpochSecond();
        message.editDate = 0;

        TdApi.MessageSenderUser sender = new TdApi.MessageSenderUser();
        sender.userId = senderUserId;
        message.senderId = sender;

        TdApi.FormattedText formattedText = new TdApi.FormattedText(text, null);
        message.content = new TdApi.MessageText(formattedText, null, null);

        return message;
    }

    private ChatConfig syncEnabledConfig(long chatId, boolean syncEnabled) {
        ChatConfig config = new ChatConfig();
        config.setChannelId(chatId);
        config.setSyncEnabled(syncEnabled);
        return config;
    }
}
