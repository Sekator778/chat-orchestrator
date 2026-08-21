package com.example.telegramuserbot.service.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/**
 * Unit tests for TextOperationsImpl.
 */
@DisplayName("TextOperationsImpl")
class TextOperationsImplTest {

    private final TextOperations textOps = new TextOperationsImpl();

    @Nested
    @DisplayName("escapeJson")
    class EscapeJsonTests {

        @Test
        @DisplayName("should return empty string when input is null")
        void shouldReturnEmptyStringWhenInputIsNull() {
            assertThat(textOps.escapeJson(null), is(emptyString()));
        }

        @Test
        @DisplayName("should return empty string when input is empty")
        void shouldReturnEmptyStringWhenInputIsEmpty() {
            assertThat(textOps.escapeJson(""), is(emptyString()));
        }

        @Test
        @DisplayName("should escape backslash characters")
        void shouldEscapeBackslashCharacters() {
            assertThat(textOps.escapeJson("path\\to\\file"), is(equalTo("path\\\\to\\\\file")));
        }

        @Test
        @DisplayName("should escape double quote characters")
        void shouldEscapeDoubleQuoteCharacters() {
            assertThat(textOps.escapeJson("He said \"hello\""), is(equalTo("He said \\\"hello\\\"")));
        }

        @Test
        @DisplayName("should escape newline characters")
        void shouldEscapeNewlineCharacters() {
            assertThat(textOps.escapeJson("line1\nline2"), is(equalTo("line1\\nline2")));
        }

        @Test
        @DisplayName("should escape carriage return characters")
        void shouldEscapeCarriageReturnCharacters() {
            assertThat(textOps.escapeJson("line1\rline2"), is(equalTo("line1\\rline2")));
        }

        @Test
        @DisplayName("should escape combined special characters")
        void shouldEscapeCombinedSpecialCharacters() {
            String input = "Path: \"C:\\test\"\nNew line\r";
            String expected = "Path: \\\"C:\\\\test\\\"\\nNew line\\r";
            assertThat(textOps.escapeJson(input), is(equalTo(expected)));
        }

        @Test
        @DisplayName("should return text unchanged when no special characters")
        void shouldReturnTextUnchangedWhenNoSpecialCharacters() {
            String input = "Hello world 123";
            assertThat(textOps.escapeJson(input), is(equalTo(input)));
        }

        @Test
        @DisplayName("should handle unicode characters without escaping")
        void shouldHandleUnicodeCharactersWithoutEscaping() {
            String input = "Привет мир 世界";
            assertThat(textOps.escapeJson(input), is(equalTo(input)));
        }
    }

    @Nested
    @DisplayName("escapeHtml")
    class EscapeHtmlTests {

        @Test
        @DisplayName("should return empty string when input is null")
        void shouldReturnEmptyStringWhenInputIsNull() {
            assertThat(textOps.escapeHtml(null), is(emptyString()));
        }

        @Test
        @DisplayName("should return empty string when input is empty")
        void shouldReturnEmptyStringWhenInputIsEmpty() {
            assertThat(textOps.escapeHtml(""), is(emptyString()));
        }

        @Test
        @DisplayName("should escape ampersand characters")
        void shouldEscapeAmpersandCharacters() {
            assertThat(textOps.escapeHtml("Tom & Jerry"), is(equalTo("Tom &amp; Jerry")));
        }

        @Test
        @DisplayName("should escape less than characters")
        void shouldEscapeLessThanCharacters() {
            assertThat(textOps.escapeHtml("a < b"), is(equalTo("a &lt; b")));
        }

        @Test
        @DisplayName("should escape greater than characters")
        void shouldEscapeGreaterThanCharacters() {
            assertThat(textOps.escapeHtml("a > b"), is(equalTo("a &gt; b")));
        }

        @Test
        @DisplayName("should escape HTML tags")
        void shouldEscapeHtmlTags() {
            assertThat(textOps.escapeHtml("<b>bold</b>"), is(equalTo("&lt;b&gt;bold&lt;/b&gt;")));
        }

        @Test
        @DisplayName("should handle combined HTML special characters")
        void shouldHandleCombinedHtmlSpecialCharacters() {
            String input = "x < y & y > z";
            String expected = "x &lt; y &amp; y &gt; z";
            assertThat(textOps.escapeHtml(input), is(equalTo(expected)));
        }

        @Test
        @DisplayName("should return text unchanged when no special characters")
        void shouldReturnTextUnchangedWhenNoSpecialCharacters() {
            String input = "Plain text without HTML";
            assertThat(textOps.escapeHtml(input), is(equalTo(input)));
        }
    }

    @Nested
    @DisplayName("truncate")
    class TruncateTests {

