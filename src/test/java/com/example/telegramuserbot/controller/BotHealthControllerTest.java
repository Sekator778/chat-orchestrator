package com.example.telegramuserbot.controller;

import com.example.telegramuserbot.dto.BotHealthResponse;
import com.example.telegramuserbot.service.BotHealthService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;

import java.util.List;

import static org.mockito.Mockito.when;

/**
 * Contract for {@link BotHealthController}: the endpoint delegates to
 * {@link BotHealthService} and emits its snapshot through a {@code Mono}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BotHealthController")
class BotHealthControllerTest {

    @Mock
    private BotHealthService botHealthService;

    @Test
    @DisplayName("GET /api/bots/health returns the health snapshot from the service")
    void healthReturnsServiceSnapshot() {
        BotHealthResponse snapshot = new BotHealthResponse("UP", 1, 1, false, List.of());
        when(botHealthService.getBotHealth()).thenReturn(snapshot);

        BotHealthController controller = new BotHealthController(botHealthService);

        StepVerifier.create(controller.health())
                .expectNext(snapshot)
                .verifyComplete();
    }
}
