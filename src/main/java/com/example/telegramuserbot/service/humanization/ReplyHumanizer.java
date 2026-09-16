package com.example.telegramuserbot.service.humanization;

import com.example.telegramuserbot.service.common.ReplyLanguage;
import com.example.telegramuserbot.util.MarkdownStripper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.random.RandomGenerator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a raw LLM completion into a line a living Telegram persona would actually
 * type: strips scaffolding the model leaked (role prefixes, wrapping quotes, Markdown,
 * assistant filler), catches the few phrases that give the game away as AI, and applies
 * the persona's own writing habits ({@link PersonaStyle}). A human who has nothing to
 * say says nothing, so several steps can end in silence rather than a canned line.
 *
 * <p>Every reply path — direct handlers and the digest generator — should run its raw
 * LLM output through {@link #humanize} exactly once before it reaches a user.
 */
@Component
public class ReplyHumanizer {

    // The prompt instructs the model to answer with exactly this token when it has
    // nothing worth saying — a human who has nothing to add stays quiet.
    private static final Set<String> SILENCE_TOKENS = Set.of("[SKIP]", "SKIP", "[skip]");

    private static final Pattern LEADING_ROLE_PREFIX =
            Pattern.compile("^\\s*(?:ASSISTANT|USER|SYSTEM)\\s*[:\\-—]\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEADING_SPEAKER_PREFIX =
            Pattern.compile("^\\s*(?:ME|P\\d+|UNKNOWN)(?:\\s*\\([^)]*\\))?\\s*[:\\-—]\\s*", Pattern.CASE_INSENSITIVE);

    private static final Pattern LIST_MARKER = Pattern.compile("^(?:[-*•]|\\d+[.)])\\s+");

    private static final Pattern EMOJI_PATTERN = Pattern.compile(
            "[\\x{1F600}-\\x{1F64F}]|[\\x{1F300}-\\x{1F5FF}]|[\\x{1F680}-\\x{1F6FF}]|[\\x{1F700}-\\x{1F77F}]" +
                    "|[\\x{1F780}-\\x{1F7FF}]|[\\x{1F800}-\\x{1F8FF}]|[\\x{2600}-\\x{26FF}]|[\\x{2700}-\\x{27BF}]");

    private static final String DASH_MARKER = " — ";

    // Leading fillers the model opens with instead of just answering — removed (whole
    // leading phrase plus the punctuation/space that follows it).
    private static final List<String> RU_LEADING_FILLERS = List.of(
            "Конечно!", "Отличный вопрос", "Хороший вопрос", "Давайте разберёмся", "Разумеется");
    private static final List<String> UK_LEADING_FILLERS = List.of(
            "Звісно!", "Чудове питання", "Гарне питання", "Давайте розберемося");
    private static final List<String> EN_LEADING_FILLERS = List.of(
            "Great question", "Sure!", "Certainly!", "Of course!", "Absolutely!");

    // Trailing "customer support" offers — removed as a whole trailing sentence.
    private static final List<String> RU_TRAILING_OFFERS = List.of(
            "Надеюсь, это поможет", "Если у вас есть ещё вопросы", "Обращайтесь", "Дайте знать, если");
    private static final List<String> UK_TRAILING_OFFERS = List.of(
            "Сподіваюся, це допоможе", "Якщо будуть питання");
    private static final List<String> EN_TRAILING_OFFERS = List.of(
            "I hope this helps", "Let me know if", "Feel free to ask");

    private static final List<Pattern> AI_TELL_RU = compile(
            "\\bя (?:бот|ИИ|искусственный интеллект|языковая модель|нейросеть|программа|ассистент)\\b",
            "\\bкак (?:ИИ|искусственный интеллект|языковая модель)\\b",
            "\\bу меня нет (?:тела|чувств|мнения)\\b");
    private static final List<Pattern> AI_TELL_UK = compile(
            "\\bя (?:бот|штучний інтелект|мовна модель|програма|асистент)\\b",
            "\\bяк штучний інтелект\\b");
    private static final List<Pattern> AI_TELL_EN = compile(
            "\\bas an ai\\b",
            "\\blanguage model\\b",
            "\\bi am an ai\\b",
            "\\bi'm an ai\\b",
            "\\bi am a bot\\b",
            "\\bas a bot\\b",
            "\\bi don't have (?:feelings|a body|personal opinions)\\b");

    private final RandomGenerator random;

    /** Production constructor — Spring picks this one (single no-arg constructor rule). */
    public ReplyHumanizer() {
        this(ThreadLocalRandom.current());
    }

    /** Test constructor — pass a seeded/fixed RandomGenerator for deterministic assertions. */
    public ReplyHumanizer(RandomGenerator random) {
        this.random = Objects.requireNonNull(random);
    }

    public record Humanized(String text, boolean skip, boolean aiTell) { }

    public Humanized humanize(String raw, String languageHint, PersonaStyle style) {
        if (raw == null || raw.isBlank()) {
            return new Humanized("", true, false);
        }

        String trimmedRaw = raw.trim();
        String firstLine = trimmedRaw.split("\\R", 2)[0].trim();
        if (SILENCE_TOKENS.contains(trimmedRaw) || SILENCE_TOKENS.contains(firstLine)) {
            return new Humanized("", true, false);
        }

        PersonaStyle effectiveStyle = style != null ? style : PersonaStyle.defaults();
        String normalizedLang = ReplyLanguage.normalize(languageHint);

        String text = stripSpeakerPrefixes(trimmedRaw);
        text = unwrapQuotes(text);
        text = stripMarkdownAndCollapseLists(text);
        text = removeAssistantIsms(text, normalizedLang);

        if (containsAiTell(text, normalizedLang)) {
            return new Humanized(text, false, true);
        }

        text = moderateDashes(text);
        text = applyEmojiStyle(text, effectiveStyle.emoji());
        text = applyLowercaseStart(text, effectiveStyle);
        text = applyDropFinalPeriod(text, effectiveStyle);

        text = text.trim().replaceAll("\n{3,}", "\n\n");
        if (text.isBlank()) {
            return new Humanized("", true, false);
        }
        return new Humanized(text, false, false);
    }

    private String stripSpeakerPrefixes(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String[] lines = text.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line == null || line.isBlank()) {
                continue;
            }
            line = LEADING_ROLE_PREFIX.matcher(line).replaceFirst("");
            line = LEADING_SPEAKER_PREFIX.matcher(line).replaceFirst("");
            lines[i] = line;
        }
        return String.join("\n", lines).trim();
    }

    private String unwrapQuotes(String text) {
        if (text.length() < 2) {
            return text;
        }
        char first = text.charAt(0);
        char last = text.charAt(text.length() - 1);
        boolean wrapped = (first == '«' && last == '»')
                || (first == '“' && last == '”')
                || (first == '"' && last == '"');
        return wrapped ? text.substring(1, text.length() - 1).trim() : text;
    }

    private String stripMarkdownAndCollapseLists(String text) {
        String plain = MarkdownStripper.stripToPlainText(text);
        if (plain == null || plain.isBlank()) {
            return plain == null ? "" : plain;
        }
        String[] lines = plain.split("\n", -1);
        List<String> outLines = new ArrayList<>();
        List<String> listSentences = new ArrayList<>();
        for (String line : lines) {
            String stripped = line.strip();
            Matcher m = LIST_MARKER.matcher(stripped);
            if (m.find()) {
                String item = stripped.substring(m.end()).strip();
                if (!item.isEmpty()) {
                    if (!endsWithSentencePunctuation(item)) {
                        item = item + ".";
                    }
                    listSentences.add(item);
                }
                continue;
            }
            flushListSentences(outLines, listSentences);
            outLines.add(line);
        }
        flushListSentences(outLines, listSentences);
        return String.join("\n", outLines);
    }

    private void flushListSentences(List<String> outLines, List<String> listSentences) {
        if (!listSentences.isEmpty()) {
            outLines.add(String.join(" ", listSentences));
            listSentences.clear();
        }
    }

    private boolean endsWithSentencePunctuation(String text) {
        if (text.isEmpty()) {
            return false;
        }
        char last = text.charAt(text.length() - 1);
        return last == '.' || last == '!' || last == '?' || last == '…';
    }

    private String removeAssistantIsms(String text, String normalizedLang) {
        List<String> leading = new ArrayList<>();
        List<String> trailing = new ArrayList<>();
        boolean auto = ReplyLanguage.AUTO.equals(normalizedLang);
        if (auto || ReplyLanguage.RU.equals(normalizedLang)) {
            leading.addAll(RU_LEADING_FILLERS);
            trailing.addAll(RU_TRAILING_OFFERS);
        }
        if (auto || ReplyLanguage.UK.equals(normalizedLang)) {
            leading.addAll(UK_LEADING_FILLERS);
            trailing.addAll(UK_TRAILING_OFFERS);
        }
        if (auto || ReplyLanguage.EN.equals(normalizedLang)) {
            leading.addAll(EN_LEADING_FILLERS);
            trailing.addAll(EN_TRAILING_OFFERS);
        }
        String result = stripLeadingFiller(text, leading);
        result = stripTrailingOffer(result, trailing);
        return result;
    }

    private String stripLeadingFiller(String text, List<String> fillers) {
        String working = text.stripLeading();
        for (String phrase : fillers) {
            if (startsWithIgnoreCase(working, phrase)) {
                String rest = working.substring(phrase.length());
                int i = 0;
                while (i < rest.length() && isFillerPunctuation(rest.charAt(i))) {
                    i++;
                }
                return rest.substring(i).stripLeading();
            }
        }
        return text;
    }

    private boolean isFillerPunctuation(char c) {
        return c == '!' || c == ',' || c == '.' || c == ':' || c == ';' || c == '—' || c == '-' || c == '…'
                || Character.isWhitespace(c);
    }

    private String stripTrailingOffer(String text, List<String> offers) {
        if (offers.isEmpty()) {
            return text;
        }
        String working = text;
        for (int guard = 0; guard < 3; guard++) {
            String[] sentences = working.split("(?<=[.!?…])\\s+");
            if (sentences.length == 0) {
                break;
            }
            String lastSentence = sentences[sentences.length - 1].strip();
            boolean matched = false;
            for (String phrase : offers) {
                if (startsWithIgnoreCase(lastSentence, phrase)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                break;
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < sentences.length - 1; i++) {
                if (i > 0) {
                    sb.append(' ');
                }
                sb.append(sentences[i]);
            }
            working = sb.toString().strip();
            if (working.isEmpty()) {
                break;
            }
        }
        return working;
    }

    private boolean startsWithIgnoreCase(String text, String prefix) {
        return text.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private boolean containsAiTell(String text, String normalizedLang) {
        boolean auto = ReplyLanguage.AUTO.equals(normalizedLang);
        if ((auto || ReplyLanguage.RU.equals(normalizedLang)) && matchesAny(text, AI_TELL_RU)) {
            return true;
        }
        if ((auto || ReplyLanguage.UK.equals(normalizedLang)) && matchesAny(text, AI_TELL_UK)) {
            return true;
        }
        return (auto || ReplyLanguage.EN.equals(normalizedLang)) && matchesAny(text, AI_TELL_EN);
    }

    private boolean matchesAny(String text, List<Pattern> patterns) {
        for (Pattern p : patterns) {
            if (p.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    private String moderateDashes(String text) {
        int first = text.indexOf(DASH_MARKER);
        if (first < 0) {
            return text;
        }
        int second = text.indexOf(DASH_MARKER, first + DASH_MARKER.length());
        if (second < 0) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.substring(0, first + DASH_MARKER.length()));
        int pos = first + DASH_MARKER.length();
        while (true) {
            int next = text.indexOf(DASH_MARKER, pos);
            if (next < 0) {
                sb.append(text.substring(pos));
                break;
            }
            sb.append(text, pos, next).append(", ");
            pos = next + DASH_MARKER.length();
        }
        return sb.toString();
    }

    private String applyEmojiStyle(String text, PersonaStyle.EmojiUsage usage) {
        int maxKeep = switch (usage) {
            case NONE -> 0;
            case RARE -> 1;
            case OFTEN -> 3;
        };
        Matcher matcher = EMOJI_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        int count = 0;
        while (matcher.find()) {
            count++;
            if (count <= maxKeep) {
                matcher.appendReplacement(sb, Matcher.quoteReplacement(matcher.group()));
            } else {
                matcher.appendReplacement(sb, "");
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String applyLowercaseStart(String text, PersonaStyle style) {
        if (!style.lowercaseStart() || text.isEmpty()) {
            return text;
        }
        if (random.nextDouble() >= 0.35) {
            return text;
        }
        int wordLen = 0;
        while (wordLen < text.length() && Character.isLetter(text.charAt(wordLen))) {
            wordLen++;
        }
        if (wordLen < 3) {
            return text;
        }
        char first = text.charAt(0);
        char lower = Character.toLowerCase(first);
        if (lower == first) {
            return text;
        }
        return lower + text.substring(1);
    }

    private String applyDropFinalPeriod(String text, PersonaStyle style) {
        if (!style.dropFinalPeriod() || text.length() > 200) {
            return text;
        }
        if (!text.endsWith(".") || text.endsWith("...")) {
            return text;
        }
        if (random.nextDouble() >= 0.6) {
            return text;
        }
        return text.substring(0, text.length() - 1);
    }

    private static List<Pattern> compile(String... regexes) {
        List<Pattern> list = new ArrayList<>();
        for (String r : regexes) {
            list.add(Pattern.compile(r, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS));
        }
        return List.copyOf(list);
    }
}
