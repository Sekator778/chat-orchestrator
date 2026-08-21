package com.example.telegramuserbot.config;

import com.example.telegramuserbot.telegram.TelegramClientFacade;
import it.tdlight.jni.TdApi;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Contract for the no-op TelegramClientFacade used in the smoke profile.
 * Mirrors the stub assertions in BaseIntegrationTest.TestConfig.
 *
 * @see NoOpTelegramClientFacade
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NoOpTelegramClientFacade")
class SmokeTelegramClientConfigTest {

    private final TelegramClientFacade facade = new NoOpTelegramClientFacade();

    @Test
    @DisplayName("send(GetMe) future completes with a dummy TdApi.User")
    void sendGetMeFutureReturnsDummyUser() throws Exception {
        CompletableFuture<TdApi.User> future = facade.send(new TdApi.GetMe());

        assertThat(future).isCompletedWithValueMatching(user ->
                user.id == 1L && "SmokeTest".equals(user.firstName)
        );
    }

    @Test
    @DisplayName("send(GetMe, handler) calls handler with success result")
    void sendGetMeHandlerCompletesWithSuccess() {
        CompletableFuture<TdApi.User> resultFuture = new CompletableFuture<>();

        facade.send(new TdApi.GetMe(), result -> {
            if (result.isError()) {
                resultFuture.completeExceptionally(
                        new RuntimeException("Handler received error: " + result.getError().message));
            } else {
                resultFuture.complete(result.get());
            }
        });

        assertThat(resultFuture).succeedsWithin(java.time.Duration.ofSeconds(1))
                .matches(user -> user.id == 1L && "SmokeTest".equals(user.firstName));
    }

    @Test
    @DisplayName("addUpdateHandler is a no-op and does not throw")
    void addUpdateHandlerDoesNotThrow() {
        assertThatCode(() -> facade.addUpdateHandler(TdApi.UpdateNewMessage.class, update -> {}))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("addUpdatesHandler is a no-op and does not throw")
    void addUpdatesHandlerDoesNotThrow() {
        assertThatCode(() -> facade.addUpdatesHandler(update -> {}))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("addUpdateExceptionHandler is a no-op and does not throw")
    void addUpdateExceptionHandlerDoesNotThrow() {
        assertThatCode(() -> facade.addUpdateExceptionHandler(e -> {}))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("addDefaultExceptionHandler is a no-op and does not throw")
    void addDefaultExceptionHandlerDoesNotThrow() {
        assertThatCode(() -> facade.addDefaultExceptionHandler(e -> {}))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("addCommandHandler is a no-op and does not throw")
    void addCommandHandlerDoesNotThrow() {
        assertThatCode(() -> facade.addCommandHandler("/start", (client, cmd, args) -> {}))
                .doesNotThrowAnyException();
    }
}
