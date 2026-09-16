package com.example.telegramuserbot.service.humanization;

import java.util.List;

/**
 * Per-persona writing habits — the knobs that make one persona's messages look
 * different from another's in a Telegram group.
 *
 * <p>Read from {@code bot.bot_personas.metadata} (JSONB) under the {@code style}
 * key; every field has a safe default so a persona with no metadata behaves as
 * a plain, moderately terse chat participant. Example metadata:
 *
 * <pre>{@code
 * {
 *   "style": {
 *     "max_sentences": 2,
 *     "emoji": "rare",            // none | rare | often
 *     "lowercase_start": true,     // sometimes starts a message in lowercase
 *     "drop_final_period": true,   // often omits the final period, like chat users do
 *     "skip_probability": 0.15,    // extra chance to stay silent when not addressed
 *     "catchphrases": ["ну такое", "по факту"],
 *     "interests": ["крипта", "макро", "ETF"],
 *     "timezone": "Europe/Berlin"
 *   }
 * }
 * }</pre>
 *
 * @param maxSentences     typical reply length in sentences (1..4)
 * @param emoji            how often emoji appear in the persona's messages
 * @param lowercaseStart   the persona sometimes starts a message in lowercase
 * @param dropFinalPeriod  the persona often omits the trailing period
 * @param skipProbability  extra probability (0..1) to stay silent when not directly addressed
 * @param catchphrases     short expressions the persona likes to use (may be empty)
 * @param interests        topics the persona cares about (may be empty)
 * @param timezone         IANA zone id used for "what time is it for me" hints, or null
 */
public record PersonaStyle(
        int maxSentences,
        EmojiUsage emoji,
        boolean lowercaseStart,
        boolean dropFinalPeriod,
        double skipProbability,
        List<String> catchphrases,
        List<String> interests,
        String timezone
) {

    public enum EmojiUsage { NONE, RARE, OFTEN }

    public PersonaStyle {
        maxSentences = Math.max(1, Math.min(4, maxSentences));
        emoji = emoji != null ? emoji : EmojiUsage.RARE;
        skipProbability = Math.max(0.0, Math.min(1.0, skipProbability));
        catchphrases = catchphrases != null ? List.copyOf(catchphrases) : List.of();
        interests = interests != null ? List.copyOf(interests) : List.of();
        timezone = timezone != null && !timezone.isBlank() ? timezone.trim() : null;
    }

    /** A plain, moderately terse chat participant — used when a persona carries no style metadata. */
    public static PersonaStyle defaults() {
        return new PersonaStyle(2, EmojiUsage.RARE, false, true, 0.0, List.of(), List.of(), null);
    }
}
