package com.example.telegramuserbot.service.ranking;

import com.example.telegramuserbot.domain.MessageEntity;
import com.example.telegramuserbot.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for ClusteringServiceImpl.
 */
@ExtendWith(MockitoExtension.class)
class ClusteringServiceImplTest {

    @Mock
    private MessageRepository messageRepository;

    private SimHashService simHashService;
    private ClusteringServiceImpl service;

    @BeforeEach
    void setUp() {
        simHashService = new SimHashService();
        service = new ClusteringServiceImpl(messageRepository, simHashService);
    }

    @Test
    void clusterRecentMessagesReturnsZeroWhenNoMessages() {
        when(messageRepository.findUnclusteredMessages(any(Instant.class), anyInt()))
                .thenReturn(Flux.empty());
        StepVerifier.create(service.clusterRecentMessages(Duration.ofHours(24)))
                .expectNext(0)
                .verifyComplete();
    }

    @Test
    void clusterRecentMessagesSkipsMessagesWithEmptyHash() {
        MessageEntity message = createMessage(1L, null, null);
        when(messageRepository.findUnclusteredMessages(any(Instant.class), anyInt()))
                .thenReturn(Flux.just(message));
        StepVerifier.create(service.clusterRecentMessages(Duration.ofHours(24)))
                .expectNext(0)
                .verifyComplete();
        verify(messageRepository, never()).updateClusterAssignment(anyLong(), anyString(), anyBoolean());
    }

    @Test
    void clusterRecentMessagesSkipsMessagesWithZeroHash() {
        MessageEntity message = createMessage(1L, "0000000000000000", null);
        when(messageRepository.findUnclusteredMessages(any(Instant.class), anyInt()))
                .thenReturn(Flux.just(message));
        StepVerifier.create(service.clusterRecentMessages(Duration.ofHours(24)))
                .expectNext(0)
                .verifyComplete();
        verify(messageRepository, never()).updateClusterAssignment(anyLong(), anyString(), anyBoolean());
    }

    @Test
    void clusterRecentMessagesCreatesNewClusterForUniqueMessage() {
        String simhash = simHashService.hash("Unique breaking news about technology");
        MessageEntity message = createMessage(1L, simhash, null);
        when(messageRepository.findUnclusteredMessages(any(Instant.class), anyInt()))
                .thenReturn(Flux.just(message));
        when(messageRepository.findCandidatesForClustering(eq(1L), any(Instant.class), anyInt()))
                .thenReturn(Flux.empty());
        when(messageRepository.updateClusterAssignment(eq(1L), anyString(), eq(false)))
                .thenReturn(Mono.just(1));
        StepVerifier.create(service.clusterRecentMessages(Duration.ofHours(24)))
                .expectNext(1)
                .verifyComplete();
        verify(messageRepository).updateClusterAssignment(eq(1L), argThat(id -> id.startsWith("c")), eq(false));
    }

    @Test
    void clusterRecentMessagesJoinsExistingClusterForSimilarMessage() {
        String simhash = simHashService.hash("Breaking news about the stock market");
        String existingClusterId = "c123456abc";
        MessageEntity newMessage = createMessage(1L, simhash, null);
        MessageEntity existingMessage = createMessage(2L, simhash, existingClusterId);
        when(messageRepository.findUnclusteredMessages(any(Instant.class), anyInt()))
                .thenReturn(Flux.just(newMessage));
        when(messageRepository.findCandidatesForClustering(eq(1L), any(Instant.class), anyInt()))
                .thenReturn(Flux.just(existingMessage));
        when(messageRepository.updateClusterAssignment(eq(1L), eq(existingClusterId), eq(false)))
                .thenReturn(Mono.just(1));
        StepVerifier.create(service.clusterRecentMessages(Duration.ofHours(24)))
                .expectNext(1)
                .verifyComplete();
        verify(messageRepository).updateClusterAssignment(1L, existingClusterId, false);
    }

