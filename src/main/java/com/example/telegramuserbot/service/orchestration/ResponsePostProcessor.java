package com.example.telegramuserbot.service.orchestration;

import com.example.telegramuserbot.domain.ResponseStyle;
import com.example.telegramuserbot.domain.ResponseTemplate;
import com.example.telegramuserbot.service.humanization.PersonaStyle;
import com.example.telegramuserbot.service.humanization.ReplyHumanizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Постобработка ответа: соблюдение стиля/длины поверх ReplyHumanizer.
 * Упрощённая версия старых Personalization/ResponseProcessing сервисов.
 */
@Component
public class ResponsePostProcessor {

    private static final Logger log = LoggerFactory.getLogger(ResponsePostProcessor.class);

    // Template-driven CONCISE trimming is stricter than a persona's own emoji style
    // (PersonaStyle.EmojiUsage), so it still runs its own pass on top of the humanizer.
    private static final Pattern EMOJI_PATTERN = ReplyHumanizer.EMOJI_UNIT;

    private final ReplyHumanizer humanizer;

    public ResponsePostProcessor(ReplyHumanizer humanizer) {
        this.humanizer = humanizer;
    }

    /**
     * Legacy entry point (no language/style context) — used by proactive posting and
     * sibling replies, which run outside the per-persona reply pipeline.
     */
    public String postProcess(String content, ResponseTemplate template) {
        return postProcess(content, template, "auto", PersonaStyle.defaults());
    }

    public String postProcess(String content, ResponseTemplate template, String languageHint, PersonaStyle style) {
        if (content == null || content.isBlank()) {
            return content;
        }

        ReplyHumanizer.Humanized humanized = humanizer.humanize(content, languageHint, style != null ? style : PersonaStyle.defaults());
        if (humanized.skip()) {
            log.info("ResponsePostProcessor: staying silent — humanizer reported nothing worth sending");
            return "";
        }
        if (humanized.aiTell()) {
            log.warn("ResponsePostProcessor: reply still reads as AI-written after cleanup — staying silent");
            return "";
        }

        String processed = humanized.text();

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

        return processed;
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
