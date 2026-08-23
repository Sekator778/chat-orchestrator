package com.example.telegramuserbot.service.embedding;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight Qdrant adapter using the Qdrant REST API over an injected WebClient.
 *
 * <p>Collection: {@code news_vectors} — one point per {@code bot.messages.id}.
 * Payload: {@code {"message_id": <long>}} for future filtering.
 * Distance metric: Cosine (for semantic similarity queries).
 * Vector size: read from {@code embedding.dim} property (default 1024 for BAAI/bge-m3).
 *
 * <p><b>All methods are fail-open:</b> any Qdrant unavailability/error is caught,
 * logged at WARN, and returns an empty Mono — the caller (embedding job) always
 * succeeds from Spring's perspective. App boot never blocks on Qdrant.
 *
 * <p>Qdrant REST calls used:
 * <ul>
 *   <li>{@code PUT  /collections/news_vectors} — create/ensure collection (idempotent)
 *   <li>{@code PUT  /collections/news_vectors/points} — upsert one point (wait=false)
 *   <li>{@code POST /collections/news_vectors/points/search} — top-K search (for later use)
 * </ul>
 *
 * <p>URL is read from {@code qdrant.url} (property, mapped from {@code MEMO_QDRANT_URL} env)
 * with a localhost fallback so tests and non-staging profiles are not affected.
 */
@Service
public class QdrantVectorStore {

    private static final Logger log = LoggerFactory.getLogger(QdrantVectorStore.class);

    static final String COLLECTION = "news_vectors";

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final WebClient webClient;
    private final int vectorSize;

