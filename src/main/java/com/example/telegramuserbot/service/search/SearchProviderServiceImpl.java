package com.example.telegramuserbot.service.search;

import com.example.telegramuserbot.domain.SearchProvider;
import com.example.telegramuserbot.dto.SearchResponseDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementation of SearchProviderService supporting multiple search providers
 */
@Service
public class SearchProviderServiceImpl implements SearchProviderService {
    
    private static final Logger log = LoggerFactory.getLogger(SearchProviderServiceImpl.class);
    
    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    
    // Configuration properties
    @Value("${search.google.api.key:}")
    private String googleApiKey;
    
    @Value("${search.google.cse.id:}")
    private String googleCseId;
    
    @Value("${search.bing.api.key:}")
    private String bingApiKey;

    @Value("${search.tavily.enabled:false}")
    private boolean tavilyEnabled;

    @Value("${search.tavily.api-key:${TAVILY_API_KEY:}}")
    private String tavilyApiKey;

    @Value("${search.providers.timeout:10000}")
    private int searchTimeoutMs;
    
    // Provider availability cache
    private final Map<SearchProvider, Boolean> providerAvailability = new ConcurrentHashMap<>();
    
    public SearchProviderServiceImpl(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.webClient = webClientBuilder
                .defaultHeader(HttpHeaders.USER_AGENT, "TelegramUserBot/1.0")
                .build();
        this.objectMapper = objectMapper;
    }
    
    @Override
    public Mono<List<SearchResponseDto.SearchItemDto>> search(String query, SearchProvider provider, Integer maxResults) {
        log.debug("Performing search with provider {}: query='{}', maxResults={}", provider, query, maxResults);
        
        if (!isProviderAvailable(provider)) {
            log.warn("Search provider {} is not available", provider);
            return Mono.just(Collections.emptyList());
        }
        
        switch (provider) {
            case GOOGLE:
                return searchGoogle(query, maxResults);
            case BING:
                return searchBing(query, maxResults);
            case DUCKDUCKGO:
                return searchDuckDuckGo(query, maxResults);
            case TAVILY:
                return searchTavily(query, maxResults);
            default:
                log.error("Unsupported search provider: {}", provider);
                return Mono.just(Collections.emptyList());
        }
    }
    
    @Override
    public boolean isProviderAvailable(SearchProvider provider) {
        // Check cache first
        Boolean cached = providerAvailability.get(provider);
        if (cached != null) {
            return cached;
        }
        
        // Check configuration and availability
        boolean available = checkProviderConfiguration(provider);
        providerAvailability.put(provider, available);
        
        return available;
    }
    
    @Override
    public Mono<Map<SearchProvider, Boolean>> getProviderHealthStatus() {
        return Mono.fromCallable(() -> {
            Map<SearchProvider, Boolean> status = new HashMap<>();
            
            for (SearchProvider provider : SearchProvider.values()) {
                status.put(provider, isProviderAvailable(provider));
            }
            
            return status;
        });
    }
    
    // Private implementation methods
    
    private boolean checkProviderConfiguration(SearchProvider provider) {
        switch (provider) {
            case GOOGLE:
                return isNotEmpty(googleApiKey) && isNotEmpty(googleCseId);
            case BING:
                return isNotEmpty(bingApiKey);
            case DUCKDUCKGO:
                return true; // DuckDuckGo doesn't require API key
            case TAVILY:
                return tavilyEnabled && isNotEmpty(tavilyApiKey);
            default:
                return false;
        }
    }
    
    private boolean isNotEmpty(String value) {
        return value != null && !value.trim().isEmpty();
    }
    
    private Mono<List<SearchResponseDto.SearchItemDto>> searchGoogle(String query, Integer maxResults) {
        if (!isNotEmpty(googleApiKey) || !isNotEmpty(googleCseId)) {
            log.error("Google Search API configuration is missing");
            return Mono.just(Collections.emptyList());
        }
        
        String url = String.format(
                "https://www.googleapis.com/customsearch/v1?key=%s&cx=%s&q=%s&num=%d",
                googleApiKey, googleCseId, encodeQuery(query), Math.min(maxResults, 10)
        );
        
        return webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(java.time.Duration.ofMillis(searchTimeoutMs))
                .map(this::parseGoogleResponse)
                .doOnError(error -> log.error("Google search failed for query: {}", query, error))
                .onErrorReturn(Collections.emptyList());
    }
    
