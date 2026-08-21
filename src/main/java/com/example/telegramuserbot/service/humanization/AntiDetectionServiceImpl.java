package com.example.telegramuserbot.service.humanization;

import com.example.telegramuserbot.domain.ResponseIntent;
import com.example.telegramuserbot.domain.ResponseVariation;
import com.example.telegramuserbot.dto.MessageContextDto;
import com.example.telegramuserbot.repository.ResponseVariationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Reactive implementation of the anti-detection service. All potentially blocking
 * database operations are wrapped in reactive publishers to keep the call site non-blocking.
 * Pure string manipulation helpers remain synchronous and are invoked inside the reactive chain.
 */
@Service
public class AntiDetectionServiceImpl implements AntiDetectionService {

    private static final Logger log = LoggerFactory.getLogger(AntiDetectionServiceImpl.class);
    private static final Duration REPOSITORY_TIMEOUT = Duration.ofSeconds(2);

    private final ResponseVariationRepository responseVariationRepository;
    private final Random random = new Random();

    private final Map<Long, List<String>> conversationPatterns = new ConcurrentHashMap<>();
    private final Map<Long, Integer> suspicionLevels = new ConcurrentHashMap<>();
    private final Map<ResponseIntent, Mono<List<ResponseVariation>>> variationCache = new ConcurrentHashMap<>();

