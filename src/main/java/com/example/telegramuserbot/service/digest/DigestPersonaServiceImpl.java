package com.example.telegramuserbot.service.digest;

import com.example.telegramuserbot.domain.DigestPersona;
import com.example.telegramuserbot.repository.DigestPersonaRepository;
import com.example.telegramuserbot.service.proactive.PersonaProfileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Implementation of DigestPersonaService.
 * Manages digest persona lifecycle with reactive database operations.
 */
@Service
public final class DigestPersonaServiceImpl implements DigestPersonaService {

    private static final Logger log = LoggerFactory.getLogger(DigestPersonaServiceImpl.class);
    private static final Duration OPERATION_TIMEOUT = Duration.ofSeconds(5);

    private final DigestPersonaRepository personaRepository;
    private final PersonaProfileService personaProfileService;

    public DigestPersonaServiceImpl(DigestPersonaRepository personaRepository,
                                    PersonaProfileService personaProfileService) {
        this.personaRepository = Objects.requireNonNull(personaRepository, "personaRepository must not be null");
        this.personaProfileService = Objects.requireNonNull(personaProfileService, "personaProfileService must not be null");
    }

    @Override
    public Mono<DigestPersona> create(DigestPersona persona) {
        Objects.requireNonNull(persona, "persona must not be null");
        log.info("Creating digest persona: name={}, botId={}, targetChannel={}",
                persona.name(), persona.botId(), persona.targetChannelId());
        persona.setCreatedAt(Instant.now());
        persona.setUpdatedAt(Instant.now());
        return personaRepository.save(persona)
                .timeout(OPERATION_TIMEOUT)
                .doOnSuccess(saved -> log.info("Created digest persona: id={}, name={}", saved.id(), saved.name()))
                .doOnError(e -> log.error("Failed to create digest persona: {}", e.getMessage()));
    }

