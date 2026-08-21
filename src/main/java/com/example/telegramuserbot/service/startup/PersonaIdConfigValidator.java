package com.example.telegramuserbot.service.startup;

import com.example.telegramuserbot.config.BotInstanceProvider;
import com.example.telegramuserbot.config.TelegramAccountProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Startup cross-validator for {@code bot.persona-ids} vs {@code telegram.accounts}.
 *
 * <p>These two config lists must stay in sync: every persona-id must have a
 * corresponding Telegram account entry (otherwise the persona cannot connect),
 * and every Telegram account should map to at least one declared persona-id
 * (otherwise the account is silently unused).
 *
 * <p>By default only logs mismatches (ERROR / WARN) so existing deployments are
 * not broken.  Set {@code bot.persona-ids.validation.fail-on-mismatch=true} to
 * make the application refuse to start when drift is detected.
 */
@Component
public class PersonaIdConfigValidator {

    private static final Logger log = LoggerFactory.getLogger(PersonaIdConfigValidator.class);

    private final BotInstanceProvider botInstanceProvider;
    private final TelegramAccountProperties accountProperties;
    private final boolean failOnMismatch;

    public PersonaIdConfigValidator(BotInstanceProvider botInstanceProvider,
                                    TelegramAccountProperties accountProperties,
                                    @Value("${bot.persona-ids.validation.fail-on-mismatch:false}") boolean failOnMismatch) {
        this.botInstanceProvider = botInstanceProvider;
        this.accountProperties = accountProperties;
        this.failOnMismatch = failOnMismatch;
    }

    @PostConstruct
    public void validate() {
        List<String> personaIds = botInstanceProvider.getInstanceIds();
        List<TelegramAccountProperties.Account> accounts = accountProperties.getAccounts();

        if (accounts == null || accounts.isEmpty()) {
            log.warn("PersonaIdConfigValidator: no telegram.accounts configured — skipping cross-validation");
            return;
        }

        Set<String> accountBotIds = accounts.stream()
                .filter(a -> a.getBotId() != null && !a.getBotId().isBlank())
                .map(TelegramAccountProperties.Account::getBotId)
                .collect(Collectors.toSet());

        // Persona-ids that have no matching account entry → cannot connect
        List<String> unmatchedPersonaIds = personaIds.stream()
                .filter(id -> !accountBotIds.contains(id))
                .toList();

        // Account bot-ids that have no persona-id → account exists but is not declared as a persona
        List<String> orphanAccountIds = accountBotIds.stream()
                .filter(id -> !personaIds.contains(id))
                .sorted()
                .toList();

        boolean mismatchFound = !unmatchedPersonaIds.isEmpty() || !orphanAccountIds.isEmpty();

        if (!unmatchedPersonaIds.isEmpty()) {
            log.error("PersonaIdConfigValidator: bot.persona-ids contains id(s) with no matching " +
                      "telegram.accounts entry — these personas cannot connect: {}", unmatchedPersonaIds);
        }

        if (!orphanAccountIds.isEmpty()) {
            log.warn("PersonaIdConfigValidator: telegram.accounts contains bot-id(s) not listed in " +
                     "bot.persona-ids — these accounts will be initialized but have no persona: {}", orphanAccountIds);
        }

        if (!mismatchFound) {
            log.info("PersonaIdConfigValidator: bot.persona-ids and telegram.accounts are in sync ({} persona(s))",
                     personaIds.size());
        }

        if (mismatchFound && failOnMismatch) {
            throw new IllegalStateException(
                    "PersonaIdConfigValidator: persona-id / telegram-account mismatch detected and " +
                    "bot.persona-ids.validation.fail-on-mismatch=true. " +
                    "Missing accounts for persona-ids: " + unmatchedPersonaIds +
                    ". Orphan account ids (no persona): " + orphanAccountIds);
        }
    }
}
