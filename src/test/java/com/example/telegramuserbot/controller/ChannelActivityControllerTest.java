package com.example.telegramuserbot.controller;

import com.example.telegramuserbot.dto.ChannelActivityEntry;
import com.example.telegramuserbot.service.channels.ChannelActivityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ChannelActivityController}.
 *
 * <p>Uses Mockito to stub {@link ChannelActivityService} and drives the controller
 * directly via {@code StepVerifier}, following the {@link BotHealthControllerTest}
 * convention. No Spring application context is loaded.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ChannelActivityController")
class ChannelActivityControllerTest {

    @Mock
    private ChannelActivityService channelActivityService;

    private ChannelActivityController controller;

    @BeforeEach
    void setUp() {
        controller = new ChannelActivityController(channelActivityService);
    }

    // -------------------------------------------------------------------------
    // AC-FR-001/002 — valid days, default days
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET ?days=7 returns HTTP 200 with JSON array (FR-001, FR-002)")
    void validDays_returns200WithList() {
        Instant lastActivity = Instant.parse("2026-06-01T10:00:00Z");
        ChannelActivityEntry entry = new ChannelActivityEntry(-100111L, "Tech", 5L, lastActivity);
        when(channelActivityService.reportActivity(7)).thenReturn(Flux.just(entry));

        StepVerifier.create(controller.activity(7))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                    @SuppressWarnings("unchecked")
                    List<ChannelActivityEntry> body = (List<ChannelActivityEntry>) response.getBody();
                    assertThat(body).hasSize(1);
                    assertThat(body.get(0).chatId()).isEqualTo(-100111L);
                    assertThat(body.get(0).channelTitle()).isEqualTo("Tech");
                    assertThat(body.get(0).messageCount()).isEqualTo(5L);
                    assertThat(body.get(0).lastActivityAt()).isEqualTo(lastActivity);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("GET with no days param (default=7) returns HTTP 200 (FR-002)")
    void defaultDays_returns200() {
        when(channelActivityService.reportActivity(7)).thenReturn(Flux.empty());

        // The controller default wires days=7; we call with 7 to simulate the default
        StepVerifier.create(controller.activity(7))
                .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK))
                .verifyComplete();
    }

    @Test
    @DisplayName("GET ?days=365 returns HTTP 200 (boundary — upper limit)")
    void days365_returns200() {
        when(channelActivityService.reportActivity(365)).thenReturn(Flux.empty());

        StepVerifier.create(controller.activity(365))
                .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK))
                .verifyComplete();
    }

    @Test
    @DisplayName("GET ?days=1 returns HTTP 200 (boundary — lower limit)")
    void days1_returns200() {
        when(channelActivityService.reportActivity(1)).thenReturn(Flux.empty());

        StepVerifier.create(controller.activity(1))
                .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK))
                .verifyComplete();
    }

    // -------------------------------------------------------------------------
    // AC-FR-003 — invalid days → HTTP 400
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET ?days=0 returns HTTP 400 with validRange in body (FR-003)")
    void days0_returns400() {
        StepVerifier.create(controller.activity(0))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> body = (Map<String, Object>) response.getBody();
                    assertThat(body).containsKey("error");
                    assertThat(body).containsKey("validRange");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("GET ?days=366 returns HTTP 400 with validRange in body (FR-003)")
    void days366_returns400() {
        StepVerifier.create(controller.activity(366))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> body = (Map<String, Object>) response.getBody();
                    assertThat(body).containsKey("error");
                    assertThat(body).containsKey("validRange");
                    @SuppressWarnings("unchecked")
                    List<Integer> range = (List<Integer>) body.get("validRange");
                    assertThat(range).containsExactly(1, 365);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("GET ?days=-5 returns HTTP 400 (FR-003 — negative value)")
    void daysNegative_returns400() {
        StepVerifier.create(controller.activity(-5))
                .assertNext(response -> assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST))
                .verifyComplete();
    }

    // -------------------------------------------------------------------------
    // FR-013 — database error → HTTP 503
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("service Flux error returns HTTP 503 with error body (FR-013)")
    void serviceError_returns503() {
        when(channelActivityService.reportActivity(7))
                .thenReturn(Flux.error(new RuntimeException("DB connection lost")));

        StepVerifier.create(controller.activity(7))
                .assertNext(response -> {
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> body = (Map<String, Object>) response.getBody();
                    assertThat(body).containsKey("error");
                })
                .verifyComplete();
    }

    // -------------------------------------------------------------------------
    // FR-004/FR-008 — response shape and ordering preserved from service
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("response preserves ordering emitted by service (FR-008)")
    void responsePreservesOrder() {
        Instant t1 = Instant.parse("2026-06-02T10:00:00Z");
        Instant t2 = Instant.parse("2026-06-01T10:00:00Z");
        ChannelActivityEntry first = new ChannelActivityEntry(-100111L, "Most Active", 10L, t1);
        ChannelActivityEntry second = new ChannelActivityEntry(-100222L, "Less Active", 3L, t2);
        ChannelActivityEntry silent = new ChannelActivityEntry(-100333L, "Silent", 0L, null);
        when(channelActivityService.reportActivity(7)).thenReturn(Flux.just(first, second, silent));

        StepVerifier.create(controller.activity(7))
                .assertNext(response -> {
                    @SuppressWarnings("unchecked")
                    List<ChannelActivityEntry> body = (List<ChannelActivityEntry>) response.getBody();
                    assertThat(body).hasSize(3);
                    assertThat(body.get(0).messageCount()).isEqualTo(10L);
                    assertThat(body.get(1).messageCount()).isEqualTo(3L);
                    assertThat(body.get(2).messageCount()).isZero();
                    assertThat(body.get(2).lastActivityAt()).isNull();
                })
                .verifyComplete();
    }

    // -------------------------------------------------------------------------
    // AC-FR-004 — response fields
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("response entry has chatId, channelTitle, messageCount, lastActivityAt (AC-FR-004)")
    void responseEntryHasExactlyRequiredFields() {
        Instant ts = Instant.parse("2026-06-01T09:00:00Z");
        ChannelActivityEntry entry = new ChannelActivityEntry(-100444L, "My Channel", 3L, ts);
        when(channelActivityService.reportActivity(7)).thenReturn(Flux.just(entry));

        StepVerifier.create(controller.activity(7))
                .assertNext(response -> {
                    @SuppressWarnings("unchecked")
                    List<ChannelActivityEntry> body = (List<ChannelActivityEntry>) response.getBody();
                    ChannelActivityEntry e = body.get(0);
                    assertThat(e.chatId()).isEqualTo(-100444L);
                    assertThat(e.channelTitle()).isEqualTo("My Channel");
                    assertThat(e.messageCount()).isEqualTo(3L);
                    assertThat(e.lastActivityAt()).isEqualTo(ts);
                })
                .verifyComplete();
    }
}
