package com.example.telegramuserbot.service.search;

import com.example.telegramuserbot.dto.SearchConfigDto;
import com.example.telegramuserbot.dto.SearchConfigStatsDto; // Import the new DTO
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Service interface for managing search configurations
 */
public interface SearchConfigurationService {
    
    /**
     * Gets search configuration for a specific chat
     * 
     * @param chatId The chat ID
     * @return Mono containing the search configuration, or default if not found
     */
    Mono<SearchConfigDto> getSearchConfig(Long chatId);
    
    /**
     * Creates or updates search configuration for a chat
     * 
     * @param config The search configuration to save
     * @return Mono containing the saved configuration
     */
    Mono<SearchConfigDto> saveSearchConfig(SearchConfigDto config);
    
    /**
     * Updates specific search configuration fields
     * 
     * @param chatId The chat ID
     * @param updates The fields to update
     * @return Mono containing the updated configuration
     */
    Mono<SearchConfigDto> updateSearchConfig(Long chatId, SearchConfigDto updates);
    
    /**
     * Deletes search configuration for a chat (reverts to default)
     * 
     * @param chatId The chat ID
     * @return Mono completing when configuration is deleted
     */
    Mono<Void> deleteSearchConfig(Long chatId);
    
    /**
     * Gets all search configurations with search enabled
     * 
     * @return Mono containing list of configurations
     */
    Mono<List<SearchConfigDto>> getAllEnabledConfigurations();
    
    /**
     * Enables or disables search for a specific chat
     * 
     * @param chatId The chat ID
     * @param enabled Whether search should be enabled
     * @return Mono containing the updated configuration
     */
    Mono<SearchConfigDto> setSearchEnabled(Long chatId, boolean enabled);
    
    /**
     * Enables or disables auto-search for a specific chat
     * 
     * @param chatId The chat ID
     * @param enabled Whether auto-search should be enabled
     * @return Mono containing the updated configuration
     */
    Mono<SearchConfigDto> setAutoSearchEnabled(Long chatId, boolean enabled);
    
    /**
     * Updates search triggers for a specific chat
     * 
     * @param chatId The chat ID
     * @param triggers List of search trigger patterns
     * @return Mono containing the updated configuration
     */
    Mono<SearchConfigDto> updateSearchTriggers(Long chatId, List<String> triggers);
    
    /**
     * Gets search configuration statistics
     * 
     * @return Mono containing configuration statistics
     */
    Mono<SearchConfigStatsDto> getConfigurationStatistics();
}