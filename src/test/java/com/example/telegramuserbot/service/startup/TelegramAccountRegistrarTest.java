package com.example.telegramuserbot.service.startup;

import com.example.telegramuserbot.config.BotInstanceProvider;
import com.example.telegramuserbot.config.TelegramAccountProperties;
import com.example.telegramuserbot.domain.TelegramAccount;
import com.example.telegramuserbot.repository.BotPersonaRepository;
import com.example.telegramuserbot.repository.TelegramAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the account-registry startup sync. Pure Mockito — no Spring
 * context, no database.
 *
 * FR-001: a configured account absent from the registry is inserted with status ACTIVE.
 * FR-002: an existing row is updated when configuration differs (phone change), untouched when equal.
 * FR-003: bot_ids present only in bot_personas are backfilled into the registry.
 * FR-004: when no collector exists, the primary instance is marked as collector.
 * FR-005: an existing collector is never reassigned.
 * FR-006: a null telegram.accounts list (e.g. smoke profile) is tolerated.
 */
@ExtendWith(MockitoExtension.class)
class TelegramAccountRegistrarTest {

    private static final String PRIMARY = "bot-primary";
    private static final String SECONDARY = "bot-secondary";

    @Mock
    private TelegramAccountRepository accountRepository;
    @Mock
    private BotPersonaRepository botPersonaRepository;
    @Mock
    private BotInstanceProvider botInstanceProvider;

    private TelegramAccountProperties properties;
    private TelegramAccountRegistrar registrar;

    @BeforeEach
    void setUp() {
        properties = new TelegramAccountProperties();
        registrar = new TelegramAccountRegistrar(properties, accountRepository, botPersonaRepository, botInstanceProvider);
    }

    private static TelegramAccountProperties.Account configured(String botId, String phone) {
        TelegramAccountProperties.Account account = new TelegramAccountProperties.Account();
        account.setBotId(botId);
        account.setPhoneNumber(phone);
        return account;
    }

    private static TelegramAccount registered(String botId, boolean collector) {
        TelegramAccount account = new TelegramAccount();
        account.setId(1L);
        account.setBotId(botId);
        account.setStatus("ACTIVE");
        account.setCollector(collector);
        return account;
    }

    private void stubTail(boolean collectorExists) {
        when(botPersonaRepository.findDistinctBotIds()).thenReturn(Flux.empty());
        when(accountRepository.collectorExists()).thenReturn(Mono.just(collectorExists));
        when(accountRepository.count()).thenReturn(Mono.just(1L));
    }

    @Test
    void insertsConfiguredAccountWhenMissing() {
        properties.setAccounts(List.of(configured(PRIMARY, "+1000")));
        when(accountRepository.findByBotId(PRIMARY)).thenReturn(Mono.empty());
        when(accountRepository.save(any(TelegramAccount.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        stubTail(true);

        StepVerifier.create(registrar.registerAll()).verifyComplete();

        ArgumentCaptor<TelegramAccount> captor = ArgumentCaptor.forClass(TelegramAccount.class);
        verify(accountRepository).save(captor.capture());
        assertThat(captor.getValue().getBotId()).isEqualTo(PRIMARY);
        assertThat(captor.getValue().getPhoneNumber()).isEqualTo("+1000");
        assertThat(captor.getValue().getStatus()).isEqualTo("ACTIVE");
        assertThat(captor.getValue().isCollector()).isFalse();
    }

    @Test
    void updatesExistingAccountWhenConfigurationDiffers() {
        properties.setAccounts(List.of(configured(PRIMARY, "+2000")));
        TelegramAccount existing = registered(PRIMARY, true);
        existing.setPhoneNumber("+1000");
        when(accountRepository.findByBotId(PRIMARY)).thenReturn(Mono.just(existing));
        when(accountRepository.save(any(TelegramAccount.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        stubTail(true);

        StepVerifier.create(registrar.registerAll()).verifyComplete();

        ArgumentCaptor<TelegramAccount> captor = ArgumentCaptor.forClass(TelegramAccount.class);
        verify(accountRepository).save(captor.capture());
        assertThat(captor.getValue().getPhoneNumber()).isEqualTo("+2000");
        assertThat(captor.getValue().getUpdatedAt()).isNotNull();
    }

    @Test
    void leavesExistingAccountUntouchedWhenConfigurationMatches() {
        properties.setAccounts(List.of(configured(PRIMARY, "+1000")));
        TelegramAccount existing = registered(PRIMARY, true);
        existing.setPhoneNumber("+1000");
        when(accountRepository.findByBotId(PRIMARY)).thenReturn(Mono.just(existing));
        stubTail(true);

        StepVerifier.create(registrar.registerAll()).verifyComplete();

        verify(accountRepository, never()).save(any(TelegramAccount.class));
    }

    @Test
    void backfillsAccountsForPersonaOnlyBotIds() {
        properties.setAccounts(List.of());
        when(botPersonaRepository.findDistinctBotIds()).thenReturn(Flux.just(SECONDARY));
        when(accountRepository.findByBotId(SECONDARY)).thenReturn(Mono.empty());
        when(accountRepository.save(any(TelegramAccount.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(accountRepository.collectorExists()).thenReturn(Mono.just(true));
        when(accountRepository.count()).thenReturn(Mono.just(1L));

        StepVerifier.create(registrar.registerAll()).verifyComplete();

        ArgumentCaptor<TelegramAccount> captor = ArgumentCaptor.forClass(TelegramAccount.class);
        verify(accountRepository).save(captor.capture());
        assertThat(captor.getValue().getBotId()).isEqualTo(SECONDARY);
    }

    @Test
    void marksPrimaryAsCollectorWhenNoneExists() {
        properties.setAccounts(List.of());
        when(botPersonaRepository.findDistinctBotIds()).thenReturn(Flux.empty());
        when(accountRepository.collectorExists()).thenReturn(Mono.just(false));
        when(botInstanceProvider.getInstanceId()).thenReturn(PRIMARY);
        when(accountRepository.findByBotId(PRIMARY)).thenReturn(Mono.just(registered(PRIMARY, false)));
        when(accountRepository.save(any(TelegramAccount.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(accountRepository.count()).thenReturn(Mono.just(1L));

        StepVerifier.create(registrar.registerAll()).verifyComplete();

        ArgumentCaptor<TelegramAccount> captor = ArgumentCaptor.forClass(TelegramAccount.class);
        verify(accountRepository).save(captor.capture());
        assertThat(captor.getValue().isCollector()).isTrue();
    }

    @Test
    void keepsExistingCollectorAssignment() {
        properties.setAccounts(List.of());
        stubTail(true);

        StepVerifier.create(registrar.registerAll()).verifyComplete();

        verify(accountRepository, never()).save(any(TelegramAccount.class));
    }

    @Test
    void toleratesNullAccountsList() {
        properties.setAccounts(null);
        stubTail(true);

        StepVerifier.create(registrar.registerAll()).verifyComplete();

        verify(accountRepository, never()).save(any(TelegramAccount.class));
    }
}
