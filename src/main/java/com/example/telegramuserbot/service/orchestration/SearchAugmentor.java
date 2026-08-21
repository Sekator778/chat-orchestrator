package com.example.telegramuserbot.service.orchestration;

import com.example.telegramuserbot.service.search.SearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Обогащение ответа поиском (опционально): по триггерам добавляем факты в промпт или в постобработку.
 */
@Component
public class SearchAugmentor {

    private static final Logger log = LoggerFactory.getLogger(SearchAugmentor.class);
    private final SearchService searchService;

    public SearchAugmentor(SearchService searchService) {
        this.searchService = searchService;
    }

    public Mono<String> augmentIfNeeded(String rawResponse, String triggeringContent, long chatId) {
        return searchService.enhanceResponseWithSearch(rawResponse, triggeringContent, chatId)
                .doOnError(e -> log.warn("[Chat {}] Search augmentation failed: {}", chatId, e.getMessage()))
                .onErrorReturn(rawResponse)
                .defaultIfEmpty(rawResponse);
    }
}

