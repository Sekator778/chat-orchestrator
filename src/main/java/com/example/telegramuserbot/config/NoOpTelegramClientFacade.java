package com.example.telegramuserbot.config;

import com.example.telegramuserbot.telegram.TelegramClientFacade;
import it.tdlight.ExceptionHandler;
import it.tdlight.client.CommandHandler;
import it.tdlight.client.GenericResultHandler;
import it.tdlight.client.GenericUpdateHandler;
import it.tdlight.client.Result;
import it.tdlight.jni.TdApi;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * No-op implementation of {@link TelegramClientFacade} that returns dummy
 * success results for every operation.
 *
 * <p>This is the smoke-profile bean that satisfies the {@code TelegramClientFacade}
 * dependency without a real TDLib client. It follows the exact pattern established
 * by {@code BaseIntegrationTest.TestConfig.mockTelegramClientFacade()} but is on
 * the runtime classpath (not test scope), activated only when
 * {@code telegram.client.enabled=false}.</p>
 *
 * <p>Key behaviours:</p>
 * <ul>
 *   <li>{@code send(GetMe)} — completes with a dummy {@link TdApi.User}
 *       (id=1, firstName="SmokeTest")</li>
 *   <li>{@code send(SendMessage)} — completes with a dummy {@link TdApi.Message}</li>
 *   <li>All update/exception/command handler registration — no-op, no exception</li>
 * </ul>
 */
public final class NoOpTelegramClientFacade implements TelegramClientFacade {

    @Override
    public <T extends TdApi.Object> CompletableFuture<T> send(TdApi.Function<T> function) {
        return CompletableFuture.completedFuture(buildResult(function));
    }

    @Override
    public <T extends TdApi.Object> void send(TdApi.Function<T> function, GenericResultHandler<T> handler) {
        handler.onResult((Result<T>) Result.of(buildResult(function)));
    }

    @Override
    public <T extends TdApi.Update> void addUpdateHandler(Class<T> type, GenericUpdateHandler<? super T> handler) {
        // no-op
    }

    @Override
    public void addUpdatesHandler(GenericUpdateHandler<TdApi.Update> handler) {
        // no-op
    }

    @Override
    public void addUpdateExceptionHandler(ExceptionHandler handler) {
        // no-op
    }

    @Override
    public void addDefaultExceptionHandler(ExceptionHandler handler) {
        // no-op
    }

    @Override
    public void addCommandHandler(String command, CommandHandler handler) {
        // no-op
    }

    /**
     * Builds a dummy success result appropriate for the given function type.
     */
    @SuppressWarnings("unchecked")
    private <T extends TdApi.Object> T buildResult(TdApi.Function<T> function) {
        if (function instanceof TdApi.GetMe) {
            TdApi.User user = new TdApi.User();
            user.id = 1L;
            user.firstName = "SmokeTest";
            return (T) user;
        }
        if (function instanceof TdApi.SendMessage sendMessage) {
            TdApi.Message msg = new TdApi.Message();
            msg.id = ThreadLocalRandom.current().nextLong(1_000_000, 9_999_999);
            msg.chatId = sendMessage.chatId;
            msg.date = (int) (System.currentTimeMillis() / 1000);
            msg.content = new TdApi.MessageText(
                    new TdApi.FormattedText("Mock smoke message", null), null, null);
            return (T) msg;
        }
        // Default: return Ok for most functions (GetAuthorizationState, ping-like calls, etc.)
        return (T) new TdApi.Ok();
    }
}
