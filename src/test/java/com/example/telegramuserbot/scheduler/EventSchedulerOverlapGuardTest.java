package com.example.telegramuserbot.scheduler;

import com.example.telegramuserbot.repository.EventRepository;
import com.example.telegramuserbot.repository.PostedRepository;
import com.example.telegramuserbot.service.events.EventWatcherService;
import com.example.telegramuserbot.service.publishing.EventPublisherService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.r2dbc.repository.Query;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The event schedulers subscribe and return, so {@code @Scheduled} cannot serialize
 * them by itself: the next tick fires while the previous cycle is still in flight.
 * Combined with a status write that was not a compare-and-set, two overlapping
 * cycles could publish the same event twice — the send happens before the row that
 * would have blocked it.
 * <p>
 * These tests cover both halves of that fix: the re-entrancy guard in the
 * schedulers, and the compare-and-set in the queries the cycles write through.
 */
class EventSchedulerOverlapGuardTest {

    @Test
    @DisplayName("publisher skips a tick while the previous cycle is still running")
    void publisherDoesNotOverlap() {
        EventPublisherService publisher = mock(EventPublisherService.class);
        Sinks.One<Integer> inFlight = Sinks.one();
        when(publisher.process()).thenReturn(inFlight.asMono());

        EventPublisherScheduler scheduler =
                new EventPublisherScheduler(publisher, mock(PostedRepository.class));

        scheduler.publishEvents();
        scheduler.publishEvents();

        verify(publisher, times(1)).process();

        // Once the cycle finishes the guard clears and the next tick runs normally.
        inFlight.tryEmitValue(0);
        scheduler.publishEvents();
        verify(publisher, times(2)).process();
    }

    @Test
    @DisplayName("watcher skips a tick while the previous cycle is still running")
    void watcherDoesNotOverlap() {
        EventWatcherService watcher = mock(EventWatcherService.class);
        Sinks.One<Integer> inFlight = Sinks.one();
        when(watcher.process()).thenReturn(inFlight.asMono());

        EventWatcherScheduler scheduler = new EventWatcherScheduler(watcher);

        scheduler.processEvents();
        scheduler.processEvents();

        verify(watcher, times(1)).process();

        inFlight.tryEmitValue(0);
        scheduler.processEvents();
        verify(watcher, times(2)).process();
    }

    @Test
    @DisplayName("a failing cycle releases the guard")
    void guardIsReleasedOnError() {
        EventWatcherService watcher = mock(EventWatcherService.class);
        when(watcher.process()).thenReturn(Mono.error(new IllegalStateException("boom")));

        EventWatcherScheduler scheduler = new EventWatcherScheduler(watcher);

        scheduler.processEvents();
        scheduler.processEvents();

        // A stuck guard would silently freeze the pipeline until restart.
        verify(watcher, times(2)).process();
    }

    @Test
    @DisplayName("status transitions are compare-and-set")
    void statusUpdatesAreCompareAndSet() {
        assertThat(queryOf("updateEventStatus",
                Long.class, String.class, String.class, LocalDateTime.class))
                .as("without the expected-status predicate two cycles both 'win' the transition")
                .contains("WHERE id = :eventId AND status = :expectedStatus");

        assertThat(queryOf("updateEventStatusWithError",
                Long.class, String.class, String.class, String.class, LocalDateTime.class))
                .contains("WHERE id = :eventId AND status = :expectedStatus");
    }

    private static String queryOf(String method, Class<?>... parameterTypes) {
        try {
            Method target = EventRepository.class.getMethod(method, parameterTypes);
            Query query = target.getAnnotation(Query.class);
            assertThat(query).as("EventRepository.%s must carry an @Query", method).isNotNull();
            return query.value().replaceAll("\\s+", " ").trim();
        } catch (NoSuchMethodException e) {
            throw new AssertionError("EventRepository.%s is gone — move this test with it".formatted(method), e);
        }
    }
}
