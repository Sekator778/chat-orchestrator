package com.example.telegramuserbot.service.publishing;

import com.example.telegramuserbot.domain.Event;
import com.example.telegramuserbot.service.common.TextOperations;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Renders events into formatted Telegram HTML posts.
 * Supports multiple templates: RICH (detailed) and SHORT (compact).
 */
@Service
public final class TelegramPostRenderer {

    private static final Logger log = LoggerFactory.getLogger(TelegramPostRenderer.class);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    private final ObjectMapper mapper;
    private final TextOperations textOps;

    /**
     * Constructs renderer with JSON mapper and text operations service.
     *
     * @param mapper JSON object mapper
     * @param textOps text operations service
     */
    public TelegramPostRenderer(ObjectMapper mapper, TextOperations textOps) {
        this.mapper = mapper;
        this.textOps = textOps;
    }

    /**
     * Renders event into HTML post using specified template.
     *
     * @param event event to render
     * @param templateCode template to use (RICH or SHORT)
     * @return formatted HTML text
     */
    public String render(Event event, String templateCode) {
        return switch (templateCode.toUpperCase()) {
            case "RICH" -> renderRich(event);
            case "SHORT" -> renderShort(event);
            default -> {
                log.warn("Unknown template code: {}, using RICH", templateCode);
                yield renderRich(event);
            }
        };
    }

    /**
     * Renders RICH template: detailed event information with all metrics.
     * Format: [TYPE] topic — spike x{ratio} over {window}
     *         Cause: {root_cause}
     *         Metrics: Msg={count} | Src={sources} | Conf={confidence} | Sev={severity}
     *         Evidence: #1 #2 #3
     */
    private String renderRich(Event event) {
        String windowDuration = formatWindowDuration(event);
        String evidenceLinks = formatEvidenceLinks(event, 3);

        return String.format("""
            <b>[%s] %s</b> — всплеск ×%.1f за %s
            Причина: %s
            Метрики: Msg=%d | Src=%d | Conf=%.2f | Sev=%s
            %s""",
            event.eventType(),
            event.topic().toUpperCase(),
            event.spikeRatio() != null ? event.spikeRatio() : 0.0,
            windowDuration,
            textOps.escapeHtml(event.rootCause()),
            event.messageCount(),
            event.uniqueSources(),
            event.confidence() != null ? event.confidence() : 0.0,
            event.severity(),
            evidenceLinks
        ).trim();
    }

    /**
     * Renders SHORT template: compact alert for quick scanning.
     * Format: **topic** — TYPE ×{ratio} • {confidence}
     *         {root_cause}
     *         {link1} {link2}
     */
    private String renderShort(Event event) {
        String evidenceLinks = formatEvidenceLinks(event, 2);

        return String.format("""
            <b>%s</b> — %s ×%.1f • %.2f
            %s
            %s""",
            event.topic().toUpperCase(),
            event.eventType(),
            event.spikeRatio() != null ? event.spikeRatio() : 0.0,
            event.confidence() != null ? event.confidence() : 0.0,
            textOps.escapeHtml(textOps.truncate(event.rootCause(), 120)),
            evidenceLinks
        ).trim();
    }

    /**
     * Formats time window duration for display.
     */
    private String formatWindowDuration(Event event) {
        if (event.windowStart() == null || event.windowEnd() == null) {
            return "15 мин";
        }

        long minutes = java.time.Duration.between(
            event.windowStart(),
            event.windowEnd()
        ).toMinutes();

        return minutes + " мин";
    }

    /**
     * Formats evidence as numbered links from JSONB.
     * Extracts up to maxLinks messages and generates Telegram deep links.
     */
    private String formatEvidenceLinks(Event event, int maxLinks) {
        if (event.evidence() == null || event.evidence().isEmpty()) {
            return "";
        }

        try {
            List<Map<String, Object>> evidenceList = mapper.readValue(
                event.evidence(),
                new TypeReference<>() {}
            );

            if (evidenceList.isEmpty()) {
                return "";
            }

            StringBuilder links = new StringBuilder("Доказательства: ");
            int count = Math.min(evidenceList.size(), maxLinks);

            for (int i = 0; i < count; i++) {
                Map<String, Object> evidence = evidenceList.get(i);
                Object msgIdObj = evidence.get("msg_id");
                Object channelIdObj = evidence.get("channel_id");

                if (msgIdObj != null && channelIdObj != null) {
                    long msgId = ((Number) msgIdObj).longValue();
                    long channelId = ((Number) channelIdObj).longValue();

                    // Generate Telegram deep link
                    String link = generateTelegramLink(channelId, msgId);
                    links.append(String.format("<a href=\"%s\">#%d</a>  ", link, i + 1));
                }
            }

            return links.toString().trim();

        } catch (Exception e) {
            log.warn("Failed to parse evidence for event {}: {}", event.id(), e.getMessage());
            return "";
        }
    }

    /**
     * Generates Telegram deep link for message.
     * Format: tg://resolve?domain={channel}&post={msgId}
     * Or for private channels: tg://privatepost?channel={id}&post={msgId}
     */
    private String generateTelegramLink(long channelId, long msgId) {
        return String.format("tg://privatepost?channel=%d&amp;post=%d", channelId, msgId);
    }
}
