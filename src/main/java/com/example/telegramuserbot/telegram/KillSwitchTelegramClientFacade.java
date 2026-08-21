package com.example.telegramuserbot.telegram;

import com.example.telegramuserbot.service.safety.OutboundKillSwitch;
import it.tdlight.ExceptionHandler;
import it.tdlight.client.CommandHandler;
import it.tdlight.client.GenericResultHandler;
import it.tdlight.client.GenericUpdateHandler;
import it.tdlight.client.Result;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * Facade decorator enforcing the owner's kill switch: while active, every
 * outbound-VISIBLE function (messages, albums, forwards, reactions, typing)
 * is suppressed for every persona. Service calls (GetMe, chat loading, update
 * handlers) pass through untouched, so the application keeps observing —
 * it just goes silent.
 */
public final class KillSwitchTelegramClientFacade implements TelegramClientFacade {

    private static final Logger log = LoggerFactory.getLogger(KillSwitchTelegramClientFacade.class);

    private final TelegramClientFacade delegate;
    private final OutboundKillSwitch killSwitch;
    private final String botId;

    public KillSwitchTelegramClientFacade(TelegramClientFacade delegate, OutboundKillSwitch killSwitch, String botId) {
        this.delegate = delegate;
        this.killSwitch = killSwitch;
        this.botId = botId;
    }

    @Override
    public <T extends TdApi.Object> CompletableFuture<T> send(TdApi.Function<T> function) {
        if (suppressed(function)) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Outbound kill switch is ACTIVE — " + function.getClass().getSimpleName() + " suppressed"));
        }
        return delegate.send(function);
    }

    @Override
    public <T extends TdApi.Object> void send(TdApi.Function<T> function, GenericResultHandler<T> handler) {
        if (suppressed(function)) {
            // Complete the callback with a synthetic error so any wrapping Mono.create terminates.
            // A silent drop would hang the pending-reply pipeline forever.
            handler.onResult(Result.ofError(
                    new IllegalStateException("Outbound kill switch is ACTIVE — " + function.getClass().getSimpleName() + " suppressed")));
            return;
        }
        delegate.send(function, handler);
    }

    @Override
    public boolean isBackingOff() {
        return delegate.isBackingOff();
    }

    private boolean suppressed(TdApi.Function<?> function) {
        if (!killSwitch.isActive() || !isOutboundVisible(function)) {
            return false;
        }
        log.warn("KILL SWITCH: suppressed {} for botId={}", function.getClass().getSimpleName(), botId);
        return true;
    }

    private static boolean isOutboundVisible(TdApi.Function<?> function) {
        return function instanceof TdApi.SendMessage
                || function instanceof TdApi.SendMessageAlbum
                || function instanceof TdApi.ForwardMessages
                || function instanceof TdApi.AddMessageReaction
                || function instanceof TdApi.SendChatAction;
    }

    @Override
    public <T extends TdApi.Update> void addUpdateHandler(Class<T> type, GenericUpdateHandler<? super T> handler) {
        delegate.addUpdateHandler(type, handler);
    }

    @Override
    public void addUpdatesHandler(GenericUpdateHandler<TdApi.Update> handler) {
        delegate.addUpdatesHandler(handler);
    }

    @Override
    public void addUpdateExceptionHandler(ExceptionHandler handler) {
        delegate.addUpdateExceptionHandler(handler);
    }

    @Override
    public void addDefaultExceptionHandler(ExceptionHandler handler) {
        delegate.addDefaultExceptionHandler(handler);
    }

    @Override
    public void addCommandHandler(String command, CommandHandler handler) {
        delegate.addCommandHandler(command, handler);
    }
}
