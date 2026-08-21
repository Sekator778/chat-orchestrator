package com.example.telegramuserbot.service.ranking;

import com.example.telegramuserbot.repository.SearchKeywordRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * F3 — Matches message text against the enabled keywords in {@code tgscan.search_keywords}.
 *
 * <p>On startup and every ~20 min, the service reloads the enabled keyword list and
 * compiles a case-insensitive, unicode-aware word-boundary {@link Pattern} for each keyword.
 * Multi-word keywords (e.g. "hedge funds", "фондовый рынок") and Cyrillic word boundaries
 * are handled correctly via {@link Pattern#UNICODE_CHARACTER_CLASS}.
 *
 * <p>The pattern cache is stored in a {@code volatile} field and swapped atomically on
 * each refresh, making {@link #match(String)} lock-free on the hot path.
 * A missing or empty keyword table is tolerated — {@link #match(String)} returns an
 * empty array until the cache is populated.
 */
@Service
public final class KeywordMatchingService {

    private static final Logger log = LoggerFactory.getLogger(KeywordMatchingService.class);

    /** Refresh cadence (ms): 20 min, matching the app-settings cache TTL. */
    private static final long REFRESH_MS = 20 * 60 * 1000L;

    private final SearchKeywordRepository searchKeywordRepository;

    /** Immutable compiled keyword list; swapped atomically on refresh. */
    private volatile List<CompiledKeyword> cache = List.of();

    public KeywordMatchingService(SearchKeywordRepository searchKeywordRepository) {
        this.searchKeywordRepository = searchKeywordRepository;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Matches {@code text} against all cached enabled keywords.
     *
     * @param text the message text (content or caption); may be {@code null}
     * @return distinct keyword strings (canonical form) that were found, never {@code null}
     */
    public String[] match(String text) {
        if (text == null || text.isBlank()) {
            return new String[0];
        }
        List<CompiledKeyword> current = cache;
        if (current.isEmpty()) {
            return new String[0];
        }
        Set<String> matched = new LinkedHashSet<>();
        for (CompiledKeyword ck : current) {
            if (ck.pattern().matcher(text).find()) {
                matched.add(ck.keyword());
            }
        }
        return matched.toArray(new String[0]);
    }

    /**
     * Returns the number of cached compiled keywords (useful for health/diagnostics).
     */
    public int cacheSize() {
        return cache.size();
    }

    // -------------------------------------------------------------------------
    // Cache lifecycle
    // -------------------------------------------------------------------------

    /** Eagerly loads the keyword cache on startup; best-effort, non-blocking. */
    @PostConstruct
    public void loadOnStartup() {
        refreshCache();
    }

    /** TTL refresh so newly-added / disabled keywords are picked up automatically. */
    @Scheduled(fixedDelay = REFRESH_MS, initialDelay = REFRESH_MS)
    public void scheduledRefresh() {
        refreshCache();
    }

    /**
     * Reloads enabled keywords from DB and recompiles their patterns.
     * On error the previous cache is retained.
     */
    public void refreshCache() {
        searchKeywordRepository.findAllEnabled()
                .collectList()
                .subscribe(
                        keywords -> {
                            List<CompiledKeyword> compiled = new ArrayList<>(keywords.size());
                            for (var kw : keywords) {
                                String raw = kw.getKeyword();
                                if (raw == null || raw.isBlank()) {
                                    continue;
                                }
                                try {
                                    Pattern p = Pattern.compile(
                                            "\\b" + Pattern.quote(raw) + "\\b",
                                            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CHARACTER_CLASS);
                                    compiled.add(new CompiledKeyword(raw, p));
                                } catch (Exception e) {
                                    log.warn("[KeywordMatching] Failed to compile pattern for keyword '{}': {}",
                                            raw, e.getMessage());
                                }
                            }
                            cache = List.copyOf(compiled);
                            log.info("[KeywordMatching] Cache refreshed: {} enabled keyword(s) compiled", compiled.size());
                        },
                        error -> log.warn("[KeywordMatching] Cache refresh failed — keeping last snapshot ({} keyword(s)): {}",
                                cache.size(), error.getMessage())
                );
    }

    // -------------------------------------------------------------------------
    // Internal record
    // -------------------------------------------------------------------------

    /** Associates the canonical keyword string with its precompiled {@link Pattern}. */
    record CompiledKeyword(String keyword, Pattern pattern) {
    }
}
