package com.example.telegramuserbot.service.digest;

import com.example.telegramuserbot.domain.DigestPersona;
import com.example.telegramuserbot.dto.digest.PublishedDigestDto;
import com.example.telegramuserbot.repository.DigestHistoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Implementation of digest scheduling service.
 * Manages scheduled execution and manual triggering of digest generation.
 */
@Service
public final class DigestSchedulerServiceImpl implements DigestSchedulerService {

    private static final Logger log = LoggerFactory.getLogger(DigestSchedulerServiceImpl.class);
    private static final Duration OPERATION_TIMEOUT = Duration.ofSeconds(30);
    private static final int DEFAULT_SCHEDULE_CHECK_WINDOW_MINUTES = 5;

    private final DigestPersonaService personaService;
    private final DigestPublishingService publishingService;
    private final DigestHistoryRepository historyRepository;
    private final AtomicReference<Instant> lastRunAt;

    @Value("${digest.scheduler.enabled:true}")
    private boolean schedulerEnabled;

    @Value("${digest.scheduler.check-window-minutes:5}")
    private int checkWindowMinutes;

    /**
     * Constructs scheduler service with required dependencies.
     *
     * @param personaService persona management service
     * @param publishingService digest publishing service
     * @param historyRepository digest history repository
     */
    public DigestSchedulerServiceImpl(
            DigestPersonaService personaService,
            DigestPublishingService publishingService,
            DigestHistoryRepository historyRepository) {
        this.personaService = Objects.requireNonNull(personaService, "personaService");
        this.publishingService = Objects.requireNonNull(publishingService, "publishingService");
        this.historyRepository = Objects.requireNonNull(historyRepository, "historyRepository");
        this.lastRunAt = new AtomicReference<>(null);
        log.info("DigestSchedulerService initialized");
    }

    @Override
    public Mono<PublishedDigestDto> triggerNow(Long personaId) {
        Objects.requireNonNull(personaId, "personaId");
        log.info("Manual trigger requested for persona id={}", personaId);
        return publishingService.generateAndPublish(personaId)
            .timeout(OPERATION_TIMEOUT)
            .doOnSuccess(result -> log.info(
                "Manual trigger completed for persona id={}: success={}, digestId={}",
                personaId, result.success(), result.digestId()))
            .doOnError(e -> log.error(
                "Manual trigger failed for persona id={}: {}", personaId, e.getMessage()));
    }

    @Override
    public Flux<PublishedDigestDto> triggerAllEnabled() {
        log.info("Manual trigger requested for all enabled personas");
        return personaService.findAllEnabled()
            .flatMap(persona -> publishingService.generateAndPublish(persona.id())
                .onErrorResume(e -> {
                    log.error("Failed to trigger persona {}: {}", persona.name(), e.getMessage());
                    return Mono.just(PublishedDigestDto.failureWithoutDigest(
                        persona.id(),
                        persona.name(),
                        persona.targetChannelId(),
                        e.getMessage()));
                }))
            .timeout(Duration.ofMinutes(10))
            .doOnComplete(() -> log.info("Manual trigger completed for all enabled personas"));
    }

    @Override
    public boolean shouldRun(DigestPersona persona) {
        Objects.requireNonNull(persona, "persona");
        if (!Boolean.TRUE.equals(persona.enabled())) {
            log.debug("Persona {} is disabled", persona.name());
            return false;
        }
        if (!isWithinActiveHours(persona)) {
            log.debug("Persona {} outside active hours", persona.name());
            return false;
        }
        if (!isScheduleDue(persona)) {
            log.debug("Persona {} schedule not due", persona.name());
            return false;
        }
        log.debug("Persona {} should run", persona.name());
        return true;
    }

    @Override
    public boolean isWithinActiveHours(DigestPersona persona) {
        Objects.requireNonNull(persona, "persona");
        LocalTime start = persona.activeHoursStart();
        LocalTime end = persona.activeHoursEnd();
        if (start == null || end == null) {
            return true;
        }
        ZoneId zone = parseTimezone(persona.scheduleTimezone());
        LocalTime now = LocalTime.now(zone);
        if (start.isBefore(end)) {
            return !now.isBefore(start) && !now.isAfter(end);
        } else {
            return !now.isBefore(start) || !now.isAfter(end);
        }
    }

    @Override
    public Mono<Instant> nextScheduledRun(DigestPersona persona) {
        Objects.requireNonNull(persona, "persona");
        return Mono.fromCallable(() -> calculateNextRun(persona))
            .timeout(Duration.ofSeconds(5));
    }

    @Override
    public Flux<Instant> nextScheduledRuns(DigestPersona persona, int count) {
        Objects.requireNonNull(persona, "persona");
        if (count <= 0) {
            return Flux.empty();
        }
        return Mono.fromCallable(() -> calculateNextRuns(persona, count))
            .flatMapMany(Flux::fromIterable)
            .timeout(Duration.ofSeconds(5));
    }

    /**
     * Calculates multiple next run times for a persona.
     *
     * @param persona the persona
     * @param count number of runs to calculate
     * @return list of next run instants
     */
    private java.util.List<Instant> calculateNextRuns(DigestPersona persona, int count) {
        java.util.List<Instant> results = new java.util.ArrayList<>();
        String cron = persona.scheduleCron();
        if (cron == null || cron.isBlank()) {
            return results;
        }
        try {
            CronExpression expression = CronExpression.parse(cron);
            ZoneId zone = parseTimezone(persona.scheduleTimezone());
            LocalDateTime current = LocalDateTime.now(zone);
            for (int i = 0; i < count; i++) {
                LocalDateTime next = expression.next(current);
                if (next == null) {
                    break;
                }
                results.add(next.atZone(zone).toInstant());
                current = next;
            }
        } catch (IllegalArgumentException e) {
            log.warn("Invalid cron expression for persona {}: {}", persona.name(), cron);
        }
        return results;
    }

