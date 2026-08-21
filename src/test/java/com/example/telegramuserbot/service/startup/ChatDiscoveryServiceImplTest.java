package com.example.telegramuserbot.service.startup;

import com.example.telegramuserbot.service.TelegramClientManager;
import com.example.telegramuserbot.telegram.TdLibOperationCoordinator;
import com.example.telegramuserbot.telegram.TelegramClientFacade;
import it.tdlight.jni.TdApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ChatDiscoveryServiceImpl.
 * Tests sequential chat loading via TdLibOperationCoordinator.
 */
class ChatDiscoveryServiceImplTest {

    private ChatDiscoveryServiceImpl service;
    private FakeTelegramClient fakeClient;
    private TdLibOperationCoordinator mockCoordinator;
    private TelegramClientManager mockClientManager;

    @BeforeEach
    void setUp() {
        fakeClient = new FakeTelegramClient();
        mockCoordinator = mock(TdLibOperationCoordinator.class);
        mockClientManager = mock(TelegramClientManager.class);
        when(mockClientManager.getAnyClient()).thenReturn(fakeClient);
        when(mockCoordinator.isTdLibReady()).thenReturn(Mono.just(true));
        when(mockCoordinator.loadChatsSequentially(any(TdApi.ChatList.class), anyInt()))
            .thenReturn(Mono.empty());
        service = new ChatDiscoveryServiceImpl(mockClientManager, mockCoordinator);
    }

    @Test
    void loadAllChatsSequentiallyReturnsEmptyListWhenNoChats() {
        fakeClient.setChatsResponse(createChats(new long[0]));
        StepVerifier.create(service.loadAllChatsSequentially())
            .assertNext(chatIds -> assertThat(chatIds, empty()))
            .verifyComplete();
    }

    @Test
    void loadAllChatsSequentiallyReturnsChatsFromGetChats() {
        long[] expectedIds = {123L, 456L, 789L};
        fakeClient.setChatsResponse(createChats(expectedIds));
        StepVerifier.create(service.loadAllChatsSequentially())
            .assertNext(chatIds -> {
                assertThat(chatIds, hasSize(3));
                assertThat(chatIds, containsInAnyOrder(123L, 456L, 789L));
            })
            .verifyComplete();
    }

    @Test
    void loadAllChatsSequentiallyDeduplicatesChatsFromMainAndArchive() {
        long[] mainIds = {100L, 200L, 300L};
        long[] archiveIds = {200L, 400L};
        fakeClient.setDualChatsResponse(createChats(mainIds), createChats(archiveIds));
        StepVerifier.create(service.loadAllChatsSequentially())
            .assertNext(chatIds -> {
                assertThat(chatIds, hasSize(4));
                assertThat(chatIds, containsInAnyOrder(100L, 200L, 300L, 400L));
            })
            .verifyComplete();
    }

    @Test
    void loadAllChatsSequentiallyCallsCoordinatorForLoadChats() {
        fakeClient.setChatsResponse(createChats(new long[0]));
        service.loadAllChatsSequentially().block();
        verify(mockCoordinator, times(2)).loadChatsSequentially(any(TdApi.ChatList.class), anyInt());
    }

    @Test
    void loadAllChatsSequentiallyCallsLoadChatsInSequentialOrder() {
        fakeClient.setChatsResponse(createChats(new long[0]));
        service.loadAllChatsSequentially().block();
        verify(mockCoordinator).loadChatsSequentially(argThat(arg -> arg instanceof TdApi.ChatListMain), eq(1000));
        verify(mockCoordinator).loadChatsSequentially(argThat(arg -> arg instanceof TdApi.ChatListArchive), eq(1000));
    }

    @Test
    void loadAllChatsSequentiallyHandlesGetChatsError() {
        fakeClient.setGetChatsError(new RuntimeException("GetChats failed"));
        StepVerifier.create(service.loadAllChatsSequentially())
            .assertNext(chatIds -> assertThat(chatIds, empty()))
            .verifyComplete();
    }

    @Test
    void loadAllChatsSequentiallyHandlesLoadChatsError() {
        fakeClient.setChatsResponse(createChats(new long[]{100L}));
        when(mockCoordinator.loadChatsSequentially(any(TdApi.ChatList.class), anyInt()))
            .thenReturn(Mono.error(new RuntimeException("LoadChats failed")));
        StepVerifier.create(service.loadAllChatsSequentially())
            .assertNext(chatIds -> {
                assertThat(chatIds, hasSize(1));
                assertThat(chatIds, contains(100L));
            })
            .verifyComplete();
    }

    @Test
    void loadAllChatsSequentiallyReceivesChatsFromUpdateHandler() {
        fakeClient.setChatsResponse(createChats(new long[0]));
        Mono<List<Long>> result = service.loadAllChatsSequentially();
        TdApi.Chat simulatedChat = new TdApi.Chat();
        simulatedChat.id = 999L;
        simulatedChat.title = "Simulated Chat";
        simulatedChat.type = new TdApi.ChatTypePrivate();
        fakeClient.simulateUpdateNewChat(simulatedChat);
        StepVerifier.create(result)
            .assertNext(chatIds -> assertThat(chatIds, contains(999L)))
            .verifyComplete();
    }

    @Test
    void loadAllChatsSequentiallyDeduplicatesUpdateHandlerChats() {
        long[] initialIds = {100L};
        fakeClient.setChatsResponse(createChats(initialIds));
        Mono<List<Long>> result = service.loadAllChatsSequentially();
        TdApi.Chat duplicateChat = new TdApi.Chat();
        duplicateChat.id = 100L;
        duplicateChat.title = "Duplicate Chat";
        duplicateChat.type = new TdApi.ChatTypePrivate();
        fakeClient.simulateUpdateNewChat(duplicateChat);
        StepVerifier.create(result)
            .assertNext(chatIds -> {
                assertThat(chatIds, hasSize(1));
                assertThat(chatIds, contains(100L));
            })
            .verifyComplete();
    }

