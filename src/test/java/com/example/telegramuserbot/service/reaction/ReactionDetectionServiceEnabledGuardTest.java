package com.example.telegramuserbot.service.reaction;

import com.example.telegramuserbot.repository.PersonaReactionConfigRepository;
import com.example.telegramuserbot.repository.PersonaReactionLogRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Contract test for the reaction-pipeline enabled guard: when the pipeline is
 * OFF, detection must NOT queue (the executor only runs when enabled, so queued
 * rows would pile up as PENDING forever).
 */
@ExtendWith(MockitoExtension.class)
class ReactionDetectionServiceEnabledGuardTest {

    @Mock
    private PersonaReactionConfigRepository configRepository;
    @Mock
    private PersonaReactionLogRepository logRepository;

    @Test
    void disabledPipelineNeverQueues() {
        ReactionProperties properties = new ReactionProperties();
        properties.setEnabled(false);
        ReactionDetectionServiceImpl service = new ReactionDetectionServiceImpl(
                configRepository, logRepository, properties, mock(ReactionEmojiSelector.class));

        StepVerifier.create(service.onNewMessageForPersona(-100L, 42L, "bot-a"))
                .expectNext(0)
                .verifyComplete();

        verifyNoInteractions(configRepository);
        verifyNoInteractions(logRepository);
    }

    @Test
    void enabledPipelineConsultsConfig() {
        ReactionProperties properties = new ReactionProperties();
        properties.setEnabled(true);
        when(configRepository.findByChannelIdAndEnabledTrue(-100L))
                .thenReturn(reactor.core.publisher.Flux.empty());
        ReactionDetectionServiceImpl service = new ReactionDetectionServiceImpl(
                configRepository, logRepository, properties, mock(ReactionEmojiSelector.class));

        // No config for the chat → no reaction queued (0 returned).
        StepVerifier.create(service.onNewMessageForPersona(-100L, 42L, "bot-a"))
                .expectNext(0)
                .verifyComplete();
    }
}
