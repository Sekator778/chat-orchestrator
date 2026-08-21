package com.example.telegramuserbot.service.search;

import com.example.telegramuserbot.domain.SearchConfig;
import com.example.telegramuserbot.domain.SearchProvider;
import com.example.telegramuserbot.domain.SearchResult;
import com.example.telegramuserbot.dto.SearchRequestDto;
import com.example.telegramuserbot.dto.SearchResponseDto;
import com.example.telegramuserbot.repository.SearchCacheStatistics;
import com.example.telegramuserbot.repository.SearchConfigRepository;
import com.example.telegramuserbot.repository.SearchResultRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SearchService implementation
 */
@ExtendWith(MockitoExtension.class)
class SearchServiceTest {
    
    @Mock
    private SearchConfigRepository searchConfigRepository;
    
    @Mock
    private SearchResultRepository searchResultRepository;
    
    @Mock
    private ObjectMapper objectMapper;
    
    @Mock
    private SearchProviderService searchProviderService;
    
    @Mock
    private SearchRateLimitService rateLimitService;

    @Mock
    private PlatformTransactionManager transactionManager;
    
    @InjectMocks
    private SearchServiceImpl searchService;
    
    private Long testChatId;
    private SearchConfig testConfig;
    private SearchRequestDto testRequest;
    
    @BeforeEach
    void setUp() {
        testChatId = 123L;
        
        testConfig = new SearchConfig(testChatId);
        testConfig.setSearchEnabled(true);
        testConfig.setAutoSearchEnabled(true);
        testConfig.setSearchProvider(SearchProvider.GOOGLE);
        testConfig.setMaxResults(5);
        testConfig.setRateLimitPerHour(30);
        testConfig.setCacheDurationMinutes(60);
        testConfig.setRelevanceThreshold(0.6);
        
        testRequest = new SearchRequestDto("test query", testChatId);
    }
    
    @Test
    void testSearchWithValidRequest() {
        // Arrange
        when(searchConfigRepository.findByChatId(testChatId))
                .thenReturn(Mono.just(testConfig));
        when(rateLimitService.checkRateLimit(testChatId, 30))
                .thenReturn(Mono.just(true));
        when(searchResultRepository.findValidCachedResult(anyString(), any(SearchProvider.class), any(Instant.class)))
                .thenReturn(Mono.empty());
        
        SearchResponseDto.SearchItemDto item = new SearchResponseDto.SearchItemDto();
        item.setTitle("Test Result");
        item.setUrl("https://example.com");
        item.setSnippet("Test snippet");
        item.setRelevanceScore(0.8);
        
        when(searchProviderService.search(anyString(), eq(SearchProvider.GOOGLE), eq(5)))
                .thenReturn(Mono.just(Arrays.asList(item)));
        when(searchResultRepository.save(any(SearchResult.class)))
                .thenReturn(Mono.just(new SearchResult()));
        
        // Act & Assert
        StepVerifier.create(searchService.search(testRequest))
                .assertNext(response -> {
                    assertNotNull(response);
                    assertEquals("test query", response.getQuery());
                    assertNotNull(response.getResults());
                    assertFalse(response.getResults().isEmpty());
                    assertEquals("Test Result", response.getResults().get(0).getTitle());
                    assertFalse(response.isFromCache());
                })
                .verifyComplete();
        
        verify(searchConfigRepository).findByChatId(testChatId);
        verify(rateLimitService).checkRateLimit(testChatId, 30);
        verify(searchProviderService).search(anyString(), eq(SearchProvider.GOOGLE), eq(5));
    }
    
    @Test
    void testSearchWithDisabledConfiguration() {
        // Arrange
        testConfig.setSearchEnabled(false);
        when(searchConfigRepository.findByChatId(testChatId))
                .thenReturn(Mono.just(testConfig));
        
        // Act & Assert
        StepVerifier.create(searchService.search(testRequest))
                .assertNext(response -> {
                    assertNotNull(response);
                    assertEquals("test query", response.getQuery());
                    assertTrue(response.getResults().isEmpty());
                })
                .verifyComplete();
        
        verify(searchConfigRepository).findByChatId(testChatId);
        verifyNoInteractions(rateLimitService);
        verifyNoInteractions(searchProviderService);
    }
    