        @Test
        @DisplayName("should return null when input is null")
        void shouldReturnNullWhenInputIsNull() {
            assertThat(textOps.truncate(null, 10), is(nullValue()));
        }

        @Test
        @DisplayName("should return original text when length equals max")
        void shouldReturnOriginalTextWhenLengthEqualsMax() {
            String text = "12345";
            assertThat(textOps.truncate(text, 5), is(equalTo(text)));
        }

        @Test
        @DisplayName("should return original text when length less than max")
        void shouldReturnOriginalTextWhenLengthLessThanMax() {
            String text = "123";
            assertThat(textOps.truncate(text, 10), is(equalTo(text)));
        }

        @Test
        @DisplayName("should truncate with ellipsis when text exceeds max")
        void shouldTruncateWithEllipsisWhenTextExceedsMax() {
            String text = "Hello World";
            String result = textOps.truncate(text, 8);
            assertThat(result, is(equalTo("Hello...")));
            assertThat(result.length(), is(8));
        }

        @Test
        @DisplayName("should handle max length smaller than ellipsis length")
        void shouldHandleMaxLengthSmallerThanEllipsisLength() {
            String text = "Hello";
            assertThat(textOps.truncate(text, 2), is(equalTo("He")));
        }

        @Test
        @DisplayName("should handle empty string")
        void shouldHandleEmptyString() {
            assertThat(textOps.truncate("", 10), is(emptyString()));
        }
    }

    @Nested
    @DisplayName("truncateWithSuffix")
    class TruncateWithSuffixTests {

        @Test
        @DisplayName("should return empty string when input is null")
        void shouldReturnEmptyStringWhenInputIsNull() {
            assertThat(textOps.truncateWithSuffix(null, 10, "..."), is(emptyString()));
        }

        @Test
        @DisplayName("should return original text when length within limit")
        void shouldReturnOriginalTextWhenLengthWithinLimit() {
            String text = "Hello";
            assertThat(textOps.truncateWithSuffix(text, 10, "..."), is(equalTo(text)));
        }

        @Test
        @DisplayName("should truncate with custom suffix")
        void shouldTruncateWithCustomSuffix() {
            String text = "Hello World Example";
            String result = textOps.truncateWithSuffix(text, 15, " [more]");
            assertThat(result, is(equalTo("Hello Wo [more]")));
            assertThat(result.length(), is(15));
        }

        @Test
        @DisplayName("should handle null suffix")
        void shouldHandleNullSuffix() {
            String text = "Hello World";
            assertThat(textOps.truncateWithSuffix(text, 5, null), is(equalTo("Hello")));
        }

        @Test
        @DisplayName("should handle empty suffix")
        void shouldHandleEmptySuffix() {
            String text = "Hello World";
            assertThat(textOps.truncateWithSuffix(text, 5, ""), is(equalTo("Hello")));
        }

        @Test
        @DisplayName("should handle suffix longer than limit")
        void shouldHandleSuffixLongerThanLimit() {
            String text = "Hello World";
            String result = textOps.truncateWithSuffix(text, 3, " ...[truncated]");
            assertThat(result, is(equalTo(" ...[truncated]")));
        }
    }

    @Nested
    @DisplayName("truncateForLog")
    class TruncateForLogTests {

        @Test
        @DisplayName("should return empty string when input is null")
        void shouldReturnEmptyStringWhenInputIsNull() {
            assertThat(textOps.truncateForLog(null, 50), is(emptyString()));
        }

        @Test
        @DisplayName("should return original text when within limit")
        void shouldReturnOriginalTextWhenWithinLimit() {
            String text = "Short text";
            assertThat(textOps.truncateForLog(text, 50), is(equalTo(text)));
        }

        @Test
        @DisplayName("should truncate with log suffix when exceeds limit")
        void shouldTruncateWithLogSuffixWhenExceedsLimit() {
            String text = "This is a very long text that exceeds the limit";
            String result = textOps.truncateForLog(text, 30);
            assertThat(result, containsString(" ...[truncated]"));
            assertThat(result.length(), is(30));
        }
    }

    @Nested
    @DisplayName("truncateForLogNormalized")
    class TruncateForLogNormalizedTests {

        @Test
        @DisplayName("should return empty string when input is null")
        void shouldReturnEmptyStringWhenInputIsNull() {
            assertThat(textOps.truncateForLogNormalized(null, 50), is(emptyString()));
        }

        @Test
        @DisplayName("should normalize multiple spaces to single space")
        void shouldNormalizeMultipleSpacesToSingleSpace() {
            String text = "Hello    World";
            assertThat(textOps.truncateForLogNormalized(text, 50), is(equalTo("Hello World")));
        }

