package com.example.telegramuserbot.telegram;

import it.tdlight.ExceptionHandler;
import it.tdlight.client.CommandHandler;
import it.tdlight.client.GenericResultHandler;
import it.tdlight.client.GenericUpdateHandler;
import it.tdlight.jni.TdApi;

import java.util.concurrent.CompletableFuture;

/**
 * Abstraction over the TDLight SimpleTelegramClient.
 * Allows us to swap implementations (real TDLib vs test stubs)
 * without forcing the rest of the application to reference final TDLight classes.
 */
public interface TelegramClientFacade {

    /**
     * Sends a TDLib function and returns a future with the result.
     */
    <T extends TdApi.Object> CompletableFuture<T> send(TdApi.Function<T> function);

    /**
     * Sends a TDLib function using the callback-style API.
     */
    <T extends TdApi.Object> void send(TdApi.Function<T> function, GenericResultHandler<T> handler);

    /**
     * Registers update handlers.
     */
    <T extends TdApi.Update> void addUpdateHandler(Class<T> type, GenericUpdateHandler<? super T> handler);

    void addUpdatesHandler(GenericUpdateHandler<TdApi.Update> handler);

    void addUpdateExceptionHandler(ExceptionHandler handler);

    void addDefaultExceptionHandler(ExceptionHandler handler);

    void addCommandHandler(String command, CommandHandler handler);

    /**
     * Whether this client is currently in a FLOOD_WAIT backoff window, during which outbound
     * sends are suppressed. Lets callers skip expensive work (e.g. LLM generation) that would
     * only be dropped at send time. Default {@code false}; only the flood-wait facade tracks it.
     */
    default boolean isBackingOff() {
        return false;
    }
}
