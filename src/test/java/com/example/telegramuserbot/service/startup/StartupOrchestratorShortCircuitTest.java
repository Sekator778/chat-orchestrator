package com.example.telegramuserbot.service.startup;

import com.example.telegramuserbot.config.BotInstanceProvider;
import com.example.telegramuserbot.service.TdLibOperationLockService;
import com.example.telegramuserbot.service.TelegramClientManager;
import com.example.telegramuserbot.service.channels.discovery.ChannelDiscoveryCoordinator;
import com.example.telegramuserbot.service.channels.pipeline.ChannelProcessingCoordinator;
import com.example.telegramuserbot.service.messagesync.ChannelMessageSynchronizationService;
import com.example.telegramuserbot.telegram.TdLibOperationCoordinator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.mockito.Mockito.*;

/**
 * Verifies the StartupOrchestrator short-circuit behaviour
 * (FR-028 to FR-031): when the active TDLib client map is empty,
 * waitForTdLibReadiness() returns Mono.empty() immediately without
 * touching TdLibOperationCoordinator.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StartupOrchestrator waitForTdLibReadiness short-circuit")
class StartupOrchestratorShortCircuitTest {

    @Mock
    private ChannelDiscoveryCoordinator channelDiscoveryCoordinator;
    @Mock
    private ChannelMessageSynchronizationService channelMessageSynchronizationService;
    @Mock
    private ChannelProcessingCoordinator channelProcessingCoordinator;
    @Mock
    private BotInstanceProvider botInstanceProvider;
    @Mock
    private TdLibOperationCoordinator tdLibOperationCoordinator;
    @Mock
    private TdLibOperationLockService lockService;
    @Mock
    private TelegramClientManager telegramClientManager;

    private StartupOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new StartupOrchestrator(
                channelDiscoveryCoordinator,
                channelMessageSynchronizationService,
                channelProcessingCoordinator,
                botInstanceProvider,
                tdLibOperationCoordinator,
                lockService,
                telegramClientManager
        );
    }

    @Test
    @DisplayName("FR-028: when getClientCount()==0, returns Mono.empty() immediately, no isTdLibReady() call")
    void shortCircuitWhenNoClients() {
        when(telegramClientManager.getClientCount()).thenReturn(0);

        StepVerifier.create(orchestrator.waitForTdLibReadiness())
                .expectComplete()
                .verify(Duration.ofSeconds(1));

        verify(tdLibOperationCoordinator, never()).isTdLibReady();
    }

    @Test
    @DisplayName("FR-029/FR-031: when getClientCount()>0, delegates to TdLibOperationCoordinator")
    void delegatesToTdLibCoordinatorWhenClientsPresent() {
        when(telegramClientManager.getClientCount()).thenReturn(1);
        when(tdLibOperationCoordinator.isTdLibReady()).thenReturn(Mono.just(true));

        StepVerifier.create(orchestrator.waitForTdLibReadiness())
                .expectComplete()
                .verify(Duration.ofSeconds(5));

        verify(tdLibOperationCoordinator).isTdLibReady();
    }

    @Test
    @DisplayName("FR-030: short-circuit uses existing getClientCount() surface, no new abstraction")
    void shortCircuitUsesExistingGetClientCount() {
        when(telegramClientManager.getClientCount()).thenReturn(0);

        orchestrator.waitForTdLibReadiness().subscribe();

        verify(telegramClientManager).getClientCount();
        verifyNoMoreInteractions(telegramClientManager);
    }
}
