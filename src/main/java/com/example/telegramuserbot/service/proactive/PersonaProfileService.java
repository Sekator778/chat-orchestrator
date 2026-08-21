package com.example.telegramuserbot.service.proactive;

import com.example.telegramuserbot.domain.BotPersona;
import com.example.telegramuserbot.domain.DigestPersona;
import com.example.telegramuserbot.repository.BotPersonaRepository;
import com.example.telegramuserbot.repository.DigestPersonaRepository;
import com.example.telegramuserbot.service.embedding.EmbeddingClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A-T2 — Derives and caches a per-persona INTEREST PROFILE vector for semantic candidate ranking.
 *
 * <p>The profile text is built from:
 * <ul>
 *   <li>{@code bot_personas.{description, behavior, traits}} (the "legend" for the bot account)</li>
 *   <li>{@code digest_personas.{custom_system_prompt, topic_keywords}} (the proactive persona config)</li>
 * </ul>
 * The text is embedded via {@link EmbeddingClient} (1024-dim cosine space, BAAI/bge-m3).
 * The resulting vector is kept in a {@link ConcurrentHashMap} — one entry per botId — and is
 * evicted per-persona by {@link #invalidate(String)} (called on each persona edit), or in bulk by
 * {@link #invalidateAll()} (hooked into {@code POST /api/admin/cache/refresh}).
 *
 * <p><b>Fail-open:</b> if {@link EmbeddingClient#embed} returns empty (TEI service down or
 * any transient error), {@link #getProfileVector(String, DigestPersona)} returns
 * {@link Mono#empty()}.  All callers MUST treat empty as "fall back to value-only ranking" —
 * never throw, never block posting.
 */
@Service
public class PersonaProfileService {

    private static final Logger log = LoggerFactory.getLogger(PersonaProfileService.class);

    /** Max characters from a single field — keeps token count bounded. */
    private static final int MAX_FIELD_CHARS = 1000;

    private final EmbeddingClient embeddingClient;
    private final BotPersonaRepository botPersonaRepository;
    private final DigestPersonaRepository digestPersonaRepository;

    /** In-memory cache: botId (String) → profile vector. Evicted by {@link #invalidate(String)} on persona edit, or {@link #invalidateAll()} on /api/admin/cache/refresh. */
    private final Map<String, float[]> vectorCache = new ConcurrentHashMap<>();

    public PersonaProfileService(EmbeddingClient embeddingClient,
                                  BotPersonaRepository botPersonaRepository,
                                  DigestPersonaRepository digestPersonaRepository) {
        this.embeddingClient       = embeddingClient;
        this.botPersonaRepository  = botPersonaRepository;
        this.digestPersonaRepository = digestPersonaRepository;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns the persona's interest-profile vector, computing and caching it on first call.
     *
     * <p>The {@code digestPersona} argument is supplied by the caller (already fetched) to avoid
     * a redundant DB round-trip; we only need a secondary DB fetch for the {@code bot_personas} legend.
     *
     * @param botId          the string bot-id (matches {@code bot_personas.bot_id})
     * @param digestPersona  the {@code digest_personas} row driving this proactive tick
     * @return {@code Mono<float[]>} (1024-dim, bge-m3) or {@link Mono#empty()} if vectors are unavailable
     */
    public Mono<float[]> getProfileVector(String botId, DigestPersona digestPersona) {
        float[] cached = vectorCache.get(botId);
        if (cached != null) {
            log.debug("[PersonaProfile] Cache hit for botId={}", botId);
            return Mono.just(cached);
        }

        return buildProfileText(botId, digestPersona)
                .flatMap(profileText -> {
                    if (profileText == null || profileText.isBlank()) {
                        log.debug("[PersonaProfile] Empty profile text for botId={} — skipping embed", botId);
                        return Mono.<float[]>empty();
                    }
                    log.debug("[PersonaProfile] Embedding profile for botId={} ({} chars)", botId, profileText.length());
                    return embeddingClient.embed(profileText)
                            .doOnNext(vec -> {
                                vectorCache.put(botId, vec);
                                log.info("[PersonaProfile] Cached profile vector for botId={} ({}d)", botId, vec.length);
                            });
                })
                .onErrorResume(ex -> {
                    log.warn("[PersonaProfile] Failed to derive profile vector for botId={}: {} — skipping",
                            botId, ex.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * Evicts the cached profile vector for a single persona.
     * Called whenever a persona's description/behavior/traits or
     * {@code digest_personas} fields are edited, so the next proactive tick
     * re-embeds the fresh profile.
     *
     * @param botId the string bot-id whose entry should be removed; no-op if null or not cached
     */
    public void invalidate(String botId) {
        if (botId == null) return;
        if (vectorCache.remove(botId) != null) {
            log.info("[PersonaProfile] Evicted cached profile vector for botId={}", botId);
        }
    }

    /**
     * Evicts all cached profile vectors.  Called from {@code AdminCacheController.refresh()}
     * (bulk reset); for single-persona edits prefer {@link #invalidate(String)}.
     */
    public void invalidateAll() {
        int count = vectorCache.size();
        vectorCache.clear();
        log.info("[PersonaProfile] Cache invalidated ({} entries evicted)", count);
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    /**
     * Assembles the profile text from both persona tables.
     *
     * <p>Fields appended (in order, blank ones skipped):
     * <ol>
     *   <li>bot_personas.description</li>
     *   <li>bot_personas.behavior</li>
     *   <li>bot_personas.traits</li>
     *   <li>digest_personas.custom_system_prompt</li>
     *   <li>digest_personas.topic_keywords (joined by ", ")</li>
     * </ol>
     */
    private Mono<String> buildProfileText(String botId, DigestPersona digestPersona) {
        // Fetch the bot_personas legend for this botId — prefer the language row that matches
        // the digest persona's language, fall back to the first row found.
        String targetLang = digestPersona.language();

        return botPersonaRepository.findByBotId(botId)
                .collectList()
                .map(rows -> pickBestBotPersonaRow(rows, targetLang))
                .map(botPersona -> {
                    StringBuilder sb = new StringBuilder();

                    // From bot_personas
                    appendField(sb, botPersona != null ? botPersona.getDescription() : null, "description");
                    appendField(sb, botPersona != null ? botPersona.getBehavior()    : null, "behavior");
                    appendField(sb, botPersona != null ? botPersona.getTraits()      : null, "traits");

                    // From digest_personas
                    appendField(sb, digestPersona.customSystemPrompt(), "system_prompt");
                    if (digestPersona.topicKeywords() != null && digestPersona.topicKeywords().length > 0) {
                        String kwLine = String.join(", ", digestPersona.topicKeywords());
                        appendField(sb, kwLine, "topic_keywords");
                    }

                    return sb.toString().trim();
                });
    }

    private BotPersona pickBestBotPersonaRow(List<BotPersona> rows, String targetLang) {
        if (rows == null || rows.isEmpty()) return null;
        // Prefer an exact language match (e.g. "ru"), then "base", then the first row.
        if (targetLang != null) {
            for (BotPersona bp : rows) {
                if (targetLang.equalsIgnoreCase(bp.getLanguage())) return bp;
            }
        }
        for (BotPersona bp : rows) {
            if ("base".equalsIgnoreCase(bp.getLanguage())) return bp;
        }
        return rows.get(0);
    }

    private void appendField(StringBuilder sb, String value, String label) {
        if (value == null || value.isBlank()) return;
        String truncated = value.length() > MAX_FIELD_CHARS ? value.substring(0, MAX_FIELD_CHARS) : value;
        if (sb.length() > 0) sb.append('\n');
        sb.append(truncated);
    }
}
