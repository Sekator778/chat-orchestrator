package com.example.telegramuserbot.service.digest;

import com.example.telegramuserbot.domain.DigestHistory;
import com.example.telegramuserbot.domain.DigestPersona;
import com.example.telegramuserbot.dto.digest.GeneratedDigestDto;
import com.example.telegramuserbot.dto.digest.PublishedDigestDto;
import com.example.telegramuserbot.repository.DigestHistoryRepository;
import com.example.telegramuserbot.repository.DigestPersonaRepository;
import com.example.telegramuserbot.service.common.TextOperations;
import com.example.telegramuserbot.service.publishing.TelegramMessageSender;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Implementation of DigestPublishingService.
 * Publishes generated digests to Telegram channels with retry logic.
 */
@Service
public final class DigestPublishingServiceImpl implements DigestPublishingService {

    private static final Logger LOG = LoggerFactory.getLogger(DigestPublishingServiceImpl.class);
    private static final Duration OPERATION_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_RETRIES = 3;
    private static final Duration RETRY_DELAY = Duration.ofSeconds(2);
    private static final int ERROR_MAX_LENGTH = 500;

    private final TelegramMessageSender sender;
    private final DigestPersonaRepository personaRepository;
    private final DigestHistoryRepository historyRepository;
    private final DigestGenerationService generationService;
    private final TextOperations textOps;

    /**
     * Constructs publishing service with dependencies.
     *
     * @param sender Telegram message sender
     * @param personaRepository persona repository
     * @param historyRepository history repository
     * @param generationService digest generation service
     * @param textOps text operations service
     */
    public DigestPublishingServiceImpl(
            TelegramMessageSender sender,
            DigestPersonaRepository personaRepository,
            DigestHistoryRepository historyRepository,
            DigestGenerationService generationService,
            TextOperations textOps
    ) {
        this.sender = Objects.requireNonNull(sender, "sender must not be null");
        this.personaRepository = Objects.requireNonNull(personaRepository, "personaRepository must not be null");
        this.historyRepository = Objects.requireNonNull(historyRepository, "historyRepository must not be null");
        this.generationService = Objects.requireNonNull(generationService, "generationService must not be null");
        this.textOps = Objects.requireNonNull(textOps, "textOps must not be null");
    }

    @Override
    public Mono<PublishedDigestDto> publish(GeneratedDigestDto digest, DigestPersona persona) {
        Objects.requireNonNull(digest, "digest must not be null");
        Objects.requireNonNull(persona, "persona must not be null");
        if (digest.messagesIncluded() == 0) {
            LOG.info("Skipping publish — no messages in digest for persona {}", persona.name());
            return Mono.just(PublishedDigestDto.success(digest, persona.targetChannelId(), 0L));
        }
        LOG.info("Publishing post {} for persona {} (botId={}) to channel {}",
                digest.digestId(), persona.name(), persona.botId(), persona.targetChannelId());
        String formatted = format(digest, persona);
        String botId = persona.botId() != null ? String.valueOf(persona.botId()) : null;
        return sendWithRetry(botId, persona.targetChannelId(), formatted)
                .flatMap(messageId -> recordSuccess(digest, persona, messageId))
                .onErrorResume(error -> recordFailure(digest, persona, error));
    }

    @Override
    public Mono<PublishedDigestDto> publish(GeneratedDigestDto digest) {
        Objects.requireNonNull(digest, "digest must not be null");
        return personaRepository.findById(digest.personaId())
                .timeout(OPERATION_TIMEOUT)
                .switchIfEmpty(Mono.error(new IllegalArgumentException(
                        "Persona not found: " + digest.personaId())))
                .flatMap(persona -> publish(digest, persona));
    }

    @Override
    public Mono<PublishedDigestDto> generateAndPublish(Long personaId) {
        Objects.requireNonNull(personaId, "personaId must not be null");
        LOG.info("Generating and publishing digest for persona {}", personaId);
        return personaRepository.findById(personaId)
                .timeout(OPERATION_TIMEOUT)
                .switchIfEmpty(Mono.error(new IllegalArgumentException(
                        "Persona not found: " + personaId)))
                .flatMap(persona -> generationService.generateDigest(persona)
                        .flatMap(digest -> publish(digest, persona)));
    }

    @Override
    public Mono<PublishedDigestDto> generateAndPublish(Long personaId, int lookbackHours) {
        Objects.requireNonNull(personaId, "personaId must not be null");
        LOG.info("Generating and publishing digest for persona {} with {} hours lookback",
                personaId, lookbackHours);
        return generationService.generateDigest(personaId, lookbackHours)
                .flatMap(this::publish);
    }

