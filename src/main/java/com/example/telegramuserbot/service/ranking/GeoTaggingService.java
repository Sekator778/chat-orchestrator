package com.example.telegramuserbot.service.ranking;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Heuristic geo-scope classifier for news messages.
 *
 * <p>Given the text of a message, returns one of: {@code RU}, {@code UA}, {@code KZ},
 * {@code BY}, {@code US}, {@code EU}, or {@code GLOBAL}.  The classifier detects
 * post-Soviet locales plus US/EU domestic-political/civic scope; everything else
 * (global markets, crypto, Fed/ECB/OPEC, US/EU macro) is classified as {@code GLOBAL}.
 *
 * <p><b>Classification rules (v1):</b>
 * <ul>
 *   <li>Count how many signals fire for each locale.</li>
 *   <li>If exactly ONE locale has at least one signal match and no other locale has any,
 *       return that locale.</li>
 *   <li>If MULTIPLE locales match (e.g. a Russia–Ukraine story), return {@code GLOBAL}
 *       (ambiguous — better to over-broadcast than to mute).</li>
 *   <li>If NO locale matches, return {@code GLOBAL} (default).</li>
 * </ul>
 *
 * <p>Geo is determined by relevance / named entities, NOT by language.
 * Russian text discussing Fed or ECB → {@code GLOBAL}.  English text mentioning
 * "Sberbank" or "Avito" → {@code RU}.  "Fed raises rates" → {@code GLOBAL}.
 * "US Congress passes immigration bill" → {@code US}.  "ECB holds rates" → {@code GLOBAL}.
 * "European Commission fines Apple" → {@code EU}.
 *
 * <p><b>US / EU signal discipline:</b> only LOCAL / political / civic / domestic-regulatory
 * entities are included.  Market/macro signals (Federal Reserve, ECB, SEC, NASDAQ, DOW,
 * Wall Street, dollar, Treasury yields, CPI, eurozone rates, euro currency) are
 * intentionally EXCLUDED — those stories are globally relevant and must stay {@code GLOBAL}.
 *
 * <p>All patterns are compiled once at construction time with
 * {@link Pattern#CASE_INSENSITIVE} and {@link Pattern#UNICODE_CHARACTER_CLASS}.
 * Single-word terms use {@code \b} word-boundaries; multi-word phrases use substring
 * matching (word-boundary on both ends of the first and last token).
 *
 * <p>To extend with more locales: add a new entry to the {@code signals} map in
 * {@link #buildSignals()} following the same pattern.
 */
@Service
public final class GeoTaggingService {

    /** Compiled signals map: locale code → list of compiled patterns. */
    private final Map<String, List<Pattern>> signals;

    public GeoTaggingService() {
        this.signals = buildSignals();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Classifies the geo scope of {@code text}.
     *
     * @param text message content or caption; may be {@code null} or blank
     * @return one of {@code RU}, {@code UA}, {@code KZ}, {@code BY}, {@code US},
     *         {@code EU}, {@code GLOBAL} — never {@code null}
     */
    public String classify(String text) {
        if (text == null || text.isBlank()) {
            return "GLOBAL";
        }

        String matchedLocale = null;
        for (Map.Entry<String, List<Pattern>> entry : signals.entrySet()) {
            String locale = entry.getKey();
            if (hasMatch(text, entry.getValue())) {
                if (matchedLocale == null) {
                    // First locale with a signal
                    matchedLocale = locale;
                } else {
                    // Multiple locales matched — ambiguous
                    return "GLOBAL";
                }
            }
        }
        return matchedLocale != null ? matchedLocale : "GLOBAL";
    }

    // -------------------------------------------------------------------------
    // Signal construction
    // -------------------------------------------------------------------------

    /**
     * Builds the compiled-pattern map.  Each locale maps to a list of {@link Pattern}s;
     * any match in the list is sufficient to count that locale as "signalled".
     *
     * <p>Insertion order: RU → UA → KZ → BY → US → EU (consistent tie-break).
     */
    private static Map<String, List<Pattern>> buildSignals() {
        // LinkedHashMap preserves insertion order (consistent iteration = consistent tie-breaking)
        Map<String, List<Pattern>> map = new LinkedHashMap<>();

        map.put("RU", compile(List.of(
                // Country name / demonym
                "russia", "russian", "россия", "россий", "рф",
                // Government / power centres
                "кремль", "kremlin",
                "госдума", "государственная дума", "state duma",
                // Cities
                "москва", "moscow",
                "санкт-петербург", "st. petersburg",
                // Politicians / officials
                "путин", "putin",
                "набиуллина", "nabiullina",
                // Currency
                "рубль", "ruble", "rubl",
                // Ministries / agencies / regulators
                "минэк", "минэкономразвития", "ministry of economic development",
                "цб рф", "банк россий", "russian central bank",
                "роскомнадзор",
                "минфин россии", "russia's finance ministry",
                // State development bank
                "вэб.рф",
                // Major companies / platforms
                "сбербанк", "sberbank",
                "газпром", "gazprom",
                "роснефть", "rosneft",
                "авито", "avito",
                "яндекс", "yandex",
                "вконтакте", "vkontakte",
                "т-банк", "тинькофф", "tinkoff",
                "ozon", "озон",
                "ростех", "rostec",
                "лукойл", "lukoil",
                "новатэк", "novatek",
                // Indices / exchanges
                "мосбиржа", "moex",
                // Culture / tourism
                "золотое кольцо", "golden ring",
                // Think-tanks / bodies
                "цср"
        )));

        map.put("UA", compile(List.of(
                // Country name / demonym
                "ukraine", "ukrainian", "україна", "украина", "украин",
                // Cities
                "київ", "киев", "kyiv", "kiev",
                "одесса", "odesa",
                "харьков", "kharkiv",
                "львов", "lviv",
                // Currency
                "гривна", "hryvnia", "hryvn",
                // Politicians
                "зеленский", "zelensky", "зеленськ",
                // Parliament / central bank
                "верховна рада", "нбу україни", "нбу",
                // Key state companies
                "укрэнерго", "ukrenergo",
                "нафтогаз", "naftogaz",
                "приватбанк", "privatbank"
        )));

        map.put("KZ", compile(List.of(
                // Country name / demonym
                "kazakhstan", "казахстан",
                // Cities
                "astana", "астана", "almaty", "алматы",
                "карагандa", "шымкент",
                // Currency
                "тенге", "tenge",
                // Politicians
                "назарбаев", "nazarbayev", "токаев", "tokayev",
                // Key companies / banks
                "казатомпром", "kazatomprom",
                "халык банк", "halyk",
                "kaspi", "каспи"
        )));

        map.put("BY", compile(List.of(
                // Country name / demonym
                "belarus", "belarusian", "беларусь", "белоруссия", "белорус",
                // Cities
                "минск", "minsk",
                // Politicians
                "лукашенко", "lukashenko",
                // Currency
                "белорусский рубль",
                // Key companies / bodies
                "беларуськалий", "belaruskali",
                "белнефтехим",
                "пвт", "hi-tech park minsk"
        )));

        // US: LOCAL / political / civic / domestic-regulatory signals ONLY.
        // EXCLUDED (intentionally, stay GLOBAL): Federal Reserve, the Fed, SEC, NASDAQ,
        // DOW, Wall Street, dollar, Treasury yields, CPI, inflation (US macro/market).
        map.put("US", compile(List.of(
                // Legislative bodies
                "u.s. congress", "us congress",
                "house of representatives",
                "u.s. senate", "us senate",
                // Executive / judicial
                "white house",
                "capitol hill",
                "pentagon",
                "u.s. supreme court", "us supreme court", "scotus",
                "department of justice",
                // Elections / domestic politics
                "us election", "u.s. election",
                "u.s. presidential", "us presidential",
                // States / DC (domestic-political contexts only — city names alone are global)
                "washington d.c.",
                "california",
                "texas",
                "new york state",
                "florida"
        )));

        // EU: LOCAL / political / civic / domestic-regulatory signals ONLY.
        // EXCLUDED (intentionally, stay GLOBAL): ECB, eurozone, euro (currency),
        // euro area rates, euro area inflation (EU macro/market).
        map.put("EU", compile(List.of(
                // EU institutions
                "european commission",
                "european parliament",
                "eu council",
                "brussels",
                // Key political figures / documents
                "von der leyen",
                // Regulatory / legal instruments
                "schengen",
                "eu directive",
                "eu regulation"
        )));

        return map;
    }

    /**
     * Compiles a list of raw term strings into regex patterns.
     *
     * <p>Single-word terms (no space, no apostrophe-space boundary) use {@code \b...\b}
     * for word-boundary matching.  Multi-word phrases (containing a space) use a
     * looser pattern: {@code (?:^|\s|[^а-яёa-z0-9])PHRASE(?:$|\s|[^а-яёa-z0-9])} which
     * functionally matches on non-alphanumeric boundaries — avoids false positives while
     * still being unicode-aware.
     */
    private static List<Pattern> compile(List<String> terms) {
        return terms.stream()
                .filter(t -> t != null && !t.isBlank())
                .map(t -> {
                    String quoted = Pattern.quote(t.trim());
                    String regex = t.contains(" ")
                            ? "(?:^|\\s|[^а-яёa-zA-Z0-9])" + quoted + "(?:$|\\s|[^а-яёa-zA-Z0-9])"
                            : "\\b" + quoted + "\\b";
                    return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
                })
                .toList();
    }

    /** Returns {@code true} if any pattern in {@code patterns} matches {@code text}. */
    private static boolean hasMatch(String text, List<Pattern> patterns) {
        for (Pattern p : patterns) {
            if (p.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }
}
