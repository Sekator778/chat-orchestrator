package com.example.telegramuserbot.service.startup;

import com.example.telegramuserbot.repository.TelegramAccountRepository;
import com.example.telegramuserbot.service.telegram.TelegramSelfUserIdResolver;
import com.example.telegramuserbot.telegram.TelegramClientFacade;
import com.example.telegramuserbot.telegram.TelegramClientLifecycleListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.Objects;

/**
 * Records the real Telegram user id of every account into the registry
 * (bot.telegram_accounts.telegram_user_id) once its client is ready.
 * Auto-discovered by TelegramClientManager for primary and secondary alike.
 */
@Component
public class TelegramAccountIdRecorder implements TelegramClientLifecycleListener {

    private static final Logger log = LoggerFactory.getLogger(TelegramAccountIdRecorder.class);

    private final TelegramSelfUserIdResolver selfUserIdResolver;
    private final TelegramAccountRepository accountRepository;

    public TelegramAccountIdRecorder(TelegramSelfUserIdResolver selfUserIdResolver,
                                     TelegramAccountRepository accountRepository) {
        this.selfUserIdResolver = selfUserIdResolver;
        this.accountRepository = accountRepository;
    }

    @Override
    public void onClientReady(String botId, TelegramClientFacade client) {
        selfUserIdResolver.resolveSelfUserId(botId)
                .flatMap(userId -> accountRepository.findByBotId(botId)
                        .filter(account -> !Objects.equals(account.getTelegramUserId(), userId))
                        .flatMap(account -> {
                            account.setTelegramUserId(userId);
                            account.setUpdatedAt(Instant.now());
                            return accountRepository.save(account);
                        }))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        saved -> log.info("Account registry: botId={} telegram_user_id={} recorded", botId, saved.getTelegramUserId()),
                        error -> log.warn("Account registry: failed to record telegram_user_id for botId={}: {}", botId, error.getMessage())
                );
    }
}
