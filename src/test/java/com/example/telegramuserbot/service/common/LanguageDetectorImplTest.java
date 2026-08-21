package com.example.telegramuserbot.service.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LanguageDetectorImpl")
class LanguageDetectorImplTest {

    private LanguageDetector detector;

    @BeforeEach
    void setUp() {
        detector = new LanguageDetectorImpl();
    }

    @Nested
    @DisplayName("detectLanguage()")
    class DetectLanguage {

        @Test
        @DisplayName("returns default language for null input")
        void returnsDefaultForNull() {
            assertThat(detector.detectLanguage(null)).isEqualTo(LanguageDetector.DEFAULT_LANGUAGE);
        }

        @Test
        @DisplayName("returns default language for empty string")
        void returnsDefaultForEmptyString() {
            assertThat(detector.detectLanguage("")).isEqualTo(LanguageDetector.DEFAULT_LANGUAGE);
        }

        @Test
        @DisplayName("returns default language for whitespace only")
        void returnsDefaultForWhitespace() {
            assertThat(detector.detectLanguage("   \t\n  ")).isEqualTo(LanguageDetector.DEFAULT_LANGUAGE);
        }

        @Test
        @DisplayName("detects English from common words")
        void detectsEnglishFromCommonWords() {
            assertThat(detector.detectLanguage("Hello, how are you?")).isEqualTo(LanguageDetector.ENGLISH);
            assertThat(detector.detectLanguage("Thank you very much")).isEqualTo(LanguageDetector.ENGLISH);
            assertThat(detector.detectLanguage("What is the meaning of this?")).isEqualTo(LanguageDetector.ENGLISH);
        }

        @Test
        @DisplayName("detects Ukrainian from specific characters")
        void detectsUkrainianFromSpecificChars() {
            assertThat(detector.detectLanguage("Як справи?")).isEqualTo(LanguageDetector.UKRAINIAN);
            assertThat(detector.detectLanguage("Привіт!")).isEqualTo(LanguageDetector.UKRAINIAN);
            assertThat(detector.detectLanguage("їжак")).isEqualTo(LanguageDetector.UKRAINIAN);
            assertThat(detector.detectLanguage("єдність")).isEqualTo(LanguageDetector.UKRAINIAN);
        }

        @Test
        @DisplayName("detects Ukrainian from common words")
        void detectsUkrainianFromCommonWords() {
            assertThat(detector.detectLanguage("дякую за допомогу")).isEqualTo(LanguageDetector.UKRAINIAN);
            assertThat(detector.detectLanguage("будь ласка допоможи")).isEqualTo(LanguageDetector.UKRAINIAN);
        }

        @Test
        @DisplayName("detects Russian from common words")
        void detectsRussianFromCommonWords() {
            assertThat(detector.detectLanguage("спасибо большое")).isEqualTo(LanguageDetector.RUSSIAN);
            assertThat(detector.detectLanguage("пожалуйста помоги")).isEqualTo(LanguageDetector.RUSSIAN);
            assertThat(detector.detectLanguage("привет как дела")).isEqualTo(LanguageDetector.RUSSIAN);
        }

        @Test
        @DisplayName("defaults to Ukrainian for ambiguous Cyrillic text")
        void defaultsToUkrainianForAmbiguousCyrillic() {
            assertThat(detector.detectLanguage("абвгд")).isEqualTo(LanguageDetector.UKRAINIAN);
        }

        @Test
        @DisplayName("prioritizes English detection over Cyrillic")
        void prioritizesEnglishOverCyrillic() {
            assertThat(detector.detectLanguage("Hello мир")).isEqualTo(LanguageDetector.ENGLISH);
        }

        @Test
        @DisplayName("handles mixed case input")
        void handlesMixedCaseInput() {
            assertThat(detector.detectLanguage("HELLO WORLD")).isEqualTo(LanguageDetector.ENGLISH);
            assertThat(detector.detectLanguage("ПРИВІТ СВІТЕ")).isEqualTo(LanguageDetector.UKRAINIAN);
        }

