package com.example.telegramuserbot.service.search;

import com.example.telegramuserbot.domain.SearchProvider;
import com.example.telegramuserbot.dto.SearchRequestDto;
import com.example.telegramuserbot.dto.SearchResponseDto;
import com.example.telegramuserbot.dto.SearchStatsDto;
import reactor.core.publisher.Mono;

/**
 * Service interface for internet search functionality.
 */
public interface SearchService {
    Mono<SearchResponseDto> search(SearchRequestDto request);

    Mono<SearchResponseDto> searchForChat(String query, Long chatId);

    Mono<Boolean> shouldPerformSearch(String messageContent, Long chatId);

    Mono<String> extractSearchQuery(String messageContent, Long chatId);

    boolean isProviderAvailable(SearchProvider provider);

    Mono<Integer> getRemainingSearchQuota(Long chatId);

    Mono<String> enhanceResponseWithSearch(String originalResponse, String messageContent, Long chatId);

    Mono<Integer> clearExpiredCache();

    Mono<SearchStatsDto> getSearchStatistics();
}
