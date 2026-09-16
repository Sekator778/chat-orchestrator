package com.example.telegramuserbot.service.publishing;

import com.example.telegramuserbot.config.BotInstanceProvider;
import com.example.telegramuserbot.service.TelegramClientManager;
import com.example.telegramuserbot.service.safety.OutboundReplyGuard;
import com.example.telegramuserbot.telegram.TelegramClientFacade;
import it.tdlight.client.GenericResultHandler;
import it.tdlight.client.Result;
import it.tdlight.jni.TdApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TelegramMessageSenderImplTest {

    private static final String BOT_ID = "37904005";
    private static final long CHAT_ID = -4964162923L;

    private TelegramClientFacade client;
    private TelegramMessageSenderImpl sender;
    private final AtomicReference<TdApi.SendMessage> captured = new AtomicReference<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        client = mock(TelegramClientFacade.class);
        TelegramClientManager clientManager = mock(TelegramClientManager.class);
        when(clientManager.getClient(anyString())).thenReturn(client);

        OutboundReplyGuard guard = mock(OutboundReplyGuard.class);
        when(guard.shouldSuppress(anyString())).thenReturn(false);

        BotInstanceProvider instanceProvider = mock(BotInstanceProvider.class);
        when(instanceProvider.getInstanceId()).thenReturn(BOT_ID);

        HumanSendPacer pacer = mock(HumanSendPacer.class);

        doAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            GenericResultHandler<TdApi.Message> handler = invocation.getArgument(1);
            TdApi.Message sent = new TdApi.Message();
            sent.id = 42L;
            handler.onResult(Result.of(sent));
            return null;
        }).when(client).send(any(TdApi.SendMessage.class), any(GenericResultHandler.class));

        sender = new TelegramMessageSenderImpl(clientManager, instanceProvider, guard, pacer);
    }

    @Test
    @DisplayName("a null reply-to id sends a plain message instead of throwing")
    void nullReplyToSendsAPlainMessage() {
        // Unboxing the null id here used to throw before the request ever reached
        // Telegram, which is how the test-chat send probe failed with a 500.
        StepVerifier.create(sender.send(BOT_ID, CHAT_ID, null, "просто сообщение"))
                .expectNextMatches(message -> message.id == 42L)
                .verifyComplete();

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().replyTo).isNull();
        assertThat(captured.get().chatId).isEqualTo(CHAT_ID);
    }

    @Test
    @DisplayName("a real reply-to id is carried into the request")
    void replyToIsPreserved() {
        StepVerifier.create(sender.send(BOT_ID, CHAT_ID, 777L, "ответ"))
                .expectNextCount(1)
                .verifyComplete();

        assertThat(captured.get().replyTo).isInstanceOf(TdApi.InputMessageReplyToMessage.class);
        TdApi.InputMessageReplyToMessage replyTo = (TdApi.InputMessageReplyToMessage) captured.get().replyTo;
        assertThat(replyTo.messageId).isEqualTo(777L);
        assertThat(replyTo.chatId).isEqualTo(CHAT_ID);
    }
}