        @Test
        @DisplayName("handles numbers and special characters")
        void handlesNumbersAndSpecialChars() {
            assertThat(detector.detectLanguage("123 !@# hello")).isEqualTo(LanguageDetector.ENGLISH);
            assertThat(detector.detectLanguage("100% дякую!")).isEqualTo(LanguageDetector.UKRAINIAN);
        }
    }

    @Nested
    @DisplayName("containsEnglishWords()")
    class ContainsEnglishWords {

        @Test
        @DisplayName("returns false for null input")
        void returnsFalseForNull() {
            assertThat(detector.containsEnglishWords(null)).isFalse();
        }

        @Test
        @DisplayName("returns false for empty string")
        void returnsFalseForEmptyString() {
            assertThat(detector.containsEnglishWords("")).isFalse();
        }

        @Test
        @DisplayName("detects common English words")
        void detectsCommonEnglishWords() {
            assertThat(detector.containsEnglishWords("the quick brown fox")).isTrue();
            assertThat(detector.containsEnglishWords("hello there")).isTrue();
            assertThat(detector.containsEnglishWords("thank you")).isTrue();
            assertThat(detector.containsEnglishWords("how are you")).isTrue();
        }

        @Test
        @DisplayName("returns false for non-English text")
        void returnsFalseForNonEnglish() {
            assertThat(detector.containsEnglishWords("привіт")).isFalse();
            assertThat(detector.containsEnglishWords("боnjour")).isFalse();
        }

        @Test
        @DisplayName("handles word boundaries correctly")
        void handlesWordBoundaries() {
            assertThat(detector.containsEnglishWords("theme")).isFalse();
            assertThat(detector.containsEnglishWords("another")).isFalse();
            assertThat(detector.containsEnglishWords("the fox")).isTrue();
        }
    }

    @Nested
    @DisplayName("containsCyrillic()")
    class ContainsCyrillic {

        @Test
        @DisplayName("returns false for null input")
        void returnsFalseForNull() {
            assertThat(detector.containsCyrillic(null)).isFalse();
        }

        @Test
        @DisplayName("returns false for empty string")
        void returnsFalseForEmptyString() {
            assertThat(detector.containsCyrillic("")).isFalse();
        }

        @Test
        @DisplayName("detects Russian Cyrillic characters")
        void detectsRussianCyrillic() {
            assertThat(detector.containsCyrillic("привет")).isTrue();
            assertThat(detector.containsCyrillic("абв")).isTrue();
            assertThat(detector.containsCyrillic("ёжик")).isTrue();
        }

        @Test
        @DisplayName("detects Ukrainian Cyrillic characters")
        void detectsUkrainianCyrillic() {
            assertThat(detector.containsCyrillic("привіт")).isTrue();
            assertThat(detector.containsCyrillic("їжак")).isTrue();
            assertThat(detector.containsCyrillic("ґудзик")).isTrue();
        }

        @Test
        @DisplayName("returns false for Latin characters only")
        void returnsFalseForLatinOnly() {
            assertThat(detector.containsCyrillic("hello world")).isFalse();
            assertThat(detector.containsCyrillic("ABC123")).isFalse();
        }

        @Test
        @DisplayName("detects mixed text with Cyrillic")
        void detectsMixedTextWithCyrillic() {
            assertThat(detector.containsCyrillic("hello мир")).isTrue();
            assertThat(detector.containsCyrillic("123 привет 456")).isTrue();
        }
    }

    @Nested
    @DisplayName("containsUkrainianSpecificChars()")
    class ContainsUkrainianSpecificChars {

        @Test
        @DisplayName("returns false for null input")
        void returnsFalseForNull() {
            assertThat(detector.containsUkrainianSpecificChars(null)).isFalse();
        }