    @Test
    void testSearchWithRateLimitExceeded() {
        // Arrange
        when(searchConfigRepository.findByChatId(testChatId))
                .thenReturn(Mono.just(testConfig));
        when(rateLimitService.checkRateLimit(testChatId, 30))
                .thenReturn(Mono.just(false));
        
        // Act & Assert
        StepVerifier.create(searchService.search(testRequest))
                .assertNext(response -> {
                    assertNotNull(response);
                    assertEquals("test query", response.getQuery());
                    assertTrue(response.getResults().isEmpty());
                    assertNotNull(response.getSummary());
                    assertTrue(response.getSummary().toLowerCase().contains("rate limit"));
                })
                .verifyComplete();
        
        verify(rateLimitService).checkRateLimit(testChatId, 30);
        verifyNoInteractions(searchProviderService);
    }
    
    @Test
    void testSearchWithCachedResult() {
        // Arrange
        when(searchConfigRepository.findByChatId(testChatId))
                .thenReturn(Mono.just(testConfig));
        when(rateLimitService.checkRateLimit(testChatId, 30))
                .thenReturn(Mono.just(true));
        
        SearchResult cachedResult = new SearchResult();
        cachedResult.setOriginalQuery("test query");
        cachedResult.setSearchProvider(SearchProvider.GOOGLE);
        cachedResult.setResultsJson("[{\"title\":\"Cached Result\",\"url\":\"https://cached.com\",\"snippet\":\"Cached snippet\"}]");
        cachedResult.setTotalResults(1L);
        cachedResult.setSearchTimeMs(100L);
        cachedResult.setRelevanceScore(0.7);
        cachedResult.setCreatedAt(Instant.now());
        
        when(searchResultRepository.findValidCachedResult(anyString(), eq(SearchProvider.GOOGLE), any(Instant.class)))
                .thenReturn(Mono.just(cachedResult));
        when(searchResultRepository.save(any(SearchResult.class)))
                .thenReturn(Mono.just(cachedResult));
        
        try {
            when(objectMapper.readValue(anyString(), any(com.fasterxml.jackson.core.type.TypeReference.class)))
                    .thenReturn(Arrays.asList(
                            new SearchResponseDto.SearchItemDto("Cached Result", "https://cached.com", "Cached snippet")
                    ));
        } catch (Exception e) {
            fail("ObjectMapper setup failed");
        }
        
        // Act & Assert
        StepVerifier.create(searchService.search(testRequest))
                .assertNext(response -> {
                    assertNotNull(response);
                    assertEquals("test query", response.getQuery());
                    assertTrue(response.isFromCache());
                    assertNotNull(response.getResults());
                    assertFalse(response.getResults().isEmpty());
                    assertEquals("Cached Result", response.getResults().get(0).getTitle());
                })
                .verifyComplete();
        
        verifyNoInteractions(searchProviderService);
    }
    
    @Test
    void testShouldPerformSearchWithTriggers() {
        // Arrange
        String messageContent = "What is the weather today?";
        when(searchConfigRepository.findByChatId(testChatId))
                .thenReturn(Mono.just(testConfig));
        
        // Act & Assert
        StepVerifier.create(searchService.shouldPerformSearch(messageContent, testChatId))
                .assertNext(shouldSearch -> {
                    assertTrue(shouldSearch);
                })
                .verifyComplete();
    }
    
    @Test
    void testShouldPerformSearchWithoutTriggers() {
        // Arrange
        String messageContent = "Hello, how are you?";
        when(searchConfigRepository.findByChatId(testChatId))
                .thenReturn(Mono.just(testConfig));
        
        // Act & Assert
        StepVerifier.create(searchService.shouldPerformSearch(messageContent, testChatId))
                .assertNext(shouldSearch -> {
                    assertFalse(shouldSearch);
                })
                .verifyComplete();
    }
    
