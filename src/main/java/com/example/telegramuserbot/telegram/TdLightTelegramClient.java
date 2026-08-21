package com.example.telegramuserbot.telegram;

import it.tdlight.ExceptionHandler;
import it.tdlight.client.CommandHandler;
import it.tdlight.client.GenericResultHandler;
import it.tdlight.client.GenericUpdateHandler;
import it.tdlight.client.SimpleTelegramClient;
import it.tdlight.jni.TdApi;

import java.util.concurrent.CompletableFuture;

/**
 * Production implementation of {@link TelegramClientFacade} that simply delegates
 * to the TDLight {@link SimpleTelegramClient}.
 */
public final class TdLightTelegramClient implements TelegramClientFacade {

    private final SimpleTelegramClient delegate;

    public TdLightTelegramClient(SimpleTelegramClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public <T extends TdApi.Object> CompletableFuture<T> send(TdApi.Function<T> function) {
        return delegate.send(function);
    }

    @Override
    public <T extends TdApi.Object> void send(TdApi.Function<T> function, GenericResultHandler<T> handler) {
        delegate.send(function, handler);
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

    public SimpleTelegramClient getDelegate() {
        return delegate;
    }
}
