package com.example.telegramuserbot.service.queue;

import com.example.telegramuserbot.domain.MessageEntity;
import com.example.telegramuserbot.domain.PendingResponse;
import com.example.telegramuserbot.domain.PendingResponseStatus;
import com.example.telegramuserbot.service.TelegramClientManager;
import com.example.telegramuserbot.service.persistence.MessagePersistenceService;
import com.example.telegramuserbot.service.publishing.HumanSendPacer;
import com.example.telegramuserbot.service.safety.OutboundReplyGuard;
import com.example.telegramuserbot.telegram.TelegramClientFacade;
import it.tdlight.ExceptionHandler;
import it.tdlight.client.CommandHandler;
import it.tdlight.client.GenericResultHandler;
import it.tdlight.client.GenericUpdateHandler;
import it.tdlight.client.Result;
import it.tdlight.jni.TdApi;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WP3: {@link PendingResponseScheduler#processPendingQueue()} must serialize two queued replies
 * of the SAME persona in the SAME chat (never in the same second — the real-data scorecard found
 * 9 same-second duplicate-send pairs), while replies for DIFFERENT chats still run concurrently.
 * {@link HumanSendPacer} is mocked out here (its own pacing math is covered by
 * {@code HumanSendPacerTest}); this test is only about the scheduler's grouping/concurrency shape.
 */
class PendingResponseSchedulerGroupingTest {

    @Test
    void sameChatAndPersonaRowsAreSentOneAfterAnother() throws InterruptedException {
        PendingResponse first = pending(1L, -100L, "bot-a", "first");
        PendingResponse second = pending(2L, -100L, "bot-a", "second");

        SendRecorder recorder = new SendRecorder(Duration.ofMillis(250));
        CountDownLatch done = new CountDownLatch(2);
        PendingResponseScheduler scheduler = buildScheduler(List.of(first, second), recorder, done);

        scheduler.processPendingQueue();

        assertThat(done.await(5, TimeUnit.SECONDS)).as("both sends must complete").isTrue();
        assertThat(recorder.starts).hasSize(2);

        long gapMs = Duration.between(recorder.starts.get(0), recorder.starts.get(1)).abs().toMillis();
        assertThat(gapMs)
                .as("the second send in the same (chat,bot) group must wait for the first to fully finish")
                .isGreaterThanOrEqualTo(200L); // artificial 250ms send delay, with slack for scheduling jitter
    }

    @Test
    void differentChatsProceedConcurrently() throws InterruptedException {
        PendingResponse chatA = pending(1L, -100L, "bot-a", "a");
        PendingResponse chatB = pending(2L, -200L, "bot-b", "b");

        SendRecorder recorder = new SendRecorder(Duration.ofMillis(300));
        CountDownLatch done = new CountDownLatch(2);
        PendingResponseScheduler scheduler = buildScheduler(List.of(chatA, chatB), recorder, done);

        scheduler.processPendingQueue();

        assertThat(done.await(5, TimeUnit.SECONDS)).as("both sends must complete").isTrue();
        assertThat(recorder.starts).hasSize(2);

        long gapMs = Duration.between(recorder.starts.get(0), recorder.starts.get(1)).abs().toMillis();
        assertThat(gapMs)
                .as("different-chat groups must run in parallel, not wait on each other")
                .isLessThan(150L); // well under the 300ms artificial send delay of either group
    }

    // --- fixtures --------------------------------------------------------------------------

    private static PendingResponse pending(Long id, Long chatId, String botId, String text) {
        PendingResponse p = new PendingResponse();
        p.setId(id);
        p.setChatId(chatId);
        p.setBotInstanceId(botId);
        p.setTriggeringMessageId(9000L + id);
        p.setPreparedResponse(text);
        p.setStatus(PendingResponseStatus.ELIGIBLE);
        return p;
    }

    private PendingResponseScheduler buildScheduler(List<PendingResponse> rows, SendRecorder recorder, CountDownLatch done) {
        PendingResponseService pendingResponseService = mock(PendingResponseService.class);
        when(pendingResponseService.findPendingThatReachedThreshold()).thenReturn(Flux.empty());
        when(pendingResponseService.claimEligibleResponses(anyInt())).thenReturn(Flux.fromIterable(rows));
        when(pendingResponseService.markAsSent(any())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            done.countDown();
            return Mono.just(rows.stream().filter(r -> r.getId().equals(id)).findFirst().orElseThrow());
        });

        TelegramClientManager clientManager = mock(TelegramClientManager.class);
        for (PendingResponse row : rows) {
            when(clientManager.getClient(row.getBotInstanceId())).thenReturn(recorder.clientFor(row.getBotInstanceId()));
        }

        MessagePersistenceService messagePersistenceService = mock(MessagePersistenceService.class);
        when(messagePersistenceService.persistMessage(anyString(), anyLong(), any()))
                .thenReturn(Mono.just(mock(MessageEntity.class)));

        OutboundReplyGuard outboundReplyGuard = mock(OutboundReplyGuard.class);
        when(outboundReplyGuard.shouldSuppress(anyString())).thenReturn(false);

        HumanSendPacer pacer = mock(HumanSendPacer.class);
        when(pacer.pace(anyString(), anyLong(), anyString(), any())).thenReturn(Mono.empty());

        PendingResponseScheduler scheduler = new PendingResponseScheduler(
                pendingResponseService, clientManager, messagePersistenceService, outboundReplyGuard, pacer);
        // @Value fields are only populated by Spring; this test builds the scheduler directly,
        // so set send-concurrency by hand (default 0 would serialize even different-chat groups).
        ReflectionTestUtils.setField(scheduler, "sendConcurrency", 8);
        ReflectionTestUtils.setField(scheduler, "claimLimitMultiplier", 4);
        return scheduler;
    }

    /** Fakes TDLib sends: records the wall-clock start of each SendMessage and blocks for a fixed delay. */
    private static final class SendRecorder {
        final List<Instant> starts = new CopyOnWriteArrayList<>();
        private final Duration sendDelay;
        private final ConcurrentHashMap<String, TelegramClientFacade> clients = new ConcurrentHashMap<>();

        SendRecorder(Duration sendDelay) {
            this.sendDelay = sendDelay;
        }

        TelegramClientFacade clientFor(String botId) {
            return clients.computeIfAbsent(botId, id -> new TelegramClientFacade() {
                @Override
                public <T extends TdApi.Object> CompletableFuture<T> send(TdApi.Function<T> function) {
                    return CompletableFuture.completedFuture(null);
                }

                @Override
                @SuppressWarnings("unchecked")
                public <T extends TdApi.Object> void send(TdApi.Function<T> function, GenericResultHandler<T> handler) {
                    if (function instanceof TdApi.SendMessage sendMessage) {
                        starts.add(Instant.now());
                        // Complete asynchronously after sendDelay, like a real TDLib network round-trip —
                        // a synchronous Thread.sleep here would block the calling thread and mask any
                        // concurrency the scheduler's flatMap is (or isn't) actually providing.
                        CompletableFuture.runAsync(() -> {
                            TdApi.Message msg = new TdApi.Message();
                            msg.chatId = sendMessage.chatId;
                            msg.id = System.nanoTime();
                            handler.onResult(Result.of((T) msg));
                        }, CompletableFuture.delayedExecutor(sendDelay.toMillis(), TimeUnit.MILLISECONDS));
                        return;
                    }
                    handler.onResult(Result.of((T) new TdApi.Ok()));
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
            });
        }
    }
}
