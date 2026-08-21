package com.example.telegramuserbot.service.search;

import com.example.telegramuserbot.domain.SearchProvider;
import com.example.telegramuserbot.dto.SearchResponseDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Enriches a news/topic with fresh web context via Tavily (owner-chosen
 * provider). Gated by search.tavily.enabled + a key: with neither, it is a
 * pure no-op (empty). Any error or timeout resolves empty, so enrichment can
 * never block or break the path that consumes it.
 *
 * Routes through {@link SearchService} so all Tavily calls benefit from the
 * DB-backed query-hash cache and per-chat rate limiting.
 */
@Service
public class TavilyEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(TavilyEnrichmentService.class);

    private final SearchProviderService searchProviderService;
    private final SearchService searchService;

    @Value("${search.tavily.enabled:false}")
    private boolean enabled;
    @Value("${search.tavily.max-results:3}")
    private int maxResults;
    @Value("${search.tavily.max-snippet-chars:200}")
    private int maxSnippetChars;

    public TavilyEnrichmentService(SearchProviderService searchProviderService,
                                   @Lazy SearchService searchService) {
        this.searchProviderService = searchProviderService;
        this.searchService = searchService;
    }

    /**
     * @return newline-separated "title — snippet" web-context lines for the
     *         topic, or empty when disabled / unconfigured / nothing found.
     *         The call goes through the cached {@link SearchService} so
     *         identical topics reuse the DB cache and respect rate limits.
     */
    public Mono<String> enrich(String topic) {
        if (!enabled || topic == null || topic.isBlank()
                || !searchProviderService.isProviderAvailable(SearchProvider.TAVILY)) {
            return Mono.empty();
        }
        // Route through the cached search service (chatId=null → global default config).
        return searchService.searchForChat(topic, null)
                .map(response -> format(response.getResults() != null ? response.getResults() : List.of()))
                .filter(s -> !s.isBlank())
                // Hard cap so enrichment never stalls a reply, regardless of the
                // shared provider timeout.
                .timeout(java.time.Duration.ofSeconds(3))
                .onErrorResume(e -> {
                    log.warn("Tavily enrichment failed for topic (continuing without it): {}", e.getMessage());
                    return Mono.empty();
                });
    }

    private String format(List<SearchResponseDto.SearchItemDto> results) {
        return results.stream()
                .filter(r -> r.getSnippet() != null && !r.getSnippet().isBlank())
                .map(r -> {
                    String snippet = r.getSnippet().replaceAll("\\s+", " ").trim();
                    if (snippet.length() > maxSnippetChars) {
                        snippet = snippet.substring(0, maxSnippetChars) + "…";
                    }
                    String title = r.getTitle() != null ? r.getTitle().trim() : "web";
                    return title + " — " + snippet;
                })
                .collect(Collectors.joining("\n"));
    }
}
