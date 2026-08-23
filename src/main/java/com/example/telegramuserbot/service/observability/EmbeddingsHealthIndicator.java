package com.example.telegramuserbot.service.observability;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * Readiness probe for the TEI embeddings service. It lives outside the stand's
 * compose stack, so nothing else notices when it disappears — and every embedding
 * call then no-ops per row.
 */
@Component("embeddings")
@ConditionalOnProperty(name = "management.health.embeddings.enabled", havingValue = "true", matchIfMissing = true)
public class EmbeddingsHealthIndicator extends HttpDependencyHealthIndicator {

    public EmbeddingsHealthIndicator(
            WebClient.Builder builder,
            @Value("${embedding.url:http://embeddings:80}") String embeddingUrl,
            @Value("${management.health.embeddings.timeout-ms:2000}") long timeoutMs) {
        super(builder, embeddingUrl, "/health", Duration.ofMillis(timeoutMs));
    }
}
