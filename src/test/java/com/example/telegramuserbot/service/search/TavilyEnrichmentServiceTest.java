package com.example.telegramuserbot.service.search;

import com.example.telegramuserbot.domain.SearchProvider;
import com.example.telegramuserbot.dto.SearchResponseDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Contract tests for Tavily enrichment safety:
 * FR-001: disabled → no provider/search call, empty result (pure no-op).
 * FR-002: enabled+available → results routed through SearchService and formatted into "title — snippet" lines.
 */
@ExtendWith(MockitoExtension.class)
class TavilyEnrichmentServiceTest {

    @Mock
    private SearchProviderService searchProviderService;
    @Mock
    private SearchService searchService;

    private TavilyEnrichmentService newService(boolean enabled) {
        TavilyEnrichmentService service = new TavilyEnrichmentService(searchProviderService, searchService);
        ReflectionTestUtils.setField(service, "enabled", enabled);
        ReflectionTestUtils.setField(service, "maxResults", 3);
        ReflectionTestUtils.setField(service, "maxSnippetChars", 200);
        return service;
    }

    @Test
    void disabledIsPureNoOp() {
        TavilyEnrichmentService service = newService(false);

        StepVerifier.create(service.enrich("oil prices")).verifyComplete();
        verifyNoInteractions(searchProviderService);
        verifyNoInteractions(searchService);
    }

    @Test
    void enabledFormatsResults() {
        TavilyEnrichmentService service = newService(true);
        when(searchProviderService.isProviderAvailable(SearchProvider.TAVILY)).thenReturn(true);
        SearchResponseDto.SearchItemDto item = new SearchResponseDto.SearchItemDto();
        item.setTitle("Oil rallies");
        item.setSnippet("Brent crude rose 3% on supply concerns");
        SearchResponseDto response = new SearchResponseDto("oil prices", List.of(item));
        when(searchService.searchForChat(any(), isNull()))
                .thenReturn(Mono.just(response));

        StepVerifier.create(service.enrich("oil prices"))
                .assertNext(block -> assertThat(block).contains("Oil rallies").contains("Brent crude"))
                .verifyComplete();
    }
}
