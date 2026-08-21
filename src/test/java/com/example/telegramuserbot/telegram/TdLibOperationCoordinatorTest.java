package com.example.telegramuserbot.telegram;

import it.tdlight.jni.TdApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Unit tests for TdLibOperationCoordinator.
 * Tests serialization of LoadChats operations and state tracking.
 */
class TdLibOperationCoordinatorTest {

    private TdLibOperationCoordinator coordinator;
    private FakeTelegramClient fakeClient;

    @BeforeEach
    void setUp() {
        fakeClient = new FakeTelegramClient();
        coordinator = new TdLibOperationCoordinator(fakeClient);
    }

    @Test
    void initialStateIsIdle() {
        assertThat(coordinator.getState(), is(TdLibOperationState.IDLE));
    }

    @Test
    void isOperationInProgressReturnsFalseWhenIdle() {
        assertThat(coordinator.isOperationInProgress(), is(false));
    }

    @Test
    void getOperationStartTimeReturnsNullWhenIdle() {
        assertThat(coordinator.getOperationStartTime(), is(nullValue()));
    }

    @Test
    void getCurrentOperationReturnsNullWhenIdle() {
        assertThat(coordinator.getCurrentOperation(), is(nullValue()));
    }

    @Test
    void getCurrentOperationDurationReturnsZeroWhenIdle() {
        assertThat(coordinator.getCurrentOperationDuration(), is(Duration.ZERO));
    }

    @Test
    void loadChatsSequentiallyCompletesSuccessfully() {
        fakeClient.setNextResponse(new TdApi.Ok());
        Mono<Void> result = coordinator.loadChatsSequentially(
            new TdApi.ChatListMain(), 100);
        StepVerifier.create(result)
            .verifyComplete();
    }

    @Test
    void loadChatsSequentiallySetsStateToLoadingDuringExecution() {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch complete = new CountDownLatch(1);
        fakeClient.setResponseProvider(() -> {
            started.countDown();
            awaitUninterruptibly(complete, 5, TimeUnit.SECONDS);
            return new TdApi.Ok();
        });
        Thread executor = new Thread(() -> {
            coordinator.loadChatsSequentially(new TdApi.ChatListMain(), 100).block();
        });
        executor.start();
        awaitUninterruptibly(started, 5, TimeUnit.SECONDS);
        assertThat(coordinator.getState(), is(TdLibOperationState.LOADING));
        assertThat(coordinator.isOperationInProgress(), is(true));
        assertThat(coordinator.getCurrentOperation(), containsString("ChatListMain"));
        complete.countDown();
        joinThread(executor, 5, TimeUnit.SECONDS);
    }

    @Test
    void loadChatsSequentiallySetsStateToCompletedAfterSuccess() {
        fakeClient.setNextResponse(new TdApi.Ok());
        coordinator.loadChatsSequentially(new TdApi.ChatListMain(), 100).block();
        assertThat(coordinator.getState(), is(TdLibOperationState.COMPLETED));
        assertThat(coordinator.isOperationInProgress(), is(false));
    }

    @Test
    void loadChatsSequentiallyHandles404AsNormalCompletion() {
        fakeClient.setNextError(new RuntimeException("404 Not Found"));
        Mono<Void> result = coordinator.loadChatsSequentially(
            new TdApi.ChatListMain(), 100);
        StepVerifier.create(result)
            .verifyComplete();
        assertThat(coordinator.getState(), is(TdLibOperationState.COMPLETED));
    }

    @Test
    void loadChatsSequentiallySetsStateToErrorOnNon404Error() {
        fakeClient.setNextError(new RuntimeException("Connection timeout"));
        Mono<Void> result = coordinator.loadChatsSequentially(
            new TdApi.ChatListMain(), 100);
        StepVerifier.create(result)
            .expectError(RuntimeException.class)
            .verify();
        assertThat(coordinator.getState(), is(TdLibOperationState.ERROR));
    }

