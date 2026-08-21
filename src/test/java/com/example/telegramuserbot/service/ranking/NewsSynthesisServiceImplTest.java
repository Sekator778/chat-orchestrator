package com.example.telegramuserbot.service.ranking;

import com.example.telegramuserbot.domain.MessageEntity;
import com.example.telegramuserbot.repository.MessageRepository;
import com.example.telegramuserbot.service.llm.client.DeepSeekApiClient;
import com.example.telegramuserbot.service.llm.dto.DeepSeekChatRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for NewsSynthesisServiceImpl.
 * Verifies LLM integration via DeepSeekApiClient for news synthesis operations.
 */
@ExtendWith(MockitoExtension.class)
final class NewsSynthesisServiceImplTest {

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private DeepSeekApiClient deepSeekApiClient;

    private NewsSynthesisServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new NewsSynthesisServiceImpl(messageRepository, deepSeekApiClient);
    }

    @Test
    void generateDigestReturnsLlmResponseForValidMessages() {
        MessageEntity message1 = createMessageEntity(1L, "Breaking news: Market reaches all-time high");
        MessageEntity message2 = createMessageEntity(2L, "Technology sector leads the gains");
        when(messageRepository.findPrimaryMessagesForDigest(any(Instant.class), anyLong(), anyInt()))
                .thenReturn(Flux.just(message1, message2));
        when(deepSeekApiClient.chat(any(DeepSeekChatRequest.class), anyLong()))
                .thenReturn(Mono.just("• Market reaches all-time high\n• Tech sector leads"));
        StepVerifier.create(service.generateDigest(Duration.ofHours(24), 50, "en"))
                .expectNext("• Market reaches all-time high\n• Tech sector leads")
                .verifyComplete();
        verify(deepSeekApiClient).chat(any(DeepSeekChatRequest.class), eq(0L));
    }

    @Test
    void generateDigestReturnsNoNewsMessageWhenNoMessagesFound() {
        when(messageRepository.findPrimaryMessagesForDigest(any(Instant.class), anyLong(), anyInt()))
                .thenReturn(Flux.empty());
        StepVerifier.create(service.generateDigest(Duration.ofHours(24), 50, "en"))
                .expectNext("No significant news in this period.")
                .verifyComplete();
        verifyNoInteractions(deepSeekApiClient);
    }

    @Test
    void generateDigestUsesRussianPromptForRuLanguage() {
        MessageEntity message = createMessageEntity(1L, "Важные новости: рынок растёт");
        when(messageRepository.findPrimaryMessagesForDigest(any(Instant.class), anyLong(), anyInt()))
                .thenReturn(Flux.just(message));
        when(deepSeekApiClient.chat(any(DeepSeekChatRequest.class), anyLong()))
                .thenReturn(Mono.just("• Рынок показывает рост"));
        ArgumentCaptor<DeepSeekChatRequest> requestCaptor = ArgumentCaptor.forClass(DeepSeekChatRequest.class);
        StepVerifier.create(service.generateDigest(Duration.ofHours(24), 50, "ru"))
                .expectNext("• Рынок показывает рост")
                .verifyComplete();
        verify(deepSeekApiClient).chat(requestCaptor.capture(), eq(0L));
        String systemPrompt = requestCaptor.getValue().messages().get(0).content();
        assertThat(systemPrompt, containsString("профессиональный аналитик"));
    }

    @Test
    void generateDigestUsesRussianPromptForUkLanguage() {
        MessageEntity message = createMessageEntity(1L, "Важливі новини");
        when(messageRepository.findPrimaryMessagesForDigest(any(Instant.class), anyLong(), anyInt()))
                .thenReturn(Flux.just(message));
        when(deepSeekApiClient.chat(any(DeepSeekChatRequest.class), anyLong()))
                .thenReturn(Mono.just("• Важливі новини"));
        ArgumentCaptor<DeepSeekChatRequest> requestCaptor = ArgumentCaptor.forClass(DeepSeekChatRequest.class);
        StepVerifier.create(service.generateDigest(Duration.ofHours(24), 50, "uk"))
                .expectNext("• Важливі новини")
                .verifyComplete();
        verify(deepSeekApiClient).chat(requestCaptor.capture(), eq(0L));
        String systemPrompt = requestCaptor.getValue().messages().get(0).content();
        assertThat(systemPrompt, containsString("Вы профессиональный"));
    }

    @Test
    void generateDigestUsesEnglishPromptForEnLanguage() {
        MessageEntity message = createMessageEntity(1L, "Important news update");
        when(messageRepository.findPrimaryMessagesForDigest(any(Instant.class), anyLong(), anyInt()))
                .thenReturn(Flux.just(message));
        when(deepSeekApiClient.chat(any(DeepSeekChatRequest.class), anyLong()))
                .thenReturn(Mono.just("• News update"));
        ArgumentCaptor<DeepSeekChatRequest> requestCaptor = ArgumentCaptor.forClass(DeepSeekChatRequest.class);
        StepVerifier.create(service.generateDigest(Duration.ofHours(24), 50, "en"))
                .expectNext("• News update")
                .verifyComplete();
        verify(deepSeekApiClient).chat(requestCaptor.capture(), eq(0L));
        String systemPrompt = requestCaptor.getValue().messages().get(0).content();
        assertThat(systemPrompt, containsString("professional news analyst"));
    }

    @Test
    void summarizeClusterReturnsEmptyForNoMessages() {
        when(messageRepository.findByClusterId("cluster-123"))
                .thenReturn(Flux.empty());
        StepVerifier.create(service.summarizeCluster("cluster-123", "en"))
                .expectNext("")
                .verifyComplete();
        verifyNoInteractions(deepSeekApiClient);
    }

    @Test
    void summarizeClusterCallsLlmWithClusterPrompt() {
        MessageEntity message1 = createMessageEntity(1L, "First related news");
        MessageEntity message2 = createMessageEntity(2L, "Second related news");
        when(messageRepository.findByClusterId("cluster-abc"))
                .thenReturn(Flux.just(message1, message2));
        when(deepSeekApiClient.chat(any(DeepSeekChatRequest.class), anyLong()))
                .thenReturn(Mono.just("Coherent summary of related news"));
        ArgumentCaptor<DeepSeekChatRequest> requestCaptor = ArgumentCaptor.forClass(DeepSeekChatRequest.class);
        StepVerifier.create(service.summarizeCluster("cluster-abc", "en"))
                .expectNext("Coherent summary of related news")
                .verifyComplete();
        verify(deepSeekApiClient).chat(requestCaptor.capture(), eq(0L));
        String systemPrompt = requestCaptor.getValue().messages().get(0).content();
        assertThat(systemPrompt, containsString("Summarize the following related messages"));
    }

    @Test
    void generateBulletPointsReturnsEmptyForNullContents() {
        StepVerifier.create(service.generateBulletPoints(null, "en"))
                .expectNext("")
                .verifyComplete();
        verifyNoInteractions(deepSeekApiClient);
    }

    @Test
    void generateBulletPointsReturnsEmptyForEmptyList() {
        StepVerifier.create(service.generateBulletPoints(Collections.emptyList(), "en"))
                .expectNext("")
                .verifyComplete();
        verifyNoInteractions(deepSeekApiClient);
    }

    @Test
    void generateBulletPointsFormatsMessagesCorrectly() {
        List<String> contents = Arrays.asList("First news item", "Second news item");
        when(deepSeekApiClient.chat(any(DeepSeekChatRequest.class), anyLong()))
                .thenReturn(Mono.just("• Bullet 1\n• Bullet 2"));
        ArgumentCaptor<DeepSeekChatRequest> requestCaptor = ArgumentCaptor.forClass(DeepSeekChatRequest.class);
        StepVerifier.create(service.generateBulletPoints(contents, "en"))
                .expectNext("• Bullet 1\n• Bullet 2")
                .verifyComplete();
        verify(deepSeekApiClient).chat(requestCaptor.capture(), eq(0L));
        String userContent = requestCaptor.getValue().messages().get(1).content();
        assertThat(userContent, containsString("1. First news item"));
        assertThat(userContent, containsString("2. Second news item"));
    }

    @Test
    void generateDigestHandlesLlmError() {
        MessageEntity message = createMessageEntity(1L, "News content");
        when(messageRepository.findPrimaryMessagesForDigest(any(Instant.class), anyLong(), anyInt()))
                .thenReturn(Flux.just(message));
        when(deepSeekApiClient.chat(any(DeepSeekChatRequest.class), anyLong()))
                .thenReturn(Mono.error(new RuntimeException("API timeout")));
        StepVerifier.create(service.generateDigest(Duration.ofHours(24), 50, "en"))
                .expectNext("Synthesis unavailable")
                .verifyComplete();
    }

    @Test
    void generateDigestHandlesEmptyLlmResponse() {
        MessageEntity message = createMessageEntity(1L, "News content");
        when(messageRepository.findPrimaryMessagesForDigest(any(Instant.class), anyLong(), anyInt()))
                .thenReturn(Flux.just(message));
        when(deepSeekApiClient.chat(any(DeepSeekChatRequest.class), anyLong()))
                .thenReturn(Mono.empty());
        StepVerifier.create(service.generateDigest(Duration.ofHours(24), 50, "en"))
                .expectNext("Synthesis unavailable")
                .verifyComplete();
    }

    @Test
    void generateDigestUsesMessageCaptionWhenContentIsNull() {
        MessageEntity message = new MessageEntity();
        message.setMessageId(1L);
        message.setCaption("Caption content when main content is null");
        when(messageRepository.findPrimaryMessagesForDigest(any(Instant.class), anyLong(), anyInt()))
                .thenReturn(Flux.just(message));
        when(deepSeekApiClient.chat(any(DeepSeekChatRequest.class), anyLong()))
                .thenReturn(Mono.just("Digest from caption"));
        ArgumentCaptor<DeepSeekChatRequest> requestCaptor = ArgumentCaptor.forClass(DeepSeekChatRequest.class);
        StepVerifier.create(service.generateDigest(Duration.ofHours(24), 50, "en"))
                .expectNext("Digest from caption")
                .verifyComplete();
        verify(deepSeekApiClient).chat(requestCaptor.capture(), eq(0L));
        String userContent = requestCaptor.getValue().messages().get(1).content();
        assertThat(userContent, containsString("Caption content when main content is null"));
    }

    @Test
    void generateDigestTruncatesLongContent() {
        String longContent = "A".repeat(600);
        MessageEntity message = createMessageEntity(1L, longContent);
        when(messageRepository.findPrimaryMessagesForDigest(any(Instant.class), anyLong(), anyInt()))
                .thenReturn(Flux.just(message));
        when(deepSeekApiClient.chat(any(DeepSeekChatRequest.class), anyLong()))
                .thenReturn(Mono.just("Truncated digest"));
        ArgumentCaptor<DeepSeekChatRequest> requestCaptor = ArgumentCaptor.forClass(DeepSeekChatRequest.class);
        StepVerifier.create(service.generateDigest(Duration.ofHours(24), 50, "en"))
                .expectNext("Truncated digest")
                .verifyComplete();
        verify(deepSeekApiClient).chat(requestCaptor.capture(), eq(0L));
        String userContent = requestCaptor.getValue().messages().get(1).content();
        assertThat(userContent.length(), lessThan(longContent.length() + 50));
        assertThat(userContent, containsString("..."));
    }

    @Test
    void generateDigestFiltersBlankContent() {
        MessageEntity message1 = createMessageEntity(1L, "Valid content");
        MessageEntity message2 = createMessageEntity(2L, "   ");
        MessageEntity message3 = createMessageEntity(3L, "");
        when(messageRepository.findPrimaryMessagesForDigest(any(Instant.class), anyLong(), anyInt()))
                .thenReturn(Flux.just(message1, message2, message3));
        when(deepSeekApiClient.chat(any(DeepSeekChatRequest.class), anyLong()))
                .thenReturn(Mono.just("Filtered digest"));
        ArgumentCaptor<DeepSeekChatRequest> requestCaptor = ArgumentCaptor.forClass(DeepSeekChatRequest.class);
        StepVerifier.create(service.generateDigest(Duration.ofHours(24), 50, "en"))
                .expectNext("Filtered digest")
                .verifyComplete();
        verify(deepSeekApiClient).chat(requestCaptor.capture(), eq(0L));
        String userContent = requestCaptor.getValue().messages().get(1).content();
        assertThat(userContent, containsString("1. Valid content"));
        assertThat(userContent, not(containsString("2.")));
    }

    @Test
    void generateDigestReturnsEmptyForMessagesWithOnlyBlankContent() {
        MessageEntity msgEmpty1 = new MessageEntity();
        msgEmpty1.setId(1L);
        msgEmpty1.setContent(null);
        msgEmpty1.setCaption(null);
        msgEmpty1.setDate(Instant.now());
        MessageEntity msgEmpty2 = new MessageEntity();
        msgEmpty2.setId(2L);
        msgEmpty2.setContent("");
        msgEmpty2.setCaption("");
        msgEmpty2.setDate(Instant.now());
        when(messageRepository.findPrimaryMessagesForDigest(any(Instant.class), anyLong(), anyInt()))
                .thenReturn(Flux.just(msgEmpty1, msgEmpty2));
        StepVerifier.create(service.generateDigest(Duration.ofHours(24), 10, "en"))
                .expectNext("")
                .verifyComplete();
        verifyNoInteractions(deepSeekApiClient);
    }

    private MessageEntity createMessageEntity(long messageId, String content) {
        MessageEntity entity = new MessageEntity();
        entity.setMessageId(messageId);
        entity.setContent(content);
        return entity;
    }
}
