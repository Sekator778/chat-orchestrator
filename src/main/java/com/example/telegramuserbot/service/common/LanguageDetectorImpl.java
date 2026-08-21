package com.example.telegramuserbot.service.common;

import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

/**
 * Implementation of language detection service.
 *
 * <p>Detects English, Ukrainian, and Russian languages using pattern matching
 * and common word detection. This is a lightweight, heuristic-based approach
 * suitable for short text messages.
 *
 * <p>Thread-safe: all methods are stateless and can be called concurrently.
 *
 * @author Development Team
 */
@Service
public final class LanguageDetectorImpl implements LanguageDetector {

    private static final Pattern CYRILLIC_PATTERN = Pattern.compile("[а-яёА-ЯЁіїєґІЇЄҐ]");
    private static final Pattern UKRAINIAN_SPECIFIC_PATTERN = Pattern.compile("[іїєґІЇЄҐ]");

    private static final String[] ENGLISH_WORDS = {
            "the", "and", "you", "are", "how", "what", "where", "when", "why",
            "hello", "hi", "thanks", "thank", "please", "yes", "no", "good", "bad",
            "can", "could", "would", "should", "will", "have", "has", "had",
            "this", "that", "these", "those", "with", "from", "about", "which"
    };

    private static final String[] UKRAINIAN_WORDS = {
            "привіт", "дякую", "будь ласка", "так", "ні", "добре", "погано",
            "де", "коли", "що", "як", "чому", "хто", "який", "яка", "які",
            "це", "той", "та", "ті", "від", "про", "з", "до", "але", "і",
            "мене", "тебе", "його", "її", "нас", "вас", "їх", "ми", "ви", "вони"
    };

    private static final String[] RUSSIAN_WORDS = {
            "привет", "спасибо", "пожалуйста", "да", "нет", "хорошо", "плохо",
            "где", "когда", "что", "как", "почему", "кто", "который", "которая", "которые",
            "это", "тот", "та", "те", "от", "про", "с", "до", "но", "и",
            "меня", "тебя", "его", "её", "нас", "вас", "их", "мы", "вы", "они"
    };

    @Override
    public String detectLanguage(String text) {
        if (text == null || text.trim().isEmpty()) {
            return DEFAULT_LANGUAGE;
        }
        String normalized = text.toLowerCase().trim();
        if (containsEnglishWords(normalized)) {
            return ENGLISH;
        }
        if (containsCyrillic(normalized)) {
            if (containsUkrainianSpecificChars(normalized) || containsUkrainianWords(normalized)) {
                return UKRAINIAN;
            } else if (containsRussianWords(normalized)) {
                return RUSSIAN;
            } else {
                return UKRAINIAN;
            }
        }
        return DEFAULT_LANGUAGE;
    }

    @Override
    public boolean containsEnglishWords(String text) {
        if (text == null) {
            return false;
        }
        for (String word : ENGLISH_WORDS) {
            if (containsWord(text, word)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean containsCyrillic(String text) {
        if (text == null) {
            return false;
        }
        return CYRILLIC_PATTERN.matcher(text).find();
    }

    @Override
    public boolean containsUkrainianSpecificChars(String text) {
        if (text == null) {
            return false;
        }
        return UKRAINIAN_SPECIFIC_PATTERN.matcher(text).find();
    }

    @Override
    public boolean containsUkrainianWords(String text) {
        if (text == null) {
            return false;
        }
        for (String word : UKRAINIAN_WORDS) {
            if (containsWord(text, word)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean containsRussianWords(String text) {
        if (text == null) {
            return false;
        }
        for (String word : RUSSIAN_WORDS) {
            if (containsWord(text, word)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsWord(String text, String word) {
        int index = text.indexOf(word);
        if (index < 0) {
            return false;
        }
        boolean startBoundary = (index == 0) || !Character.isLetterOrDigit(text.charAt(index - 1));
        int endIndex = index + word.length();
        boolean endBoundary = (endIndex >= text.length()) || !Character.isLetterOrDigit(text.charAt(endIndex));
        return startBoundary && endBoundary;
    }
}
