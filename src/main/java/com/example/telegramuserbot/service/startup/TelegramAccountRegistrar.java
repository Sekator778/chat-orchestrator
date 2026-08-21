package com.example.telegramuserbot.service.startup;

import com.example.telegramuserbot.config.BotInstanceProvider;
import com.example.telegramuserbot.config.TelegramAccountProperties;
import com.example.telegramuserbot.domain.TelegramAccount;
import com.example.telegramuserbot.repository.BotPersonaRepository;
import com.example.telegramuserbot.repository.TelegramAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Converges bot.telegram_accounts (the account registry) with reality at startup:
 * upserts every account from telegram.accounts[], backfills rows for bot_ids that
 * only exist as personas, and guarantees exactly one collector account exists
 * (defaults to the primary instance). Additive only — never deletes or disables.
 */
@Component
public class TelegramAccountRegistrar {

    private static final Logger log = LoggerFactory.getLogger(TelegramAccountRegistrar.class);

    private final TelegramAccountProperties accountProperties;
    private final TelegramAccountRepository accountRepository;
    private final BotPersonaRepository botPersonaRepository;
    private final BotInstanceProvider botInstanceProvider;

    public TelegramAccountRegistrar(TelegramAccountProperties accountProperties,
                                    TelegramAccountRepository accountRepository,
                                    BotPersonaRepository botPersonaRepository,
                                    BotInstanceProvider botInstanceProvider) {
        this.accountProperties = accountProperties;
        this.accountRepository = accountRepository;
        this.botPersonaRepository = botPersonaRepository;
        this.botInstanceProvider = botInstanceProvider;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady(ApplicationReadyEvent event) {
        registerAll()
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        unused -> { },
                        error -> log.error("TelegramAccountRegistrar: registry sync failed: {}", error.getMessage(), error)
                );
    }

    Mono<Void> registerAll() {
        List<TelegramAccountProperties.Account> accounts = accountProperties.getAccounts();
        Flux<TelegramAccountProperties.Account> configured =
                accounts == null ? Flux.empty() : Flux.fromIterable(accounts);

        return configured
                .filter(account -> account.getBotId() != null && !account.getBotId().isBlank())
                .concatMap(this::upsertConfiguredAccount)
                .thenMany(backfillPersonaAccounts())
                .then(ensureCollector())
                .then(logRegistry());
    }

    private Mono<TelegramAccount> upsertConfiguredAccount(TelegramAccountProperties.Account account) {
        return accountRepository.findByBotId(account.getBotId())
                .flatMap(existing -> {
                    boolean changed = false;
                    if (account.getName() != null && !Objects.equals(existing.getName(), account.getName())) {
                        existing.setName(account.getName());
                        changed = true;
                    }
                    if (account.getPhoneNumber() != null && !Objects.equals(existing.getPhoneNumber(), account.getPhoneNumber())) {
                        existing.setPhoneNumber(account.getPhoneNumber());
                        changed = true;
                    }
                    if (account.getSessionsDirectory() != null && !Objects.equals(existing.getSessionsDirectory(), account.getSessionsDirectory())) {
                        existing.setSessionsDirectory(account.getSessionsDirectory());
                        changed = true;
                    }
                    if (!changed) {
                        return Mono.just(existing);
                    }
                    existing.setUpdatedAt(Instant.now());
                    return accountRepository.save(existing)
                            .doOnNext(saved -> log.info("Account registry: updated botId={}", saved.getBotId()));
                })
                .switchIfEmpty(Mono.defer(() -> accountRepository.save(newAccount(account))
                        .doOnNext(saved -> log.info("Account registry: registered botId={} from configuration", saved.getBotId()))));
    }

    private Flux<TelegramAccount> backfillPersonaAccounts() {
        return botPersonaRepository.findDistinctBotIds()
                .concatMap(botId -> accountRepository.findByBotId(botId)
                        .switchIfEmpty(Mono.defer(() -> {
                            TelegramAccountProperties.Account minimal = new TelegramAccountProperties.Account();
                            minimal.setBotId(botId);
                            return accountRepository.save(newAccount(minimal))
                                    .doOnNext(saved -> log.info("Account registry: backfilled botId={} from personas", saved.getBotId()));
                        })));
    }

    private Mono<Void> ensureCollector() {
        return accountRepository.collectorExists()
                .filter(exists -> !exists)
                .flatMap(missing -> accountRepository.findByBotId(botInstanceProvider.getInstanceId()))
                .flatMap(primary -> {
                    primary.setCollector(true);
                    primary.setUpdatedAt(Instant.now());
                    return accountRepository.save(primary)
                            .doOnNext(saved -> log.info("Account registry: botId={} marked as the news collector", saved.getBotId()));
                })
                .then();
    }

    private Mono<Void> logRegistry() {
        return accountRepository.count()
                .doOnNext(count -> log.info("Account registry in sync: {} account(s) registered", count))
                .then();
    }

    private TelegramAccount newAccount(TelegramAccountProperties.Account account) {
        TelegramAccount entity = new TelegramAccount();
        entity.setBotId(account.getBotId());
        entity.setName(account.getName());
        entity.setPhoneNumber(account.getPhoneNumber());
        entity.setSessionsDirectory(account.getSessionsDirectory());
        entity.setStatus("ACTIVE");
        entity.setCollector(false);
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        return entity;
    }
}
