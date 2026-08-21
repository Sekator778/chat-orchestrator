package com.example.telegramuserbot.service.cache;

import com.example.telegramuserbot.domain.ChatConfig;
import com.example.telegramuserbot.repository.ChatConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SyncEnabledChatsCacheTest {

    @Mock
    private ChatConfigRepository chatConfigRepository;

    @Mock
    private com.example.telegramuserbot.service.ProblematicChatService problematicChatService;

    private SyncEnabledChatsCache cache;

    @BeforeEach
    void setUp() {
        when(problematicChatService.shouldProcess(anyLong())).thenReturn(Mono.just(true));
        when(problematicChatService.listProblematicChatIds()).thenReturn(Mono.just(java.util.Set.of()));
        cache = new SyncEnabledChatsCache(chatConfigRepository, problematicChatService);
    }

    @Test
    void treatsLinkedDiscussionAsSyncEnabledEvenWhenFlagDisabled() {
        long chatId = -100_500L;
        ChatConfig config = new ChatConfig();
        config.setChannelId(chatId);
        config.setPrimaryChannelId(-2000L);
        config.setSyncEnabled(false);

        when(chatConfigRepository.findByChannelChatId(chatId)).thenReturn(Mono.just(config));

        StepVerifier.create(cache.syncEnabled(chatId))
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    void returnsConfigForSubsequentCalls() {
        long chatId = -100_300L;
        ChatConfig config = new ChatConfig();
        config.setChannelId(chatId);
        config.setPrimaryChannelId(null);
        config.setSyncEnabled(true);

        when(chatConfigRepository.findByChannelChatId(chatId)).thenReturn(Mono.just(config));

        StepVerifier.create(cache.getConfig(chatId))
                .expectNext(config)
                .verifyComplete();

        // Cached call should not hit repository again; ensure default fallback works
        when(chatConfigRepository.findByChannelChatId(anyLong())).thenReturn(Mono.empty());

        StepVerifier.create(cache.getConfig(chatId))
                .expectNext(config)
                .verifyComplete();
    }
}