        @Test
        @DisplayName("returns false for empty string")
        void returnsFalseForEmptyString() {
            assertThat(detector.containsUkrainianSpecificChars("")).isFalse();
        }

        @Test
        @DisplayName("detects Ukrainian specific lowercase characters")
        void detectsLowercaseUkrainianChars() {
            assertThat(detector.containsUkrainianSpecificChars("і")).isTrue();
            assertThat(detector.containsUkrainianSpecificChars("ї")).isTrue();
            assertThat(detector.containsUkrainianSpecificChars("є")).isTrue();
            assertThat(detector.containsUkrainianSpecificChars("ґ")).isTrue();
        }

        @Test
        @DisplayName("detects Ukrainian specific uppercase characters")
        void detectsUppercaseUkrainianChars() {
            assertThat(detector.containsUkrainianSpecificChars("І")).isTrue();
            assertThat(detector.containsUkrainianSpecificChars("Ї")).isTrue();
            assertThat(detector.containsUkrainianSpecificChars("Є")).isTrue();
            assertThat(detector.containsUkrainianSpecificChars("Ґ")).isTrue();
        }

        @Test
        @DisplayName("returns false for Russian-only Cyrillic")
        void returnsFalseForRussianCyrillic() {
            assertThat(detector.containsUkrainianSpecificChars("привет")).isFalse();
            assertThat(detector.containsUkrainianSpecificChars("абвгдеж")).isFalse();
        }

        @Test
        @DisplayName("detects in mixed text")
        void detectsInMixedText() {
            assertThat(detector.containsUkrainianSpecificChars("hello привіт")).isTrue();
            assertThat(detector.containsUkrainianSpecificChars("єдність")).isTrue();
        }
    }

    @Nested
    @DisplayName("containsUkrainianWords()")
    class ContainsUkrainianWords {

        @Test
        @DisplayName("returns false for null input")
        void returnsFalseForNull() {
            assertThat(detector.containsUkrainianWords(null)).isFalse();
        }

        @Test
        @DisplayName("returns false for empty string")
        void returnsFalseForEmptyString() {
            assertThat(detector.containsUkrainianWords("")).isFalse();
        }

        @Test
        @DisplayName("detects common Ukrainian greetings")
        void detectsUkrainianGreetings() {
            assertThat(detector.containsUkrainianWords("привіт друже")).isTrue();
        }

        @Test
        @DisplayName("detects Ukrainian courtesy words")
        void detectsUkrainianCourtesyWords() {
            assertThat(detector.containsUkrainianWords("дякую вам")).isTrue();
            assertThat(detector.containsUkrainianWords("будь ласка допоможи")).isTrue();
        }

        @Test
        @DisplayName("detects Ukrainian question words")
        void detectsUkrainianQuestionWords() {
            assertThat(detector.containsUkrainianWords("де знаходиться")).isTrue();
            assertThat(detector.containsUkrainianWords("коли буде")).isTrue();
            assertThat(detector.containsUkrainianWords("хто там")).isTrue();
        }

        @Test
        @DisplayName("returns false for Russian words")
        void returnsFalseForRussianWords() {
            assertThat(detector.containsUkrainianWords("спасибо")).isFalse();
            assertThat(detector.containsUkrainianWords("пожалуйста")).isFalse();
        }
    }

    @Nested
    @DisplayName("containsRussianWords()")
    class ContainsRussianWords {

        @Test
        @DisplayName("returns false for null input")
        void returnsFalseForNull() {
            assertThat(detector.containsRussianWords(null)).isFalse();
        }

        @Test
        @DisplayName("returns false for empty string")
        void returnsFalseForEmptyString() {
            assertThat(detector.containsRussianWords("")).isFalse();
        }

        @Test
        @DisplayName("detects common Russian greetings")
        void detectsRussianGreetings() {
            assertThat(detector.containsRussianWords("привет друг")).isTrue();
        }

