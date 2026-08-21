package com.example.telegramuserbot.service.queue;

import com.example.telegramuserbot.domain.PendingResponse;
import com.example.telegramuserbot.domain.PendingResponseStatus;
import com.example.telegramuserbot.repository.ChatMessageStatsRepository;
import com.example.telegramuserbot.repository.PendingResponseRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PendingResponseServiceTest {

    @Test
    void enqueueShouldUpdateExistingPendingInsteadOfCreatingDuplicate() {
        PendingResponseRepository repository = mock(PendingResponseRepository.class);
        ChatMessageStatsRepository statsRepository = mock(ChatMessageStatsRepository.class);
        String botInstanceId = "default-bot";

        PendingResponse existing = new PendingResponse();
        existing.setId(82L);
        existing.setChatId(-1001234567890L);
        existing.setTriggeringMessageId(9573498880L);
        existing.setStatus(PendingResponseStatus.PENDING);
        existing.setBaseCount(59L);
        existing.setRequiredDelta(0);
        existing.setCreatedAt(Instant.now().minusSeconds(10));
        existing.setExpiresAt(Instant.now().plusSeconds(3600));

        when(repository.upsertActivePending(eq(-1001234567890L), eq(9573498880L), any(), any(), any(), any(), any(), any(), any(), any(), eq(botInstanceId)))
                .thenReturn(Mono.just(existing));

        PendingResponseService service = new PendingResponseService(repository, statsRepository);

        Instant eligibleAt = Instant.now().plusSeconds(5);
        PendingResponse saved = service.enqueue(
                -1001234567890L,
                9573498880L,
                botInstanceId,
                "new-response",
                "reply",
                "NEUTRAL",
                "ADAPTIVE",
                0,
                eligibleAt
        ).block();

        assertThat(saved).isNotNull();
        assertThat(saved.getId()).isEqualTo(82L);

        ArgumentCaptor<Instant> eligibleCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(repository).upsertActivePending(eq(-1001234567890L), eq(9573498880L), eq("new-response"), eq("reply"), eq("NEUTRAL"), eq("ADAPTIVE"), eq(0),
                eligibleCaptor.capture(), any(Instant.class), any(Instant.class), eq(botInstanceId));
        assertThat(eligibleCaptor.getValue()).isEqualTo(eligibleAt);

        verify(statsRepository, never()).findCountByChatId(anyLong());
    }

    @Test
    void enqueueShouldCreateNewPendingWhenNoneExists() {
        PendingResponseRepository repository = mock(PendingResponseRepository.class);
        ChatMessageStatsRepository statsRepository = mock(ChatMessageStatsRepository.class);
        String botInstanceId = "default-bot";

        PendingResponse inserted = new PendingResponse();
        inserted.setId(100L);
        inserted.setChatId(1L);
        inserted.setTriggeringMessageId(2L);
        inserted.setBaseCount(7L);
        when(repository.upsertActivePending(eq(1L), eq(2L), eq("resp"), eq("reply"), eq("NEUTRAL"), eq("ADAPTIVE"), eq(0), any(Instant.class), any(Instant.class), any(Instant.class), eq(botInstanceId)))
                .thenReturn(Mono.just(inserted));

        PendingResponseService service = new PendingResponseService(repository, statsRepository);

        PendingResponse saved = service.enqueue(1L, 2L, botInstanceId, "resp", "reply", "NEUTRAL", "ADAPTIVE", 0, Instant.now()).block();

        assertThat(saved).isNotNull();
        assertThat(saved.getId()).isEqualTo(100L);
        assertThat(saved.getChatId()).isEqualTo(1L);
        assertThat(saved.getTriggeringMessageId()).isEqualTo(2L);
        assertThat(saved.getBaseCount()).isEqualTo(7L);

        verify(repository).upsertActivePending(eq(1L), eq(2L), eq("resp"), eq("reply"), eq("NEUTRAL"), eq("ADAPTIVE"), eq(0), any(Instant.class), any(Instant.class), any(Instant.class), eq(botInstanceId));
        verify(statsRepository, never()).findCountByChatId(anyLong());
    }
}
