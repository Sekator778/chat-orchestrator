package com.example.telegramuserbot.service.observability;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Shared shape for the fail-open HTTP dependencies: ask a cheap endpoint, report
 * UP or DOWN, never hang.
 * <p>
 * These belong to the readiness group, not to liveness. Qdrant and the embeddings
 * service being down degrades semantic ranking to value-only — the app keeps
 * serving, so restarting it would fix nothing and lose the Telegram session.
 * The probe timeout is deliberately short: a health endpoint that blocks is worse
 * than one that reports DOWN.
 */
abstract class HttpDependencyHealthIndicator implements ReactiveHealthIndicator {

    private final WebClient webClient;
    private final String baseUrl;
    private final String probePath;
    private final Duration timeout;

    protected HttpDependencyHealthIndicator(WebClient.Builder builder,
                                            String baseUrl,
                                            String probePath,
                                            Duration timeout) {
        this.webClient = builder.baseUrl(baseUrl).build();
        this.baseUrl = baseUrl;
        this.probePath = probePath;
        this.timeout = timeout;
    }

    @Override
    public Mono<Health> health() {
        return webClient.get()
                .uri(probePath)
                .retrieve()
                .toBodilessEntity()
                .timeout(timeout)
                .map(response -> Health.up()
                        .withDetail("url", baseUrl)
                        .withDetail("status", response.getStatusCode().value())
                        .build())
                .onErrorResume(error -> Mono.just(Health.down()
                        .withDetail("url", baseUrl)
                        .withDetail("error", error.getClass().getSimpleName()
                                + ": " + String.valueOf(error.getMessage()))
                        .build()));
    }
}