        @Test
        @DisplayName("detects Russian courtesy words")
        void detectsRussianCourtesyWords() {
            assertThat(detector.containsRussianWords("спасибо вам")).isTrue();
            assertThat(detector.containsRussianWords("пожалуйста помоги")).isTrue();
        }

        @Test
        @DisplayName("detects Russian question words")
        void detectsRussianQuestionWords() {
            assertThat(detector.containsRussianWords("где находится")).isTrue();
            assertThat(detector.containsRussianWords("когда будет")).isTrue();
            assertThat(detector.containsRussianWords("кто там")).isTrue();
        }

        @Test
        @DisplayName("returns false for Ukrainian words")
        void returnsFalseForUkrainianWords() {
            assertThat(detector.containsRussianWords("дякую")).isFalse();
            assertThat(detector.containsRussianWords("будь ласка")).isFalse();
        }
    }

    @Nested
    @DisplayName("Edge cases and real-world scenarios")
    class EdgeCases {

        @Test
        @DisplayName("handles emoji in text")
        void handlesEmojiInText() {
            assertThat(detector.detectLanguage("hello 👋")).isEqualTo(LanguageDetector.ENGLISH);
            assertThat(detector.detectLanguage("привіт 👋")).isEqualTo(LanguageDetector.UKRAINIAN);
        }

        @Test
        @DisplayName("handles URLs in text")
        void handlesUrlsInText() {
            assertThat(detector.detectLanguage("check this: https://example.com please")).isEqualTo(LanguageDetector.ENGLISH);
        }

        @Test
        @DisplayName("handles very long text")
        void handlesVeryLongText() {
            String longText = "hello ".repeat(1000);
            assertThat(detector.detectLanguage(longText)).isEqualTo(LanguageDetector.ENGLISH);
        }

        @Test
        @DisplayName("handles single word input")
        void handlesSingleWordInput() {
            assertThat(detector.detectLanguage("hello")).isEqualTo(LanguageDetector.ENGLISH);
            assertThat(detector.detectLanguage("привіт")).isEqualTo(LanguageDetector.UKRAINIAN);
            assertThat(detector.detectLanguage("привет")).isEqualTo(LanguageDetector.RUSSIAN);
        }

        @Test
        @DisplayName("handles Transliteration should not detect as English")
        void handlesTransliteration() {
            assertThat(detector.detectLanguage("privet")).isEqualTo(LanguageDetector.DEFAULT_LANGUAGE);
            assertThat(detector.detectLanguage("spasibo")).isEqualTo(LanguageDetector.DEFAULT_LANGUAGE);
        }

        @Test
        @DisplayName("handles punctuation-only text")
        void handlesPunctuationOnlyText() {
            assertThat(detector.detectLanguage("!!! ??? ...")).isEqualTo(LanguageDetector.DEFAULT_LANGUAGE);
        }

        @Test
        @DisplayName("handles numbers-only text")
        void handlesNumbersOnlyText() {
            assertThat(detector.detectLanguage("12345")).isEqualTo(LanguageDetector.DEFAULT_LANGUAGE);
        }

        @Test
        @DisplayName("thread safety with concurrent calls")
        void threadSafetyWithConcurrentCalls() throws InterruptedException {
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(100);
            java.util.concurrent.atomic.AtomicInteger errors = new java.util.concurrent.atomic.AtomicInteger(0);
            for (int i = 0; i < 100; i++) {
                final int index = i;
                new Thread(() -> {
                    try {
                        String input = index % 3 == 0 ? "hello world" :
                                       index % 3 == 1 ? "привіт світе" : "привет мир";
                        String expected = index % 3 == 0 ? LanguageDetector.ENGLISH :
                                          index % 3 == 1 ? LanguageDetector.UKRAINIAN : LanguageDetector.RUSSIAN;
                        if (!detector.detectLanguage(input).equals(expected)) {
                            errors.incrementAndGet();
                        }
                    } finally {
                        latch.countDown();
                    }
                }).start();
            }
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(errors.get()).isZero();
        }
    }
}
