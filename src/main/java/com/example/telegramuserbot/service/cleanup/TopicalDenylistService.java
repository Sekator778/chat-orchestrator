package com.example.telegramuserbot.service.cleanup;

import com.example.telegramuserbot.service.config.AppSettingsService;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Single source of truth for the topical denylist that gates channel joins and off-topic cleanup.
 *
 * <p>The token list is read at <em>call time</em> from {@code bot.app_settings} key
 * {@code discovery.join.title-denylist} so the owner can edit it in the DB without a redeploy
 * ({@link AppSettingsService} TTL-caches the table ~20 min). When the setting is absent, the
 * compile-time {@link #DEFAULT_TITLE_DENYLIST} is used as the fallback.
 *
 * <p>Tokens are comma- or newline-separated, matched case-insensitively against the supplied
 * text using {@link String#contains}. An empty/blank setting disables the gate entirely —
 * existing behavior is preserved when no tokens match.
 */
@Service
public class TopicalDenylistService {

    static final String SETTING_KEY = "discovery.join.title-denylist";

    /**
     * Conservative default covering the junk categories observed in the audit:
     * airdrop/farm spam, memepad, channel-sale brokers, ad agencies, real-estate, and
     * "халява" (freebie) channels. Tokens are lowercase so they match both Cyrillic and Latin.
     * Legit crypto/finance channels are NOT matched by these tokens.
     */
    public static final String DEFAULT_TITLE_DENYLIST =
            "airdrop,аирдроп,аірдроп," +
            "farm,фарм," +
            "халяв," +
            "memepad,мемпад," +
            "продам канал,куплю канал,продажа канал,продати канал,купити канал," +
            "биржа каналов,біржа каналів,биржа tumobog,toba agency," +
            "агентство медиа,агентство медіа,веб3 агентство," +
            "недвиж,нерухом," +
            "циан," +
            "пхукет";

    private final AppSettingsService appSettings;

    public TopicalDenylistService(AppSettingsService appSettings) {
        this.appSettings = appSettings;
    }

    /**
     * Returns the first denylist token that matches any of the supplied text fields,
     * or {@link Optional#empty()} when no token matches (channel is allowed to join).
     *
     * <p>All inputs are treated as optional — null or blank texts are safely skipped.
     * The gate is a no-op when the effective token list is empty.
     *
     * @param texts one or more text fields to check (title, username, description, …)
     * @return the first matched token, or empty when no match
     */
    public Optional<String> matchedToken(String... texts) {
        List<String> tokens = parseTokens(
                appSettings.getString(SETTING_KEY, DEFAULT_TITLE_DENYLIST));
        if (tokens.isEmpty()) {
            return Optional.empty();
        }

        for (String text : texts) {
            if (text == null || text.isBlank()) {
                continue;
            }
            String lower = text.toLowerCase(Locale.ROOT);
            for (String token : tokens) {
                if (lower.contains(token)) {
                    return Optional.of(token);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Parses a comma- or newline-separated token list into lowercase trimmed tokens.
     * Returns an empty list when the input is null/blank (gate disabled).
     */
    static List<String> parseTokens(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split("[,\n]+"))
                .map(String::trim)
                .filter(t -> !t.isBlank())
                .map(t -> t.toLowerCase(Locale.ROOT))
                .toList();
    }
}
