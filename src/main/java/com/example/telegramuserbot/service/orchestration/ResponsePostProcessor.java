package com.example.telegramuserbot.service.orchestration;

import com.example.telegramuserbot.domain.ResponseStyle;
import com.example.telegramuserbot.domain.ResponseTemplate;
import com.example.telegramuserbot.util.MarkdownStripper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Постобработка ответа: лёгкая персонализация и соблюдение стиля/длины.
 * Упрощённая версия старых Personalization/ResponseProcessing сервисов.
 */
@Component
public class ResponsePostProcessor {

    private static final Pattern EMOJI_PATTERN = Pattern.compile("[\\x{1F600}-\\x{1F64F}]|[\\x{1F300}-\\x{1F5FF}]|[\\x{1F680}-\\x{1F6FF}]|[\\x{1F700}-\\x{1F77F}]|[\\x{1F780}-\\x{1F7FF}]|[\\x{1F800}-\\x{1F8FF}]|[\\x{2600}-\\x{26FF}]|[\\x{2700}-\\x{27BF}]");
    private static final Pattern LEADING_ROLE_PREFIX = Pattern.compile("^\\s*(?:ASSISTANT|USER|SYSTEM)\\s*[:\\-—]\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern LEADING_SPEAKER_PREFIX = Pattern.compile("^\\s*(?:ME|P\\d+|UNKNOWN)(?:\\s*\\([^)]*\\))?\\s*[:\\-—]\\s*", Pattern.CASE_INSENSITIVE);

    public String postProcess(String content, ResponseTemplate template) {
        if (content == null || content.isBlank()) {
            return content;
        }

        String processed = stripSpeakerPrefixes(content.trim());

        // Respect max length if defined — cut at last sentence boundary before limit
        Integer maxLen = Optional.ofNullable(template).map(ResponseTemplate::getMaxResponseLength).orElse(null);
        if (maxLen != null && maxLen > 0 && processed.length() > maxLen) {
            processed = truncateAtSentence(processed, maxLen);
        }

        // Very light personalization: if template wants concise, strip emojis and shorten sentences
        if (template != null && template.getResponseStyle() == ResponseStyle.CONCISE) {
            processed = removeExcessEmojis(processed);
            processed = keepShort(processed);
        }

        // Strip any Markdown emphasis the LLM may have emitted — messages are sent as plain text
        // so literal asterisks, underscores, backticks and heading chars would be visible to users.
        processed = MarkdownStripper.stripToPlainText(processed);

        return processed;
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

    private String removeExcessEmojis(String text) {
        var matcher = EMOJI_PATTERN.matcher(text);
        int count = 0;
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            count++;
            if (count > 2) {
                matcher.appendReplacement(sb, ""); // drop extra emojis
            } else {
                matcher.appendReplacement(sb, matcher.group());
            }
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String truncateAtSentence(String text, int maxLen) {
        String cut = text.substring(0, maxLen);
        int lastPeriod = Math.max(cut.lastIndexOf('.'), Math.max(cut.lastIndexOf('!'), cut.lastIndexOf('?')));
        if (lastPeriod > maxLen / 2) {
            return cut.substring(0, lastPeriod + 1);
        }
        int lastSpace = cut.lastIndexOf(' ');
        return lastSpace > 0 ? cut.substring(0, lastSpace) : cut;
    }

    private String keepShort(String text) {
        // Keep first sentence or first ~140 chars to preserve concise style
        int dot = text.indexOf('.');
        if (dot > 0 && dot < 140) {
            return text.substring(0, dot + 1);
        }
        return text.length() > 140 ? text.substring(0, 140) : text;
    }
}
