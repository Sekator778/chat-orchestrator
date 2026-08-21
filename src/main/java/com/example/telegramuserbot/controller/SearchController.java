package com.example.telegramuserbot.controller;

import com.example.telegramuserbot.config.ConditionalOnHttpEnabled;
import com.example.telegramuserbot.dto.*;
import com.example.telegramuserbot.service.search.SearchConfigurationService;
import com.example.telegramuserbot.service.search.SearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API controller for search functionality management
 */
@RestController
@ConditionalOnHttpEnabled
@RequestMapping("/api/v1/search")
@Tag(name = "Search Management", description = "APIs for managing search functionality and configurations")
public class SearchController {
    
    private static final Logger uiLog = LoggerFactory.getLogger("frontend.ui");
    private static final Logger log = LoggerFactory.getLogger(SearchController.class);
    
    private final SearchService searchService;
    private final SearchConfigurationService searchConfigurationService;
    
    public SearchController(SearchService searchService, 
                           SearchConfigurationService searchConfigurationService) {
        this.searchService = searchService;
        this.searchConfigurationService = searchConfigurationService;
    }
    
    // Search Operations
    
    @PostMapping("/query")
    @Operation(summary = "Perform web search", description = "Execute a web search query with optional configuration")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Search completed successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid search request"),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded"),
            @ApiResponse(responseCode = "500", description = "Search service error")
    })
    public Mono<ResponseEntity<SearchResponseDto>> performSearch(
            @Valid @RequestBody SearchRequestDto request) {
        
        log.info("Search request received: query='{}', chatId={}", request.getQuery(), request.getChatId());
        
        return searchService.search(request)
                .map(response -> {
                    log.info("Search completed: query='{}', results={}, fromCache={}", 
                            request.getQuery(), 
                            response.getResults() != null ? response.getResults().size() : 0,
                            response.isFromCache());
                    return ResponseEntity.ok(response);
                })
                .onErrorResume(IllegalArgumentException.class, e -> {
                    log.warn("Invalid search request: {}", e.getMessage());
                    return Mono.just(ResponseEntity.badRequest().build());
                })
                .onErrorResume(e -> {
                    log.error("Search service error", e);
                    return Mono.just(ResponseEntity.internalServerError().build());
                });
    }
    
    @GetMapping("/chat/{chatId}/should-search")
    @Operation(summary = "Check if search is recommended", 
               description = "Analyze message content to determine if search would be beneficial")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Analysis completed"),
            @ApiResponse(responseCode = "400", description = "Invalid request parameters")
    })
    public Mono<ResponseEntity<Map<String, Object>>> shouldPerformSearch(
            @Parameter(description = "Chat ID") @PathVariable Long chatId,
            @Parameter(description = "Message content to analyze") @RequestParam String content) {
        
        return searchService.shouldPerformSearch(content, chatId)
                .flatMap(shouldSearch -> {
                    if (shouldSearch) {
                        return searchService.extractSearchQuery(content, chatId)
                                .map(query -> {
                                    Map<String, Object> result = new HashMap<>();
                                    result.put("shouldSearch", shouldSearch);
                                    result.put("extractedQuery", query != null ? query : "");
                                    result.put("chatId", chatId);
                                    return result;
                                });
                    } else {
                        Map<String, Object> result = new HashMap<>();
                        result.put("shouldSearch", shouldSearch);
                        result.put("extractedQuery", "");
                        result.put("chatId", chatId);
                        return Mono.just(result);
                    }
                })
                .map(result -> ResponseEntity.ok(result))
                .onErrorReturn(ResponseEntity.badRequest().build());
    }
    
    @GetMapping("/chat/{chatId}/quota")
    @Operation(summary = "Get remaining search quota", 
               description = "Check remaining search requests for the current hour")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Quota information retrieved"),
            @ApiResponse(responseCode = "404", description = "Chat configuration not found")
    })
    public Mono<ResponseEntity<Map<String, Integer>>> getSearchQuota(
            @Parameter(description = "Chat ID") @PathVariable Long chatId) {
        
        return searchService.getRemainingSearchQuota(chatId)
                .map(quota -> ResponseEntity.ok(Map.of("remainingQuota", quota)))
                .onErrorReturn(ResponseEntity.notFound().build());
    }
    
    @GetMapping("/statistics")
    @Operation(summary = "Get search statistics", 
               description = "Retrieve comprehensive search usage statistics")
    @ApiResponse(responseCode = "200", description = "Statistics retrieved successfully")
    public Mono<ResponseEntity<SearchStatsDto>> getSearchStatistics() {
        return searchService.getSearchStatistics()
                .map(ResponseEntity::ok)
                .onErrorReturn(ResponseEntity.internalServerError().build());
    }
    
    @DeleteMapping("/cache/expired")
    @Operation(summary = "Clear expired cache", 
               description = "Remove expired search results from cache")
    @ApiResponse(responseCode = "200", description = "Cache cleanup completed")
    public Mono<ResponseEntity<Map<String, Integer>>> clearExpiredCache() {
        return searchService.clearExpiredCache()
                .map(deletedCount -> ResponseEntity.ok(Map.of("deletedEntries", deletedCount)))
                .onErrorReturn(ResponseEntity.internalServerError().build());
    }
    
    // Configuration Management
    
    @GetMapping("/config/{chatId}")
    @Operation(summary = "Get search configuration", 
               description = "Retrieve search configuration for a specific chat")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Configuration retrieved successfully"),
            @ApiResponse(responseCode = "404", description = "Configuration not found")
    })
    public Mono<ResponseEntity<SearchConfigDto>> getSearchConfig(
            @Parameter(description = "Chat ID") @PathVariable Long chatId) {
        uiLog.info("UI:getSearchConfig chatId={}", chatId);
        
        return searchConfigurationService.getSearchConfig(chatId)
                .map(ResponseEntity::ok)
                .onErrorReturn(ResponseEntity.notFound().build());
    }
    
    @PostMapping("/config")
    @Operation(summary = "Create or update search configuration", 
               description = "Save search configuration for a chat")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Configuration saved successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid configuration data")
    })
    public Mono<ResponseEntity<SearchConfigDto>> saveSearchConfig(
            @Valid @RequestBody SearchConfigDto config) {
        
        uiLog.info("UI:saveSearchConfig chatId={} enabled={} autoSearch={} provider={}",
                config.getChatId(), config.isSearchEnabled(), config.isAutoSearchEnabled(), config.getSearchProvider());
        log.info("Saving search configuration for chat {}: enabled={}, autoSearch={}", 
                config.getChatId(), config.isSearchEnabled(), config.isAutoSearchEnabled());
        
        return searchConfigurationService.saveSearchConfig(config)
                .map(saved -> {
                    log.info("Search configuration saved for chat {}", saved.getChatId());
                    return ResponseEntity.ok(saved);
                })
                .onErrorResume(IllegalArgumentException.class, e -> {
                    log.warn("Invalid search configuration: {}", e.getMessage());
                    return Mono.just(ResponseEntity.badRequest().build());
                })
                .onErrorReturn(ResponseEntity.internalServerError().build());
    }
    
    @PutMapping("/config/{chatId}")
    @Operation(summary = "Update search configuration", 
               description = "Update specific fields of search configuration")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Configuration updated successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid configuration data"),
            @ApiResponse(responseCode = "404", description = "Configuration not found")
    })
    public Mono<ResponseEntity<SearchConfigDto>> updateSearchConfig(
            @Parameter(description = "Chat ID") @PathVariable Long chatId,
            @RequestBody SearchConfigDto updates) {
        uiLog.info("UI:updateSearchConfig chatId={} enabled={} autoSearch={} provider={}",
                chatId, updates.isSearchEnabled(), updates.isAutoSearchEnabled(), updates.getSearchProvider());
        
        return searchConfigurationService.updateSearchConfig(chatId, updates)
                .map(ResponseEntity::ok)
                .onErrorResume(IllegalArgumentException.class, e -> {
                    log.warn("Invalid search configuration update: {}", e.getMessage());
                    return Mono.just(ResponseEntity.badRequest().build());
                })
                .onErrorReturn(ResponseEntity.internalServerError().build());
    }
    
    @DeleteMapping("/config/{chatId}")
    @Operation(summary = "Delete search configuration", 
               description = "Remove search configuration (reverts to defaults)")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Configuration deleted successfully"),
            @ApiResponse(responseCode = "404", description = "Configuration not found")
    })
    public Mono<ResponseEntity<Void>> deleteSearchConfig(
            @Parameter(description = "Chat ID") @PathVariable Long chatId) {
        
        return searchConfigurationService.deleteSearchConfig(chatId)
                .then(Mono.just(ResponseEntity.noContent().<Void>build()))
                .onErrorReturn(ResponseEntity.notFound().build());
    }
    
    @PutMapping("/config/{chatId}/enabled")
    @Operation(summary = "Enable/disable search", 
               description = "Toggle search functionality for a specific chat")
    @ApiResponse(responseCode = "200", description = "Search status updated successfully")
    public Mono<ResponseEntity<SearchConfigDto>> setSearchEnabled(
            @Parameter(description = "Chat ID") @PathVariable Long chatId,
            @Parameter(description = "Enable search") @RequestParam boolean enabled) {
        
        return searchConfigurationService.setSearchEnabled(chatId, enabled)
                .map(ResponseEntity::ok)
                .onErrorReturn(ResponseEntity.internalServerError().build());
    }
    
    @PutMapping("/config/{chatId}/auto-search")
    @Operation(summary = "Enable/disable auto-search", 
               description = "Toggle automatic search detection for a specific chat")
    @ApiResponse(responseCode = "200", description = "Auto-search status updated successfully")
    public Mono<ResponseEntity<SearchConfigDto>> setAutoSearchEnabled(
            @Parameter(description = "Chat ID") @PathVariable Long chatId,
            @Parameter(description = "Enable auto-search") @RequestParam boolean enabled) {
        
        return searchConfigurationService.setAutoSearchEnabled(chatId, enabled)
                .map(ResponseEntity::ok)
                .onErrorReturn(ResponseEntity.internalServerError().build());
    }
    
    @PutMapping("/config/{chatId}/triggers")
    @Operation(summary = "Update search triggers", 
               description = "Update custom search trigger patterns for a chat")
    @ApiResponse(responseCode = "200", description = "Search triggers updated successfully")
    public Mono<ResponseEntity<SearchConfigDto>> updateSearchTriggers(
            @Parameter(description = "Chat ID") @PathVariable Long chatId,
            @RequestBody List<String> triggers) {
        
        return searchConfigurationService.updateSearchTriggers(chatId, triggers)
                .map(ResponseEntity::ok)
                .onErrorReturn(ResponseEntity.internalServerError().build());
    }
    
    @GetMapping("/config/all/enabled")
    @Operation(summary = "Get all enabled configurations", 
               description = "Retrieve all search configurations with search enabled")
    @ApiResponse(responseCode = "200", description = "Configurations retrieved successfully")
    public Mono<ResponseEntity<List<SearchConfigDto>>> getAllEnabledConfigurations() {
        return searchConfigurationService.getAllEnabledConfigurations()
                .map(ResponseEntity::ok)
                .onErrorReturn(ResponseEntity.internalServerError().build());
    }
    
    @GetMapping("/config/statistics")
    @Operation(summary = "Get configuration statistics", 
               description = "Retrieve statistics about search configurations")
    @ApiResponse(responseCode = "200", description = "Statistics retrieved successfully")
    public Mono<ResponseEntity<SearchConfigStatsDto>> getConfigurationStatistics() {
        return searchConfigurationService.getConfigurationStatistics()
                .map(ResponseEntity::ok)
                .onErrorReturn(ResponseEntity.internalServerError().build());
    }
}
