package com.example.telegramuserbot.service.common;

import java.util.Locale;

/**
 * The one place that decides what a language hint means for the reply path.
 *
 * <p>Hints come from {@code chat_configs.language} (the chat's audience), persona
 * bundles ({@code bot_personas.language}) and the {@code "auto"} placeholder. They
 * are normalised to the three languages the persona bundles ship in, plus
 * {@link #AUTO} ("answer in the language of the message you are replying to").
 */
public final class ReplyLanguage {

    public static final String RU = "ru";
    public static final String UK = "uk";
    public static final String EN = "en";
    public static final String AUTO = "auto";

    private ReplyLanguage() {
    }

    /**
     * Normalises a raw hint to {@code ru}, {@code uk}, {@code en} or {@code auto}.
     * Unknown or blank hints resolve to {@code auto}; {@code base} (the language-less
     * persona bundle) resolves to {@code ru}, which is what every base bundle is written in.
     */
    public static String normalize(String hint) {
        if (hint == null || hint.isBlank()) {
            return AUTO;
        }
        String h = hint.trim().toLowerCase(Locale.ROOT);
        if (h.startsWith("ru")) {
            return RU;
        }
        if (h.startsWith("uk") || h.startsWith("ua")) {
            return UK;
        }
        if (h.startsWith("en")) {
            return EN;
        }
        if (h.equals("base")) {
            return RU;
        }
        return AUTO;
    }

    /** True when the hint is a concrete language rather than {@link #AUTO}. */
    public static boolean isConcrete(String normalized) {
        return RU.equals(normalized) || UK.equals(normalized) || EN.equals(normalized);
    }

    /**
     * The language the scaffolding text (instructions around the persona) should be
     * written in. {@code auto} falls back to English, which is the neutral instruction
     * language for a model that must then mirror whatever language the chat uses.
     */
    public static String instructionLanguage(String normalized) {
        return isConcrete(normalized) ? normalized : EN;
    }
}
