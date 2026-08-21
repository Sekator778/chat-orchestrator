package com.example.telegramuserbot.service.channels;

import com.example.telegramuserbot.dto.ChannelActivityEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec;
import org.springframework.r2dbc.core.FetchSpec;
import org.springframework.r2dbc.core.RowsFetchSpec;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ChannelActivityService}.
 *
 * <p>Uses Mockito to stub {@link DatabaseClient} and verify the service maps
 * aggregate rows to {@link ChannelActivityEntry} correctly without requiring
 * a live database.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChannelActivityService")
class ChannelActivityServiceTest {

    @Mock
    private DatabaseClient databaseClient;

    @Mock
    private GenericExecuteSpec executeSpec;

    @SuppressWarnings("rawtypes")
    @Mock
    private RowsFetchSpec rowsFetchSpec;

    private ChannelActivityService service;

    @BeforeEach
    void setUp() {
        service = new ChannelActivityService(databaseClient);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Configures the DatabaseClient mock chain and returns the RowsFetchSpec mock
     * that the service's .map(...).all() call will delegate to.
     */
    @SuppressWarnings("unchecked")
    private void givenDatabaseClientReturns(Flux<ChannelActivityEntry> flux) {
        when(databaseClient.sql(anyString())).thenReturn(executeSpec);
        when(executeSpec.bind(anyString(), any())).thenReturn(executeSpec);
        when(executeSpec.map(any(BiFunction.class))).thenReturn(rowsFetchSpec);
        when(rowsFetchSpec.all()).thenReturn((Flux) flux);
    }

    // -------------------------------------------------------------------------
    // Tests — happy path (active channel)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("active channel: returns entry with messageCount > 0 and lastActivityAt set")
    void activeChannel_returnsCountAndLastActivity() {
        Instant lastActivity = Instant.parse("2026-06-01T10:00:00Z");
        ChannelActivityEntry active = new ChannelActivityEntry(
                -1001234567890L, "Tech News", 42L, lastActivity);

        givenDatabaseClientReturns(Flux.just(active));

        StepVerifier.create(service.reportActivity(7))
                .assertNext(entry -> {
                    assertThat(entry.chatId()).isEqualTo(-1001234567890L);
                    assertThat(entry.channelTitle()).isEqualTo("Tech News");
                    assertThat(entry.messageCount()).isEqualTo(42L);
                    assertThat(entry.lastActivityAt()).isEqualTo(lastActivity);
                })
                .verifyComplete();
    }

    // -------------------------------------------------------------------------
    // Tests — silent channel (zero messages in window)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("silent channel: appears with messageCount=0 and lastActivityAt=null (FR-006)")
    void silentChannel_appearsWithZeroCountAndNullTimestamp() {
        ChannelActivityEntry silent = new ChannelActivityEntry(
                -1009876543210L, "Silent Channel", 0L, null);

        givenDatabaseClientReturns(Flux.just(silent));

        StepVerifier.create(service.reportActivity(7))
                .assertNext(entry -> {
                    assertThat(entry.chatId()).isEqualTo(-1009876543210L);
                    assertThat(entry.channelTitle()).isEqualTo("Silent Channel");
                    assertThat(entry.messageCount()).isZero();
                    assertThat(entry.lastActivityAt()).isNull();
                })
                .verifyComplete();
    }

    // -------------------------------------------------------------------------
    // Tests — mixed: active and silent channels both returned
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("mixed: active channel precedes silent channel in output")
    void mixedChannels_bothReturnedActiveFirst() {
        Instant lastActivity = Instant.parse("2026-06-02T08:00:00Z");
        ChannelActivityEntry active = new ChannelActivityEntry(-100111L, "Active", 5L, lastActivity);
        ChannelActivityEntry silent = new ChannelActivityEntry(-100222L, "Silent", 0L, null);

        givenDatabaseClientReturns(Flux.just(active, silent));

        StepVerifier.create(service.reportActivity(7))
                .assertNext(e -> assertThat(e.messageCount()).isPositive())
                .assertNext(e -> assertThat(e.messageCount()).isZero())
                .verifyComplete();
    }

    // -------------------------------------------------------------------------
    // Tests — null channelTitle (FR-007)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("channel with null title: channelTitle field is null in DTO (FR-007)")
    void channelWithNullTitle_channelTitleIsNull() {
        ChannelActivityEntry entry = new ChannelActivityEntry(-100333L, null, 3L,
                Instant.parse("2026-06-01T12:00:00Z"));

        givenDatabaseClientReturns(Flux.just(entry));

        StepVerifier.create(service.reportActivity(7))
                .assertNext(e -> assertThat(e.channelTitle()).isNull())
                .verifyComplete();
    }

    // -------------------------------------------------------------------------
    // Tests — `days` parameter is bound to the query
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("days parameter is forwarded as a binding to DatabaseClient")
    void daysParameterBoundToQuery() {
        givenDatabaseClientReturns(Flux.empty());

        service.reportActivity(14).blockFirst(); // subscribe to trigger execution

        verify(databaseClient).sql(anyString());
        verify(executeSpec).bind(eq("days"), eq(14));
    }

    // -------------------------------------------------------------------------
    // Tests — empty result (no channels at all)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("empty database: reportActivity emits empty Flux without error")
    void emptyDatabase_emitsEmptyFlux() {
        givenDatabaseClientReturns(Flux.empty());

        StepVerifier.create(service.reportActivity(7))
                .verifyComplete();
    }
}