    public QdrantVectorStore(
            WebClient.Builder webClientBuilder,
            @Value("${qdrant.url:http://localhost:6333}") String qdrantUrl,
            @Value("${embedding.dim:1024}") int vectorSize) {
        this.webClient = webClientBuilder
                .baseUrl(qdrantUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
        this.vectorSize = vectorSize;
        log.info("[QdrantVectorStore] Initialized with base URL: {}, vectorSize={}", qdrantUrl, vectorSize);
    }

    /**
     * Ensures the {@code news_vectors} collection exists in Qdrant.
     * Called once at startup (after ApplicationReady, at low order so it runs late).
     * Fires-and-forgets: if Qdrant is down, the job will just fail-open per row.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(100)
    public void ensureCollection() {
        Map<String, Object> params = Map.of(
                "vectors", Map.of(
                        "size", vectorSize,
                        "distance", "Cosine"
                )
        );

        webClient.put()
                .uri("/collections/" + COLLECTION)
                .bodyValue(params)
                .retrieve()
                .toBodilessEntity()
                .timeout(REQUEST_TIMEOUT)
                .doOnSuccess(r -> log.info("[QdrantVectorStore] Collection '{}' ready (status={})",
                        COLLECTION, r.getStatusCode()))
                .then()
                .onErrorResume(ex -> {
                    // 400 "already exists" is benign — the collection is already there.
                    // However, we must validate the existing collection's vector dimension
                    // matches our configured vectorSize to catch a future embedding-model swap
                    // that changed embedding.dim without dropping+recreating the collection.
                    if (ex instanceof WebClientResponseException wce
                            && wce.getStatusCode() == HttpStatus.BAD_REQUEST
                            && wce.getResponseBodyAsString().contains("already exists")) {
                        return webClient.get()
                                .uri("/collections/" + COLLECTION)
                                .retrieve()
                                .bodyToMono(CollectionInfoResponse.class)
                                .timeout(REQUEST_TIMEOUT)
                                .doOnNext(info -> {
                                    Integer actualSize = null;
                                    try {
                                        actualSize = info.result().config().params().vectors().size();
                                    } catch (NullPointerException npe) {
                                        // named-vector collections have a different schema; skip dim check
                                    }
                                    if (actualSize == null) {
                                        log.info("[QdrantVectorStore] Collection '{}' already exists — " +
                                                "could not read vector size (named-vector schema?), skipping dim check",
                                                COLLECTION);
                                    } else if (actualSize == vectorSize) {
                                        log.info("[QdrantVectorStore] Collection '{}' already exists — " +
                                                "dim matches configured value ({}) — OK", COLLECTION, vectorSize);
                                    } else {
                                        log.error("[QdrantVectorStore] Collection '{}' dimension MISMATCH: " +
                                                "Qdrant has size={} but embedding.dim={}.  " +
                                                "Every upsert will fail until you fix this. " +
                                                "Remediation: drop the collection in Qdrant " +
                                                "(DELETE /collections/{}) and restart, " +
                                                "or set embedding.dim={} to match the existing collection.",
                                                COLLECTION, actualSize, vectorSize, COLLECTION, actualSize);
                                    }
                                })
                                .onErrorResume(infoEx -> {
                                    log.warn("[QdrantVectorStore] Collection '{}' already exists but " +
                                            "could not verify its dimension: {}", COLLECTION, infoEx.getMessage());
                                    return Mono.empty();
                                })
                                .then();
                    }
                    log.warn("[QdrantVectorStore] Could not ensure collection '{}' (Qdrant may be down): {}",
                            COLLECTION, ex.getMessage());
                    return Mono.empty();
                })
                .subscribe();
    }

    /**
     * Upserts a single point into Qdrant.
     * Point id = {@code messageId} (matches {@code bot.messages.id}).
     *
     * <p><b>Returns {@code true} on a confirmed HTTP success, {@code false} on any
     * error.</b> The caller MUST check this value before marking {@code embedded_at} —
     * this is the key invariant that prevents Postgres↔Qdrant drift (a failed upsert
     * must not mark the row as done).
     *
     * @param messageId the {@code bot.messages.id} primary key
     * @param vector    1024-dim float array from the local TEI embeddings service (bge-m3)
     * @return {@code Mono<Boolean>}: true = point confirmed in Qdrant; false = upsert failed (fail-open)
     */
    public Mono<Boolean> upsert(long messageId, float[] vector) {
        List<Float> vectorList = new ArrayList<>(vector.length);
        for (float v : vector) {
            vectorList.add(v);
        }

        Map<String, Object> point = Map.of(
                "id", messageId,
                "vector", vectorList,
                "payload", Map.of("message_id", messageId)
        );
        Map<String, Object> body = Map.of("points", List.of(point));

        return webClient.put()
                .uri("/collections/" + COLLECTION + "/points?wait=false")
                .bodyValue(body)
                .retrieve()
                .toBodilessEntity()
                .timeout(REQUEST_TIMEOUT)
                .thenReturn(true)
                .onErrorResume(ex -> {
                    log.warn("[QdrantVectorStore] upsert failed for messageId={}: {}",
                            messageId, ex.getMessage());
                    return Mono.just(false);
                });
    }

    /**
     * Cosine similarity search <em>restricted to a specific set of candidate ids</em>.
     *
     * <p>Instead of scanning the whole collection (which causes a
     * {@code DataBufferLimitException} once the collection grows beyond ~16 K points at the
     * default 256 KB WebClient buffer), this method sends a Qdrant {@code has_id} filter so
     * only the supplied candidate ids are scored and returned.  The response payload is tiny
     * regardless of collection size — it is bounded by {@code candidateIds.size()}.
     *
     * <p>This is the only search the store offers, on purpose. The whole-collection variant
     * it replaced needed a hand-set {@code news.relevance.qdrant-top-k=50000} to work at all
     * and broke again past that many points; scoring only the ids the caller already has is
     * correct at any collection size and cheaper.
     *
     * @param vector       query vector (1024-dim, bge-m3)
     * @param candidateIds the exact point ids to score — must be {@code bot.messages.id} values
     * @return cosine-scored hits for the matching ids (only points already in the collection
     *         are returned), or empty on Qdrant error (fail-open)
     */
    public Mono<List<ScoredHit>> searchScoredAmong(float[] vector, java.util.List<Long> candidateIds) {
        if (candidateIds == null || candidateIds.isEmpty()) {
            return Mono.just(List.of());
        }

        List<Float> vectorList = new ArrayList<>(vector.length);
        for (float v : vector) {
            vectorList.add(v);
        }

        Map<String, Object> filter = Map.of(
                "must", List.of(
                        Map.of("has_id", candidateIds)
                )
        );

        Map<String, Object> body = new HashMap<>();
        body.put("vector", vectorList);
        body.put("filter", filter);
        body.put("limit", candidateIds.size());
        body.put("with_payload", false);

        return webClient.post()
                .uri("/collections/" + COLLECTION + "/points/search")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(SearchResponse.class)
                .timeout(REQUEST_TIMEOUT)
                .map(resp -> {
                    List<ScoredHit> hits = new ArrayList<>();
                    if (resp.result() != null) {
                        for (ScoredPoint p : resp.result()) {
                            if (p.id() != null && p.score() != null) {
                                hits.add(new ScoredHit(p.id(), p.score()));
                            }
                        }
                    }
                    return hits;
                })
                .onErrorResume(ex -> {
                    log.warn("[QdrantVectorStore] searchScoredAmong failed (candidateIds.size={}): {}",
                            candidateIds.size(), ex.getMessage());
                    return Mono.just(List.of());
                });
    }

    /**
     * Top-K cosine similarity search.  Intended for the persona-matching integration
     * (Track A, A-T3) — wired here for completeness so the API is in place.
     *
     * @param vector query vector (1024-dim, bge-m3)
     * @param topK   number of nearest neighbours
     * @return list of matching message ids (bot.messages.id), or empty on error
     */
    public Mono<List<Long>> search(float[] vector, int topK) {
        List<Float> vectorList = new ArrayList<>(vector.length);
        for (float v : vector) {
            vectorList.add(v);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("vector", vectorList);
        body.put("limit", topK);
        body.put("with_payload", false);

        return webClient.post()
                .uri("/collections/" + COLLECTION + "/points/search")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(SearchResponse.class)
                .timeout(REQUEST_TIMEOUT)
                .map(resp -> {
                    List<Long> ids = new ArrayList<>();
                    if (resp.result() != null) {
                        for (ScoredPoint p : resp.result()) {
                            ids.add(p.id());
                        }
                    }
                    return ids;
                })
                .onErrorResume(ex -> {
                    log.warn("[QdrantVectorStore] search failed: {}", ex.getMessage());
                    return Mono.just(List.of());
                });
    }

    // -------------------------------------------------------------------------
    // Public DTO
    // -------------------------------------------------------------------------

    /**
     * A single Qdrant search result carrying both the point id and its cosine score.
     * Used by {@code PersonaProfileService} for the value×cosine blend.
     *
     * @param id    {@code bot.messages.id} of the matching point
     * @param score cosine similarity in [-1,1] (raw Qdrant cosine; not normalised to [0,1])
     */
    public record ScoredHit(Long id, double score) {}

    // -------------------------------------------------------------------------
    // Private DTOs for search response deserialization
    // -------------------------------------------------------------------------

    private record SearchResponse(@JsonProperty("result") List<ScoredPoint> result) {}

    private record ScoredPoint(@JsonProperty("id") Long id,
                                @JsonProperty("score") Double score) {}

    // -------------------------------------------------------------------------
    // Private DTOs for collection-info response (GET /collections/{name})
    // Used by ensureCollection to validate existing collection's vector dimension.
    // Only the fields we need are mapped; all others are ignored by Jackson.
    // -------------------------------------------------------------------------

    private record CollectionInfoResponse(
            @JsonProperty("result") CollectionInfoResult result) {}

    private record CollectionInfoResult(
            @JsonProperty("config") CollectionConfig config) {}

    private record CollectionConfig(
            @JsonProperty("params") CollectionParams params) {}

    private record CollectionParams(
            @JsonProperty("vectors") VectorParams vectors) {}

    private record VectorParams(
            @JsonProperty("size") Integer size) {}
}