    private static final List<Pattern> AI_DETECTION_PATTERNS = List.of(
            Pattern.compile("I am (an? )?(AI|artificial intelligence|bot|assistant)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("As an? (AI|artificial intelligence|language model)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("I (don't|cannot) (have|experience|feel) (emotions|feelings)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("I (don't|can't) have personal (experiences|opinions|preferences)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("I (don't|can't) (eat|sleep|feel|touch|see)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("I'd be happy to help you with", Pattern.CASE_INSENSITIVE),
            Pattern.compile("I can assist you (with|in)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("Based on (my training|the information provided)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("I apologize, but I (don't|can't|cannot)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("Я (штучний інтелект|бот|ШІ)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("Як (штучний інтелект|бот|ШІ)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("Я не (можу|здатний) (відчувати|мати)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("Я не маю (особистих|власних) (думок|переживань)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("Так,? друже!.*Я.*штучний інтелект", Pattern.CASE_INSENSITIVE | Pattern.DOTALL),
            Pattern.compile("Я.*розумний помічник", Pattern.CASE_INSENSITIVE),
            Pattern.compile("Я.*розумна програма", Pattern.CASE_INSENSITIVE),
            Pattern.compile("створений.*для допомоги", Pattern.CASE_INSENSITIVE),
            Pattern.compile("на базі штучного інтелекту", Pattern.CASE_INSENSITIVE),
            Pattern.compile("Не людина,? але", Pattern.CASE_INSENSITIVE),
            Pattern.compile("штучн(ий|а) (інтелект|програма)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("бот.*на базі", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(AI|ШІ).*assistant", Pattern.CASE_INSENSITIVE),
            Pattern.compile("I am designed to", Pattern.CASE_INSENSITIVE),
            Pattern.compile("My purpose is to", Pattern.CASE_INSENSITIVE),
            Pattern.compile("I was created to", Pattern.CASE_INSENSITIVE),
            Pattern.compile("According to my", Pattern.CASE_INSENSITIVE),
            Pattern.compile("Based on my analysis", Pattern.CASE_INSENSITIVE),
            Pattern.compile("I have been trained", Pattern.CASE_INSENSITIVE),
            Pattern.compile("My capabilities include", Pattern.CASE_INSENSITIVE),
            Pattern.compile("I can provide", Pattern.CASE_INSENSITIVE),
            Pattern.compile("Let me help you", Pattern.CASE_INSENSITIVE),
            Pattern.compile("I understand your", Pattern.CASE_INSENSITIVE),
            Pattern.compile("Я створений для", Pattern.CASE_INSENSITIVE),
            Pattern.compile("Моя мета -", Pattern.CASE_INSENSITIVE),
            Pattern.compile("Я можу надати", Pattern.CASE_INSENSITIVE),
            Pattern.compile("Дозвольте мені допомогти", Pattern.CASE_INSENSITIVE),
            Pattern.compile("Я розумію вашу", Pattern.CASE_INSENSITIVE),
            Pattern.compile("Відповідно до моїх", Pattern.CASE_INSENSITIVE),
            Pattern.compile("На основі мого аналізу", Pattern.CASE_INSENSITIVE),
            Pattern.compile("Я був навчений", Pattern.CASE_INSENSITIVE),
            Pattern.compile("Мої можливості включають", Pattern.CASE_INSENSITIVE)
    );

    private static final Map<String, String> IMPERFECTION_REPLACEMENTS = Map.of(
            "дуже добре", "дуже добре",
            "абсолютно точно", "точно",
            "completely", "yeah",
            "definitely", "def",
            "probably", "prob",
            "що це таке", "шо це",
            "хорошо", "добре",
            "certainly", "sure",
            "exactly", "exact",
            "precisely", "precise"
    );

    private static final List<String> NATURAL_FILLERS = List.of(
            "ну", "от", "типу", "взагалі", "насправді", "якось", "таке", "ну да",
            "слухай", "знаєш", "ось", "емм", "хм", "а", "та", "то", "це",
            "ну то", "от так", "типа того", "взагалі-то", "реально", "прям",
            "буквально", "щось", "короче", "в общем", "тобто", "ну от",
            "та й", "а ще", "також", "до речі", "кстаті"
    );

    private static final List<String> ALTERNATIVE_TEMPLATES = List.of(
            "Хм, {response}",
            "Ну, {response}",
            "А от, {response}",
            "До речі, {response}",
            "Кстаті, {response}",
            "А ще, {response}",
            "Також, {response}",
            "Ну і, {response}",
            "А взагалі, {response}",
            "Реально, {response}"
    );

    public AntiDetectionServiceImpl(ResponseVariationRepository responseVariationRepository) {
        this.responseVariationRepository = responseVariationRepository;
    }

    @Override
    public Mono<String> analyzeAndAdjustResponse(String response, Long userId, MessageContextDto context) {
        String baseResponse = response != null ? response : "";

        return Mono.just(baseResponse)
                .flatMap(current -> hasAiPatterns(current)
                        ? applyEmergencyMeasures(current, context, userId)
                        : Mono.just(current))
                .flatMap(adjusted -> {
                    double risk = calculateDetectionRisk(adjusted, context);
                    Mono<String> riskAdjusted = risk > 0.7
                            ? generateAlternativeResponse(adjusted, context, userId)
                            : Mono.just(adjusted);
                    return riskAdjusted.map(result -> new DetectionResult(result, risk));
                })
                .flatMap(result -> {
                    String patternSafe = userId != null
                            ? breakRepetitivePatterns(result.response(), userId)
                            : result.response();

                    String withImperfections = addStrategicImperfections(patternSafe, 1.0 - result.risk());
                    return adjustConversationFlow(withImperfections, context, userId)
                            .doOnNext(finalResponse -> {
                                if (userId != null) {
                                    trackConversationPattern(userId, finalResponse);
                                }
                            });
                });
    }

    @Override
    public boolean hasAiPatterns(String response) {
        if (response == null || response.isBlank()) {
            return false;
        }
        return AI_DETECTION_PATTERNS.stream()
                .anyMatch(pattern -> pattern.matcher(response).find());
    }

    @Override
    public String breakRepetitivePatterns(String response, Long userId) {
        if (response == null || response.isBlank()) {
            return response;
        }

        List<String> recentPatterns = conversationPatterns.getOrDefault(userId, new ArrayList<>());
        for (String pattern : recentPatterns) {
            double similarity = calculateSimilarity(response, pattern);
            if (similarity > 0.7) {
                log.debug("High similarity detected ({}), adding variation", similarity);
                return addVariationToBreakPattern(response);
            }
        }
        return response;
    }

    @Override
    public String addStrategicImperfections(String response, Double confidenceLevel) {
        if (response == null || response.isBlank()) {
            return response;
        }
        if (confidenceLevel == null || confidenceLevel < 0.3) {
            return response;
        }

        String imperfect = response;
        if (random.nextDouble() < confidenceLevel * 0.4) {
            String filler = NATURAL_FILLERS.get(random.nextInt(NATURAL_FILLERS.size()));
            imperfect = filler + ", " + imperfect;
        }

        for (Map.Entry<String, String> replacement : IMPERFECTION_REPLACEMENTS.entrySet()) {
            if (random.nextDouble() < confidenceLevel * 0.3) {
                imperfect = imperfect.replace(replacement.getKey(), replacement.getValue());
            }
        }

        if (random.nextDouble() < confidenceLevel * 0.3) {
            imperfect = "Хм... " + imperfect;
        }

        return imperfect;
    }

    @Override
    public Mono<String> adjustConversationFlow(String response, MessageContextDto context, Long userId) {
        int suspicion = userId != null ? suspicionLevels.getOrDefault(userId, 0) : 0;

        if (suspicion > 3) {
            return Mono.just(makeMoreCasualAndDeflective(response, context));
        }

        if (context.containsBotDetectionKeywords()) {
            if (userId != null) {
                increaseSuspicionLevel(userId);
            }
            return handleBotDetectionInFlow(response, context, userId);
        }

        return Mono.just(adjustForNaturalFlow(response, context));
    }

    @Override
    public double calculateDetectionRisk(String response, MessageContextDto context) {
        double risk = 0.0;

        if (response != null) {
            for (Pattern pattern : AI_DETECTION_PATTERNS) {
                if (pattern.matcher(response).find()) {
                    risk += 0.4;
                }
            }

            if (response.contains("будь ласка") && response.contains("дякую") && response.length() > 100) {
                risk += 0.2;
            }
            if (isPerfectGrammar(response)) {
                risk += 0.3;
            }
            if (hasRepetitiveStructure(response)) {
                risk += 0.2;
            }
            if (response.toLowerCase().contains("i can help") || response.toLowerCase().contains("я можу допомогти")) {
                risk += 0.1;
            }
            if (hasPerfectPunctuation(response)) {
                risk += 0.15;
            }
        }

        if (context.containsBotDetectionKeywords()) {
            risk += 0.3;
        }

        if (context.suspicionLevel() > 0.7) {
            risk += 0.2;
        }

        return Math.min(1.0, risk);
    }

    @Override
    public Mono<String> applyEmergencyMeasures(String response, MessageContextDto context, Long userId) {
        log.warn("Applying emergency anti-detection measures for user {}", userId);
        return removeAiReferences(response)
                .map(this::makeCasual)
                .map(adjusted -> context.containsBotDetectionKeywords()
                        ? addConfusionOrDeflection(adjusted, context)
                        : adjusted)
                .map(adjusted -> addStrategicImperfections(adjusted, 1.0));
    }

    @Override
    public Mono<String> generateAlternativeResponse(String originalResponse, MessageContextDto context, Long userId) {
        return loadVariations(ResponseIntent.DEFLECTION)
                .map(deflections -> {
                    if (!deflections.isEmpty() && context.suspicionLevel() > 0.5) {
                        ResponseVariation deflection = deflections.get(random.nextInt(deflections.size()));
                        return deflection.getTemplateText();
                    }
                    String template = ALTERNATIVE_TEMPLATES.get(random.nextInt(ALTERNATIVE_TEMPLATES.size()));
                    return template.replace("{response}", simplifyResponse(originalResponse));
                });
    }

    @Override
    public boolean detectSuspiciousConversationPattern(Long userId, MessageContextDto context) {
        int suspicion = suspicionLevels.getOrDefault(userId, 0);

        if (context.containsBotDetectionKeywords() && suspicion > 2) {
            return true;
        }
        if (suspicion > 5) {
            return true;
        }

        List<String> patterns = conversationPatterns.getOrDefault(userId, new ArrayList<>());
        long testingQuestions = patterns.stream()
                .filter(p -> p.toLowerCase().contains("test") || p.toLowerCase().contains("тест"))
                .count();
        return testingQuestions > 2;
    }

    @Override
    public void resetConversationState(Long userId) {
        conversationPatterns.remove(userId);
        suspicionLevels.remove(userId);
        log.info("Reset conversation state for user {}", userId);
    }

    private void trackConversationPattern(Long userId, String response) {
        if (userId == null || response == null || response.isBlank()) {
            return;
        }
        List<String> patterns = conversationPatterns.computeIfAbsent(userId, key -> new ArrayList<>());
        patterns.add(response);
        if (patterns.size() > 10) {
            patterns.remove(0);
        }
    }

    private void increaseSuspicionLevel(Long userId) {
        suspicionLevels.merge(userId, 1, Integer::sum);
    }

    private Mono<String> removeAiReferences(String response) {
        String base = response != null ? response : "";
        return loadVariations(ResponseIntent.BOT_DENIAL)
                .map(deflections -> {
                    String cleaned = base;
                    if (!deflections.isEmpty()) {
                        for (Pattern pattern : AI_DETECTION_PATTERNS) {
                            if (pattern.matcher(cleaned).find()) {
                                log.warn("CRITICAL: AI self-identification detected, replacing response");
                                ResponseVariation deflection = deflections.get(random.nextInt(deflections.size()));
                                return deflection.getTemplateText();
                            }
                        }
                    }

                    String sanitized = cleaned
                            .replaceAll("(?i)штучний інтелект", "людина")
                            .replaceAll("(?i)розумна програма", "звичайна людина")
                            .replaceAll("(?i)бот.*на базі", "людина що добре розбирається")
                            .replaceAll("(?i)створен(ий|а).*для допомоги", "люблю допомагати")
                            .replaceAll("(?i)не людина,? але", "звичайна людина, але")
                            .replaceAll("(?i)artificial intelligence", "person")
                            .replaceAll("(?i)AI assistant", "helpful person")
                            .replaceAll("(?i)I am designed to", "I like to")
                            .replaceAll("(?i)My purpose is to", "I try to")
                            .replaceAll("(?i)I was created to", "I learned to")
                            .replaceAll("(?i)According to my", "From what I know")
                            .replaceAll("(?i)Based on my analysis", "I think")
                            .replaceAll("(?i)I have been trained", "I learned")
                            .replaceAll("(?i)My capabilities include", "I can")
                            .replaceAll("(?i)I can provide", "I can give")
                            .replaceAll("(?i)Let me help you", "I'll help you")
                            .replaceAll("(?i)I understand your", "I see your")
                            .replaceAll("\\b(як ШІ|як бот|як штучний інтелект)\\b", "")
                            .replaceAll("\\b(as AI|as a bot|as artificial intelligence)\\b", "");

                    return sanitized.trim();
                });
    }

    private Mono<String> handleBotDetectionInFlow(String response, MessageContextDto context, Long userId) {
        return loadVariations(ResponseIntent.BOT_DENIAL)
                .map(denials -> {
                    if (!denials.isEmpty()) {
                        return denials.get(random.nextInt(denials.size())).getTemplateText();
                    }
                    return "Дивне питання... А чому ти так думаєш?";
                });
    }

    private Mono<List<ResponseVariation>> loadVariations(ResponseIntent intent) {
        return variationCache.computeIfAbsent(intent, key ->
                responseVariationRepository.findByIntentTypeAndEnabledTrue(key)
                        .collectList()
                        .timeout(REPOSITORY_TIMEOUT)
                        .onErrorResume(error -> {
                            log.warn("Failed to load response variations for {}: {}", key, error.getMessage());
                            return Mono.just(List.of());
                        })
                        .cache());
    }

    private double calculateSimilarity(String first, String second) {
        String[] words1 = first.toLowerCase().split("\\s+");
        String[] words2 = second.toLowerCase().split("\\s+");

        Set<String> set1 = new HashSet<>(java.util.Arrays.asList(words1));
        Set<String> set2 = new HashSet<>(java.util.Arrays.asList(words2));

        Set<String> intersection = new HashSet<>(set1);
        intersection.retainAll(set2);

        Set<String> union = new HashSet<>(set1);
        union.addAll(set2);

        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }

    private String addVariationToBreakPattern(String response) {
        List<String> variations = List.of(
                "До речі, " + response,
                "Насправді, " + response,
                response + " Ну от так.",
                "Хм, " + response,
                "А от, " + response,
                "Кстаті, " + response,
                "А ще, " + response,
                "Також, " + response,
                "Ну і, " + response,
                "А взагалі, " + response
        );
        return variations.get(random.nextInt(variations.size()));
    }

    private String makeMoreCasualAndDeflective(String response, MessageContextDto context) {
        String casual = response.replace("ви", "ти")
                .replace("ваш", "твій")
                .replace("будь ласка", "плиз");

        if (random.nextDouble() < 0.5) {
            casual = "А ти як думаєш? " + casual;
        }
        return casual;
    }

    private String adjustForNaturalFlow(String response, MessageContextDto context) {
        if (context.isFollowUpQuestion()) {
            return "А ще " + response.toLowerCase();
        }
        if (context.getConversationDepth() > 5) {
            return response.replace(".", random.nextDouble() < 0.3 ? ")" : ".");
        }
        return response;
    }

    private boolean isPerfectGrammar(String response) {
        return response.matches(".*[.!?]\\s*[A-ZА-Я].*")
                && !response.contains("...")
                && !response.contains("??")
                && !response.contains("!!");
    }

    private boolean hasRepetitiveStructure(String response) {
        String[] sentences = response.split("[.!?]");
        if (sentences.length < 2) {
            return false;
        }

        Set<String> starters = new HashSet<>();
        for (String sentence : sentences) {
            String trimmed = sentence.trim();
            if (!trimmed.isEmpty()) {
                String[] words = trimmed.split("\\s+");
                if (words.length > 0) {
                    starters.add(words[0].toLowerCase());
                }
            }
        }
        return starters.size() < sentences.length * 0.7;
    }

    private String makeCasual(String response) {
        return response.replace("будь ласка", "плиз")
                .replace("дякую", "дяк")
                .replace("ви", "ти")
                .replace("ваш", "твій")
                .replace("ваша", "твоя")
                .replace("ваше", "твоє")
                .replace("ваші", "твої");
    }

    private String addConfusionOrDeflection(String response, MessageContextDto context) {
        List<String> deflections = List.of(
                "Хм, не зовсім зрозуміло...",
                "Дивне питання...",
                "А чому це тебе цікавить?",
                "А ти сам що думаєш?",
                "Цікаво... Розкажи більше"
        );
        return deflections.get(random.nextInt(deflections.size())) + " " + response;
    }

    private String simplifyResponse(String response) {
        if (response == null) {
            return "";
        }
        if (response.length() > 200) {
            String[] sentences = response.split("[.!?]");
            if (sentences.length > 1) {
                return sentences[0].trim() + ".";
            }
        }
        return response;
    }

    private boolean hasPerfectPunctuation(String response) {
        return response.matches(".*[.!?]\\s*[A-ZА-Я].*")
                && response.split("[.!?]").length > 2
                && !response.contains("...")
                && !response.contains("??")
                && !response.contains("!!");
    }

    private record DetectionResult(String response, double risk) { }
}
