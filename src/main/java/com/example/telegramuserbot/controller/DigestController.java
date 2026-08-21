package com.example.telegramuserbot.controller;

import com.example.telegramuserbot.config.ConditionalOnHttpEnabled;
import com.example.telegramuserbot.domain.DigestPersona;
import com.example.telegramuserbot.dto.digest.*;
import com.example.telegramuserbot.repository.DigestHistoryRepository;
import com.example.telegramuserbot.service.digest.*;
import com.example.telegramuserbot.service.maintenance.ClusteringScheduledJob;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST controller for digest management operations.
 * Provides endpoints for persona CRUD, digest generation, and analytics.
 */
@RestController
@ConditionalOnHttpEnabled
@RequestMapping("/api/digest")
@Tag(name = "Digest Management", description = "Digest personas, scheduling, and analytics")
public final class DigestController {

    private static final Logger LOG = LoggerFactory.getLogger(DigestController.class);
    private static final Logger UI_LOG = LoggerFactory.getLogger("frontend.ui");
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final DigestPersonaService personaService;
    private final DigestGenerationService generationService;
    private final DigestPublishingService publishingService;
    private final DigestSchedulerService schedulerService;
    private final DigestAnalyticsService analyticsService;
    private final DigestHistoryRepository historyRepository;
    private final Optional<ClusteringScheduledJob> clusteringJob;

    public DigestController(
            DigestPersonaService personaService,
            DigestGenerationService generationService,
            DigestPublishingService publishingService,
            DigestSchedulerService schedulerService,
            DigestAnalyticsService analyticsService,
            DigestHistoryRepository historyRepository,
            @Autowired(required = false) ClusteringScheduledJob clusteringJob
    ) {
        this.personaService = personaService;
        this.generationService = generationService;
        this.publishingService = publishingService;
        this.schedulerService = schedulerService;
        this.analyticsService = analyticsService;
        this.historyRepository = historyRepository;
        this.clusteringJob = Optional.ofNullable(clusteringJob);
    }

    /**
     * Lists all digest personas.
     *
     * @return flux of personas
     */
    @GetMapping("/personas")
    @Operation(summary = "List all personas", description = "Returns all digest personas")
    public Mono<ResponseEntity<List<DigestPersonaDto>>> listPersonas() {
        UI_LOG.info("UI:digest:list-personas");
        return personaService.findAll()
                .map(DigestPersonaDto::from)
                .collectList()
                .timeout(TIMEOUT)
                .map(ResponseEntity::ok)
                .onErrorResume(e -> {
                    LOG.error("Failed to list personas: {}", e.getMessage());
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
                });
    }