    @Test
    @Timeout(10)
    void loadChatsSequentiallySerializesConcurrentRequests() throws InterruptedException {
        AtomicInteger concurrentCount = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        CountDownLatch allStarted = new CountDownLatch(3);
        CountDownLatch proceed = new CountDownLatch(1);
        fakeClient.setResponseProvider(() -> {
            int current = concurrentCount.incrementAndGet();
            maxConcurrent.updateAndGet(max -> Math.max(max, current));
            allStarted.countDown();
            awaitUninterruptibly(proceed, 5, TimeUnit.SECONDS);
            concurrentCount.decrementAndGet();
            return new TdApi.Ok();
        });
        Thread t1 = new Thread(() -> coordinator.loadChatsSequentially(
            new TdApi.ChatListMain(), 100).block());
        Thread t2 = new Thread(() -> coordinator.loadChatsSequentially(
            new TdApi.ChatListArchive(), 100).block());
        Thread t3 = new Thread(() -> coordinator.loadChatsSequentially(
            new TdApi.ChatListMain(), 200).block());
        t1.start();
        t2.start();
        t3.start();
        Thread.sleep(500);
        proceed.countDown();
        joinThread(t1, 10, TimeUnit.SECONDS);
        joinThread(t2, 10, TimeUnit.SECONDS);
        joinThread(t3, 10, TimeUnit.SECONDS);
        assertThat(maxConcurrent.get(), is(1));
    }

    @Test
    void loadChatsSequentiallyTimesOutWhenCannotAcquireSemaphore() {
        Semaphore blockedSemaphore = new Semaphore(0);
        TdLibOperationCoordinator blockedCoordinator =
            new TdLibOperationCoordinator(fakeClient, blockedSemaphore);
        fakeClient.setNextResponse(new TdApi.Ok());
        Duration shortTimeout = Duration.ofMillis(100);
        Mono<Void> result = blockedCoordinator.loadChatsSequentially(
            new TdApi.ChatListMain(),
            100,
            shortTimeout,
            Duration.ofSeconds(60));
        StepVerifier.create(result)
            .expectError(TdLibOperationCoordinator.TdLibOperationTimeoutException.class)
            .verify(Duration.ofSeconds(5));
    }

    @Test
    void loadChatsSequentiallyReleasesSemaphoreOnSuccess() {
        fakeClient.setNextResponse(new TdApi.Ok());
        coordinator.loadChatsSequentially(new TdApi.ChatListMain(), 100).block();
        fakeClient.setNextResponse(new TdApi.Ok());
        coordinator.loadChatsSequentially(new TdApi.ChatListArchive(), 100).block();
        assertThat(coordinator.getState(), is(TdLibOperationState.COMPLETED));
    }

    @Test
    void loadChatsSequentiallyReleasesSemaphoreOnError() {
        fakeClient.setNextError(new RuntimeException("Connection failed"));
        try {
            coordinator.loadChatsSequentially(new TdApi.ChatListMain(), 100).block();
        } catch (RuntimeException ignored) {
        }
        fakeClient.setNextResponse(new TdApi.Ok());
        coordinator.loadChatsSequentially(new TdApi.ChatListArchive(), 100).block();
        assertThat(coordinator.getState(), is(TdLibOperationState.COMPLETED));
    }

    @Test
    void loadChatsSequentiallyFormatsMainListOperationName() {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch complete = new CountDownLatch(1);
        fakeClient.setResponseProvider(() -> {
            started.countDown();
            awaitUninterruptibly(complete, 5, TimeUnit.SECONDS);
            return new TdApi.Ok();
        });
        Thread executor = new Thread(() -> {
            coordinator.loadChatsSequentially(new TdApi.ChatListMain(), 100).block();
        });
        executor.start();
        awaitUninterruptibly(started, 5, TimeUnit.SECONDS);
        assertThat(coordinator.getCurrentOperation(), is("LoadChats(ChatListMain)"));
        complete.countDown();
        joinThread(executor, 5, TimeUnit.SECONDS);
    }