    private Mono<List<SearchResponseDto.SearchItemDto>> searchBing(String query, Integer maxResults) {
        if (!isNotEmpty(bingApiKey)) {
            log.error("Bing Search API configuration is missing");
            return Mono.just(Collections.emptyList());
        }
        
        String url = String.format(
                "https://api.bing.microsoft.com/v7.0/search?q=%s&count=%d",
                encodeQuery(query), Math.min(maxResults, 20)
        );
        
        return webClient.get()
                .uri(url)
                .header("Ocp-Apim-Subscription-Key", bingApiKey)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(java.time.Duration.ofMillis(searchTimeoutMs))
                .map(this::parseBingResponse)
                .doOnError(error -> log.error("Bing search failed for query: {}", query, error))
                .onErrorReturn(Collections.emptyList());
    }

    private Mono<List<SearchResponseDto.SearchItemDto>> searchTavily(String query, Integer maxResults) {
        return callTavily(query, maxResults, "basic", false);
    }

    @Override
    public Mono<List<SearchResponseDto.SearchItemDto>> searchTavilyDeep(String query, Integer maxResults) {
        return callTavily(query, maxResults, "advanced", true);
    }

    /**
     * Single Tavily call. {@code depth="basic"}/{@code includeRaw=false} reproduces the original
     * behaviour exactly (reply-path unaffected); {@code "advanced"}/{@code true} returns the full
     * article body in each result's {@code raw_content}.
     */
    private Mono<List<SearchResponseDto.SearchItemDto>> callTavily(
            String query, Integer maxResults, String depth, boolean includeRaw) {
        if (!isNotEmpty(tavilyApiKey)) {
            log.error("Tavily Search API configuration is missing");
            return Mono.just(Collections.emptyList());
        }
        // Tavily is a POST API; the key travels in the JSON body, not a header.
        Map<String, Object> body = new HashMap<>();
        body.put("api_key", tavilyApiKey);
        body.put("query", query);
        body.put("max_results", Math.min(maxResults != null ? maxResults : 5, 10));
        body.put("search_depth", depth);
        if (includeRaw) {
            body.put("include_raw_content", true);
        }

        return webClient.post()
                .uri("https://api.tavily.com/search")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(java.time.Duration.ofMillis(searchTimeoutMs))
                .map(resp -> parseTavilyResponse(resp, includeRaw))
                .doOnError(error -> log.error("Tavily search failed for query: {}", query, error))
                .onErrorReturn(Collections.emptyList());
    }