    @Test
    void testShouldPerformSearchWithAutoSearchDisabled() {
        // Arrange
        testConfig.setAutoSearchEnabled(false);
        String messageContent = "What is the weather today?";
        when(searchConfigRepository.findByChatId(testChatId))
                .thenReturn(Mono.just(testConfig));
        
        // Act & Assert
        StepVerifier.create(searchService.shouldPerformSearch(messageContent, testChatId))
                .assertNext(shouldSearch -> {
                    assertFalse(shouldSearch);
                })
                .verifyComplete();
    }
    
    @Test
    void testExtractSearchQuery() {
        // Arrange
        String messageContent = "What is the capital of France?";
        
        // Act & Assert
        StepVerifier.create(searchService.extractSearchQuery(messageContent, testChatId))
                .assertNext(query -> {
                    assertNotNull(query);
                    assertFalse(query.isEmpty());
                    assertTrue(query.toLowerCase().contains("capital") || query.toLowerCase().contains("france"));
                })
                .verifyComplete();
    }
    
    @Test
    void testEnhanceResponseWithSearch() {
        // Arrange
        String originalResponse = "The capital of France is Paris.";
        String messageContent = "What is the capital of France?";
        
        when(searchConfigRepository.findByChatId(testChatId))
                .thenReturn(Mono.just(testConfig));
        when(rateLimitService.checkRateLimit(testChatId, 30))
                .thenReturn(Mono.just(true));
        when(searchResultRepository.findValidCachedResult(anyString(), any(SearchProvider.class), any(Instant.class)))
                .thenReturn(Mono.empty());
        
        SearchResponseDto.SearchItemDto item = new SearchResponseDto.SearchItemDto();
        item.setTitle("Paris - Capital of France");
        item.setUrl("https://example.com/paris");
        item.setSnippet("Paris is the capital and most populous city of France.");
        
        when(searchProviderService.search(anyString(), eq(SearchProvider.GOOGLE), eq(5)))
                .thenReturn(Mono.just(Arrays.asList(item)));
        when(searchResultRepository.save(any(SearchResult.class)))
                .thenReturn(Mono.just(new SearchResult()));
        
        // Act & Assert
        StepVerifier.create(searchService.enhanceResponseWithSearch(originalResponse, messageContent, testChatId))
                .assertNext(enhancedResponse -> {
                    assertNotNull(enhancedResponse);
                    assertTrue(enhancedResponse.length() > originalResponse.length());
                    assertTrue(enhancedResponse.contains(originalResponse));
                    assertTrue(enhancedResponse.contains("Additional Information"));
                })
                .verifyComplete();
    }
    
    @Test
    void testGetRemainingSearchQuota() {
        // Arrange
        when(rateLimitService.getRemainingQuota(testChatId))
                .thenReturn(Mono.just(25));
        
        // Act & Assert
        StepVerifier.create(searchService.getRemainingSearchQuota(testChatId))
                .assertNext(quota -> {
                    assertEquals(25, quota);
                })
                .verifyComplete();
    }
    
    @Test
    void testClearExpiredCache() {
        // Arrange
        when(searchResultRepository.deleteExpiredResults(any(Instant.class)))
                .thenReturn(Mono.just(5));
        
        // Act & Assert
        StepVerifier.create(searchService.clearExpiredCache())
                .assertNext(deletedCount -> {
                    assertEquals(5, deletedCount);
                })
                .verifyComplete();
    }
    
    @Test
    void testSearchWithEmptyQuery() {
        // Arrange
        SearchRequestDto emptyRequest = new SearchRequestDto("", testChatId);
        
        // Act & Assert
        StepVerifier.create(searchService.search(emptyRequest))
                .expectError(IllegalArgumentException.class)
                .verify();
    }
    
