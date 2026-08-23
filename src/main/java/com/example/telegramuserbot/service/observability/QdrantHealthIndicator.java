package com.example.telegramuserbot.service.observability;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * Readiness probe for the vector store. Without it a Qdrant outage is invisible:
 * the embedding job and cosine ranking fail open, so the app looks healthy while
 * ranking silently degrades to value-only.
 */
@Component("qdrant")
@ConditionalOnProperty(name = "management.health.qdrant.enabled", havingValue = "true", matchIfMissing = true)
public class QdrantHealthIndicator extends HttpDependencyHealthIndicator {

    public QdrantHealthIndicator(
            WebClient.Builder builder,
            @Value("${qdrant.url:http://localhost:6333}") String qdrantUrl,
            @Value("${management.health.qdrant.timeout-ms:2000}") long timeoutMs) {
        super(builder, qdrantUrl, "/readyz", Duration.ofMillis(timeoutMs));
    }
}