    /**
     * Gets a persona by ID.
     *
     * @param id persona ID
     * @return persona details
     */
    @GetMapping("/personas/{id}")
    @Operation(summary = "Get persona", description = "Returns persona by ID")
    public Mono<ResponseEntity<DigestPersonaDto>> getPersona(
            @PathVariable @Parameter(description = "Persona ID") Long id
    ) {
        UI_LOG.info("UI:digest:get-persona id={}", id);
        return personaService.findById(id)
                .map(DigestPersonaDto::from)
                .timeout(TIMEOUT)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build())
                .onErrorResume(e -> {
                    LOG.error("Failed to get persona {}: {}", id, e.getMessage());
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
                });
    }

    /**
     * Creates a new digest persona.
     *
     * @param dto persona data
     * @return created persona
     */
    @PostMapping("/personas")
    @Operation(summary = "Create persona", description = "Creates a new digest persona")
    public Mono<ResponseEntity<DigestPersonaDto>> createPersona(@RequestBody DigestPersonaDto dto) {
        UI_LOG.info("UI:digest:create-persona name={} botId={} targetChannel={}",
                dto.name(), dto.botId(), dto.targetChannelId());
        DigestPersona entity = dto.toEntity();
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());
        return personaService.create(entity)
                .map(DigestPersonaDto::from)
                .timeout(TIMEOUT)
                .map(created -> ResponseEntity.status(HttpStatus.CREATED).body(created))
                .onErrorResume(e -> {
                    LOG.error("Failed to create persona: {}", e.getMessage());
                    return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST)
                            .body(null));
                });
    }

    /**
     * Updates an existing digest persona.
     *
     * @param id persona ID
     * @param dto updated persona data
     * @return updated persona
     */
    @PutMapping("/personas/{id}")
    @Operation(summary = "Update persona", description = "Updates an existing digest persona")
    public Mono<ResponseEntity<DigestPersonaDto>> updatePersona(
            @PathVariable @Parameter(description = "Persona ID") Long id,
            @RequestBody DigestPersonaDto dto
    ) {
        UI_LOG.info("UI:digest:update-persona id={} name={}", id, dto.name());
        DigestPersona entity = dto.toEntity();
        entity.setUpdatedAt(Instant.now());
        return personaService.update(id, entity)
                .map(DigestPersonaDto::from)
                .timeout(TIMEOUT)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build())
                .onErrorResume(e -> {
                    LOG.error("Failed to update persona {}: {}", id, e.getMessage());
                    return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).build());
                });
    }

    /**
     * Deletes a digest persona.
     *
     * @param id persona ID
     * @return no content on success
     */
    @DeleteMapping("/personas/{id}")
    @Operation(summary = "Delete persona", description = "Deletes a digest persona")
    public Mono<ResponseEntity<Void>> deletePersona(
            @PathVariable @Parameter(description = "Persona ID") Long id
    ) {
        UI_LOG.info("UI:digest:delete-persona id={}", id);
        return personaService.delete(id)
                .timeout(TIMEOUT)
                .then(Mono.just(ResponseEntity.noContent().<Void>build()))
                .onErrorResume(e -> {
                    LOG.error("Failed to delete persona {}: {}", id, e.getMessage());
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
                });
    }

    /**
     * Enables a persona.
     *
     * @param id persona ID
     * @return updated persona
     */
    @PostMapping("/personas/{id}/enable")
    @Operation(summary = "Enable persona", description = "Enables digest generation for persona")
    public Mono<ResponseEntity<DigestPersonaDto>> enablePersona(
            @PathVariable @Parameter(description = "Persona ID") Long id
    ) {
        UI_LOG.info("UI:digest:enable-persona id={}", id);
        return personaService.enable(id)
                .map(DigestPersonaDto::from)
                .timeout(TIMEOUT)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build())
                .onErrorResume(e -> {
                    LOG.error("Failed to enable persona {}: {}", id, e.getMessage());
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
                });
    }

    /**
     * Disables a persona.
     *
     * @param id persona ID
     * @return updated persona
     */
    @PostMapping("/personas/{id}/disable")
    @Operation(summary = "Disable persona", description = "Disables digest generation for persona")
    public Mono<ResponseEntity<DigestPersonaDto>> disablePersona(
            @PathVariable @Parameter(description = "Persona ID") Long id
    ) {
        UI_LOG.info("UI:digest:disable-persona id={}", id);
        return personaService.disable(id)
                .map(DigestPersonaDto::from)
                .timeout(TIMEOUT)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build())
                .onErrorResume(e -> {
                    LOG.error("Failed to disable persona {}: {}", id, e.getMessage());
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
                });
    }

    /**
     * Generates a test digest for a persona.
     *
     * @param id persona ID
     * @param request test parameters
     * @return generated digest preview
     */
    @PostMapping("/personas/{id}/test")
    @Operation(summary = "Generate test digest", description = "Generates a preview digest without publishing")
    public Mono<ResponseEntity<GeneratedDigestDto>> testDigest(
            @PathVariable @Parameter(description = "Persona ID") Long id,
            @RequestBody(required = false) DigestTestRequest request
    ) {
        DigestTestRequest req = request != null ? request : DigestTestRequest.defaults();
        UI_LOG.info("UI:digest:test id={} lookback={} max={} preview={}",
                id, req.lookbackHours(), req.maxMessages(), req.isPreview());
        return generationService.generateTestDigest(id)
                .timeout(Duration.ofMinutes(2))
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build())
                .onErrorResume(e -> {
                    LOG.error("Failed to generate test digest for persona {}: {}", id, e.getMessage());
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .body(GeneratedDigestDto.empty(id, "Unknown")));
                });
    }

    /**
     * Publishes a digest for a persona immediately.
     *
     * @param id persona ID
     * @param lookbackHours optional custom lookback hours
     * @return published digest result
     */
    @PostMapping("/personas/{id}/publish")
    @Operation(summary = "Publish digest now", description = "Generates and publishes a digest immediately")
    public Mono<ResponseEntity<PublishedDigestDto>> publishNow(
            @PathVariable @Parameter(description = "Persona ID") Long id,
            @RequestParam(required = false) @Parameter(description = "Custom lookback hours") Integer lookbackHours
    ) {
        UI_LOG.info("UI:digest:publish-now id={} lookback={}", id, lookbackHours);
        Mono<PublishedDigestDto> result = lookbackHours != null && lookbackHours > 0
                ? publishingService.generateAndPublish(id, lookbackHours)
                : publishingService.generateAndPublish(id);
        return result
                .timeout(Duration.ofMinutes(2))
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build())
                .onErrorResume(e -> {
                    LOG.error("Failed to publish digest for persona {}: {}", id, e.getMessage());
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
                });
    }

    /**
     * Gets history for a persona.
     *
     * @param id persona ID
     * @param limit max records to return
     * @return history entries
     */
    @GetMapping("/personas/{id}/history")
    @Operation(summary = "Get persona history", description = "Returns digest history for a persona")
    public Mono<ResponseEntity<List<DigestHistoryDto>>> getPersonaHistory(
            @PathVariable @Parameter(description = "Persona ID") Long id,
            @RequestParam(defaultValue = "20") @Parameter(description = "Maximum records") int limit
    ) {
        UI_LOG.info("UI:digest:history id={} limit={}", id, limit);
        return personaService.findById(id)
                .flatMap(persona ->
                        historyRepository.findRecentByPersonaId(id, limit)
                                .map(h -> DigestHistoryDto.from(h, persona.name()))
                                .collectList()
                )
                .timeout(TIMEOUT)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build())
                .onErrorResume(e -> {
                    LOG.error("Failed to get history for persona {}: {}", id, e.getMessage());
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
                });
    }

    /**
     * Gets next scheduled runs for a persona.
     *
     * @param id persona ID
     * @param count number of runs to preview
     * @return list of next run times
     */
    @GetMapping("/personas/{id}/schedule")
    @Operation(summary = "Get next scheduled runs", description = "Returns next scheduled run times for a persona")
    public Mono<ResponseEntity<List<Instant>>> getSchedule(
            @PathVariable @Parameter(description = "Persona ID") Long id,
            @RequestParam(defaultValue = "5") @Parameter(description = "Number of runs to preview") int count
    ) {
        UI_LOG.info("UI:digest:schedule id={} count={}", id, count);
        return personaService.findById(id)
                .flatMap(persona ->
                        schedulerService.nextScheduledRuns(persona, count)
                                .collectList()
                )
                .timeout(TIMEOUT)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build())
                .onErrorResume(e -> {
                    LOG.error("Failed to get schedule for persona {}: {}", id, e.getMessage());
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
                });
    }

    /**
     * Triggers clustering job manually.
     *
     * @return status message
     */
    @PostMapping("/cluster-now")
    @Operation(summary = "Trigger clustering", description = "Manually triggers the clustering job")
    public Mono<ResponseEntity<Map<String, String>>> triggerClustering() {
        UI_LOG.info("UI:digest:cluster-now");
        if (clusteringJob.isEmpty()) {
            LOG.warn("Clustering job is not enabled (clustering.job.enabled=false)");
            return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("status", "unavailable",
                            "message", "Clustering job is not enabled. Set clustering.job.enabled=true")));
        }
        try {
            clusteringJob.get().runClustering();
            return Mono.just(ResponseEntity.ok(Map.of(
                    "status", "triggered",
                    "message", "Clustering job has been triggered"
            )));
        } catch (Exception e) {
            LOG.error("Failed to trigger clustering: {}", e.getMessage());
            return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("status", "error", "message", e.getMessage())));
        }
    }

    /**
     * Triggers all enabled personas to generate digests.
     *
     * @return results for all personas
     */
    @PostMapping("/trigger-all")
    @Operation(summary = "Trigger all personas", description = "Generates and publishes digests for all enabled personas")
    public Mono<ResponseEntity<List<PublishedDigestDto>>> triggerAll() {
        UI_LOG.info("UI:digest:trigger-all");
        return schedulerService.triggerAllEnabled()
                .collectList()
                .timeout(Duration.ofMinutes(5))
                .map(ResponseEntity::ok)
                .onErrorResume(e -> {
                    LOG.error("Failed to trigger all personas: {}", e.getMessage());
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
                });
    }

    /**
     * Gets comprehensive analytics.
     *
     * @param lookbackHours hours to look back for daily stats
     * @return analytics data
     */
    @GetMapping("/analytics")
    @Operation(summary = "Get analytics", description = "Returns comprehensive digest system analytics")
    public Mono<ResponseEntity<DigestAnalyticsDto>> getAnalytics(
            @RequestParam(defaultValue = "24") @Parameter(description = "Lookback hours") int lookbackHours
    ) {
        UI_LOG.info("UI:digest:analytics lookback={}", lookbackHours);
        return analyticsService.getAnalytics(lookbackHours)
                .timeout(TIMEOUT)
                .map(ResponseEntity::ok)
                .onErrorResume(e -> {
                    LOG.error("Failed to get analytics: {}", e.getMessage());
                    return Mono.just(ResponseEntity.ok(DigestAnalyticsDto.empty()));
                });
    }

    /**
     * Gets cluster statistics.
     *
     * @param lookbackHours hours to look back
     * @return cluster stats
     */
    @GetMapping("/analytics/clusters")
    @Operation(summary = "Get cluster stats", description = "Returns clustering statistics")
    public Mono<ResponseEntity<ClusterStatsDto>> getClusterStats(
            @RequestParam(defaultValue = "24") @Parameter(description = "Lookback hours") int lookbackHours
    ) {
        UI_LOG.info("UI:digest:cluster-stats lookback={}", lookbackHours);
        return analyticsService.getClusterStats(lookbackHours)
                .timeout(TIMEOUT)
                .map(ResponseEntity::ok)
                .onErrorResume(e -> {
                    LOG.error("Failed to get cluster stats: {}", e.getMessage());
                    return Mono.just(ResponseEntity.ok(ClusterStatsDto.empty()));
                });
    }

    /**
     * Gets source trust statistics.
     *
     * @return source stats
     */
    @GetMapping("/analytics/sources")
    @Operation(summary = "Get source stats", description = "Returns source trust statistics")
    public Mono<ResponseEntity<SourceStatsDto>> getSourceStats() {
        UI_LOG.info("UI:digest:source-stats");
        return analyticsService.getSourceStats()
                .timeout(TIMEOUT)
                .map(ResponseEntity::ok)
                .onErrorResume(e -> {
                    LOG.error("Failed to get source stats: {}", e.getMessage());
                    return Mono.just(ResponseEntity.ok(SourceStatsDto.empty()));
                });
    }

    /**
     * Gets recent activity.
     *
     * @param limit max entries to return
     * @return activity entries
     */
    @GetMapping("/analytics/activity")
    @Operation(summary = "Get recent activity", description = "Returns recent digest activity timeline")
    public Mono<ResponseEntity<List<DigestAnalyticsDto.ActivityEntry>>> getRecentActivity(
            @RequestParam(defaultValue = "20") @Parameter(description = "Maximum entries") int limit
    ) {
        UI_LOG.info("UI:digest:activity limit={}", limit);
        return analyticsService.getRecentActivity(limit)
                .timeout(TIMEOUT)
                .map(ResponseEntity::ok)
                .onErrorResume(e -> {
                    LOG.error("Failed to get recent activity: {}", e.getMessage());
                    return Mono.just(ResponseEntity.ok(List.of()));
                });
    }

    /**
     * Gets all digest history.
     *
     * @param limit max records to return
     * @return all history entries
     */
    @GetMapping("/history")
    @Operation(summary = "Get all history", description = "Returns all digest history")
    public Mono<ResponseEntity<List<DigestHistoryDto>>> getAllHistory(
            @RequestParam(defaultValue = "50") @Parameter(description = "Maximum records") int limit
    ) {
        UI_LOG.info("UI:digest:all-history limit={}", limit);
        return historyRepository.findAllRecent(limit)
                .flatMap(h ->
                        personaService.findById(h.personaId())
                                .map(p -> DigestHistoryDto.from(h, p.name()))
                                .defaultIfEmpty(DigestHistoryDto.from(h))
                )
                .collectList()
                .timeout(TIMEOUT)
                .map(ResponseEntity::ok)
                .onErrorResume(e -> {
                    LOG.error("Failed to get all history: {}", e.getMessage());
                    return Mono.just(ResponseEntity.ok(List.of()));
                });
    }

    /**
     * Gets scheduler status.
     *
     * @return scheduler status
     */
    @GetMapping("/scheduler/status")
    @Operation(summary = "Get scheduler status", description = "Returns digest scheduler status and statistics")
    public Mono<ResponseEntity<DigestSchedulerService.SchedulerStatus>> getSchedulerStatus() {
        UI_LOG.info("UI:digest:scheduler-status");
        return schedulerService.status()
                .timeout(TIMEOUT)
                .map(ResponseEntity::ok)
                .onErrorResume(e -> {
                    LOG.error("Failed to get scheduler status: {}", e.getMessage());
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
                });
    }

    /**
     * Republishes an existing digest.
     *
     * @param digestId digest ID to republish
     * @return republished digest result
     */
    @PostMapping("/republish/{digestId}")
    @Operation(summary = "Republish digest", description = "Republishes an existing digest")
    public Mono<ResponseEntity<PublishedDigestDto>> republish(
            @PathVariable @Parameter(description = "Digest ID") String digestId
    ) {
        UI_LOG.info("UI:digest:republish digestId={}", digestId);
        return publishingService.republish(digestId)
                .timeout(Duration.ofMinutes(1))
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.notFound().build())
                .onErrorResume(e -> {
                    LOG.error("Failed to republish digest {}: {}", digestId, e.getMessage());
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
                });
    }
}