    @Test
    void testSearchWithNullQuery() {
        // Arrange
        SearchRequestDto nullRequest = new SearchRequestDto(null, testChatId);
        
        // Act & Assert
        StepVerifier.create(searchService.search(nullRequest))
                .expectError(IllegalArgumentException.class)
                .verify();
    }
    
    @Test
    void testGetSearchStatisticsWithValidData() {
        // Arrange - simulate database returning Long and Double types
        SearchCacheStatistics dbStats = new SearchCacheStatistics(10L, 8L, 2.5, 150.0);
        when(searchResultRepository.getCacheStatistics(any(Instant.class)))
                .thenReturn(Mono.just(dbStats));
        when(searchConfigRepository.countSearchEnabledConfigurations())
                .thenReturn(Mono.just(3L));
        
        // Act & Assert
        StepVerifier.create(searchService.getSearchStatistics())
                .assertNext(stats -> {
                    assertNotNull(stats);
                    assertEquals(10L, stats.getTotalSearches());
                    assertEquals(8L, stats.getCacheHits());
                    assertEquals(2L, stats.getCacheMisses());
                    assertEquals(150.0, stats.getAverageSearchTime());
                    assertEquals(3L, stats.getActiveConfigurations());
                })
                .verifyComplete();
    }
    
    @Test
    void testGetSearchStatisticsWithMixedNumericTypes() {
        // Arrange - simulate database returning mixed numeric types (BigInteger, BigDecimal, etc.)
        SearchCacheStatistics dbStats = new SearchCacheStatistics(15L, 12L, 3.7, 200.5);
        when(searchResultRepository.getCacheStatistics(any(Instant.class)))
                .thenReturn(Mono.just(dbStats));
        when(searchConfigRepository.countSearchEnabledConfigurations())
                .thenReturn(Mono.just(5L));
        
        // Act & Assert
        StepVerifier.create(searchService.getSearchStatistics())
                .assertNext(stats -> {
                    assertNotNull(stats);
                    assertEquals(15L, stats.getTotalSearches());
                    assertEquals(12L, stats.getCacheHits());
                    assertEquals(3L, stats.getCacheMisses());
                    assertEquals(200.5, stats.getAverageSearchTime(), 0.001);
                    assertEquals(5L, stats.getActiveConfigurations());
                })
                .verifyComplete();
    }
    
    @Test
    void testGetSearchStatisticsWithNullValues() {
        // Arrange - simulate database returning null values
        SearchCacheStatistics dbStats = new SearchCacheStatistics(null, null, null, null);
        when(searchResultRepository.getCacheStatistics(any(Instant.class)))
                .thenReturn(Mono.just(dbStats));
        when(searchConfigRepository.countSearchEnabledConfigurations())
                .thenReturn(Mono.just(2L));
        
        // Act & Assert
        StepVerifier.create(searchService.getSearchStatistics())
                .assertNext(stats -> {
                    assertNotNull(stats);
                    assertEquals(0L, stats.getTotalSearches());
                    assertEquals(0L, stats.getCacheHits());
                    assertEquals(0L, stats.getCacheMisses());
                    assertEquals(0.0, stats.getAverageSearchTime());
                    assertEquals(2L, stats.getActiveConfigurations());
                })
                .verifyComplete();
    }
    
    @Test
    void testGetSearchStatisticsWithEmptyResult() {
        // Arrange - simulate database returning empty result
        when(searchResultRepository.getCacheStatistics(any(Instant.class)))
                .thenReturn(Mono.empty());
        when(searchConfigRepository.countSearchEnabledConfigurations())
                .thenReturn(Mono.just(1L));
        
        // Act & Assert
        StepVerifier.create(searchService.getSearchStatistics())
                .assertNext(stats -> {
                    assertNotNull(stats);
                    assertEquals(0L, stats.getTotalSearches());
                    assertEquals(0L, stats.getCacheHits());
                    assertEquals(0L, stats.getCacheMisses());
                    assertEquals(0.0, stats.getAverageSearchTime());
                    assertEquals(1L, stats.getActiveConfigurations());
                })
                .verifyComplete();
    }
}