    private List<SearchResponseDto.SearchItemDto> parseTavilyResponse(String response, boolean includeRaw) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode results = root.get("results");
            if (results == null || !results.isArray()) {
                return Collections.emptyList();
            }
            List<SearchResponseDto.SearchItemDto> items = new ArrayList<>();
            for (JsonNode item : results) {
                SearchResponseDto.SearchItemDto result = new SearchResponseDto.SearchItemDto();
                result.setTitle(getJsonString(item, "title"));
                result.setUrl(getJsonString(item, "url"));
                result.setSnippet(getJsonString(item, "content"));
                JsonNode score = item.get("score");
                result.setRelevanceScore(score != null && score.isNumber() ? score.asDouble() : 0.7);
                if (includeRaw) {
                    result.setRawContent(getJsonString(item, "raw_content"));
                }
                items.add(result);
            }
            log.debug("Parsed {} Tavily search results", items.size());
            return items;
        } catch (Exception e) {
            log.error("Failed to parse Tavily search response", e);
            return Collections.emptyList();
        }
    }

    private Mono<List<SearchResponseDto.SearchItemDto>> searchDuckDuckGo(String query, Integer maxResults) {
        // DuckDuckGo Instant Answer API (free but limited)
        String url = String.format(
                "https://api.duckduckgo.com/?q=%s&format=json&no_html=1&skip_disambig=1",
                encodeQuery(query)
        );
        
        return webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(java.time.Duration.ofMillis(searchTimeoutMs))
                .map(response -> parseDuckDuckGoResponse(response, maxResults))
                .doOnError(error -> log.error("DuckDuckGo search failed for query: {}", query, error))
                .onErrorReturn(Collections.emptyList());
    }
    
    private String encodeQuery(String query) {
        try {
            return java.net.URLEncoder.encode(query, "UTF-8");
        } catch (Exception e) {
            log.warn("Failed to encode query: {}", query, e);
            return query.replaceAll("\\s+", "+");
        }
    }
    
    private List<SearchResponseDto.SearchItemDto> parseGoogleResponse(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode items = root.get("items");
            
            if (items == null || !items.isArray()) {
                return Collections.emptyList();
            }
            
            List<SearchResponseDto.SearchItemDto> results = new ArrayList<>();
            
            for (JsonNode item : items) {
                SearchResponseDto.SearchItemDto result = new SearchResponseDto.SearchItemDto();
                result.setTitle(getJsonString(item, "title"));
                result.setUrl(getJsonString(item, "link"));
                result.setSnippet(getJsonString(item, "snippet"));
                result.setDisplayUrl(getJsonString(item, "displayLink"));
                result.setRelevanceScore(0.8); // Default relevance score for Google results
                
                results.add(result);
            }
            
            log.debug("Parsed {} Google search results", results.size());
            return results;
            
        } catch (Exception e) {
            log.error("Failed to parse Google search response", e);
            return Collections.emptyList();
        }
    }
    
    private List<SearchResponseDto.SearchItemDto> parseBingResponse(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode webPages = root.get("webPages");
            
            if (webPages == null) {
                return Collections.emptyList();
            }
            
            JsonNode value = webPages.get("value");
            if (value == null || !value.isArray()) {
                return Collections.emptyList();
            }
            
            List<SearchResponseDto.SearchItemDto> results = new ArrayList<>();
            
            for (JsonNode item : value) {
                SearchResponseDto.SearchItemDto result = new SearchResponseDto.SearchItemDto();
                result.setTitle(getJsonString(item, "name"));
                result.setUrl(getJsonString(item, "url"));
                result.setSnippet(getJsonString(item, "snippet"));
                result.setDisplayUrl(getJsonString(item, "displayUrl"));
                result.setRelevanceScore(0.75); // Default relevance score for Bing results
                
                // Parse date if available
                String dateLastCrawled = getJsonString(item, "dateLastCrawled");
                if (dateLastCrawled != null) {
                    result.setPublishedDate(dateLastCrawled);
                }
                
                results.add(result);
            }
            
            log.debug("Parsed {} Bing search results", results.size());
            return results;
            
        } catch (Exception e) {
            log.error("Failed to parse Bing search response", e);
            return Collections.emptyList();
        }
    }
    
    private List<SearchResponseDto.SearchItemDto> parseDuckDuckGoResponse(String response, Integer maxResults) {
        try {
            JsonNode root = objectMapper.readTree(response);
            List<SearchResponseDto.SearchItemDto> results = new ArrayList<>();
            
            // DuckDuckGo Instant Answer API provides different types of results
            
            // Check for abstract (main answer)
            String abstractText = getJsonString(root, "Abstract");
            String abstractUrl = getJsonString(root, "AbstractURL");
            if (abstractText != null && !abstractText.isEmpty()) {
                SearchResponseDto.SearchItemDto result = new SearchResponseDto.SearchItemDto();
                result.setTitle("DuckDuckGo Instant Answer");
                result.setUrl(abstractUrl != null ? abstractUrl : "https://duckduckgo.com");
                result.setSnippet(abstractText);
                result.setRelevanceScore(0.9); // High relevance for instant answers
                results.add(result);
            }
            
            // Check for definition
            String definition = getJsonString(root, "Definition");
            String definitionUrl = getJsonString(root, "DefinitionURL");
            if (definition != null && !definition.isEmpty()) {
                SearchResponseDto.SearchItemDto result = new SearchResponseDto.SearchItemDto();
                result.setTitle("Definition");
                result.setUrl(definitionUrl != null ? definitionUrl : "https://duckduckgo.com");
                result.setSnippet(definition);
                result.setRelevanceScore(0.85);
                results.add(result);
            }
            
            // Check for related topics
            JsonNode relatedTopics = root.get("RelatedTopics");
            if (relatedTopics != null && relatedTopics.isArray()) {
                int count = 0;
                for (JsonNode topic : relatedTopics) {
                    if (count >= maxResults - results.size()) break;
                    
                    String text = getJsonString(topic, "Text");
                    String firstUrl = getJsonString(topic, "FirstURL");
                    
                    if (text != null && !text.isEmpty()) {
                        SearchResponseDto.SearchItemDto result = new SearchResponseDto.SearchItemDto();
                        result.setTitle("Related Topic");
                        result.setUrl(firstUrl != null ? firstUrl : "https://duckduckgo.com");
                        result.setSnippet(text);
                        result.setRelevanceScore(0.6);
                        results.add(result);
                        count++;
                    }
                }
            }
            
            log.debug("Parsed {} DuckDuckGo search results", results.size());
            return results.subList(0, Math.min(results.size(), maxResults));
            
        } catch (Exception e) {
            log.error("Failed to parse DuckDuckGo search response", e);
            return Collections.emptyList();
        }
    }
    
    private String getJsonString(JsonNode node, String fieldName) {
        JsonNode field = node.get(fieldName);
        return field != null && !field.isNull() ? field.asText() : null;
    }
}