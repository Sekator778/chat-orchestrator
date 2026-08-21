package com.example.telegramuserbot.service.search;

import com.example.telegramuserbot.domain.SearchConfig;
import com.example.telegramuserbot.domain.SearchProvider;
import com.example.telegramuserbot.dto.SearchConfigDto;
import com.example.telegramuserbot.dto.SearchConfigStatsDto;
import com.example.telegramuserbot.repository.SearchConfigRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class SearchConfigurationServiceImpl implements SearchConfigurationService {

    private static final Logger log = LoggerFactory.getLogger(SearchConfigurationServiceImpl.class);

    private final SearchConfigRepository searchConfigRepository;
    private final ObjectMapper objectMapper;

    @Value("${search.default.enabled:false}")
    private boolean defaultSearchEnabled;

    @Value("${search.default.auto-search.enabled:false}")
    private boolean defaultAutoSearchEnabled;

    @Value("${search.default.provider:GOOGLE}")
    private String defaultProvider;

    @Value("${search.default.max-results:5}")
    private int defaultMaxResults;

    @Value("${search.default.cache-duration:60}")
    private int defaultCacheDuration;

    @Value("${search.default.rate-limit:30}")
    private int defaultRateLimit;

    public SearchConfigurationServiceImpl(SearchConfigRepository searchConfigRepository, ObjectMapper objectMapper) {
        this.searchConfigRepository = searchConfigRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<SearchConfigDto> getSearchConfig(Long chatId) {
        if (chatId == null) {
            return Mono.just(createDefaultConfigDto());
        }
        return searchConfigRepository.findByChatId(chatId)
                .map(this::convertToDto)
                .defaultIfEmpty(createDefaultConfigDto(chatId));
    }

    @Override
    public Mono<SearchConfigDto> saveSearchConfig(SearchConfigDto configDto) {
        if (configDto.getChatId() == null) {
            return Mono.error(new IllegalArgumentException("Chat ID cannot be null"));
        }
        SearchConfig entity = convertToEntity(configDto);
        return searchConfigRepository.save(entity)
                .map(this::convertToDto);
    }

    @Override
    public Mono<SearchConfigDto> updateSearchConfig(Long chatId, SearchConfigDto updates) {
        if (chatId == null) {
            return Mono.error(new IllegalArgumentException("Chat ID cannot be null"));
        }
        return searchConfigRepository.findByChatId(chatId)
                .defaultIfEmpty(createDefaultEntity(chatId))
                .flatMap(existing -> {
                    updateEntityFromDto(existing, updates);
                    return searchConfigRepository.save(existing);
                })
                .map(this::convertToDto);
    }

    @Override
    public Mono<Void> deleteSearchConfig(Long chatId) {
        if (chatId == null) {
            return Mono.empty();
        }
        return searchConfigRepository.findByChatId(chatId)
                .flatMap(searchConfigRepository::delete);
    }

    @Override
    public Mono<List<SearchConfigDto>> getAllEnabledConfigurations() {
        return searchConfigRepository.findAllWithSearchEnabled()
                .map(this::convertToDto)
                .collectList();
    }

    @Override
    public Mono<SearchConfigDto> setSearchEnabled(Long chatId, boolean enabled) {
        return updateSingleField(chatId, config -> config.setSearchEnabled(enabled));
    }

    @Override
    public Mono<SearchConfigDto> setAutoSearchEnabled(Long chatId, boolean enabled) {
        return updateSingleField(chatId, config -> config.setAutoSearchEnabled(enabled));
    }

    @Override
    public Mono<SearchConfigDto> updateSearchTriggers(Long chatId, List<String> triggers) {
        return updateSingleField(chatId, config -> config.setSearchTriggers(convertTriggersToJson(triggers)));
    }

    private Mono<SearchConfigDto> updateSingleField(Long chatId, java.util.function.Consumer<SearchConfig> updater) {
        if (chatId == null) {
            return Mono.error(new IllegalArgumentException("Chat ID cannot be null"));
        }
        return searchConfigRepository.findByChatId(chatId)
                .defaultIfEmpty(createDefaultEntity(chatId))
                .flatMap(config -> {
                    updater.accept(config);
                    return searchConfigRepository.save(config);
                })
                .map(this::convertToDto);
    }

    @Override
    public Mono<SearchConfigStatsDto> getConfigurationStatistics() {
        return searchConfigRepository.findAll().collectList()
                .map(allConfigs -> {
                    long total = allConfigs.size();
                    long enabled = allConfigs.stream().filter(SearchConfig::isSearchEnabled).count();
                    long autoEnabled = allConfigs.stream().filter(SearchConfig::isAutoSearchEnabled).count();

                    Map<String, Long> providerDist = allConfigs.stream()
                            .collect(Collectors.groupingBy(config -> config.getSearchProvider().name(), Collectors.counting()));

                    double avgMaxResults = allConfigs.stream().mapToInt(SearchConfig::getMaxResults).average().orElse(0.0);
                    double avgCache = allConfigs.stream().mapToInt(SearchConfig::getCacheDurationMinutes).average().orElse(0.0);
                    double avgRateLimit = allConfigs.stream().mapToInt(SearchConfig::getRateLimitPerHour).average().orElse(0.0);

                    return new SearchConfigStatsDto(total, enabled, autoEnabled, providerDist, avgMaxResults, avgCache, avgRateLimit);
                });
    }

    // Private helper methods

    private void updateEntityFromDto(SearchConfig entity, SearchConfigDto dto) {
        if (dto.isSearchEnabled() != entity.isSearchEnabled()) entity.setSearchEnabled(dto.isSearchEnabled());
        if (dto.isAutoSearchEnabled() != entity.isAutoSearchEnabled()) entity.setAutoSearchEnabled(dto.isAutoSearchEnabled());
        if (dto.getSearchProvider() != null) entity.setSearchProvider(dto.getSearchProvider());
        if (dto.getMaxResults() != null) entity.setMaxResults(dto.getMaxResults());
        if (dto.getCacheDurationMinutes() != null) entity.setCacheDurationMinutes(dto.getCacheDurationMinutes());
        if (dto.getRateLimitPerHour() != null) entity.setRateLimitPerHour(dto.getRateLimitPerHour());
        if (dto.isIncludeAttribution() != entity.isIncludeAttribution()) entity.setIncludeAttribution(dto.isIncludeAttribution());
        if (dto.getRelevanceThreshold() != null) entity.setRelevanceThreshold(dto.getRelevanceThreshold());
        if (dto.getSearchTriggers() != null) entity.setSearchTriggers(convertTriggersToJson(dto.getSearchTriggers()));
    }

    private SearchConfigDto createDefaultConfigDto() {
        SearchConfigDto dto = new SearchConfigDto();
        dto.setSearchEnabled(defaultSearchEnabled);
        dto.setAutoSearchEnabled(defaultAutoSearchEnabled);
        dto.setSearchProvider(SearchProvider.valueOf(defaultProvider));
        dto.setMaxResults(defaultMaxResults);
        dto.setCacheDurationMinutes(defaultCacheDuration);
        dto.setRateLimitPerHour(defaultRateLimit);
        dto.setIncludeAttribution(true);
        dto.setRelevanceThreshold(0.6);
        dto.setSearchTriggers(new ArrayList<>());
        return dto;
    }

    private SearchConfigDto createDefaultConfigDto(Long chatId) {
        SearchConfigDto dto = new SearchConfigDto(chatId);
        dto.setSearchEnabled(defaultSearchEnabled);
        dto.setAutoSearchEnabled(defaultAutoSearchEnabled);
        dto.setSearchProvider(SearchProvider.valueOf(defaultProvider));
        dto.setMaxResults(defaultMaxResults);
        dto.setCacheDurationMinutes(defaultCacheDuration);
        dto.setRateLimitPerHour(defaultRateLimit);
        dto.setIncludeAttribution(true);
        dto.setRelevanceThreshold(0.6);
        dto.setSearchTriggers(new ArrayList<>());
        return dto;
    }

    private SearchConfig createDefaultEntity(Long chatId) {
        SearchConfig config = new SearchConfig(chatId);
        config.setSearchEnabled(defaultSearchEnabled);
        config.setAutoSearchEnabled(defaultAutoSearchEnabled);
        config.setSearchProvider(SearchProvider.valueOf(defaultProvider));
        config.setMaxResults(defaultMaxResults);
        config.setCacheDurationMinutes(defaultCacheDuration);
        config.setRateLimitPerHour(defaultRateLimit);
        config.setIncludeAttribution(true);
        config.setRelevanceThreshold(0.6);
        return config;
    }

    private SearchConfigDto convertToDto(SearchConfig entity) {
        SearchConfigDto dto = new SearchConfigDto(entity.getChatId());
        dto.setId(entity.getId());
        dto.setSearchEnabled(entity.isSearchEnabled());
        dto.setAutoSearchEnabled(entity.isAutoSearchEnabled());
        dto.setSearchProvider(entity.getSearchProvider());
        dto.setMaxResults(entity.getMaxResults());
        dto.setCacheDurationMinutes(entity.getCacheDurationMinutes());
        dto.setRateLimitPerHour(entity.getRateLimitPerHour());
        dto.setIncludeAttribution(entity.isIncludeAttribution());
        dto.setRelevanceThreshold(entity.getRelevanceThreshold());
        dto.setSearchTriggers(parseTriggersFromJson(entity.getSearchTriggers()));
        return dto;
    }

    private SearchConfig convertToEntity(SearchConfigDto dto) {
        SearchConfig entity = new SearchConfig();
        entity.setId(dto.getId());
        entity.setChatId(dto.getChatId());
        entity.setSearchEnabled(dto.isSearchEnabled());
        entity.setAutoSearchEnabled(dto.isAutoSearchEnabled());
        entity.setSearchProvider(dto.getSearchProvider());
        entity.setMaxResults(dto.getMaxResults());
        entity.setCacheDurationMinutes(dto.getCacheDurationMinutes());
        entity.setRateLimitPerHour(dto.getRateLimitPerHour());
        entity.setIncludeAttribution(dto.isIncludeAttribution());
        entity.setRelevanceThreshold(dto.getRelevanceThreshold());
        entity.setSearchTriggers(convertTriggersToJson(dto.getSearchTriggers()));
        return entity;
    }

    private String convertTriggersToJson(List<String> triggers) {
        if (triggers == null || triggers.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(triggers);
        } catch (JsonProcessingException e) {
            log.warn("Failed to convert search triggers to JSON", e);
            return null;
        }
    }

    private List<String> parseTriggersFromJson(String json) {
        if (json == null || json.trim().isEmpty()) return new ArrayList<>();
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse search triggers from JSON: {}", json, e);
            return new ArrayList<>();
        }
    }
}
