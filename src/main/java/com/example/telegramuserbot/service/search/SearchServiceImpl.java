package com.example.telegramuserbot.service.search;

import com.example.telegramuserbot.domain.SearchConfig;
import com.example.telegramuserbot.domain.SearchProvider;
import com.example.telegramuserbot.domain.SearchResult;
import com.example.telegramuserbot.dto.SearchRequestDto;
import com.example.telegramuserbot.dto.SearchResponseDto;
import com.example.telegramuserbot.dto.SearchStatsDto;
import com.example.telegramuserbot.repository.SearchCacheStatistics;
import com.example.telegramuserbot.repository.SearchConfigRepository;
import com.example.telegramuserbot.repository.SearchResultRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Service
public class SearchServiceImpl implements SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchServiceImpl.class);

    private final SearchConfigRepository searchConfigRepository;
    private final SearchResultRepository searchResultRepository;
    private final ObjectMapper objectMapper;
    private final SearchProviderService searchProviderService;
    private final SearchRateLimitService rateLimitService;

    private final Map<Long, SearchConfig> configCache = new ConcurrentHashMap<>();

    private static final List<Pattern> DEFAULT_SEARCH_TRIGGERS = Arrays.asList(
            Pattern.compile("(?i)\\b(what is|what are|who is|who are|when did|when was|where is|where are|how to|why is|why are)\\b"),
            Pattern.compile("(?i)\\b(current|latest|recent|news|today|yesterday|this week|this month|this year)\\b")
    );

    @Value("${search.default.enabled:false}")
    private boolean defaultSearchEnabled;

    public SearchServiceImpl(SearchConfigRepository searchConfigRepository,
                           SearchResultRepository searchResultRepository,
                           ObjectMapper objectMapper,
                           SearchProviderService searchProviderService,
                           SearchRateLimitService rateLimitService) {
        this.searchConfigRepository = searchConfigRepository;
        this.searchResultRepository = searchResultRepository;
        this.objectMapper = objectMapper;
        this.searchProviderService = searchProviderService;
        this.rateLimitService = rateLimitService;
    }

    @Override
    public Mono<SearchResponseDto> search(SearchRequestDto request) {
        if (request.getQuery() == null || request.getQuery().trim().isEmpty()) {
            return Mono.error(new IllegalArgumentException("Search query cannot be empty"));
        }

        return getSearchConfigForChat(request.getChatId())
                .flatMap(config -> {
                    if (!config.isSearchEnabled()) {
                        log.debug("Search is disabled for chat {}", request.getChatId());
                        return Mono.just(createEmptyResponse(request.getQuery()));
                    }
                    return rateLimitService.checkRateLimit(request.getChatId(), config.getRateLimitPerHour())
                            .flatMap(allowed -> {
                                if (!allowed) {
                                    log.warn("Rate limit exceeded for chat {}", request.getChatId());
                                    return Mono.just(createRateLimitedResponse(request.getQuery()));
                                }
                                return performSearch(request, config);
                            });
                });
    }

    @Override
    public Mono<SearchResponseDto> searchForChat(String query, Long chatId) {
        return search(new SearchRequestDto(query, chatId));
    }

    @Override
    public Mono<Boolean> shouldPerformSearch(String messageContent, Long chatId) {
        if (messageContent == null || messageContent.trim().isEmpty()) {
            return Mono.just(false);
        }

        return getSearchConfigForChat(chatId)
                .map(config -> {
                    if (!config.isAutoSearchEnabled()) {
                        return false;
                    }
                    for (Pattern trigger : DEFAULT_SEARCH_TRIGGERS) {
                        if (trigger.matcher(messageContent).find()) {
                            return true;
                        }
                    }
                    return false; // Simplified for now
                });
    }

    @Override
    public Mono<String> extractSearchQuery(String messageContent, Long chatId) {
        return Mono.just(cleanQuery(messageContent));
    }

    @Override
    public boolean isProviderAvailable(SearchProvider provider) {
        return searchProviderService.isProviderAvailable(provider);
    }

    @Override
    public Mono<Integer> getRemainingSearchQuota(Long chatId) {
        return rateLimitService.getRemainingQuota(chatId);
    }

    @Override
    public Mono<String> enhanceResponseWithSearch(String originalResponse, String messageContent, Long chatId) {
        return shouldPerformSearch(messageContent, chatId)
                .flatMap(shouldSearch -> {
                    if (!shouldSearch) {
                        return Mono.just(originalResponse);
                    }
                    return extractSearchQuery(messageContent, chatId)
                            .flatMap(query -> searchForChat(query, chatId)
                                    .map(searchResponse -> enhanceResponseWithSearchResults(originalResponse, searchResponse))
                                    .defaultIfEmpty(originalResponse));
                });
    }

    @Override
    public Mono<Integer> clearExpiredCache() {
        return searchResultRepository.deleteExpiredResults(Instant.now());
    }

    @Override
    public Mono<SearchStatsDto> getSearchStatistics() {
        Mono<SearchCacheStatistics> cacheStatsMono = searchResultRepository.getCacheStatistics(Instant.now())
                .defaultIfEmpty(new SearchCacheStatistics(0L, 0L, 0.0, 0.0));
        Mono<Long> activeConfigsMono = searchConfigRepository.countSearchEnabledConfigurations();

        return Mono.zip(cacheStatsMono, activeConfigsMono)
                .map(tuple -> {
                    SearchCacheStatistics stats = tuple.getT1();
                    long activeConfigs = tuple.getT2();
                    long total = Optional.ofNullable(stats.totalResults()).orElse(0L);
                    long valid = Optional.ofNullable(stats.validResults()).orElse(0L);
                    double avgTime = Optional.ofNullable(stats.avgSearchTime()).orElse(0.0);
                    return new SearchStatsDto(total, valid, total - valid, avgTime, activeConfigs);
                });
    }

    private Mono<SearchConfig> getSearchConfigForChat(Long chatId) {
        if (chatId == null) {
            return Mono.just(createDefaultEntity(null));
        }
        return Mono.justOrEmpty(configCache.get(chatId))
                .switchIfEmpty(searchConfigRepository.findByChatId(chatId)
                        .doOnNext(config -> configCache.put(chatId, config)))
                .switchIfEmpty(Mono.defer(() -> searchConfigRepository.save(createDefaultEntity(chatId))
                        .doOnNext(config -> configCache.put(chatId, config))));
    }

    private Mono<SearchResponseDto> performSearch(SearchRequestDto request, SearchConfig config) {
        String normalizedQuery = normalizeQuery(request.getQuery());
        String queryHash = generateQueryHash(normalizedQuery);
        SearchProvider provider = Optional.ofNullable(request.getSearchProvider()).orElse(config.getSearchProvider());

        if (!request.isForceRefresh()) {
            return checkCache(queryHash, provider)
                    .map(this::convertCachedResultToResponse)
                    .switchIfEmpty(Mono.defer(() -> performActualSearch(request.getQuery(), normalizedQuery, queryHash, provider, config)));
        }
        return performActualSearch(request.getQuery(), normalizedQuery, queryHash, provider, config);
    }

    private Mono<SearchResult> checkCache(String queryHash, SearchProvider provider) {
        return searchResultRepository.findValidCachedResult(queryHash, provider, Instant.now())
                .flatMap(cached -> {
                    cached.recordAccess();
                    return searchResultRepository.save(cached);
                });
    }

    private Mono<SearchResponseDto> performActualSearch(String originalQuery, String normalizedQuery, String queryHash, SearchProvider provider, SearchConfig config) {
        long startTime = System.currentTimeMillis();
        return searchProviderService.search(normalizedQuery, provider, config.getMaxResults())
                .flatMap(results -> {
                    long searchTime = System.currentTimeMillis() - startTime;
                    SearchResponseDto response = new SearchResponseDto(originalQuery, results);
                    response.setNormalizedQuery(normalizedQuery);
                    response.setSearchProvider(provider.getDisplayName());
                    response.setSearchTimeMs(searchTime);
                    response.setFromCache(false);
                    response.setTotalResults((long) results.size());
                    response.setRelevanceScore(calculateRelevanceScore(results));
                    if (config.isIncludeAttribution()) {
                        response.setAttribution(generateAttribution(provider, results.size()));
                    }
                    return cacheSearchResults(queryHash, originalQuery, normalizedQuery, provider, response, config)
                            .thenReturn(response);
                });
    }

    private Mono<SearchResult> cacheSearchResults(String queryHash, String originalQuery, String normalizedQuery, SearchProvider provider, SearchResponseDto response, SearchConfig config) {
        try {
            String resultsJson = objectMapper.writeValueAsString(response.getResults());
            SearchResult searchResult = new SearchResult(queryHash, originalQuery, normalizedQuery, provider, resultsJson);
            searchResult.setTotalResults(response.getTotalResults());
            searchResult.setSearchTimeMs(response.getSearchTimeMs());
            searchResult.setRelevanceScore(response.getRelevanceScore());
            searchResult.setExpiresAt(Instant.now().plusSeconds(config.getCacheDurationMinutes() * 60L));
            return searchResultRepository.save(searchResult);
        } catch (JsonProcessingException e) {
            return Mono.error(e);
        }
    }

    private SearchResponseDto convertCachedResultToResponse(SearchResult cached) {
        try {
            List<SearchResponseDto.SearchItemDto> results = objectMapper.readValue(cached.getResultsJson(), new TypeReference<>() {});
            SearchResponseDto response = new SearchResponseDto(cached.getOriginalQuery(), results);
            response.setFromCache(true);
            // ... set other fields ...
            return response;
        } catch (JsonProcessingException e) {
            return createEmptyResponse(cached.getOriginalQuery());
        }
    }

    private String enhanceResponseWithSearchResults(String originalResponse, SearchResponseDto searchResponse) {
        if (searchResponse.getResults() == null || searchResponse.getResults().isEmpty()) {
            return originalResponse;
        }
        StringBuilder enhanced = new StringBuilder(originalResponse);
        enhanced.append("\n\n--- Additional Information ---\n");
        searchResponse.getResults().stream().limit(3).forEach(result ->
                enhanced.append(String.format("\n%d. %s\n%s\n", searchResponse.getResults().indexOf(result) + 1, result.getTitle(), result.getSnippet())));
        if (searchResponse.getAttribution() != null) {
            enhanced.append("\n").append(searchResponse.getAttribution());
        }
        return enhanced.toString();
    }

    private String normalizeQuery(String query) { return query.trim().toLowerCase(); }
    private String cleanQuery(String query) { return query.trim(); }
    private String generateQueryHash(String normalizedQuery) { try { MessageDigest md = MessageDigest.getInstance("SHA-256"); byte[] hash = md.digest(normalizedQuery.getBytes(StandardCharsets.UTF_8)); return Base64.getEncoder().encodeToString(hash); } catch (Exception e) { return String.valueOf(normalizedQuery.hashCode()); } }
    private double calculateRelevanceScore(List<SearchResponseDto.SearchItemDto> results) { return results.stream().mapToDouble(item -> Optional.ofNullable(item.getRelevanceScore()).orElse(0.5)).average().orElse(0.0); }
    private SearchResponseDto createEmptyResponse(String query) { return new SearchResponseDto(query, Collections.emptyList()); }
    private SearchResponseDto createRateLimitedResponse(String query) { SearchResponseDto r = createEmptyResponse(query); r.setSummary("Rate limited."); return r; }
    private String generateAttribution(SearchProvider p, int c) { return String.format("Results from %s (%d found)", p.getDisplayName(), c); }
    private SearchConfig createDefaultEntity(Long chatId) { SearchConfig config = new SearchConfig(chatId); config.setSearchEnabled(defaultSearchEnabled); return config; }
}
