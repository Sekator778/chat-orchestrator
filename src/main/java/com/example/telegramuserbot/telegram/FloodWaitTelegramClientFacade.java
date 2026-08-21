package com.example.telegramuserbot.telegram;

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
 * Facade decorator that observes FLOOD_WAIT responses and, while the account is
 * parked, short-circuits outbound-VISIBLE sends (messages, albums, forwards,
 * reactions, typing) instead of piling on more rejected requests. Service calls
 * (GetMe, chat loading) always pass so the app keeps observing. Sits INSIDE the
 * kill-switch decorator: the emergency stop still wins.
 */
public final class FloodWaitTelegramClientFacade implements TelegramClientFacade {

    private static final Logger log = LoggerFactory.getLogger(FloodWaitTelegramClientFacade.class);

    private final TelegramClientFacade delegate;
    private final FloodWaitGuard guard;
    private final String botId;

    public FloodWaitTelegramClientFacade(TelegramClientFacade delegate, FloodWaitGuard guard, String botId) {
        this.delegate = delegate;
        this.guard = guard;
        this.botId = botId;
    }

    @Override
    public <T extends TdApi.Object> CompletableFuture<T> send(TdApi.Function<T> function) {
        if (parked(function)) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("FLOOD_WAIT backoff active for botId=" + botId));
        }
        return delegate.send(function).whenComplete((result, error) -> observe(error));
    }

    @Override
    public <T extends TdApi.Object> void send(TdApi.Function<T> function, GenericResultHandler<T> handler) {
        if (parked(function)) {
            // Complete the callback with a synthetic error so any wrapping Mono.create terminates.
            // A silent drop would hang the pending-reply pipeline forever.
            handler.onResult(Result.ofError(
                    new IllegalStateException("FLOOD_WAIT backoff active for botId=" + botId)));
            return;
        }
        delegate.send(function, result -> {
            observe(result);
            handler.onResult(result);
        });
    }

    @Override
    public boolean isBackingOff() {
        return guard.isBackingOff();
    }

    private boolean parked(TdApi.Function<?> function) {
        if (!guard.isBackingOff() || !isSuppressedDuringBackoff(function)) {
            return false;
        }
        log.warn("FLOOD_WAIT: suppressed {} for botId={} during backoff", function.getClass().getSimpleName(), botId);
        return true;
    }

    private static boolean isSuppressedDuringBackoff(TdApi.Function<?> function) {
        return isOutboundVisible(function) || isMembershipMutation(function);
    }

    private void observe(Result<?> result) {
        if (result != null && result.isError()) {
            TdApi.Error error = result.getError();
            if (FloodWaitGuard.isFloodWait(error.code, error.message)) {
                guard.recordFloodWait(error.code, error.message);
            }
        }
    }

    private void observe(Throwable error) {
        if (error == null) {
            return;
        }
        String message = error.getMessage();
        if (message != null && FloodWaitGuard.isFloodWait(extractCode(message), message)) {
            guard.recordFloodWait(extractCode(message), message);
        }
    }

    private static int extractCode(String message) {
        if (message != null && message.contains("429")) {
            return 429;
        }
        if (message != null && message.contains("420")) {
            return 420;
        }
        return 0;
    }

    private static boolean isOutboundVisible(TdApi.Function<?> function) {
        return function instanceof TdApi.SendMessage
                || function instanceof TdApi.SendMessageAlbum
                || function instanceof TdApi.ForwardMessages
                || function instanceof TdApi.AddMessageReaction
                || function instanceof TdApi.SendChatAction;
    }

    /**
     * Channel-membership mutations are the primary FLOOD_WAIT / ban trigger. Firing a
     * {@code JoinChat} while the account is already parked only extends the backoff
     * (each rejected join records another flood-wait), so we suppress joins during
     * backoff exactly like outbound-visible sends. This guards every join path
     * (Phase 1 ingestion, discovery sweep, reconciliation) at the client boundary.
     */
    private static boolean isMembershipMutation(TdApi.Function<?> function) {
        return function instanceof TdApi.JoinChat
                || function instanceof TdApi.JoinChatByInviteLink
                || function instanceof TdApi.LeaveChat;
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
