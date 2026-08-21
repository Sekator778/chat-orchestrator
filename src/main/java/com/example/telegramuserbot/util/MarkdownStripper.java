package com.example.telegramuserbot.util;

import java.util.regex.Pattern;

/**
 * Strips Markdown formatting from persona reply text so that literal asterisks,
 * underscores, backticks, and heading characters are not visible to Telegram users
 * when the message is sent in plain-text mode (no parse_mode).
 *
 * <p>Conversions performed:
 * <ul>
 *   <li>{@code **bold**} / {@code __bold__} → {@code bold}</li>
 *   <li>{@code *italic*} / {@code _italic_} → {@code italic}
 *       (only paired markers around non-whitespace, never lone markers,
 *        never {@code $200}, {@code 5*4}, or snake_case/file_names)</li>
 *   <li>Inline {@code `code`} and fenced {@code ```block```} → content only</li>
 *   <li>Leading {@code #}/{@code ##}/{@code ###} heading markers removed</li>
 *   <li>{@code [text](url)} → {@code text}</li>
 *   <li>Doubled spaces collapsed to one</li>
 * </ul>
 *
 * <p>Conservative regexes: normal text, currency, math expressions, and underscores
 * in identifiers are left untouched.
 */
public final class MarkdownStripper {

    private MarkdownStripper() { /* utility class */ }

    // Fenced code block: ```optional-lang\ncontent\n```
    private static final Pattern FENCED_CODE = Pattern.compile(
            "```[^`]*?```", Pattern.DOTALL);

    // Inline code: `code`
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`\n]+)`");

    // Bold: **text** or __text__ (non-empty, no surrounding spaces inside)
    private static final Pattern BOLD_ASTERISK = Pattern.compile(
            "\\*\\*([^\\s*][^*]*?[^\\s*]|[^\\s*])\\*\\*");
    private static final Pattern BOLD_UNDERSCORE = Pattern.compile(
            "__([^\\s_][^_]*?[^\\s_]|[^\\s_])__");

    // Italic: *text* or _text_
    // *italic*: must not be preceded/followed by * (so ** is already consumed);
    //   require non-space, non-digit before to avoid $2*4 or 5*4 math;
    //   use a negative lookbehind for digit to skip "5*4".
    private static final Pattern ITALIC_ASTERISK = Pattern.compile(
            "(?<![\\d*])\\*([^\\s*][^*]*?[^\\s*]|[^\\s*])\\*(?![\\d*])");

    // _italic_: only when both delimiters are at a word boundary-ish position
    //   (preceded/followed by non-word or start/end of string).
    //   This skips snake_case and file_names where _ is interior.
    private static final Pattern ITALIC_UNDERSCORE = Pattern.compile(
            "(?<![\\w])_([^\\s_][^_]*?[^\\s_]|[^\\s_])_(?![\\w])");

    // Markdown links: [text](url)
    private static final Pattern MD_LINK = Pattern.compile(
            "\\[([^\\[\\]]+)\\]\\([^)]+\\)");

    // ATX headings: leading # symbols at start of a line
    private static final Pattern HEADING = Pattern.compile(
            "(?m)^#{1,6}\\s+");

    // Collapsed multiple spaces (but not newlines)
    private static final Pattern DOUBLE_SPACE = Pattern.compile("[ \\t]{2,}");

    /**
     * Converts {@code text} to plain conversational text by removing Markdown
     * emphasis markers. Returns {@code null} if the input is {@code null};
     * returns the input unchanged if it is blank.
     *
     * @param text the raw LLM reply text, may be null
     * @return plain-text version with Markdown markers stripped
     */
    public static String stripToPlainText(String text) {
        if (text == null) {
            return null;
        }
        if (text.isBlank()) {
            return text;
        }

        String result = text;

        // Fenced blocks first (greedy multi-line) → keep inner content
        result = FENCED_CODE.matcher(result).replaceAll(m -> {
            String inner = m.group();
            // Remove opening fence (``` + optional lang + newline) and closing fence
            inner = inner.replaceFirst("^```[^\\n]*\\n?", "");
            inner = inner.replaceFirst("```$", "");
            return inner.trim();
        });

        // Inline code → content only
        result = INLINE_CODE.matcher(result).replaceAll("$1");

        // Bold (must come before italic so ** is consumed first)
        result = BOLD_ASTERISK.matcher(result).replaceAll("$1");
        result = BOLD_UNDERSCORE.matcher(result).replaceAll("$1");

        // Italic
        result = ITALIC_ASTERISK.matcher(result).replaceAll("$1");
        result = ITALIC_UNDERSCORE.matcher(result).replaceAll("$1");

        // Links
        result = MD_LINK.matcher(result).replaceAll("$1");

        // Headings
        result = HEADING.matcher(result).replaceAll("");

        // Collapse doubled spaces (but preserve newlines)
        result = DOUBLE_SPACE.matcher(result).replaceAll(" ");

        return result;
    }
}