    @Override
    public Mono<PublishedDigestDto> republish(String digestId) {
        Objects.requireNonNull(digestId, "digestId must not be null");
        LOG.info("Republishing digest {}", digestId);
        return historyRepository.findByDigestId(digestId)
                .timeout(OPERATION_TIMEOUT)
                .switchIfEmpty(Mono.error(new IllegalArgumentException(
                        "Digest not found: " + digestId)))
                .flatMap(history -> personaRepository.findById(history.personaId())
                        .timeout(OPERATION_TIMEOUT)
                        .map(persona -> new RepublishContext(history, persona)))
                .flatMap(this::republishFromHistory);
    }

    @Override
    public String format(GeneratedDigestDto digest, DigestPersona persona) {
        Objects.requireNonNull(digest, "digest must not be null");
        Objects.requireNonNull(persona, "persona must not be null");
        String content = digest.content() != null ? digest.content() : "";
        if (content.isEmpty()) {
            content = "Нет значимых новостей за данный период.";
        }
        return textOps.truncateTelegramMessage(content);
    }

    private Mono<Long> sendWithRetry(String botId, Long channelId, String text) {
        Mono<TdApi.Message> sendMono = botId != null
                ? sender.send(botId, channelId, text)
                : sender.send(channelId, text);
        return sendMono
                .timeout(OPERATION_TIMEOUT)
                .retryWhen(Retry.backoff(MAX_RETRIES, RETRY_DELAY)
                        .filter(this::isRetryable)
                        .doBeforeRetry(signal -> LOG.warn(
                                "Retrying Telegram send, attempt {}",
                                signal.totalRetries() + 1)))
                .map(message -> message.id)
                .doOnSuccess(id -> LOG.info("Successfully sent message {} to channel {} via botId={}", id, channelId, botId))
                .doOnError(error -> LOG.error("Failed to send message to channel {} via botId={}: {}",
                        channelId, botId, error.getMessage()));
    }

    private boolean isRetryable(Throwable error) {
        String message = error.getMessage();
        if (message == null) {
            return true;
        }
        return !message.contains("CHAT_NOT_FOUND")
                && !message.contains("CHAT_WRITE_FORBIDDEN")
                && !message.contains("USER_BANNED_IN_CHANNEL");
    }

    private Mono<PublishedDigestDto> recordSuccess(
            GeneratedDigestDto digest,
            DigestPersona persona,
            Long messageId
    ) {
        LOG.info("Recording successful publish for digest {}, message ID {}", digest.digestId(), messageId);
        return historyRepository.findByDigestId(digest.digestId())
                .timeout(OPERATION_TIMEOUT)
                .flatMap(history -> {
                    history.markPublished(messageId);
                    return historyRepository.save(history);
                })
                .then(personaRepository.updateLastPublished(persona.id(), digest.digestId()))
                .thenReturn(PublishedDigestDto.success(digest, persona.targetChannelId(), messageId))
                .doOnSuccess(result -> LOG.info(
                        "Published digest {} to channel {}, telegram_message_id={}",
                        result.digestId(), result.targetChannelId(), result.telegramMessageId()));
    }

    private Mono<PublishedDigestDto> recordFailure(
            GeneratedDigestDto digest,
            DigestPersona persona,
            Throwable error
    ) {
        String errorMsg = error.getMessage() != null ? error.getMessage() : "Unknown error";
        LOG.error("Failed to publish digest {}: {}", digest.digestId(), errorMsg);
        return historyRepository.findByDigestId(digest.digestId())
                .timeout(OPERATION_TIMEOUT)
                .flatMap(history -> {
                    history.markFailed(textOps.truncate(errorMsg, ERROR_MAX_LENGTH));
                    return historyRepository.save(history);
                })
                .then(Mono.just(PublishedDigestDto.failure(digest, persona.targetChannelId(), errorMsg)));
    }

    private Mono<PublishedDigestDto> republishFromHistory(RepublishContext context) {
        DigestHistory history = context.history();
        DigestPersona persona = context.persona();
        LOG.info("Republishing digest {} from history", history.digestId());
        GeneratedDigestDto digest = new GeneratedDigestDto(
                history.digestId(),
                history.personaId(),
                persona.name(),
                history.content(),
                history.messagesIncluded() != null ? history.messagesIncluded() : 0,
                history.clustersUsed() != null ? history.clustersUsed() : 0,
                java.util.List.of(),
                history.generationTimeMs() != null ? history.generationTimeMs() : 0L,
                history.createdAt()
        );
        return publish(digest, persona);
    }

    /**
     * Context for republishing from history.
     */
    private record RepublishContext(DigestHistory history, DigestPersona persona) {}
}
