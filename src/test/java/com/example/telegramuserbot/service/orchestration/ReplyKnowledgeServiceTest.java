package com.example.telegramuserbot.service.orchestration;

import com.example.telegramuserbot.domain.MessageEntity;
import com.example.telegramuserbot.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Contract tests for the knowledge-block rules:
 * FR-001: disabled flag → empty result, repository never touched.
 * FR-002: a lookup failure NEVER blocks the reply — block resolves empty.
 * FR-003: items overlapping the conversation topic are preferred.
 * FR-004: no topic overlap → fall back to importance order (still returns items).
 */
@ExtendWith(MockitoExtension.class)
class ReplyKnowledgeServiceTest {

    @Mock
    private MessageRepository messageRepository;
    @Mock
    private com.example.telegramuserbot.service.search.TavilyEnrichmentService tavilyEnrichmentService;

    private ReplyKnowledgeService service;

    @BeforeEach
    void setUp() {
        service = new ReplyKnowledgeService(messageRepository, tavilyEnrichmentService);
        // Tavily off by default in these tests → DB-only behavior preserved.
        org.mockito.Mockito.lenient().when(tavilyEnrichmentService.enrich(org.mockito.ArgumentMatchers.any()))
                .thenReturn(reactor.core.publisher.Mono.empty());
        ReflectionTestUtils.setField(service, "windowHours", 24);
        ReflectionTestUtils.setField(service, "maxItems", 5);
        ReflectionTestUtils.setField(service, "maxItemChars", 200);
        ReflectionTestUtils.setField(service, "candidateMultiplier", 4);
    }

    private static MessageEntity msg(String content) {
        MessageEntity m = new MessageEntity();
        m.setContent(content);
        return m;
    }

    @Test
    void disabledFlagSkipsLookupEntirely() {
        ReflectionTestUtils.setField(service, "enabled", false);

        StepVerifier.create(service.buildKnowledgeBlock("anything")).verifyComplete();
        verifyNoInteractions(messageRepository);
    }

    @Test
    void lookupFailureNeverBlocksTheReply() {
        ReflectionTestUtils.setField(service, "enabled", true);
        when(messageRepository.findPrimaryMessagesForDigest(any(Instant.class), anyLong(), anyInt()))
                .thenReturn(Flux.error(new IllegalStateException("db down")));

        StepVerifier.create(service.buildKnowledgeBlock("topic")).verifyComplete();
    }

    @Test
    void prefersItemsMatchingTheConversationTopic() {
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "maxItems", 1);
        when(messageRepository.findPrimaryMessagesForDigest(any(Instant.class), anyLong(), anyInt()))
                .thenReturn(Flux.just(
                        msg("Weather will be sunny tomorrow"),
                        msg("Bitcoin price surged past resistance today")));

        StepVerifier.create(service.buildKnowledgeBlock("what about bitcoin trading?"))
                .assertNext(block -> assertThat(block).contains("Bitcoin"))
                .verifyComplete();
    }

    @Test
    void fallsBackToImportanceOrderWhenNoTopicOverlap() {
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "maxItems", 1);
        when(messageRepository.findPrimaryMessagesForDigest(any(Instant.class), anyLong(), anyInt()))
                .thenReturn(Flux.just(
                        msg("Bitcoin price surged past resistance"),
                        msg("Weather sunny tomorrow")));

        // No term overlaps the candidates → keep the query's importance order (first item).
        StepVerifier.create(service.buildKnowledgeBlock("completely unrelated xyzzy"))
                .assertNext(block -> assertThat(block).contains("Bitcoin"))
                .verifyComplete();
    }
}
