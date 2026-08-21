package com.example.telegramuserbot.service.humanization.helper;

import com.example.telegramuserbot.domain.User;
import com.example.telegramuserbot.domain.UserCommunicationProfile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

@Component
public class TextHumanizationHelper {

    private final Random random = new Random();
    private final UserProfileManager userProfileManager;

    private static final List<String> UKRAINIAN_FILLERS = List.of(
            "ну", "от", "типу", "взагалі", "насправді", "якось", "таке", "ну да",
            "слухай", "знаєш", "ось", "емм", "хм", "а", "та", "то", "це",
            "ну то", "от так", "типа того", "взагалі-то", "реально", "прям",
            "буквально", "щось", "короче", "в общем", "тобто"
    );

    // Casual speech patterns and contractions
    private static final Map<String, String> CASUAL_REPLACEMENTS = Map.of(
            "дуже добре", "дуже круто",
            "це правильно", "це топ",
            "не можу", "не можна",
            "що це", "шо це",
            "такий", "такий от",
            "зараз", "щас",
            "сейчас", "щас",
            "хорошо", "окей",
            "спасибо", "спс",
            "дякую", "дяк"
    );

    // Thinking indicators and hesitation markers
    private static final List<String> THINKING_INDICATORS = List.of(
            "Хм...", "Емм...", "Ну...", "А...", "Та...", "Гм...",
            "Дай подумати...", "Секунду...", "Хвилинку...", "Так..."
    );

    // Natural conversation starters and connectors
    private static final List<String> CONVERSATION_CONNECTORS = List.of(
            "До речі", "Кстаті", "А ще", "Також", "Ну і", "А взагалі",
            "А от", "Та й", "І взагалі", "Насправді", "Реально"
    );

    private static final List<String> FILLER_WORDS = List.of(
            "hmm", "well", "you know", "like", "actually", "basically",
            "honestly", "frankly", "I mean", "sort of", "kind of"
    );

    public TextHumanizationHelper(UserProfileManager userProfileManager) {
        this.userProfileManager = userProfileManager;
    }

    /**
     * Add natural imperfections to make response more human-like
     */
    public String addNaturalImperfections(String response, UserCommunicationProfile profile) {
        String imperfect = response;

        // Determine language and casualness level
        Optional<User> profileUser = (profile != null)
                ? userProfileManager.getUserByInternalIdBlocking(profile.getUserId())
                : Optional.empty();
        boolean isUkrainian = profileUser.map(user -> "uk".equals(user.getLanguagePreference())).orElse(false);
        int casualness = profile != null && profile.getFormalityLevel() != null ?
                5 - profile.getFormalityLevel() : 3; // Higher = more casual

        // Add filler words with variable probability based on casualness
        double fillerProbability = Math.min(0.4, casualness * 0.08);
        if (random.nextDouble() < fillerProbability) {
            List<String> fillers = isUkrainian ? UKRAINIAN_FILLERS : FILLER_WORDS;
            String filler = fillers.get(random.nextInt(fillers.size()));

            // Choose position: beginning, middle, or end
            int position = random.nextInt(3);
            switch (position) {
                case 0 -> imperfect = filler + ", " + imperfect;
                case 1 -> {
                    String[] words = imperfect.split("\\. ");
                    if (words.length > 1) {
                        int insertPoint = random.nextInt(words.length - 1) + 1;
                        words[insertPoint] = filler + ", " + words[insertPoint];
                        imperfect = String.join(". ", words);
                    } else {
                        imperfect = filler + ", " + imperfect;
                    }
                }
                case 2 -> imperfect = imperfect + ", " + filler;
            }
        }

        // Add thinking indicators at the beginning
        if (random.nextDouble() < 0.12) {
            String thinking = THINKING_INDICATORS.get(random.nextInt(THINKING_INDICATORS.size()));
            imperfect = thinking + " " + imperfect;
        }

        // Add conversation connectors occasionally
        if (random.nextDouble() < 0.08) {
            String connector = CONVERSATION_CONNECTORS.get(random.nextInt(CONVERSATION_CONNECTORS.size()));
            imperfect = connector + ", " + imperfect.toLowerCase();
        }

        // Apply casual replacements based on casualness level
        if (casualness >= 3) {
            for (Map.Entry<String, String> replacement : CASUAL_REPLACEMENTS.entrySet()) {
                if (random.nextDouble() < 0.3) {
                    imperfect = imperfect.replace(replacement.getKey(), replacement.getValue());
                }
            }
        }

        // Add uncertainty phrases for more natural hesitation
        if (random.nextDouble() < Math.min(0.25, casualness * 0.05)) {
            imperfect = addUncertaintyPhrases(imperfect);
        }

        // Make more conversational for casual users
        if (casualness >= 3) {
            imperfect = makeMoreConversational(imperfect, profile);
        }

        // Add natural typos occasionally (very rarely)
        if (random.nextDouble() < 0.03) {
            imperfect = addSubtleTypos(imperfect);
        }

        // Add repetitive elements for emphasis (casual speech pattern)
        if (random.nextDouble() < 0.06 && casualness >= 4) {
            imperfect = addRepetitiveElements(imperfect);
        }

        return imperfect;
    }

    /**
     * Helper methods for text processing
     */
    private String addUncertaintyPhrases(String response) {
        List<String> uncertaintyPhrases = List.of(
                "Мабуть, ", "Здається, ", "Можливо, ", "Я думаю, ", "Швидше за все, "
        );
        String phrase = uncertaintyPhrases.get(random.nextInt(uncertaintyPhrases.size()));
        return phrase + response.toLowerCase();
    }

    private String makeMoreConversational(String response, UserCommunicationProfile profile) {
        // Add conversational elements
        return response.replace(".", random.nextDouble() < 0.3 ? ")" : ".")
                .replace("Так", random.nextDouble() < 0.5 ? "Ага" : "Так");
    }

    /**
     * Add subtle typos to simulate human imperfection
     */
    private String addSubtleTypos(String response) {
        if (response.length() < 20) {
            return response; // Too short for typos
        }

        // Common Ukrainian typo patterns
        Map<String, String> typoPatterns = Map.of(
                "що", "шо", // Already common
                "щось", "шось",
                "чому", "чому", // Keep some correct
                "можливо", "можливо", // Keep correct more often
                "дійсно", "дійсно"
        );

        String result = response;
        for (Map.Entry<String, String> pattern : typoPatterns.entrySet()) {
            if (random.nextDouble() < 0.15) { // Very low probability
                result = result.replace(pattern.getKey(), pattern.getValue());
            }
        }

        return result;
    }

    /**
     * Add repetitive elements for emphasis (casual speech)
     */
    private String addRepetitiveElements(String response) {
        List<String> repetitivePatterns = List.of(
                response + ", так-так",
                response + ", ага",
                response + " точно-точно",
                response + ", так от",
                "Та " + response.toLowerCase(),
                response + ", реально"
        );

        return repetitivePatterns.get(random.nextInt(repetitivePatterns.size()));
    }
}