    @Test
    void loadChatsSequentiallyFormatsArchiveListOperationName() {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch complete = new CountDownLatch(1);
        fakeClient.setResponseProvider(() -> {
            started.countDown();
            awaitUninterruptibly(complete, 5, TimeUnit.SECONDS);
            return new TdApi.Ok();
        });
        Thread executor = new Thread(() -> {
            coordinator.loadChatsSequentially(new TdApi.ChatListArchive(), 100).block();
        });
        executor.start();
        awaitUninterruptibly(started, 5, TimeUnit.SECONDS);
        assertThat(coordinator.getCurrentOperation(), is("LoadChats(ChatListArchive)"));
        complete.countDown();
        joinThread(executor, 5, TimeUnit.SECONDS);
    }

    @Test
    void loadChatsSequentiallyFormatsFolderListOperationName() {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch complete = new CountDownLatch(1);
        fakeClient.setResponseProvider(() -> {
            started.countDown();
            awaitUninterruptibly(complete, 5, TimeUnit.SECONDS);
            return new TdApi.Ok();
        });
        Thread executor = new Thread(() -> {
            coordinator.loadChatsSequentially(new TdApi.ChatListFolder(42), 100).block();
        });
        executor.start();
        awaitUninterruptibly(started, 5, TimeUnit.SECONDS);
        assertThat(coordinator.getCurrentOperation(), is("LoadChats(ChatListFolder:42)"));
        complete.countDown();
        joinThread(executor, 5, TimeUnit.SECONDS);
    }

    @Test
    void isTdLibReadyReturnsTrueWhenAuthorized() {
        fakeClient.setNextResponse(new TdApi.AuthorizationStateReady());
        StepVerifier.create(coordinator.isTdLibReady())
            .expectNext(true)
            .verifyComplete();
    }

    @Test
    void isTdLibReadyReturnsFalseWhenNotAuthorized() {
        fakeClient.setNextResponse(new TdApi.AuthorizationStateWaitPhoneNumber());
        StepVerifier.create(coordinator.isTdLibReady())
            .expectNext(false)
            .verifyComplete();
    }

    @Test
    void isTdLibReadyReturnsFalseOnError() {
        fakeClient.setNextError(new RuntimeException("Connection error"));
        StepVerifier.create(coordinator.isTdLibReady())
            .expectNext(false)
            .verifyComplete();
    }

    @Test
    void forceReleaseIfStuckReturnsFalseWhenNoOperationRunning() {
        boolean released = coordinator.forceReleaseIfStuck(Duration.ofMillis(100));
        assertThat(released, is(false));
    }

    @Test
    void forceReleaseIfStuckReturnsFalseWhenOperationNotStuck() {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch complete = new CountDownLatch(1);
        fakeClient.setResponseProvider(() -> {
            started.countDown();
            awaitUninterruptibly(complete, 5, TimeUnit.SECONDS);
            return new TdApi.Ok();
        });
        Thread executor = new Thread(() -> {
            coordinator.loadChatsSequentially(new TdApi.ChatListMain(), 100).block();
        });
        executor.start();
        awaitUninterruptibly(started, 5, TimeUnit.SECONDS);
        boolean released = coordinator.forceReleaseIfStuck(Duration.ofHours(1));
        assertThat(released, is(false));
        complete.countDown();
        joinThread(executor, 5, TimeUnit.SECONDS);
    }