    @Override
    public Mono<DigestPersona> update(Long id, DigestPersona persona) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(persona, "persona must not be null");
        log.info("Updating digest persona: id={}", id);
        return personaRepository.findById(id)
                .timeout(OPERATION_TIMEOUT)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Persona not found: " + id)))
                .flatMap(existing -> {
                    copyUpdatableFields(persona, existing);
                    existing.setUpdatedAt(Instant.now());
                    return personaRepository.save(existing);
                })
                .timeout(OPERATION_TIMEOUT)
                .doOnSuccess(updated -> {
                    log.info("Updated digest persona: id={}, name={}", updated.id(), updated.name());
                    if (updated.botId() != null) {
                        personaProfileService.invalidate(String.valueOf(updated.botId()));
                    }
                })
                .doOnError(e -> log.error("Failed to update digest persona {}: {}", id, e.getMessage()));
    }

    @Override
    public Mono<Void> delete(Long id) {
        Objects.requireNonNull(id, "id must not be null");
        log.info("Deleting digest persona: id={}", id);
        return personaRepository.deleteById(id)
                .timeout(OPERATION_TIMEOUT)
                .doOnSuccess(v -> log.info("Deleted digest persona: id={}", id))
                .doOnError(e -> log.error("Failed to delete digest persona {}: {}", id, e.getMessage()));
    }

    @Override
    public Mono<DigestPersona> findById(Long id) {
        Objects.requireNonNull(id, "id must not be null");
        return personaRepository.findById(id)
                .timeout(OPERATION_TIMEOUT);
    }

    @Override
    public Mono<DigestPersona> findByName(String name) {
        Objects.requireNonNull(name, "name must not be null");
        return personaRepository.findByName(name)
                .timeout(OPERATION_TIMEOUT);
    }

    @Override
    public Flux<DigestPersona> findAll() {
        return personaRepository.findAllOrderByName()
                .timeout(OPERATION_TIMEOUT);
    }

    @Override
    public Flux<DigestPersona> findAllEnabled() {
        return personaRepository.findAllEnabled()
                .timeout(OPERATION_TIMEOUT);
    }

    @Override
    public Flux<DigestPersona> findByBotId(Long botId) {
        Objects.requireNonNull(botId, "botId must not be null");
        return personaRepository.findByBotId(botId)
                .timeout(OPERATION_TIMEOUT);
    }

    @Override
    public Mono<DigestPersona> enable(Long id) {
        Objects.requireNonNull(id, "id must not be null");
        log.info("Enabling digest persona: id={}", id);
        return personaRepository.updateEnabled(id, true)
                .timeout(OPERATION_TIMEOUT)
                .then(personaRepository.findById(id))
                .timeout(OPERATION_TIMEOUT)
                .doOnSuccess(p -> log.info("Enabled digest persona: id={}, name={}", id, p != null ? p.name() : "unknown"));
    }

    @Override
    public Mono<DigestPersona> disable(Long id) {
        Objects.requireNonNull(id, "id must not be null");
        log.info("Disabling digest persona: id={}", id);
        return personaRepository.updateEnabled(id, false)
                .timeout(OPERATION_TIMEOUT)
                .then(personaRepository.findById(id))
                .timeout(OPERATION_TIMEOUT)
                .doOnSuccess(p -> log.info("Disabled digest persona: id={}, name={}", id, p != null ? p.name() : "unknown"));
    }

    @Override
    public Mono<Long> count() {
        return personaRepository.count()
                .timeout(OPERATION_TIMEOUT);
    }

    @Override
    public Mono<Long> countEnabled() {
        return personaRepository.countEnabled()
                .timeout(OPERATION_TIMEOUT);
    }

    @Override
    public Mono<Boolean> isNameAvailable(String name) {
        Objects.requireNonNull(name, "name must not be null");
        return personaRepository.existsByName(name)
                .timeout(OPERATION_TIMEOUT)
                .map(exists -> !exists);
    }

    private void copyUpdatableFields(DigestPersona source, DigestPersona target) {
        if (source.name() != null) {
            target.setName(source.name());
        }
        if (source.description() != null) {
            target.setDescription(source.description());
        }
        if (source.botId() != null) {
            target.setBotId(source.botId());
        }
        if (source.targetChannelId() != null) {
            target.setTargetChannelId(source.targetChannelId());
        }
        if (source.enabled() != null) {
            target.setEnabled(source.enabled());
        }
        if (source.personaStyle() != null) {
            target.setPersonaStyle(source.personaStyle());
        }
        if (source.customSystemPrompt() != null) {
            target.setCustomSystemPrompt(source.customSystemPrompt());
        }
        if (source.scheduleCron() != null) {
            target.setScheduleCron(source.scheduleCron());
        }
        if (source.scheduleTimezone() != null) {
            target.setScheduleTimezone(source.scheduleTimezone());
        }
        target.setActiveHoursStart(source.activeHoursStart());
        target.setActiveHoursEnd(source.activeHoursEnd());
        if (source.lookbackHours() != null) {
            target.setLookbackHours(source.lookbackHours());
        }
        if (source.maxMessages() != null) {
            target.setMaxMessages(source.maxMessages());
        }
        if (source.language() != null) {
            target.setLanguage(source.language());
        }
        if (source.minClusterSize() != null) {
            target.setMinClusterSize(source.minClusterSize());
        }
        if (source.minImportanceScore() != null) {
            target.setMinImportanceScore(source.minImportanceScore());
        }
        if (source.sourceTrustThreshold() != null) {
            target.setSourceTrustThreshold(source.sourceTrustThreshold());
        }
        target.setExcludedChannelIds(source.excludedChannelIds());
        target.setTopicKeywords(source.topicKeywords());
        target.setNegativeKeywords(source.negativeKeywords());
        if (source.modelName() != null) {
            target.setModelName(source.modelName());
        }
        if (source.temperature() != null) {
            target.setTemperature(source.temperature());
        }
        if (source.maxTokens() != null) {
            target.setMaxTokens(source.maxTokens());
        }
    }
}