    @Test
    void clusterMessageReturnsClusterIdForValidMessage() {
        String simhash = simHashService.hash("Test message content");
        MessageEntity message = createMessage(1L, simhash, null);
        when(messageRepository.findById(1L)).thenReturn(Mono.just(message));
        when(messageRepository.findCandidatesForClustering(eq(1L), any(Instant.class), anyInt()))
                .thenReturn(Flux.empty());
        when(messageRepository.updateClusterAssignment(eq(1L), anyString(), eq(false)))
                .thenReturn(Mono.just(1));
        StepVerifier.create(service.clusterMessage(1L))
                .expectNextMatches(clusterId -> clusterId != null && clusterId.startsWith("c"))
                .verifyComplete();
    }

    @Test
    void clusterMessageReturnsEmptyForMessageWithNullHash() {
        MessageEntity message = createMessage(1L, null, null);
        when(messageRepository.findById(1L)).thenReturn(Mono.just(message));
        StepVerifier.create(service.clusterMessage(1L))
                .verifyComplete();
    }

    @Test
    void recalculatePrimaryMessagesReturnsZeroWhenNoClusters() {
        when(messageRepository.findDistinctClusterIds(any(Instant.class)))
                .thenReturn(Flux.empty());
        StepVerifier.create(service.recalculatePrimaryMessages(Duration.ofHours(24)))
                .expectNext(0)
                .verifyComplete();
    }

    @Test
    void recalculatePrimaryMessagesSelectsHighestImportanceAsPrimary() {
        String clusterId = "c123456abc";
        MessageEntity msg1 = createMessageWithImportance(1L, clusterId, 0.5);
        MessageEntity msg2 = createMessageWithImportance(2L, clusterId, 0.8);
        MessageEntity msg3 = createMessageWithImportance(3L, clusterId, 0.3);
        when(messageRepository.findDistinctClusterIds(any(Instant.class)))
                .thenReturn(Flux.just(clusterId));
        when(messageRepository.resetClusterPrimary(clusterId))
                .thenReturn(Mono.just(3));
        when(messageRepository.findByClusterId(clusterId))
                .thenReturn(Flux.just(msg1, msg2, msg3));
        when(messageRepository.updateClusterAssignment(eq(2L), eq(clusterId), eq(true)))
                .thenReturn(Mono.just(1));
        StepVerifier.create(service.recalculatePrimaryMessages(Duration.ofHours(24)))
                .expectNext(1)
                .verifyComplete();
        verify(messageRepository).updateClusterAssignment(2L, clusterId, true);
    }

    @Test
    void recalculatePrimaryMessagesContinuesOnErrorForSingleCluster() {
        String clusterId = "c222";
        MessageEntity msg = createMessageWithImportance(1L, clusterId, 0.5);
        when(messageRepository.findDistinctClusterIds(any(Instant.class)))
                .thenReturn(Flux.just(clusterId));
        when(messageRepository.resetClusterPrimary(clusterId))
                .thenReturn(Mono.just(1));
        when(messageRepository.findByClusterId(clusterId))
                .thenReturn(Flux.just(msg));
        when(messageRepository.updateClusterAssignment(eq(1L), eq(clusterId), eq(true)))
                .thenReturn(Mono.just(1));
        StepVerifier.create(service.recalculatePrimaryMessages(Duration.ofHours(24)))
                .expectNext(1)
                .verifyComplete();
    }

    private MessageEntity createMessage(Long id, String contentSimhash, String clusterId) {
        MessageEntity entity = new MessageEntity();
        entity.setId(id);
        entity.setContentSimhash(contentSimhash);
        entity.setClusterId(clusterId);
        entity.setDate(Instant.now());
        return entity;
    }

    private MessageEntity createMessageWithImportance(Long id, String clusterId, Double importance) {
        MessageEntity entity = new MessageEntity();
        entity.setId(id);
        entity.setClusterId(clusterId);
        entity.setImportance(importance);
        entity.setDate(Instant.now());
        return entity;
    }
}
