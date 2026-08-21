package com.example.telegramuserbot.telegram;

import com.example.telegramuserbot.service.safety.OutboundKillSwitch;
import it.tdlight.jni.TdApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Contract tests for the owner's emergency stop:
 * FR-001: switch ACTIVE → no outbound-visible function reaches the client.
 * FR-002: switch ACTIVE → service calls (GetMe) still pass (the app keeps observing).
 * FR-003: switch INACTIVE → everything passes untouched.
 */
@ExtendWith(MockitoExtension.class)
class KillSwitchTelegramClientFacadeTest {

    @Mock
    private TelegramClientFacade delegate;
    @Mock
    private OutboundKillSwitch killSwitch;

    private KillSwitchTelegramClientFacade facade;

    @BeforeEach
    void setUp() {
        facade = new KillSwitchTelegramClientFacade(delegate, killSwitch, "bot-test");
    }

    @Test
    void activeSwitchSuppressesOutboundMessages() {
        when(killSwitch.isActive()).thenReturn(true);

        CompletableFuture<TdApi.Message> result = facade.send(new TdApi.SendMessage());

        assertThat(result.isCompletedExceptionally()).isTrue();
        verify(delegate, never()).send(any(TdApi.SendMessage.class));
    }

    @Test
    void activeSwitchSuppressesCallbackStyleSends() {
        when(killSwitch.isActive()).thenReturn(true);

        facade.send(new TdApi.SendChatAction(), result -> { });

        verify(delegate, never()).send(any(TdApi.SendChatAction.class), any());
    }

    @Test
    void activeSwitchStillAllowsServiceCalls() {
        lenient().when(killSwitch.isActive()).thenReturn(true);
        when(delegate.send(any(TdApi.GetMe.class))).thenReturn(CompletableFuture.completedFuture(new TdApi.User()));

        CompletableFuture<TdApi.User> result = facade.send(new TdApi.GetMe());

        assertThat(result.isCompletedExceptionally()).isFalse();
        verify(delegate).send(any(TdApi.GetMe.class));
    }

    @Test
    void inactiveSwitchPassesMessagesThrough() {
        when(killSwitch.isActive()).thenReturn(false);
        when(delegate.send(any(TdApi.SendMessage.class))).thenReturn(CompletableFuture.completedFuture(new TdApi.Message()));

        CompletableFuture<TdApi.Message> result = facade.send(new TdApi.SendMessage());

        assertThat(result.isCompletedExceptionally()).isFalse();
        verify(delegate).send(any(TdApi.SendMessage.class));
    }
}
