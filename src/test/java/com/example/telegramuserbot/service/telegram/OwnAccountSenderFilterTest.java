package com.example.telegramuserbot.service.telegram;

import com.example.telegramuserbot.config.BotInstanceProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the bot-to-bot ban filter. Pure Mockito — no Spring context.
 *
 * FR-001: a sender id matching ANY configured account's self id is OWN.
 * FR-002: a regular user id is not own.
 * FR-003: null sender (channels/service messages) is not own.
 * FR-004: fail-open — unresolved clients (empty self id) never block a message.
 */
@ExtendWith(MockitoExtension.class)
class OwnAccountSenderFilterTest {

    private static final long SELF_A = 111L;
    private static final long SELF_B = 222L;
    private static final long STRANGER = 999L;

    @Mock
    private TelegramSelfUserIdResolver resolver;
    @Mock
    private BotInstanceProvider botInstanceProvider;

    private OwnAccountSenderFilter filter;

    @BeforeEach
    void setUp() {
        filter = new OwnAccountSenderFilter(resolver, botInstanceProvider);
        lenient().when(botInstanceProvider.getInstanceIds()).thenReturn(List.of("bot-a", "bot-b"));
    }

    @Test
    void detectsSenderOfSecondaryAccountAsOwn() {
        when(resolver.resolveSelfUserId("bot-a")).thenReturn(Mono.just(SELF_A));
        when(resolver.resolveSelfUserId("bot-b")).thenReturn(Mono.just(SELF_B));

        StepVerifier.create(filter.isOwnSender(SELF_B)).expectNext(true).verifyComplete();
    }

    @Test
    void passesRegularUserThrough() {
        when(resolver.resolveSelfUserId("bot-a")).thenReturn(Mono.just(SELF_A));
        when(resolver.resolveSelfUserId("bot-b")).thenReturn(Mono.just(SELF_B));

        StepVerifier.create(filter.isOwnSender(STRANGER)).expectNext(false).verifyComplete();
    }

    @Test
    void nullSenderIsNeverOwn() {
        StepVerifier.create(filter.isOwnSender(null)).expectNext(false).verifyComplete();
    }

    @Test
    void unresolvedClientsFailOpen() {
        when(resolver.resolveSelfUserId("bot-a")).thenReturn(Mono.empty());
        when(resolver.resolveSelfUserId("bot-b")).thenReturn(Mono.empty());

        StepVerifier.create(filter.isOwnSender(STRANGER)).expectNext(false).verifyComplete();
    }
}
