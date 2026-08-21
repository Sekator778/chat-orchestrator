package com.example.telegramuserbot.service;

import com.example.telegramuserbot.service.TelegramClientManager;
import com.example.telegramuserbot.service.startup.ChatDiscoveryService;
import com.example.telegramuserbot.telegram.TelegramClientFacade;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Lightweight heuristic language detector for channels.
 * Uses last accessible message or chat metadata to guess the dominant language.
 */
@Service
public class ChannelLanguageDetectionService {

    private static final Logger log = LoggerFactory.getLogger(ChannelLanguageDetectionService.class);

    public static final String DEFAULT_LANGUAGE = "ru";
    private static final Pattern CYRILLIC_PATTERN = Pattern.compile("\\p{IsCyrillic}");
    private static final Pattern LATIN_PATTERN = Pattern.compile("\\p{IsLatin}");
    private static final int SAMPLE_MESSAGE_LIMIT = 5;

    private final TelegramClientManager telegramClientManager;

    public ChannelLanguageDetectionService(TelegramClientManager telegramClientManager) {
        this.telegramClientManager = telegramClientManager;
    }

    public Mono<String> detectLanguage(ChatDiscoveryService.ChatInfo chatInfo) {
        return fetchSampleTexts(chatInfo)
                .map(this::detectFromSamples)
                .defaultIfEmpty(DEFAULT_LANGUAGE)
                .onErrorResume(error -> {
                    log.debug("Language detection fallback for chat {}: {}", chatInfo.chatId(), error.getMessage());
                    return Mono.just(detectFromSamples(java.util.List.of(chatInfo.title())));
                });
    }

    private Mono<java.util.List<String>> fetchSampleTexts(ChatDiscoveryService.ChatInfo chatInfo) {
        if (chatInfo == null) {
            return Mono.just(java.util.List.of());
        }

        TdApi.GetChatHistory request = new TdApi.GetChatHistory(
                chatInfo.chatId(),
                0,
                0,
                SAMPLE_MESSAGE_LIMIT,
                false
        );

        TelegramClientFacade client = telegramClientManager.getAnyClient();
        if (client == null) {
            return Mono.just(java.util.List.of());
        }
        return Mono.fromFuture(() -> client.send(request))
                .cast(TdApi.Messages.class)
                .map(messages -> {
                    java.util.List<String> samples = new java.util.ArrayList<>();
                    for (TdApi.Message message : messages.messages) {
                        String text = extractText(message);
                        if (text != null && !text.isBlank()) {
                            samples.add(text.strip());
                        }
                    }
                    if (samples.isEmpty() && chatInfo.title() != null && !chatInfo.title().isBlank()) {
                        samples.add(chatInfo.title().strip());
                    }
                    return samples;
                })
                .timeout(Duration.ofSeconds(3))
                .onErrorResume(error -> {
                    log.debug("Failed to fetch samples for chat {}: {}", chatInfo.chatId(), error.getMessage());
                    if (chatInfo.title() != null && !chatInfo.title().isBlank()) {
                        return Mono.just(java.util.List.of(chatInfo.title().strip()));
                    }
                    return Mono.just(java.util.List.of());
                });
    }

    private String extractText(TdApi.Message message) {
        if (message == null || message.content == null) {
            return null;
        }
        TdApi.MessageContent content = message.content;
        if (content instanceof TdApi.MessageText messageText && messageText.text != null) {
            return messageText.text.text;
        }
        return null;
    }

    private String detectFromSamples(java.util.List<String> samples) {
        if (samples == null || samples.isEmpty()) {
            return DEFAULT_LANGUAGE;
        }

        int cyrillicHits = 0;
        int latinHits = 0;

        for (String sample : samples) {
            if (sample == null || sample.isBlank()) {
                continue;
            }
            String normalized = sample.strip();
            if (CYRILLIC_PATTERN.matcher(normalized).find()) {
                cyrillicHits++;
            }
            if (LATIN_PATTERN.matcher(normalized).find()) {
                latinHits++;
            }
        }

        if (cyrillicHits == 0 && latinHits == 0) {
            return DEFAULT_LANGUAGE;
        }
        if (cyrillicHits >= latinHits) {
            return "ru";
        }
        return "en";
    }
}
