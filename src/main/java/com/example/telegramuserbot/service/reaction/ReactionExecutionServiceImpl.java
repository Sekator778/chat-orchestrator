package com.example.telegramuserbot.service.reaction;

import com.example.telegramuserbot.domain.PersonaReactionLog;
import com.example.telegramuserbot.domain.ReactionStatus;
import com.example.telegramuserbot.repository.PersonaReactionLogRepository;
import com.example.telegramuserbot.service.TelegramClientManager;
import com.example.telegramuserbot.telegram.TelegramClientFacade;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Implementation of {@link ReactionExecutionService} that sends reactions via TDLib
 * and updates the log entry status after each attempt.
 *
 * <p>Each persona's reaction entry stores a message ID from that persona's own TDLib
 * session (populated by {@link SecondaryClientReactionHandler} for secondary clients).
 * Since the message is already in the client's cache from UpdateNewMessage, no
 * additional loading is needed — just call AddMessageReaction directly.</p>
 */
@Service
public final class ReactionExecutionServiceImpl implements ReactionExecutionService {

    private static final Logger log = LoggerFactory.getLogger(ReactionExecutionServiceImpl.class);
    private static final Duration REPO_TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_ATTEMPTS = 3;
    private static final int RETRY_DELAY_MINUTES = 5;

    private final PersonaReactionLogRepository logRepository;
    private final TelegramClientManager telegramClientManager;
    private final ReactionProperties properties;

    /**
     * Constructs the execution service with required dependencies.
     *
     * @param logRepository         the reaction log repository
     * @param telegramClientManager the manager for TDLib clients
     * @param properties            the reaction system configuration
     */
    public ReactionExecutionServiceImpl(PersonaReactionLogRepository logRepository,
                                        TelegramClientManager telegramClientManager,
                                        ReactionProperties properties) {
        this.logRepository = logRepository;
        this.telegramClientManager = telegramClientManager;
        this.properties = properties;
    }

    @Override
    public Mono<Integer> executePendingReactions() {
        return logRepository.findPendingDue(properties.maxConcurrentExecutions())
            .timeout(REPO_TIMEOUT)
            .collectList()
            .flatMapMany(entries -> {
                Map<String, List<PersonaReactionLog>> batches = entries.stream()
                    .collect(Collectors.groupingBy(
                        e -> e.personaId() + "|" + e.channelId(),
                        LinkedHashMap::new,
                        Collectors.toList()
                    ));
                return Flux.fromIterable(batches.values())
                    .concatMap(batch -> executeBatch(batch)
                        .delaySubscription(Duration.ofSeconds(properties.minGapBetweenReactionsSeconds())));
            })
            .reduce(0, Integer::sum)
            .doOnSuccess(count -> {
                if (count > 0) {
                    log.info("Executed {} reactions in this cycle", count);
                }
            })
            .onErrorResume(ex -> {
                log.error("Error during reaction execution cycle: {}", ex.getMessage(), ex);
                return Mono.just(0);
            });
    }

    /**
     * Executes a batch of reactions sharing the same persona and channel.
     *
     * @param batch list of entries with the same personaId and channelId
     * @return mono of total successful reactions in this batch
     */
    private Mono<Integer> executeBatch(List<PersonaReactionLog> batch) {
        PersonaReactionLog first = batch.get(0);
        TelegramClientFacade client = telegramClientManager.getClient(first.personaId());
        if (client == null) {
            log.warn("No TDLib client for persona={}, marking {} reactions as SKIPPED",
                first.personaId(), batch.size());
            return Flux.fromIterable(batch)
                .concatMap(this::markSkipped)
                .then(Mono.just(0));
        }
        return Flux.fromIterable(batch)
            .concatMap(entry -> executeOne(client, entry))
            .reduce(0, Integer::sum);
    }

    /**
     * Executes a single reaction. The message ID is from this persona's own TDLib
     * session, so it should be in the local cache from UpdateNewMessage.
     *
     * @param client the TDLib client for the persona
     * @param entry  the reaction log entry
     * @return mono of 1 on success, 0 on failure
     */
    private Mono<Integer> executeOne(TelegramClientFacade client, PersonaReactionLog entry) {
        return Mono.defer(() -> {
            log.info("Sending reaction persona={} channel={} message={} emoji={}",
                entry.personaId(), entry.channelId(), entry.messageId(), entry.reactionEmoji());
            return doAddReaction(client, entry);
        })
        .flatMap(ok -> markDone(entry).thenReturn(1))
        .onErrorResume(ex -> handleError(entry, ex).thenReturn(0));
    }

    private Mono<TdApi.Ok> doAddReaction(TelegramClientFacade client, PersonaReactionLog entry) {
        TdApi.AddMessageReaction request = new TdApi.AddMessageReaction(
            entry.channelId(),
            entry.messageId(),
            new TdApi.ReactionTypeEmoji(entry.reactionEmoji()),
            false,
            false
        );
        return Mono.<TdApi.Ok>create(sink ->
            client.send(request, result -> {
                if (result.isError()) {
                    sink.error(new RuntimeException(result.getError().message));
                } else {
                    sink.success((TdApi.Ok) result.get());
                }
            })
        ).timeout(Duration.ofSeconds(15));
    }

    private Mono<PersonaReactionLog> markDone(PersonaReactionLog entry) {
        entry.setStatus(ReactionStatus.DONE.name());
        entry.setExecutedAt(Instant.now());
        log.info("Reaction DONE id={} persona={} channel={} message={} emoji={}",
            entry.id(), entry.personaId(), entry.channelId(), entry.messageId(), entry.reactionEmoji());
        return logRepository.save(entry).timeout(REPO_TIMEOUT);
    }

    private Mono<PersonaReactionLog> markSkipped(PersonaReactionLog entry) {
        entry.setStatus(ReactionStatus.SKIPPED.name());
        return logRepository.save(entry).timeout(REPO_TIMEOUT);
    }

    private Mono<PersonaReactionLog> handleError(PersonaReactionLog entry, Throwable ex) {
        String message = ex.getMessage() != null ? ex.getMessage() : "Unknown error";
        entry.setErrorMessage(message);
        entry.setAttemptCount(entry.attemptCount() + 1);
        if (message.contains("FLOOD_WAIT")) {
            log.warn("FLOOD_WAIT received for persona={}, rescheduling reaction {} by {}min",
                entry.personaId(), entry.id(), properties.floodWaitBackoffMinutes());
            entry.setStatus(ReactionStatus.FLOOD_WAIT.name());
            entry.setScheduledAt(Instant.now().plus(properties.floodWaitBackoffMinutes(), ChronoUnit.MINUTES));
        } else if (entry.attemptCount() >= MAX_ATTEMPTS) {
            log.warn("Max attempts ({}) reached for reaction {}, persona={}, marking FAILED: {}",
                MAX_ATTEMPTS, entry.id(), entry.personaId(), message);
            entry.setStatus(ReactionStatus.FAILED.name());
        } else {
            log.debug("Retrying reaction {} for persona={}, attempt {}, rescheduling +{}min: {}",
                entry.id(), entry.personaId(), entry.attemptCount(), RETRY_DELAY_MINUTES, message);
            entry.setStatus(ReactionStatus.PENDING.name());
            entry.setScheduledAt(Instant.now().plus(RETRY_DELAY_MINUTES, ChronoUnit.MINUTES));
        }
        return logRepository.save(entry).timeout(REPO_TIMEOUT)
            .onErrorResume(saveEx -> {
                log.error("Failed to update reaction log {} after error: {}", entry.id(), saveEx.getMessage());
                return Mono.just(entry);
            });
    }
}