    @Test
    void discoverAvailableChatsChecksCoordinatorReadiness() {
        when(mockCoordinator.isTdLibReady()).thenReturn(Mono.just(false));
        StepVerifier.create(service.discoverAvailableChats().collectList())
            .assertNext(chats -> assertThat(chats, empty()))
            .verifyComplete();
    }

    @Test
    void discoverAvailableChatsProceedsWhenCoordinatorReady() {
        when(mockCoordinator.isTdLibReady()).thenReturn(Mono.just(true));
        fakeClient.setChatsResponse(createChats(new long[0]));
        StepVerifier.create(service.discoverAvailableChats().collectList())
            .assertNext(chats -> assertThat(chats, empty()))
            .verifyComplete();
    }

    @Test
    @Timeout(10)
    void loadAllChatsSequentiallyCompletesWithinTimeout() {
        fakeClient.setChatsResponse(createChats(new long[]{1L, 2L, 3L}));
        List<Long> result = service.loadAllChatsSequentially().block();
        assertThat(result, hasSize(3));
    }

    @Test
    void loadAllChatsSequentiallyReturnsNewArrayList() {
        fakeClient.setChatsResponse(createChats(new long[]{1L}));
        List<Long> result = service.loadAllChatsSequentially().block();
        assertThat(result, instanceOf(java.util.ArrayList.class));
    }

    @Test
    void loadChatsMainCalledBeforeArchive() {
        fakeClient.setChatsResponse(createChats(new long[0]));
        AtomicInteger mainCallOrder = new AtomicInteger(-1);
        AtomicInteger archiveCallOrder = new AtomicInteger(-1);
        AtomicInteger callCounter = new AtomicInteger(0);
        when(mockCoordinator.loadChatsSequentially(any(TdApi.ChatList.class), anyInt()))
            .thenAnswer(invocation -> {
                TdApi.ChatList list = invocation.getArgument(0);
                int order = callCounter.getAndIncrement();
                if (list instanceof TdApi.ChatListMain) {
                    mainCallOrder.set(order);
                } else if (list instanceof TdApi.ChatListArchive) {
                    archiveCallOrder.set(order);
                }
                return Mono.empty();
            });
        service.loadAllChatsSequentially().block();
        assertThat(mainCallOrder.get(), is(0));
        assertThat(archiveCallOrder.get(), is(1));
    }

    private static TdApi.Chats createChats(long[] chatIds) {
        TdApi.Chats chats = new TdApi.Chats();
        chats.chatIds = chatIds;
        chats.totalCount = chatIds.length;
        return chats;
    }

    /**
     * Fake TelegramClientFacade for testing.
     */
    private static final class FakeTelegramClient implements TelegramClientFacade {

        private volatile TdApi.Chats mainChatsResponse;
        private volatile TdApi.Chats archiveChatsResponse;
        private volatile Throwable getChatsError;
        private final List<Consumer<TdApi.Update>> updateHandlers = new CopyOnWriteArrayList<>();

        void setChatsResponse(TdApi.Chats response) {
            this.mainChatsResponse = response;
            this.archiveChatsResponse = response;
            this.getChatsError = null;
        }

        void setDualChatsResponse(TdApi.Chats main, TdApi.Chats archive) {
            this.mainChatsResponse = main;
            this.archiveChatsResponse = archive;
            this.getChatsError = null;
        }

        void setGetChatsError(Throwable error) {
            this.getChatsError = error;
            this.mainChatsResponse = null;
            this.archiveChatsResponse = null;
        }

        void simulateUpdateNewChat(TdApi.Chat chat) {
            TdApi.UpdateNewChat update = new TdApi.UpdateNewChat();
            update.chat = chat;
            for (Consumer<TdApi.Update> handler : updateHandlers) {
                handler.accept(update);
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T extends TdApi.Object> CompletableFuture<T> send(TdApi.Function<T> function) {
            if (getChatsError != null) {
                CompletableFuture<T> future = new CompletableFuture<>();
                future.completeExceptionally(getChatsError);
                return future;
            }
            if (function instanceof TdApi.GetChats) {
                TdApi.GetChats getChats = (TdApi.GetChats) function;
                TdApi.Chats response;
                if (getChats.chatList instanceof TdApi.ChatListArchive) {
                    response = archiveChatsResponse != null ? archiveChatsResponse : createEmptyChats();
                } else {
                    response = mainChatsResponse != null ? mainChatsResponse : createEmptyChats();
                }
                return CompletableFuture.completedFuture((T) response);
            }
            return CompletableFuture.completedFuture((T) new TdApi.Ok());
        }

        private static TdApi.Chats createEmptyChats() {
            TdApi.Chats chats = new TdApi.Chats();
            chats.chatIds = new long[0];
            chats.totalCount = 0;
            return chats;
        }

        @Override
        public <T extends TdApi.Object> void send(
                TdApi.Function<T> function,
                it.tdlight.client.GenericResultHandler<T> handler) {
            throw new UnsupportedOperationException("Not implemented for test");
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T extends TdApi.Update> void addUpdateHandler(
                Class<T> type,
                it.tdlight.client.GenericUpdateHandler<? super T> handler) {
            if (type == TdApi.UpdateNewChat.class) {
                updateHandlers.add(update -> {
                    if (type.isInstance(update)) {
                        handler.onUpdate((T) update);
                    }
                });
            }
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
    }
}
