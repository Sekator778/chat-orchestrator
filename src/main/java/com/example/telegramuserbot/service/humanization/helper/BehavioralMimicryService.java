package com.example.telegramuserbot.service.humanization.helper;

import com.example.telegramuserbot.domain.UserCommunicationProfile;
import com.example.telegramuserbot.dto.MessageContextDto;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Random;

@Component
public class BehavioralMimicryService {

    private final Random random = new Random();

    public String apply(String response, UserCommunicationProfile profile, MessageContextDto context) {
        String adapted = response;

        if (profile.getConversationLengthPreference() != null) {
            switch (profile.getConversationLengthPreference()) {
                case "brief" -> adapted = shortenResponse(adapted);
                case "extended" -> adapted = expandResponse(adapted);
            }
        }
        if (profile.getFormalityLevel() != null) {
            adapted = adjustFormality(adapted, profile.getFormalityLevel());
        }
        if (profile.getEmotionalExpressiveness() != null) {
            adapted = adjustEmotionalExpressiveness(adapted, profile.getEmotionalExpressiveness());
        }
        if (profile.getPunctuationStyle() != null) {
            adapted = adjustPunctuation(adapted, profile.getPunctuationStyle());
        }
        if (profile.getEmoticonUsageFrequency() != null) {
            adapted = adjustEmojiUsage(adapted, profile.getEmoticonUsageFrequency());
        }
        if (Boolean.TRUE.equals(profile.getUsesSlang())) {
            adapted = addSlangElements(adapted);
        }
        if (profile.getResponseSpeedPreference() != null) {
            adapted = adjustResponseComplexity(adapted, profile.getResponseSpeedPreference());
        }
        if (context.getConversationDepth() > 5) {
            adapted = addConversationalIntimacy(adapted, profile);
        }
        return adapted;
    }

    private String shortenResponse(String response) {
        if (response.length() > 100) {
            int cutPoint = response.lastIndexOf('.', 100);
            if (cutPoint > 50) {
                return response.substring(0, cutPoint + 1);
            }
        }
        return response;
    }

    private String expandResponse(String response) {
        List<String> elaborations = List.of(
                " Детальніше можу розказати, якщо цікаво.",
                " Це досить цікава тема, насправді.",
                " Є ще кілька нюансів з цього приводу."
        );
        return response + elaborations.get(random.nextInt(elaborations.size()));
    }

    private String adjustFormality(String response, int formalityLevel) {
        if (formalityLevel <= 2) {
            return response.replace("Ви", "ти").replace("Ваш", "твій").replace("будь ласка", "плиз");
        } else if (formalityLevel >= 4) {
            return response.replace("ти", "Ви").replace("твій", "Ваш");
        }
        return response;
    }

    private String adjustPunctuation(String response, String style) {
        return switch (style) {
            case "minimal" -> response.replaceAll("[!]{2,}", "!")
                    .replaceAll("[?]{2,}", "?");
            case "excessive" -> response.replace(".", "...")
                    .replace("!", "!!!");
            default -> response;
        };
    }

    private String addSlangElements(String response) {
        Map<String, String> slangReplacements = Map.of(
                "дуже", "дуже круто",
                "хороший", "топовий",
                "погано", "не айс",
                "цікаво", "прикольно",
                "класно", "кльово",
                "добре", "окей",
                "правильно", "так і є",
                "звичайно", "звісно ж"
        );

        String result = response;
        for (Map.Entry<String, String> entry : slangReplacements.entrySet()) {
            if (random.nextDouble() < 0.3) {
                result = result.replace(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    /**
     * Adjust emotional expressiveness to match user's style
     */
    private String adjustEmotionalExpressiveness(String response, int expressiveness) {
        String result = response;

        switch (expressiveness) {
            case 1, 2 -> {
                // Reserved - remove excessive punctuation and emojis
                result = result.replaceAll("[!]{2,}", "!")
                        .replaceAll("[?]{2,}", "?")
                        .replaceAll("😀|😁|😄|😃|🤣|😊|😍|😘", "");
            }
            case 4, 5 -> {
                // Very expressive - add emphasis
                if (random.nextDouble() < 0.3) {
                    result = result.replace("!", "!!");
                }
                if (random.nextDouble() < 0.2) {
                    result = result + " 😊";
                }
                if (random.nextDouble() < 0.15) {
                    result = "Ой, " + result.toLowerCase();
                }
            }
        }

        return result;
    }

    /**
     * Adjust emoji usage to match user's frequency
     */
    private String adjustEmojiUsage(String response, double userFrequency) {
        // userFrequency is emojis per character
        double targetEmojis = response.length() * userFrequency;

        if (targetEmojis > 0.5 && random.nextDouble() < targetEmojis) {
            List<String> contextualEmojis = List.of(
                    "😊", "😄", "👍", "🤔", "😅", "🙂", "😉", "👌", "🤗", "😌"
            );

            String emoji = contextualEmojis.get(random.nextInt(contextualEmojis.size()));

            // Add emoji at the end or middle
            if (random.nextBoolean()) {
                response = response + " " + emoji;
            } else {
                String[] sentences = response.split("\\. ");
                if (sentences.length > 1) {
                    int insertPoint = random.nextInt(sentences.length);
                    sentences[insertPoint] = sentences[insertPoint] + " " + emoji;
                    response = String.join(". ", sentences);
                }
            }
        }

        return response;
    }

    /**
     * Adjust response complexity based on user's speed preference
     */
    private String adjustResponseComplexity(String response, String speedPreference) {
        return switch (speedPreference) {
            case "quick" -> {
                // Simplify response for quick repliers
                String[] sentences = response.split("\\. ");
                if (sentences.length > 2) {
                    yield sentences[0] + ". " + sentences[1] + ".";
                }
                yield response.length() > 100 ? response.substring(0, 100) + "..." : response;
            }
            case "thoughtful" -> {
                // Add more depth for thoughtful repliers
                if (random.nextDouble() < 0.3) {
                    yield response + " А що ти думаєш з цього приводу?";
                }
                yield response;
            }
            default -> response;
        };
    }

    /**
     * Add conversational intimacy for long conversations
     */
    private String addConversationalIntimacy(String response, UserCommunicationProfile profile) {
        if (random.nextDouble() < 0.2) {
            List<String> intimateMarkers = List.of(
                    "слухай, " + response.toLowerCase(),
                    "знаєш, " + response.toLowerCase(),
                    response + ", як думаєш?",
                    "між нами кажучи, " + response.toLowerCase(),
                    response + " Ти ж розумієш"
            );

            return intimateMarkers.get(random.nextInt(intimateMarkers.size()));
        }

        return response;
    }
}