package com.example.telegramuserbot.service.humanization.helper;

import com.example.telegramuserbot.domain.ResponseIntent;
import com.example.telegramuserbot.domain.ResponseVariation;
import com.example.telegramuserbot.repository.ResponseVariationRepository;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ResponseVariationManager {

    private final ResponseVariationRepository responseVariationRepository;
    private final Map<Long, List<String>> recentResponses = new ConcurrentHashMap<>();
    private final Random random = new Random();
    private static final Duration REPOSITORY_TIMEOUT = Duration.ofSeconds(3);

    public ResponseVariationManager(ResponseVariationRepository responseVariationRepository) {
        this.responseVariationRepository = responseVariationRepository;
    }

    public Mono<String> applyVariation(String originalResponse, ResponseIntent intent, Flux<ResponseVariation> variationsFlux) {
        // Если подходящие вариации не были переданы, ищем их в БД
        Flux<ResponseVariation> variationsToUse = (variationsFlux != null) ? variationsFlux : responseVariationRepository.findByIntentTypeAndEnabledTrue(intent);

        return variationsToUse
                .collectList()
                .flatMap(variations -> {
                    if (variations.isEmpty()) {
                        return Mono.just(originalResponse); // Нет вариаций, возвращаем исходный ответ
                    }

                    // Асинхронно получаем недавно использованные вариации
                    return responseVariationRepository.findRecentlyUsedVariations(intent, LocalDateTime.now().minusHours(1))
                            .collectList()
                            .flatMap(recentlyUsed -> {
                                variations.removeAll(recentlyUsed);

                                if (variations.isEmpty()) {
                                    return Mono.just(originalResponse); // Все подходящие вариации недавно использовались
                                }

                                ResponseVariation chosen = chooseWeightedRandom(variations);
                                chosen.recordUsage();

                                // Асинхронно сохраняем изменения и возвращаем результат
                                return responseVariationRepository.save(chosen)
                                        .map(savedVariation -> replaceTemplatePlaceholders(savedVariation.getTemplateText(), originalResponse));
                            });
                });
    }

    public String avoidRepetition(String response, Long userId) {
        if (userId == null) {
            return response;
        }
        List<String> recent = recentResponses.computeIfAbsent(userId, k -> new ArrayList<>());
        for (String recentResponse : recent) {
            if (calculateSimilarity(response, recentResponse) > 0.8) {
                return addVariationToResponse(response);
            }
        }
        return response;
    }

    public void trackResponse(Long userId, String response) {
        if (userId == null) {
            return;
        }
        List<String> recent = recentResponses.computeIfAbsent(userId, k -> new ArrayList<>());
        recent.add(response);
        if (recent.size() > 10) {
            recent.remove(0);
        }
    }

    public void resetUserResponseHistory(Long userId) {
        recentResponses.remove(userId);
    }

    private ResponseVariation chooseWeightedRandom(List<ResponseVariation> variations) {
        int totalWeight = variations.stream().mapToInt(ResponseVariation::getWeight).sum();
        int randomWeight = random.nextInt(totalWeight);

        int currentWeight = 0;
        for (ResponseVariation variation : variations) {
            currentWeight += variation.getWeight();
            if (randomWeight < currentWeight) {
                return variation;
            }
        }

        return variations.get(0); // Fallback
    }

    private String replaceTemplatePlaceholders(String template, String originalResponse) {
        return template.replace("{original}", originalResponse)
                .replace("{response}", originalResponse);
    }

    private double calculateSimilarity(String s1, String s2) {
        // Simple similarity calculation
        String[] words1 = s1.toLowerCase().split("\\s+");
        String[] words2 = s2.toLowerCase().split("\\s+");

        Set<String> set1 = new HashSet<>(java.util.Arrays.asList(words1));
        Set<String> set2 = new HashSet<>(java.util.Arrays.asList(words2));

        Set<String> intersection = new HashSet<>(set1);
        intersection.retainAll(set2);

        Set<String> union = new HashSet<>(set1);
        union.addAll(set2);

        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    private String addVariationToResponse(String response) {
        // Add small variations to avoid repetition
        List<String> variations = List.of(
                "До речі, " + response,
                "Насправді, " + response,
                response + " Так от.",
                "Ну, " + response,
                "А ще, " + response,
                "Кстаті, " + response,
                response + " Ну от так.",
                "Взагалі, " + response
        );
        return variations.get(random.nextInt(variations.size()));
    }
}