    @Test
    void forceReleaseIfStuckReleasesSemaphoreWhenOperationExceedsThreshold() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean shouldComplete = new AtomicBoolean(false);
        fakeClient.setResponseProvider(() -> {
            started.countDown();
            while (!shouldComplete.get()) {
                Thread.sleep(10);
            }
            return new TdApi.Ok();
        });
        Thread executor = new Thread(() -> {
            try {
                coordinator.loadChatsSequentially(new TdApi.ChatListMain(), 100).block();
            } catch (Exception ignored) {
            }
        });
        executor.start();
        awaitUninterruptibly(started, 5, TimeUnit.SECONDS);
        Thread.sleep(150);
        boolean released = coordinator.forceReleaseIfStuck(Duration.ofMillis(100));
        assertThat(released, is(true));
        assertThat(coordinator.getState(), is(TdLibOperationState.ERROR));
        shouldComplete.set(true);
        joinThread(executor, 5, TimeUnit.SECONDS);
    }

    @Test
    void operationDurationIncreasesWhileOperationRunning() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch complete = new CountDownLatch(1);
        fakeClient.setResponseProvider(() -> {
            started.countDown();
            awaitUninterruptibly(complete, 5, TimeUnit.SECONDS);
            return new TdApi.Ok();
        });
        Thread executor = new Thread(() -> {
            coordinator.loadChatsSequentially(new TdApi.ChatListMain(), 100).block();
        });
        executor.start();
        awaitUninterruptibly(started, 5, TimeUnit.SECONDS);
        Duration initialDuration = coordinator.getCurrentOperationDuration();
        Thread.sleep(100);
        Duration laterDuration = coordinator.getCurrentOperationDuration();
        assertThat(laterDuration.toMillis(), greaterThan(initialDuration.toMillis()));
        complete.countDown();
        joinThread(executor, 5, TimeUnit.SECONDS);
    }

    private void awaitUninterruptibly(CountDownLatch latch, long timeout, TimeUnit unit) {
        try {
            latch.await(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void joinThread(Thread thread, long timeout, TimeUnit unit) {
        try {
            thread.join(unit.toMillis(timeout));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Fake TelegramClientFacade for testing that allows controlled responses.
     */
    private static final class FakeTelegramClient implements TelegramClientFacade {

        private volatile TdApi.Object nextResponse;
        private volatile Throwable nextError;
        private volatile ResponseProvider responseProvider;

        void setNextResponse(TdApi.Object response) {
            this.nextResponse = response;
            this.nextError = null;
            this.responseProvider = null;
        }

        void setNextError(Throwable error) {
            this.nextError = error;
            this.nextResponse = null;
            this.responseProvider = null;
        }

        void setResponseProvider(ResponseProvider provider) {
            this.responseProvider = provider;
            this.nextResponse = null;
            this.nextError = null;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T extends TdApi.Object> CompletableFuture<T> send(TdApi.Function<T> function) {
            if (responseProvider != null) {
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        return (T) responseProvider.provide();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            }
            if (nextError != null) {
                CompletableFuture<T> future = new CompletableFuture<>();
                future.completeExceptionally(nextError);
                return future;
            }
            return CompletableFuture.completedFuture((T) nextResponse);
        }

        @Override
        public <T extends TdApi.Object> void send(
                TdApi.Function<T> function,
                it.tdlight.client.GenericResultHandler<T> handler) {
            throw new UnsupportedOperationException("Not implemented for test");
        }

        @Override
        public <T extends TdApi.Update> void addUpdateHandler(
                Class<T> type,
                it.tdlight.client.GenericUpdateHandler<? super T> handler) {
        }

        @Override
        public void addUpdatesHandler(
                it.tdlight.client.GenericUpdateHandler<TdApi.Update> handler) {
        }

        @Override
        public void addUpdateExceptionHandler(it.tdlight.ExceptionHandler handler) {
        }

        @Override
        public void addDefaultExceptionHandler(it.tdlight.ExceptionHandler handler) {
        }

        @Override
        public void addCommandHandler(String command, it.tdlight.client.CommandHandler handler) {
        }

        interface ResponseProvider {
            TdApi.Object provide() throws Exception;
        }
    }
}
