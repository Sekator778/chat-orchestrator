package com.example.telegramuserbot.service.reaction;

import com.example.telegramuserbot.domain.PersonaReactionLog;
import com.example.telegramuserbot.domain.ReactionStatus;
import com.example.telegramuserbot.repository.PersonaReactionConfigRepository;
import com.example.telegramuserbot.repository.PersonaReactionLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Implementation of {@link ReactionDetectionService} that evaluates daily limits,
 * channel gaps, and global quotas before scheduling new reactions.
 */
@Service
public final class ReactionDetectionServiceImpl implements ReactionDetectionService {

    private static final Logger log = LoggerFactory.getLogger(ReactionDetectionServiceImpl.class);
    private static final Duration REPO_TIMEOUT = Duration.ofSeconds(5);

    private final PersonaReactionConfigRepository configRepository;
    private final PersonaReactionLogRepository logRepository;
    private final ReactionProperties properties;
    private final ReactionEmojiSelector emojiSelector;

    /**
     * Constructs the detection service with required dependencies.
     *
     * @param configRepository the persona reaction config repository
     * @param logRepository    the persona reaction log repository
     * @param properties       the reaction system configuration
     * @param emojiSelector    the emoji selection service
     */
    public ReactionDetectionServiceImpl(PersonaReactionConfigRepository configRepository,
                                        PersonaReactionLogRepository logRepository,
                                        ReactionProperties properties,
                                        ReactionEmojiSelector emojiSelector) {
        this.configRepository = configRepository;
        this.logRepository = logRepository;
        this.properties = properties;
        this.emojiSelector = emojiSelector;
    }

    @Override
    public Mono<Integer> onNewMessageForPersona(long chatId, long messageId, String personaId) {
        if (!properties.enabled()) {
            // Pipeline off: do NOT queue. The executor (ReactionExecutorScheduler)
            // only runs when enabled=true, so queuing here would pile up PENDING
            // rows that never execute.
            return Mono.just(0);
        }
        Instant today = Instant.now().truncatedTo(ChronoUnit.DAYS);
        return configRepository.findByChannelIdAndEnabledTrue(chatId)
            .timeout(REPO_TIMEOUT)
            .filter(config -> config.personaId().equals(personaId))
            .next()
            .flatMap(config -> {
                Long channelId = config.channelId();
                return checkGlobalDailyLimit(personaId, today)
                    .flatMap(globalExceeded -> {
                        if (globalExceeded) {
                            log.debug("Persona {} hit global daily limit, skipping reaction for chat={}", personaId, chatId);
                            return Mono.just(0);
                        }
                        return checkChannelDailyLimit(personaId, channelId, config.maxPerDay(), today);
                    })
                    .flatMap(channelExceeded -> {
                        if (channelExceeded.equals(Boolean.TRUE) || channelExceeded.equals(1)) {
                            return Mono.just(0);
                        }
                        return checkChannelGap(personaId, channelId);
                    })
                    .flatMap(gapViolated -> {
                        if (gapViolated.equals(Boolean.TRUE) || gapViolated.equals(1)) {
                            return Mono.just(0);
                        }
                        return scheduleReaction(personaId, channelId, messageId);
                    })
                    .onErrorResume(DataIntegrityViolationException.class, ex -> {
                        log.debug("Duplicate reaction detected for persona={}, channel={}, message={}, skipping",
                            personaId, channelId, messageId);
                        return Mono.just(0);
                    })
                    .onErrorResume(ex -> {
                        log.warn("Error queuing reaction for persona={}, chat={}: {}", personaId, chatId, ex.getMessage());
                        return Mono.just(0);
                    });
            })
            .defaultIfEmpty(0);
    }

    @Override
    public Mono<Integer> onNewMessage(long chatId, long messageId) {
        Instant today = Instant.now().truncatedTo(ChronoUnit.DAYS);
        return configRepository.findByChannelIdAndEnabledTrue(chatId)
            .timeout(REPO_TIMEOUT)
            .flatMap(config -> {
                String personaId = config.personaId();
                Long channelId = config.channelId();
                return checkGlobalDailyLimit(personaId, today)
                    .flatMap(globalExceeded -> {
                        if (globalExceeded) {
                            log.debug("Persona {} hit global daily limit, skipping reaction for chat={}", personaId, chatId);
                            return Mono.just(0);
                        }
                        return checkChannelDailyLimit(personaId, channelId, config.maxPerDay(), today);
                    })
                    .flatMap(channelExceeded -> {
                        if (channelExceeded.equals(Boolean.TRUE) || channelExceeded.equals(1)) {
                            return Mono.just(0);
                        }
                        return checkChannelGap(personaId, channelId);
                    })
                    .flatMap(gapViolated -> {
                        if (gapViolated.equals(Boolean.TRUE) || gapViolated.equals(1)) {
                            return Mono.just(0);
                        }
                        return scheduleReaction(personaId, channelId, messageId);
                    })
                    .onErrorResume(DataIntegrityViolationException.class, ex -> {
                        log.debug("Duplicate reaction detected for persona={}, channel={}, message={}, skipping",
                            personaId, channelId, messageId);
                        return Mono.just(0);
                    })
                    .onErrorResume(ex -> {
                        log.warn("Error queuing reaction for persona={}, chat={}: {}", personaId, chatId, ex.getMessage());
                        return Mono.just(0);
                    });
            })
            .reduce(0, Integer::sum)
            .doOnSuccess(count -> {
                if (count > 0) {
                    log.info("Queued {} reactions for chat={}, messageId={}", count, chatId, messageId);
                }
            });
    }