    @Override
    public Flux<PublishedDigestDto> processScheduledDigests() {
        if (!schedulerEnabled) {
            log.debug("Digest scheduler is disabled");
            return Flux.empty();
        }
        log.debug("Processing scheduled digests");
        lastRunAt.set(Instant.now());
        return personaService.findAllEnabled()
            .filter(this::shouldRun)
            .doOnNext(persona -> log.info("Processing scheduled digest for persona: {}", persona.name()))
            .flatMap(persona -> {
                Mono<PublishedDigestDto> publishMono = publishingService.generateAndPublish(persona.id())
                    .onErrorResume(e -> {
                        log.error("Scheduled digest failed for persona {}: {}", persona.name(), e.getMessage());
                        return Mono.just(PublishedDigestDto.failureWithoutDigest(
                            persona.id(),
                            persona.name(),
                            persona.targetChannelId(),
                            e.getMessage()));
                    });
                Integer maxDelay = persona.randomDelayMaxMinutes();
                if (maxDelay != null && maxDelay > 0) {
                    int jitterSeconds = ThreadLocalRandom.current().nextInt(1, maxDelay * 60 + 1);
                    log.info("Applying anti-detection jitter of {}s for persona: {}", jitterSeconds, persona.name());
                    return Mono.delay(Duration.ofSeconds(jitterSeconds)).then(publishMono);
                }
                return publishMono;
            })
            .doOnComplete(() -> log.debug("Scheduled digest processing completed"));
    }

    @Override
    public Mono<SchedulerStatus> status() {
        return personaService.count()
            .zipWith(personaService.countEnabled())
            .zipWith(countDigestsToday())
            .map(tuple -> new SchedulerStatus(
                schedulerEnabled,
                tuple.getT1().getT1(),
                tuple.getT1().getT2(),
                lastRunAt.get(),
                tuple.getT2()
            ))
            .timeout(OPERATION_TIMEOUT);
    }

    /**
     * Checks if persona schedule is due within the check window.
     *
     * @param persona the persona to check
     * @return true if schedule is due
     */
    private boolean isScheduleDue(DigestPersona persona) {
        String cron = persona.scheduleCron();
        if (cron == null || cron.isBlank()) {
            log.debug("Persona {} has no schedule configured", persona.name());
            return false;
        }
        try {
            CronExpression expression = CronExpression.parse(cron);
            ZoneId zone = parseTimezone(persona.scheduleTimezone());
            ZonedDateTime now = ZonedDateTime.now(zone);
            ZonedDateTime windowStart = now.minus(checkWindowMinutes, ChronoUnit.MINUTES);
            LocalDateTime lastRunLocal = persona.lastRunAt() != null
                ? LocalDateTime.ofInstant(persona.lastRunAt(), zone)
                : null;
            LocalDateTime nextAfterLastRun = lastRunLocal != null
                ? expression.next(lastRunLocal)
                : expression.next(windowStart.toLocalDateTime());
            if (nextAfterLastRun == null) {
                return false;
            }
            ZonedDateTime nextRun = nextAfterLastRun.atZone(zone);
            boolean isInWindow = !nextRun.isAfter(now) && nextRun.isAfter(windowStart);
            boolean isOverdue = !nextRun.isAfter(windowStart);
            if (isOverdue) {
                log.info("Persona {} is overdue: nextRun={}, windowStart={}, catching up",
                    persona.name(), nextRun, windowStart);
            }
            if (isInWindow) {
                log.debug("Persona {} schedule is due: nextRun={}, now={}",
                    persona.name(), nextRun, now);
            }
            return isInWindow || isOverdue;
        } catch (IllegalArgumentException e) {
            log.warn("Invalid cron expression for persona {}: {}", persona.name(), cron);
            return false;
        }
    }

    /**
     * Calculates next run time for a persona.
     *
     * @param persona the persona
     * @return next run instant or null
     */
    private Instant calculateNextRun(DigestPersona persona) {
        String cron = persona.scheduleCron();
        if (cron == null || cron.isBlank()) {
            return null;
        }
        try {
            CronExpression expression = CronExpression.parse(cron);
            ZoneId zone = parseTimezone(persona.scheduleTimezone());
            LocalDateTime now = LocalDateTime.now(zone);
            LocalDateTime next = expression.next(now);
            if (next == null) {
                return null;
            }
            return next.atZone(zone).toInstant();
        } catch (IllegalArgumentException e) {
            log.warn("Invalid cron expression: {}", cron);
            return null;
        }
    }

    /**
     * Parses timezone string to ZoneId.
     *
     * @param timezone timezone string
     * @return ZoneId or UTC if invalid
     */
    private ZoneId parseTimezone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return ZoneId.of("UTC");
        }
        try {
            return ZoneId.of(timezone);
        } catch (Exception e) {
            log.warn("Invalid timezone {}, using UTC", timezone);
            return ZoneId.of("UTC");
        }
    }

    /**
     * Counts digests generated today.
     *
     * @return count of digests
     */
    private Mono<Long> countDigestsToday() {
        Instant startOfDay = LocalDateTime.now()
            .truncatedTo(ChronoUnit.DAYS)
            .atZone(ZoneId.systemDefault())
            .toInstant();
        return historyRepository.findByStatus("PUBLISHED")
            .filter(h -> h.publishedAt() != null && h.publishedAt().isAfter(startOfDay))
            .count()
            .timeout(Duration.ofSeconds(10))
            .onErrorReturn(0L);
    }
}
