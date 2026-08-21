package com.example.telegramuserbot.service;

import com.example.telegramuserbot.config.BotInstanceProvider;
import com.example.telegramuserbot.dto.BotHealthResponse;
import com.example.telegramuserbot.dto.BotStatus;
import com.example.telegramuserbot.telegram.TelegramClientFacade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Contract for {@link BotHealthService}: the health snapshot is derived from
 * the configured bot ids and which of them have an initialized TDLib client.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BotHealthService")
class BotHealthServiceTest {

    @Mock
    private BotInstanceProvider botInstanceProvider;
    @Mock
    private TelegramClientManager telegramClientManager;

    private BotHealthService service() {
        return new BotHealthService(botInstanceProvider, telegramClientManager);
    }

    @Test
    @DisplayName("reports UP when every configured bot has an initialized client")
    void allInitialized() {
        when(botInstanceProvider.getInstanceIds()).thenReturn(List.of("100", "200"));
        when(botInstanceProvider.getInstanceId()).thenReturn("100");
        when(telegramClientManager.getClient("100")).thenReturn(mock(TelegramClientFacade.class));
        when(telegramClientManager.getClient("200")).thenReturn(mock(TelegramClientFacade.class));
        when(telegramClientManager.hasPendingSecondaryClients()).thenReturn(false);

        BotHealthResponse health = service().getBotHealth();

        assertThat(health.status()).isEqualTo("UP");
        assertThat(health.configuredCount()).isEqualTo(2);
        assertThat(health.initializedCount()).isEqualTo(2);
        assertThat(health.pendingSecondary()).isFalse();
        assertThat(health.bots()).hasSize(2);
    }

    @Test
    @DisplayName("reports DEGRADED when only some configured bots are initialized")
    void someInitialized() {
        when(botInstanceProvider.getInstanceIds()).thenReturn(List.of("100", "200"));
        when(botInstanceProvider.getInstanceId()).thenReturn("100");
        when(telegramClientManager.getClient("100")).thenReturn(mock(TelegramClientFacade.class));
        when(telegramClientManager.hasPendingSecondaryClients()).thenReturn(true);

        BotHealthResponse health = service().getBotHealth();

        assertThat(health.status()).isEqualTo("DEGRADED");
        assertThat(health.configuredCount()).isEqualTo(2);
        assertThat(health.initializedCount()).isEqualTo(1);
        assertThat(health.pendingSecondary()).isTrue();
    }

    @Test
    @DisplayName("reports DOWN when no configured bot has an initialized client")
    void noneInitialized() {
        when(botInstanceProvider.getInstanceIds()).thenReturn(List.of("100", "200"));
        when(botInstanceProvider.getInstanceId()).thenReturn("100");

        BotHealthResponse health = service().getBotHealth();

        assertThat(health.status()).isEqualTo("DOWN");
        assertThat(health.initializedCount()).isZero();
        assertThat(health.bots()).hasSize(2);
    }

    @Test
    @DisplayName("marks the primary bot and sets per-bot status")
    void perBotStatus() {
        when(botInstanceProvider.getInstanceIds()).thenReturn(List.of("100", "200"));
        when(botInstanceProvider.getInstanceId()).thenReturn("100");
        when(telegramClientManager.getClient("100")).thenReturn(mock(TelegramClientFacade.class));

        BotHealthResponse health = service().getBotHealth();

        BotStatus primary = health.bots().stream()
                .filter(b -> b.botId().equals("100")).findFirst().orElseThrow();
        BotStatus secondary = health.bots().stream()
                .filter(b -> b.botId().equals("200")).findFirst().orElseThrow();

        assertThat(primary.primary()).isTrue();
        assertThat(primary.initialized()).isTrue();
        assertThat(primary.status()).isEqualTo("UP");
        assertThat(secondary.primary()).isFalse();
        assertThat(secondary.initialized()).isFalse();
        assertThat(secondary.status()).isEqualTo("DOWN");
    }

    @Test
    @DisplayName("reports UP for a single configured and initialized bot")
    void singleBotUp() {
        when(botInstanceProvider.getInstanceIds()).thenReturn(List.of("100"));
        when(botInstanceProvider.getInstanceId()).thenReturn("100");
        when(telegramClientManager.getClient("100")).thenReturn(mock(TelegramClientFacade.class));

        BotHealthResponse health = service().getBotHealth();

        assertThat(health.status()).isEqualTo("UP");
        assertThat(health.bots()).singleElement()
                .satisfies(b -> assertThat(b.botId()).isEqualTo("100"));
    }
}
