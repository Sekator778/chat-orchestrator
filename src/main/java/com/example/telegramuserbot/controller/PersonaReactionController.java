package com.example.telegramuserbot.controller;

import com.example.telegramuserbot.config.ConditionalOnHttpEnabled;
import com.example.telegramuserbot.domain.PersonaReactionConfig;
import com.example.telegramuserbot.domain.PersonaReactionLog;
import com.example.telegramuserbot.domain.ReactionStatus;
import com.example.telegramuserbot.repository.PersonaReactionConfigRepository;
import com.example.telegramuserbot.repository.PersonaReactionLogRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

/**
 * REST controller for managing persona reaction configurations and monitoring.
 * Provides endpoints for CRUD operations on reaction configs, daily stats, and health.
 */
@RestController
@ConditionalOnHttpEnabled
@RequestMapping("/api/persona/reactions")
@Tag(name = "Persona Reactions", description = "Manage persona reaction configurations and monitor status")
public final class PersonaReactionController {

    private static final Logger log = LoggerFactory.getLogger(PersonaReactionController.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final PersonaReactionConfigRepository configRepository;
    private final PersonaReactionLogRepository logRepository;

    /**
     * Constructs the controller with required repositories.
     *
     * @param configRepository the reaction config repository
     * @param logRepository    the reaction log repository
     */
    public PersonaReactionController(PersonaReactionConfigRepository configRepository,
                                     PersonaReactionLogRepository logRepository) {
        this.configRepository = configRepository;
        this.logRepository = logRepository;
    }

    /**
     * Returns all reaction configurations.
     *
     * @return flux of all reaction configs
     */
    @GetMapping("/config")
    @Operation(summary = "List all reaction configs", description = "Returns all persona reaction configurations")
    public Flux<PersonaReactionConfig> listAll() {
        return configRepository.findAll()
            .timeout(TIMEOUT)
            .doOnError(e -> log.error("Failed to list reaction configs: {}", e.getMessage()));
    }

    /**
     * Returns all reaction configurations for a specific persona.
     *
     * @param personaId the persona identifier
     * @return flux of reaction configs for the persona
     */
    @GetMapping("/config/{personaId}")
    @Operation(summary = "Get configs for persona", description = "Returns all reaction configs for the given persona")
    public Flux<PersonaReactionConfig> listByPersona(
            @PathVariable @Parameter(description = "Persona ID") String personaId
    ) {
        return configRepository.findByPersonaId(personaId)
            .timeout(TIMEOUT)
            .doOnError(e -> log.error("Failed to list configs for persona={}: {}", personaId, e.getMessage()));
    }

    /**
     * Creates a new reaction configuration.
     *
     * @param body request body with personaId, channelId, maxPerDay, enabled
     * @return created reaction config
     */
    @PostMapping("/config")
    @Operation(summary = "Create reaction config", description = "Creates a new persona reaction configuration")
    public Mono<ResponseEntity<PersonaReactionConfig>> create(@RequestBody Map<String, Object> body) {
        PersonaReactionConfig config = new PersonaReactionConfig();
        config.setPersonaId((String) body.get("personaId"));
        config.setChannelId(toLong(body.get("channelId")));
        if (body.containsKey("maxPerDay")) {
            config.setMaxPerDay(toInt(body.get("maxPerDay")));
        }
        if (body.containsKey("enabled")) {
            config.setEnabled((Boolean) body.get("enabled"));
        }
        config.setCreatedAt(Instant.now());
        config.setUpdatedAt(Instant.now());
        return configRepository.save(config)
            .timeout(TIMEOUT)
            .map(saved -> ResponseEntity.status(HttpStatus.CREATED).body(saved))
            .onErrorResume(e -> {
                log.error("Failed to create reaction config: {}", e.getMessage());
                return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).build());
            });
    }

    /**
     * Updates an existing reaction configuration.
     *
     * @param id   the config ID
     * @param body request body with updatable fields
     * @return updated reaction config
     */
    @PutMapping("/config/{id}")
    @Operation(summary = "Update reaction config", description = "Updates an existing persona reaction configuration")
    public Mono<ResponseEntity<PersonaReactionConfig>> update(
            @PathVariable @Parameter(description = "Config ID") Long id,
            @RequestBody Map<String, Object> body
    ) {
        return configRepository.findById(id)
            .timeout(TIMEOUT)
            .flatMap(config -> {
                if (body.containsKey("maxPerDay")) {
                    config.setMaxPerDay(toInt(body.get("maxPerDay")));
                }
                if (body.containsKey("enabled")) {
                    config.setEnabled((Boolean) body.get("enabled"));
                }
                config.setUpdatedAt(Instant.now());
                return configRepository.save(config).timeout(TIMEOUT);
            })
            .map(ResponseEntity::ok)
            .defaultIfEmpty(ResponseEntity.notFound().build())
            .onErrorResume(e -> {
                log.error("Failed to update reaction config {}: {}", id, e.getMessage());
                return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
            });
    }

    /**
     * Deletes a reaction configuration.
     *
     * @param id the config ID
     * @return no content on success
     */
    @DeleteMapping("/config/{id}")
    @Operation(summary = "Delete reaction config", description = "Deletes a persona reaction configuration")
    public Mono<ResponseEntity<Void>> delete(
            @PathVariable @Parameter(description = "Config ID") Long id
    ) {
        return configRepository.deleteById(id)
            .timeout(TIMEOUT)
            .then(Mono.just(ResponseEntity.noContent().<Void>build()))
            .onErrorResume(e -> {
                log.error("Failed to delete reaction config {}: {}", id, e.getMessage());
                return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
            });
    }

    /**
     * Enables a reaction configuration.
     *
     * @param id the config ID
     * @return updated reaction config
     */
    @PostMapping("/config/{id}/enable")
    @Operation(summary = "Enable reaction config", description = "Enables a persona reaction configuration")
    public Mono<ResponseEntity<PersonaReactionConfig>> enable(
            @PathVariable @Parameter(description = "Config ID") Long id
    ) {
        return configRepository.findById(id)
            .timeout(TIMEOUT)
            .flatMap(config -> {
                config.setEnabled(true);
                config.setUpdatedAt(Instant.now());
                return configRepository.save(config).timeout(TIMEOUT);
            })
            .map(ResponseEntity::ok)
            .defaultIfEmpty(ResponseEntity.notFound().build())
            .onErrorResume(e -> {
                log.error("Failed to enable reaction config {}: {}", id, e.getMessage());
                return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
            });
    }

    /**
     * Disables a reaction configuration.
     *
     * @param id the config ID
     * @return updated reaction config
     */
    @PostMapping("/config/{id}/disable")
    @Operation(summary = "Disable reaction config", description = "Disables a persona reaction configuration")
    public Mono<ResponseEntity<PersonaReactionConfig>> disable(
            @PathVariable @Parameter(description = "Config ID") Long id
    ) {
        return configRepository.findById(id)
            .timeout(TIMEOUT)
            .flatMap(config -> {
                config.setEnabled(false);
                config.setUpdatedAt(Instant.now());
                return configRepository.save(config).timeout(TIMEOUT);
            })
            .map(ResponseEntity::ok)
            .defaultIfEmpty(ResponseEntity.notFound().build())
            .onErrorResume(e -> {
                log.error("Failed to disable reaction config {}: {}", id, e.getMessage());
                return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
            });
    }

    /**
     * Returns daily reaction statistics for a persona.
     *
     * @param personaId the persona identifier
     * @return map with doneToday, failedToday, floodWaitToday counts
     */
    @GetMapping("/stats/{personaId}")
    @Operation(summary = "Get daily stats", description = "Returns daily reaction statistics for a persona")
    public Mono<Map<String, Object>> stats(
            @PathVariable @Parameter(description = "Persona ID") String personaId
    ) {
        Instant today = Instant.now().truncatedTo(ChronoUnit.DAYS);
        Mono<Long> done = logRepository.countByStatusSince(ReactionStatus.DONE.name(), today).timeout(TIMEOUT);
        Mono<Long> failed = logRepository.countByStatusSince(ReactionStatus.FAILED.name(), today).timeout(TIMEOUT);
        Mono<Long> floodWait = logRepository.countByStatusSince(ReactionStatus.FLOOD_WAIT.name(), today).timeout(TIMEOUT);
        return Mono.zip(done, failed, floodWait)
            .map(tuple -> {
                Map<String, Object> result = new HashMap<>();
                result.put("personaId", personaId);
                result.put("doneToday", tuple.getT1());
                result.put("failedToday", tuple.getT2());
                result.put("floodWaitToday", tuple.getT3());
                result.put("since", today.toString());
                return result;
            })
            .onErrorResume(e -> {
                log.error("Failed to get stats for persona={}: {}", personaId, e.getMessage());
                Map<String, Object> error = new HashMap<>();
                error.put("error", e.getMessage());
                return Mono.just(error);
            });
    }

    /**
     * Returns recent reaction history for a persona.
     *
     * @param personaId the persona identifier
     * @return flux of recent log entries
     */
    @GetMapping("/stats/{personaId}/history")
    @Operation(summary = "Get persona history", description = "Returns recent reaction log entries for a persona")
    public Flux<PersonaReactionLog> history(
            @PathVariable @Parameter(description = "Persona ID") String personaId
    ) {
        return logRepository.findByPersonaIdOrderByCreatedAtDesc(personaId)
            .timeout(TIMEOUT)
            .doOnError(e -> log.error("Failed to fetch history for persona={}: {}", personaId, e.getMessage()));
    }

    /**
     * Returns overall system health metrics for the reaction system.
     *
     * @return map with pendingCount, doneToday, failedToday
     */
    @GetMapping("/health")
    @Operation(summary = "System health", description = "Returns reaction system health metrics")
    public Mono<Map<String, Object>> health() {
        Instant today = Instant.now().truncatedTo(ChronoUnit.DAYS);
        Mono<Long> pending = logRepository.countByStatusSince(ReactionStatus.PENDING.name(), today).timeout(TIMEOUT);
        Mono<Long> done = logRepository.countByStatusSince(ReactionStatus.DONE.name(), today).timeout(TIMEOUT);
        Mono<Long> failed = logRepository.countByStatusSince(ReactionStatus.FAILED.name(), today).timeout(TIMEOUT);
        Mono<Long> floodWait = logRepository.countByStatusSince(ReactionStatus.FLOOD_WAIT.name(), today).timeout(TIMEOUT);
        Mono<Long> total = configRepository.count().timeout(TIMEOUT);
        Mono<Long> enabled = configRepository.findAll().timeout(TIMEOUT).filter(c -> c.enabled()).count();
        return Mono.zip(pending, done, failed, floodWait, total, enabled)
            .map(tuple -> {
                Map<String, Object> result = new HashMap<>();
                result.put("pendingCount", tuple.getT1());
                result.put("doneToday", tuple.getT2());
                result.put("failedToday", tuple.getT3());
                result.put("floodWaitToday", tuple.getT4());
                result.put("totalConfigs", tuple.getT5());
                result.put("enabledConfigs", tuple.getT6());
                result.put("since", today.toString());
                result.put("status", tuple.getT3() > 5 ? "DEGRADED" : "HEALTHY");
                return result;
            })
            .onErrorResume(e -> {
                log.error("Failed to get reaction system health: {}", e.getMessage());
                Map<String, Object> error = new HashMap<>();
                error.put("status", "UNKNOWN");
                error.put("error", e.getMessage());
                return Mono.just(error);
            });
    }

    private Long toLong(Object value) {
        if (value instanceof Number num) {
            return num.longValue();
        }
        if (value instanceof String s) {
            return Long.parseLong(s);
        }
        return null;
    }

    private int toInt(Object value) {
        if (value instanceof Number num) {
            return num.intValue();
        }
        if (value instanceof String s) {
            return Integer.parseInt(s);
        }
        return 0;
    }
}
