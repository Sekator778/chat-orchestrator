package com.example.telegramuserbot.service.safety;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Outbound moderation: the last gate before a generated reply is sent. If the
 * text self-identifies as AI/bot or matches a configured denylist, the reply is
 * SUPPRESSED — the persona stays silent rather than sending a canned deflection
 * (a repeated stock phrase is itself a bot tell). Fail-closed by design.
 */
@Service
public class OutboundReplyGuard {

    private static final Logger log = LoggerFactory.getLogger(OutboundReplyGuard.class);

    private final List<String> bannedSubstrings;

    public OutboundReplyGuard(
            @Value("${bot.outbound-guard.banned-substrings:я бот,я штучний інтелект,я ai,i am an ai,i'm an ai,i am a bot,i'm a bot,as an ai,language model}")
            String bannedCsv) {
        this.bannedSubstrings = Arrays.stream(bannedCsv.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /**
     * @return true when the reply must NOT be sent (self-identifies as AI/bot or
     *         hits the denylist). Blank text is not the guard's concern.
     */
    public boolean shouldSuppress(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String banned : bannedSubstrings) {
            if (lower.contains(banned)) {
                log.warn("⊘ OUTBOUND GUARD: reply suppressed — matched banned substring '{}'", banned);
                return true;
            }
        }
        return false;
    }
}