        @Test
        @DisplayName("should normalize tabs and newlines to spaces")
        void shouldNormalizeTabsAndNewlinesToSpaces() {
            String text = "Hello\t\nWorld";
            assertThat(textOps.truncateForLogNormalized(text, 50), is(equalTo("Hello World")));
        }

        @Test
        @DisplayName("should trim whitespace from ends")
        void shouldTrimWhitespaceFromEnds() {
            String text = "  Hello World  ";
            assertThat(textOps.truncateForLogNormalized(text, 50), is(equalTo("Hello World")));
        }

        @Test
        @DisplayName("should normalize and truncate when exceeds limit")
        void shouldNormalizeAndTruncateWhenExceedsLimit() {
            String text = "Hello    World    Example    Text";
            String result = textOps.truncateForLogNormalized(text, 15);
            assertThat(result, is(equalTo("Hello World ...")));
            assertThat(result.length(), is(15));
        }
    }

    @Nested
    @DisplayName("truncateTelegramMessage")
    class TruncateTelegramMessageTests {

        @Test
        @DisplayName("should return null when input is null")
        void shouldReturnNullWhenInputIsNull() {
            assertThat(textOps.truncateTelegramMessage(null), is(nullValue()));
        }

        @Test
        @DisplayName("should return original text when within Telegram limit")
        void shouldReturnOriginalTextWhenWithinTelegramLimit() {
            String text = "Short message";
            assertThat(textOps.truncateTelegramMessage(text), is(equalTo(text)));
        }

        @Test
        @DisplayName("should truncate text at Telegram limit boundary")
        void shouldTruncateTextAtTelegramLimitBoundary() {
            String text = "x".repeat(4096);
            String result = textOps.truncateTelegramMessage(text);
            assertThat(result, is(equalTo(text)));
            assertThat(result.length(), is(4096));
        }

        @Test
        @DisplayName("should truncate text exceeding Telegram limit")
        void shouldTruncateTextExceedingTelegramLimit() {
            String text = "x".repeat(5000);
            String result = textOps.truncateTelegramMessage(text);
            assertThat(result.length(), is(4096));
            assertThat(result.endsWith("..."), is(true));
        }
    }

    @Nested
    @DisplayName("truncateForPayloadLogging")
    class TruncateForPayloadLoggingTests {

        @Test
        @DisplayName("should return null when input is null")
        void shouldReturnNullWhenInputIsNull() {
            assertThat(textOps.truncateForPayloadLogging(null, 100), is(nullValue()));
        }

        @Test
        @DisplayName("should return original text when within limit")
        void shouldReturnOriginalTextWhenWithinLimit() {
            String text = "Short payload";
            assertThat(textOps.truncateForPayloadLogging(text, 100), is(equalTo(text)));
        }

        @Test
        @DisplayName("should include character count in truncation message")
        void shouldIncludeCharacterCountInTruncationMessage() {
            String text = "x".repeat(1000);
            String result = textOps.truncateForPayloadLogging(text, 100);
            assertThat(result, containsString("[truncated 900 chars]"));
        }

        @Test
        @DisplayName("should format truncation message with newline")
        void shouldFormatTruncationMessageWithNewline() {
            String text = "x".repeat(200);
            String result = textOps.truncateForPayloadLogging(text, 50);
            assertThat(result, containsString("\n...[truncated"));
        }
    }

    @Nested
    @DisplayName("Edge cases and Unicode")
    class EdgeCaseTests {

        @Test
        @DisplayName("should handle text with only special JSON characters")
        void shouldHandleTextWithOnlySpecialJsonCharacters() {
            assertThat(textOps.escapeJson("\\\"\n\r"), is(equalTo("\\\\\\\"\\n\\r")));
        }

        @Test
        @DisplayName("should handle text with only special HTML characters")
        void shouldHandleTextWithOnlySpecialHtmlCharacters() {
            assertThat(textOps.escapeHtml("<&>"), is(equalTo("&lt;&amp;&gt;")));
        }

        @Test
        @DisplayName("should handle cyrillic text truncation")
        void shouldHandleCyrillicTextTruncation() {
            String text = "Привет мир это длинный текст";
            String result = textOps.truncate(text, 15);
            assertThat(result.length(), is(15));
            assertThat(result.endsWith("..."), is(true));
        }

        @Test
        @DisplayName("should handle emoji in text")
        void shouldHandleEmojiInText() {
            String text = "Hello 👋 World 🌍";
            assertThat(textOps.escapeJson(text), is(equalTo(text)));
            assertThat(textOps.escapeHtml(text), is(equalTo(text)));
        }

        @Test
        @DisplayName("should handle very long text efficiently")
        void shouldHandleVeryLongTextEfficiently() {
            String text = "x".repeat(100000);
            String result = textOps.truncate(text, 100);
            assertThat(result.length(), is(100));
        }
    }
}
