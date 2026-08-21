package com.example.telegramuserbot.service.ranking;

import com.example.telegramuserbot.domain.MessageEntity;
import com.example.telegramuserbot.repository.MessageRepository;
import com.example.telegramuserbot.service.llm.client.DeepSeekApiClient;
import com.example.telegramuserbot.service.llm.dto.ApiMessage;
import com.example.telegramuserbot.service.llm.dto.DeepSeekChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Implementation of news synthesis service using DeepSeek LLM.
 */
@Service
public final class NewsSynthesisServiceImpl implements NewsSynthesisService {

    private static final Logger log = LoggerFactory.getLogger(NewsSynthesisServiceImpl.class);
    private static final int MAX_CONTENT_LENGTH = 500;
    private static final String DIGEST_SYSTEM_PROMPT_EN = """
            You are a professional news analyst. Create a concise digest from the provided news messages.
            Format: Use bullet points, group related items, highlight key facts.
            Style: Professional, objective, factual.
            Length: 3-5 key points maximum.
            """;
    private static final String DIGEST_SYSTEM_PROMPT_RU = """
            Вы профессиональный аналитик новостей. Создайте краткий дайджест из предоставленных сообщений.
            Формат: Используйте маркированный список, группируйте связанные темы.
            Стиль: Профессиональный, объективный, фактологический.
            Длина: Максимум 3-5 ключевых пунктов.
            """;
    private static final String CLUSTER_SYSTEM_PROMPT = """
            Summarize the following related messages into one coherent paragraph.
            Focus on the key facts and remove redundancy.
            Keep the summary under 100 words.
            """;
    private static final long SYNTHESIS_CHAT_ID = 0L;
    private final MessageRepository messageRepository;
    private final DeepSeekApiClient deepSeekApiClient;

    /**
     * Constructs a new NewsSynthesisServiceImpl with required dependencies.
     *
     * @param messageRepository repository for accessing messages
     * @param deepSeekApiClient unified API client for LLM interactions
     */
    public NewsSynthesisServiceImpl(
            MessageRepository messageRepository,
            DeepSeekApiClient deepSeekApiClient) {
        this.messageRepository = messageRepository;
        this.deepSeekApiClient = deepSeekApiClient;
    }

    @Override
    public Mono<String> generateDigest(Duration window, int maxMessages, String targetLanguage) {
        Instant since = Instant.now().minus(window);
        log.info("Generating digest for messages since {}, max={}, lang={}", since, maxMessages, targetLanguage);
        return messageRepository.findPrimaryMessagesForDigest(since, SYNTHESIS_CHAT_ID, maxMessages)
                .collectList()
                .flatMap(messages -> {
                    if (messages.isEmpty()) {
                        log.info("No primary messages found for digest");
                        return Mono.just("No significant news in this period.");
                    }
                    List<String> contents = messages.stream()
                            .map(this::extractContent)
                            .filter(s -> s != null && !s.isBlank())
                            .collect(Collectors.toList());
                    return generateBulletPoints(contents, targetLanguage);
                });
    }

    @Override
    public Mono<String> summarizeCluster(String clusterId, String targetLanguage) {
        log.debug("Summarizing cluster {}", clusterId);
        return messageRepository.findByClusterId(clusterId)
                .collectList()
                .flatMap(messages -> {
                    if (messages.isEmpty()) {
                        return Mono.just("");
                    }
                    List<String> contents = messages.stream()
                            .map(this::extractContent)
                            .filter(s -> s != null && !s.isBlank())
                            .collect(Collectors.toList());
                    return callLlm(CLUSTER_SYSTEM_PROMPT, formatMessagesForLlm(contents));
                });
    }

    @Override
    public Mono<String> generateBulletPoints(List<String> contents, String targetLanguage) {
        if (contents == null || contents.isEmpty()) {
            return Mono.just("");
        }
        String systemPrompt = "ru".equalsIgnoreCase(targetLanguage) || "uk".equalsIgnoreCase(targetLanguage)
                ? DIGEST_SYSTEM_PROMPT_RU
                : DIGEST_SYSTEM_PROMPT_EN;
        String userContent = formatMessagesForLlm(contents);
        return callLlm(systemPrompt, userContent);
    }

    private Mono<String> callLlm(String systemPrompt, String userContent) {
        List<ApiMessage> messages = new ArrayList<>();
        messages.add(new ApiMessage("system", systemPrompt));
        messages.add(new ApiMessage("user", userContent));
        DeepSeekChatRequest request = new DeepSeekChatRequest(messages, null);
        return deepSeekApiClient.chat(request, SYNTHESIS_CHAT_ID)
                .doOnSuccess(result -> log.debug("LLM synthesis completed, length={}", result != null ? result.length() : 0))
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("LLM synthesis returned empty response");
                    return Mono.just("Synthesis unavailable");
                }))
                .onErrorResume(e -> {
                    log.error("LLM synthesis failed: {}", e.getMessage());
                    return Mono.just("Synthesis unavailable");
                });
    }

    private String formatMessagesForLlm(List<String> contents) {
        StringBuilder sb = new StringBuilder("News messages:\n\n");
        for (int i = 0; i < contents.size(); i++) {
            String content = contents.get(i);
            if (content.length() > MAX_CONTENT_LENGTH) {
                content = content.substring(0, MAX_CONTENT_LENGTH) + "...";
            }
            sb.append(i + 1).append(". ").append(content).append("\n\n");
        }
        return sb.toString();
    }

    private String extractContent(MessageEntity message) {
        if (message.getContent() != null && !message.getContent().isBlank()) {
            return message.getContent();
        }
        if (message.getCaption() != null && !message.getCaption().isBlank()) {
            return message.getCaption();
        }
        return null;
    }
}
