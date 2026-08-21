package com.example.telegramuserbot.service.common;

/**
 * Service for detecting the language of text content.
 *
 * <p>Provides methods to identify whether text is in English, Ukrainian, Russian,
 * or other languages based on character patterns and common word detection.
 *
 * <p>Usage example:
 * <pre>{@code
 * @Autowired
 * private LanguageDetector languageDetector;
 *
 * String lang = languageDetector.detectLanguage("Привіт, як справи?");
 * // Returns "uk" for Ukrainian
 * }</pre>
 *
 * @author Development Team
 */
public interface LanguageDetector {

    /**
     * Default language code returned when detection is inconclusive.
     */
    String DEFAULT_LANGUAGE = "uk";

    /**
     * English language code.
     */
    String ENGLISH = "en";

    /**
     * Ukrainian language code.
     */
    String UKRAINIAN = "uk";

    /**
     * Russian language code.
     */
    String RUSSIAN = "ru";

    /**
     * Detects the language of the given text.
     *
     * <p>The detection algorithm:
     * <ol>
     *   <li>Returns default language if text is null or empty</li>
     *   <li>Checks for common English words first</li>
     *   <li>If Cyrillic characters found, distinguishes Ukrainian from Russian</li>
     *   <li>Defaults to Ukrainian for Cyrillic text without clear indicators</li>
     * </ol>
     *
     * @param text the text to analyze, may be null
     * @return language code: "en", "uk", or "ru"
     */
    String detectLanguage(String text);

    /**
     * Checks if the text contains common English words.
     *
     * @param text the text to check, should be lowercase
     * @return true if English patterns detected
     */
    boolean containsEnglishWords(String text);

    /**
     * Checks if the text contains Cyrillic characters.
     *
     * <p>Includes both Russian and Ukrainian Cyrillic alphabets.
     *
     * @param text the text to check
     * @return true if Cyrillic characters found
     */
    boolean containsCyrillic(String text);

    /**
     * Checks if the text contains Ukrainian-specific characters.
     *
     * <p>Ukrainian-specific characters: і, ї, є, ґ (and uppercase variants).
     *
     * @param text the text to check
     * @return true if Ukrainian-specific characters found
     */
    boolean containsUkrainianSpecificChars(String text);

    /**
     * Checks if the text contains common Ukrainian words.
     *
     * @param text the text to check, should be lowercase
     * @return true if Ukrainian word patterns detected
     */
    boolean containsUkrainianWords(String text);

    /**
     * Checks if the text contains common Russian words.
     *
     * @param text the text to check, should be lowercase
     * @return true if Russian word patterns detected
     */
    boolean containsRussianWords(String text);
}