    private Mono<Boolean> checkGlobalDailyLimit(String personaId, Instant today) {
        return logRepository.countDoneByPersonaIdSince(personaId, today)
            .timeout(REPO_TIMEOUT)
            .map(count -> count >= properties.dailyLimitPerPersona())
            .onErrorResume(ex -> {
                log.warn("Failed to check global daily limit for persona={}: {}", personaId, ex.getMessage());
                return Mono.just(false);
            });
    }

    private Mono<Integer> checkChannelDailyLimit(String personaId, Long channelId, int maxPerDay, Instant today) {
        return logRepository.countDoneByPersonaIdAndChannelIdSince(personaId, channelId, today)
            .timeout(REPO_TIMEOUT)
            .map(count -> {
                if (count >= maxPerDay) {
                    log.debug("Persona {} hit channel daily limit ({}/{}) for channel={}", personaId, count, maxPerDay, channelId);
                    return 1;
                }
                return 0;
            })
            .onErrorResume(ex -> {
                log.warn("Failed to check channel daily limit for persona={}, channel={}: {}", personaId, channelId, ex.getMessage());
                return Mono.just(0);
            });
    }

    private Mono<Integer> checkChannelGap(String personaId, Long channelId) {
        return logRepository.findLastDoneByPersonaIdAndChannelId(personaId, channelId)
            .timeout(REPO_TIMEOUT)
            .map(lastLog -> {
                if (lastLog.executedAt() == null) {
                    return 0;
                }
                Instant gapThreshold = Instant.now().minus(properties.minGapSameChannelMinutes(), ChronoUnit.MINUTES);
                if (lastLog.executedAt().isAfter(gapThreshold)) {
                    log.debug("Persona {} too soon to react on channel={}, last reaction at {}", personaId, channelId, lastLog.executedAt());
                    return 1;
                }
                return 0;
            })
            .defaultIfEmpty(0)
            .onErrorResume(ex -> {
                log.warn("Failed to check channel gap for persona={}, channel={}: {}", personaId, channelId, ex.getMessage());
                return Mono.just(0);
            });
    }

    private Mono<Integer> scheduleReaction(String personaId, Long channelId, long messageId) {
        // Probability gate: each persona draws independently, so a sub-100 chance
        // makes reactions sparse and lets the two personas diverge (random subset
        // reacts, often none or one) instead of every persona reacting to every message.
        int chance = properties.reactProbabilityPercent();
        if (chance < 100 && ThreadLocalRandom.current().nextInt(100) >= chance) {
            log.debug("Probability gate skipped reaction persona={} channel={} message={} (chance={}%)",
                personaId, channelId, messageId, chance);
            return Mono.just(0);
        }

        String emoji = emojiSelector.select();
        int delaySeconds = ThreadLocalRandom.current().nextInt(
            properties.delayMinMinutes() * 60,
            properties.delayMaxMinutes() * 60 + 1
        );
        Instant scheduledAt = Instant.now().plusSeconds(delaySeconds);
        PersonaReactionLog entry = new PersonaReactionLog(personaId, channelId, messageId, emoji, scheduledAt);
        entry.setCreatedAt(Instant.now());
        return logRepository.save(entry)
            .timeout(REPO_TIMEOUT)
            .map(saved -> {
                log.info("Scheduled reaction persona={} channel={} message={} emoji={} at={} delaySeconds={}",
                    personaId, channelId, messageId, emoji, scheduledAt, delaySeconds);
                return 1;
            })
            .onErrorResume(DataIntegrityViolationException.class, ex -> {
                log.debug("Reaction already scheduled for persona={}, channel={}, message={}", personaId, channelId, messageId);
                return Mono.just(0);
            });
    }
}
