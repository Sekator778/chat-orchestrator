package com.example.telegramuserbot.service.search;

import com.example.telegramuserbot.domain.SearchProvider;
import com.example.telegramuserbot.dto.SearchResponseDto;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Service interface for managing different search providers
 */
public interface SearchProviderService {
    
    /**
     * Performs search using the specified provider
     * 
     * @param query The search query
     * @param provider The search provider to use
     * @param maxResults Maximum number of results to return
     * @return Mono containing list of search results
     */
    Mono<List<SearchResponseDto.SearchItemDto>> search(String query, SearchProvider provider, Integer maxResults);

    /**
     * Deep Tavily search: {@code search_depth=advanced} + {@code include_raw_content=true}, so each
     * result carries the full article body ({@link SearchResponseDto.SearchItemDto#getRawContent()}),
     * not just a snippet. A SEPARATE path from {@link #search} so the reply-path / #51 query cache
     * (which use basic depth) are entirely unaffected. Returns empty if Tavily is not configured.
     */
    Mono<List<SearchResponseDto.SearchItemDto>> searchTavilyDeep(String query, Integer maxResults);

    /**
     * Checks if the specified provider is available and configured
     * 
     * @param provider The search provider to check
     * @return true if provider is available
     */
    boolean isProviderAvailable(SearchProvider provider);
    
    /**
     * Gets the health status of all configured providers
     * 
     * @return Map of provider health status
     */
    Mono<java.util.Map<SearchProvider, Boolean>> getProviderHealthStatus();
}