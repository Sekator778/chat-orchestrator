package com.example.telegramuserbot.service.hn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Reactive client for the Hacker News Firebase REST API.
 *
 * <p>Uses the free, unauthenticated HN Firebase endpoint:
 * <ul>
 *   <li>{@code GET /v0/topstories.json} — returns an ordered list of up to ~500 story ids.</li>
 *   <li>{@code GET /v0/item/{id}.json} — returns the full item object for one story.</li>
 * </ul>
 *
 * <h2>Fail-open contract</h2>
 * Any network, timeout, or parse error is caught per-item, logged at WARN level, and
 * returns {@link Mono#empty()} / {@link Flux#empty()} — one bad fetch never aborts a harvest run.
 *
 * <h2>No blocking at construction</h2>
 * The {@link WebClient} is built eagerly but no network calls are made until a reactive operator
 * subscribes. Construction is safe inside a Spring context-load (smoke-gate safe).
 */
@Service
public class HnClient {

    private static final Logger log = LoggerFactory.getLogger(HnClient.class);

    private static final String HN_BASE_URL    = "https://hacker-news.firebaseio.com";
    private static final String TOP_STORIES    = "/v0/topstories.json";
    private static final String ITEM_TEMPLATE  = "/v0/item/{id}.json";
    private static final String USER_AGENT     = "TelegramUserBot-HnCollector/1.0";

    /** Per-request timeout for the top-stories list fetch. */
    private static final Duration LIST_TIMEOUT = Duration.ofSeconds(15);
    /** Per-item timeout for each story-detail fetch. */
    private static final Duration ITEM_TIMEOUT = Duration.ofSeconds(10);

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public HnClient(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.webClient = webClientBuilder
                .baseUrl(HN_BASE_URL)
                .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                .build();
        this.objectMapper = objectMapper;
    }

    /**
     * Fetches the ordered top-story id list from HN and emits one {@link HnStory} per id
     * (up to {@code maxStories}). Only items with {@code type=story} are emitted.
     *
     * <p>Per-item errors are caught and logged as WARN; the stream continues with the next id.
     *
     * @param maxStories maximum number of story ids to request from the top-stories list
     * @return cold {@link Flux} of parsed stories, possibly empty on error
     */
    public Flux<HnStory> fetchTopStories(int maxStories) {
        return webClient.get()
                .uri(TOP_STORIES)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(LIST_TIMEOUT)
                .flatMapMany(json -> parseIdList(json, maxStories))
                .concatMap(id -> fetchItem(id)
                        .onErrorResume(ex -> {
                            log.warn("[HnClient] Failed to fetch item id={}: {}", id, ex.toString());
                            return Mono.empty();
                        }))
                .filter(story -> "story".equals(story.type()))
                .onErrorResume(ex -> {
                    log.warn("[HnClient] Failed to fetch top-stories list: {}", ex.toString());
                    return Flux.empty();
                });
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private Flux<Long> parseIdList(String json, int maxStories) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.isArray()) {
                log.warn("[HnClient] top-stories response is not a JSON array");
                return Flux.empty();
            }
            List<Long> ids = new java.util.ArrayList<>(Math.min(maxStories, root.size()));
            for (int i = 0; i < Math.min(maxStories, root.size()); i++) {
                ids.add(root.get(i).asLong());
            }
            return Flux.fromIterable(ids);
        } catch (Exception ex) {
            log.warn("[HnClient] Failed to parse top-stories id list: {}", ex.toString());
            return Flux.empty();
        }
    }

    private Mono<HnStory> fetchItem(long id) {
        return webClient.get()
                .uri(ITEM_TEMPLATE, id)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(ITEM_TIMEOUT)
                .flatMap(json -> parseItem(id, json));
    }

    private Mono<HnStory> parseItem(long id, String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node == null || node.isNull()) {
                // Deleted or not-yet-propagated items return null
                return Mono.empty();
            }
            String type        = node.path("type").asText(null);
            String title       = node.path("title").asText(null);
            String url         = node.path("url").asText(null);
            int    score       = node.path("score").asInt(0);
            int    descendants = node.path("descendants").asInt(0);
            long   epochSec    = node.path("time").asLong(0);
            Instant time       = epochSec > 0 ? Instant.ofEpochSecond(epochSec) : Instant.now();

            return Mono.just(new HnStory(id, title, url, score, descendants, time, type));
        } catch (Exception ex) {
            log.warn("[HnClient] Failed to parse item id={}: {}", id, ex.toString());
            return Mono.empty();
        }
    }
}
