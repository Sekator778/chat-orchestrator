package com.example.telegramuserbot.service.embedding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

/**
 * Thin reactive wrapper around the self-hosted HuggingFace Text Embeddings Inference (TEI)
 * service for generating text embeddings via BAAI/bge-m3.
 *
 * <p>Model: {@code BAAI/bge-m3} (1024-dim, top multilingual model including Russian,
 * free, no quota). One call per distinct news story; the embedding job caches by
 * {@code content_simhash} so cluster siblings never make a second call.
 *
 * <p><b>Fail-open:</b> any error (network, timeout, service down, etc.)
 * returns {@link Mono#empty()} and logs WARN — never throws to the caller, never blocks
 * app boot. If the embeddings service is unreachable (e.g. in the headless smoke boot),
 * embed() simply returns empty and the job no-ops per row.
 *
 * <p>Uses TEI's native {@code POST /embed} endpoint (fully reactive, non-blocking WebClient).
 * Request body: {@code {"inputs": "<text>"}}. Response: {@code [[f, f, ...]]} (array of
 * arrays); we take {@code [0]} as the 1024-dim result vector.
 *
 * <p>URL from {@code embedding.url} property (default {@code http://embeddings:80}).
 */
@Service
public class EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingClient.class);

    /** Model served by the local TEI instance. */
    static final String MODEL = "BAAI/bge-m3";

    /** Output dimensionality for bge-m3. */
    static final int EMBEDDING_DIM = 1024;

    /** Truncate input to this character length to stay comfortably under the token limit. */
    private static final int MAX_INPUT_CHARS = 8000;

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final WebClient webClient;

    public EmbeddingClient(
            WebClient.Builder webClientBuilder,
            @Value("${embedding.url:http://embeddings:80}") String embeddingUrl) {
        this.webClient = webClientBuilder
                .baseUrl(embeddingUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        // Do NOT call the embeddings service here — lazy; constructor must be inert.
    }

    /**
     * Logs startup state after the context is ready. Does not connect to the embeddings service.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("[EmbeddingClient] Initialized with local TEI embeddings service; model={}, dim={}",
                MODEL, EMBEDDING_DIM);
    }

    /**
     * Embeds {@code text} using the local TEI service (BAAI/bge-m3).
     *
     * <p>Calls {@code POST /embed} with body {@code {"inputs": "<text>"}} and parses the
     * response {@code [[f, f, ...]]} (array of arrays), taking the first element as the
     * 1024-dim result vector.
     *
     * @param text input text (title + content, pre-concatenated by the caller)
     * @return {@code Mono<float[]>} with 1024 dimensions, or {@link Mono#empty()} on any error
     */
    public Mono<float[]> embed(String text) {
        if (text == null || text.isBlank()) {
            return Mono.empty();
        }

        String truncated = text.length() > MAX_INPUT_CHARS ? text.substring(0, MAX_INPUT_CHARS) : text;
        Map<String, String> body = Map.of("inputs", truncated);

        return webClient.post()
                .uri("/embed")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(float[][].class)
                .timeout(REQUEST_TIMEOUT)
                .flatMap(matrix -> {
                    if (matrix == null || matrix.length == 0
                            || matrix[0] == null || matrix[0].length == 0) {
                        log.warn("[EmbeddingClient] TEI returned empty or null embedding matrix — skipping");
                        return Mono.<float[]>empty();
                    }
                    return Mono.just(matrix[0]);
                })
                .onErrorResume(ex -> {
                    log.warn("[EmbeddingClient] Embedding call failed ({}): {} — skipping",
                            ex.getClass().getSimpleName(), ex.getMessage());
                    return Mono.empty();
                });
    }
}
