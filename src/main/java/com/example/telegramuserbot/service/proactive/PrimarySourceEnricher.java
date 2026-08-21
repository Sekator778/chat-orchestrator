package com.example.telegramuserbot.service.proactive;

import com.example.telegramuserbot.domain.MessageEntity;
import com.example.telegramuserbot.domain.SearchProvider;
import com.example.telegramuserbot.dto.SearchRequestDto;
import com.example.telegramuserbot.dto.SearchResponseDto;
import com.example.telegramuserbot.dto.SearchResponseDto.SearchItemDto;
import com.example.telegramuserbot.service.config.AppSettingsService;
import com.example.telegramuserbot.service.search.SearchProviderService;
import com.example.telegramuserbot.service.search.SearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * PSE Phase-1 — finds the ORIGINAL web source of a harvested Telegram news item and returns a
 * short, citation-safe "primary source" block to graft into the proactive-post USER prompt.
 *
 * <p>Reuses the existing cached search facade ({@link SearchService}, #51) over the Tavily provider
 * (#33). Everything here is FAIL-OPEN: any miss/timeout/error/disabled-flag yields {@code ""}, so
 * enrichment can only improve a post or no-op it — never break the proactive pipeline or drop a post.
 *
 * <p>Cost is bounded by (a) a {@code content_simhash}-keyed in-memory TTL cache so N personas
 * reacting to one story trigger ONE search (hits AND misses are cached), and (b) an own UTC-day
 * search budget (the existing {@code SearchRateLimitService} is a no-op on this path). A result is
 * accepted only through a TRUST GATE (relevance floor + entity overlap + domain denylist + mirror
 * dedup) so the post never cites a source that does not actually match the item.
 *
 * <p>All knobs are read at run time from {@code bot.app_settings} (DB, runtime-tunable).
 */
@Service
public class PrimarySourceEnricher {

    private static final Logger log = LoggerFactory.getLogger(PrimarySourceEnricher.class);

    /** Degenerate hash for media/empty content — never search/cite these. */
    private static final String ALL_ZERO_SIMHASH = "0000000000000000";
    private static final int MIN_CONTENT_LEN = 40;
    private static final int MAX_QUERY_LEN = 480; // SearchRequestDto caps at 500
    private static final String DEFAULT_DENYLIST =
            "t.me,telegram.me,telegram.org,google.com,news.google.com,bing.com,youtube.com,youtu.be";

    /** Distinctive tokens: Capitalized unicode words (len>=3) or numbers (len>=2). */
    private static final Pattern DISTINCTIVE = Pattern.compile(
            "\\b(?:\\p{Lu}\\p{L}{2,}|\\d{2,})\\b", Pattern.UNICODE_CHARACTER_CLASS);

    private final SearchService searchService;
    private final SearchProviderService searchProviderService;
    private final AppSettingsService appSettings;

    /** simhash -> (block, expiresAt). block may be "" (a verified miss is cached too). */
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final AtomicInteger searchesToday = new AtomicInteger(0);
    private volatile LocalDate budgetDay = LocalDate.now(ZoneOffset.UTC);

    public PrimarySourceEnricher(SearchService searchService,
                                 SearchProviderService searchProviderService,
                                 AppSettingsService appSettings) {
        this.searchService = searchService;
        this.searchProviderService = searchProviderService;
        this.appSettings = appSettings;
    }

    private record CacheEntry(String block, Instant expiresAt) {}

    /**
     * @return Mono of a "outlet — snippet" block to append to the prompt, or "" when no trustworthy
     *         source is found / feature off / budget hit / error. Never errors.
     */
    public Mono<String> findPrimarySource(MessageEntity msg, String content) {
        if (!appSettings.getBoolean("news.web-enrich.enabled", false)) {
            return Mono.just("");
        }
        String simhash = msg.getContentSimhash();
        if (simhash == null || simhash.isBlank() || ALL_ZERO_SIMHASH.equals(simhash)) {
            return Mono.just(""); // media/empty content — not enrichable
        }
        if (content == null || content.strip().length() < MIN_CONTENT_LEN) {
            return Mono.just("");
        }

        CacheEntry hit = cache.get(simhash);
        if (hit != null && hit.expiresAt().isAfter(Instant.now())) {
            log.debug("[WebEnrich] cache hit simhash={} (block={})", simhash, hit.block().isEmpty() ? "none" : "yes");
            return Mono.just(hit.block());
        }
        if (!tryConsumeBudget()) {
            log.info("[WebEnrich] daily search budget exhausted — posting without enrichment");
            return Mono.just("");
        }

        String query = buildQuery(content);
        if (query.isBlank()) {
            return Mono.just("");
        }

        double floor = appSettings.getDouble("news.web-enrich.relevance-floor", 0.6);
        int minOverlap = appSettings.getInt("news.web-enrich.min-entity-overlap", 2);
        int snippetMax = appSettings.getInt("news.web-enrich.snippet-max-chars", 400);
        Set<String> denylist = parseCsvDomains(appSettings.getString("news.web-enrich.domain-denylist", DEFAULT_DENYLIST));
        // Authority gate (research's "prefer real outlets, not relevance alone"): when set, ONLY cite
        // these trusted outlets — the proper fix for social/aggregator pages scoring high (e.g. a tweet).
        // Empty = denylist-only behavior (opt-in).
        Set<String> allowlist = parseCsvDomains(appSettings.getString("news.web-enrich.outlet-allowlist", ""));

        // Phase 2: deep search returns the full article body (raw_content). It bypasses the #51 query
        // cache (own SearchProviderService call) — the simhash cache + daily budget bound the cost, and
        // the reply-path / basic cache stay untouched.
        boolean deep = "advanced".equalsIgnoreCase(appSettings.getString("news.web-enrich.depth", "basic"));
        int rawMax = appSettings.getInt("news.web-enrich.raw-content-max-chars", 1600);
        // Advanced search (Tavily search_depth=advanced + raw_content) is much slower than basic.
        // Use a separate, larger timeout for it to avoid false timeouts on a slow but successful response.
        int timeoutMs = deep
                ? appSettings.getInt("news.web-enrich.deep-timeout-ms", 10000)
                : appSettings.getInt("news.web-enrich.timeout-ms", 4000);

        Set<String> itemTokens = distinctiveTokens(content);

        Mono<List<SearchItemDto>> resultsMono;
        if (deep) {
            resultsMono = searchProviderService.searchTavilyDeep(query, 5);
        } else {
            SearchRequestDto req = new SearchRequestDto(query);
            req.setSearchProvider(SearchProvider.TAVILY);
            req.setMaxResults(5);
            req.setForceRefresh(false);
            resultsMono = searchService.search(req)
                    .map(resp -> resp != null && resp.getResults() != null ? resp.getResults() : List.<SearchItemDto>of());
        }

        return resultsMono
                .map(results -> selectAndFormat(results, itemTokens, floor, minOverlap, snippetMax,
                        denylist, allowlist, deep, rawMax))
                .timeout(Duration.ofMillis(timeoutMs))
                .onErrorResume(err -> {
                    log.warn("[WebEnrich] search failed ({}), posting without enrichment", err.toString());
                    return Mono.just("");
                })
                .defaultIfEmpty("")
                .doOnNext(block -> store(simhash, block));
    }

    private String selectAndFormat(List<SearchItemDto> results, Set<String> itemTokens,
                                   double floor, int minOverlap, int snippetMax,
                                   Set<String> denylist, Set<String> allowlist,
                                   boolean deep, int rawMax) {
        if (results == null || results.isEmpty()) {
            return "";
        }
        // Collect all gate-passing candidates, then apply a mild recency tiebreak:
        // among those within 0.1 of the best relevance score, prefer the more recent one.
        List<SearchItemDto> passers = new ArrayList<>();
        List<Double> passerScores = new ArrayList<>();
        Set<String> seenDomains = new HashSet<>();
        for (SearchItemDto r : results) {
            String url = r.getUrl();
            if (url == null || url.isBlank()) continue;
            String domain = registrableDomain(url);
            if (domain == null || denylist.contains(domain)) continue;
            // Authority gate: when an allowlist is configured, only trusted outlets may be cited.
            if (!allowlist.isEmpty() && !allowlist.contains(domain)) continue;
            if (!seenDomains.add(domain)) continue; // mirror dedup — one candidate per outlet
            Double rel = r.getRelevanceScore();
            double relScore = rel != null ? rel : 0.0;
            // Relevance floor only when the provider actually returned a score; otherwise rely on overlap.
            if (rel != null && relScore < floor) continue;
            String hay = safe(r.getTitle()) + " " + safe(r.getSnippet());
            if (countOverlap(itemTokens, hay) < minOverlap) continue;
            passers.add(r);
            passerScores.add(relScore);
        }
        if (passers.isEmpty()) {
            log.info("[WebEnrich] no result passed the trust gate ({} candidates)", results.size());
            return "";
        }
        // Recency tiebreak: anchor the band to the overall max relevance so chaining cannot drift.
        double maxRel = passerScores.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        SearchItemDto best = null;
        double bestScore = -1;
        Instant bestDate = null;
        for (int i = 0; i < passers.size(); i++) {
            SearchItemDto r = passers.get(i);
            double relScore = passerScores.get(i);
            // Only consider candidates that are within 0.1 of the best relevance overall.
            if (relScore < maxRel - 0.1) continue;
            Instant published = parsePublishedDate(r.getPublishedDate());
            // Prefer recency within the band; fall back to relevance when dates are equal/absent.
            boolean preferThis = best == null
                    || (published != null && (bestDate == null || published.isAfter(bestDate)))
                    || (java.util.Objects.equals(published, bestDate) && relScore > bestScore);
            if (preferThis) {
                best = r;
                bestScore = relScore;
                bestDate = published;
            }
        }
        if (best == null) {
            log.info("[WebEnrich] no result passed the trust gate ({} candidates)", results.size());
            return "";
        }
        String outlet = registrableDomain(best.getUrl());
        // Phase 2: prefer the full article body (raw_content) when deep search supplied it; else snippet.
        boolean usedRaw = deep && best.getRawContent() != null && !best.getRawContent().isBlank();
        String text = usedRaw ? best.getRawContent()
                : (!safe(best.getSnippet()).isBlank() ? best.getSnippet() : safe(best.getTitle()));
        int cap = usedRaw ? rawMax : snippetMax;
        text = text.replaceAll("\\s+", " ").strip();
        if (text.length() > cap) {
            text = text.substring(0, cap) + "...";
        }
        log.info("[WebEnrich] source picked outlet={} url={} relevance={} body={}",
                outlet, best.getUrl(), bestScore, usedRaw ? "raw(" + text.length() + ")" : "snippet");
        return outlet + " — " + text;
    }

    private synchronized boolean tryConsumeBudget() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        if (!today.equals(budgetDay)) {
            budgetDay = today;
            searchesToday.set(0);
        }
        int max = appSettings.getInt("news.web-enrich.max-searches-per-day", 50);
        if (searchesToday.get() >= max) {
            return false;
        }
        searchesToday.incrementAndGet();
        return true;
    }

    private void store(String simhash, String block) {
        int ttlMin = appSettings.getInt("news.web-enrich.cache-ttl-min", 720);
        cache.put(simhash, new CacheEntry(block, Instant.now().plus(Duration.ofMinutes(ttlMin))));
        // opportunistic eviction so the map cannot grow unbounded
        if (cache.size() > 5000) {
            Instant now = Instant.now();
            cache.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(now));
        }
    }

    /** First sentence (or ~150 chars) of the news content — the story substance, not the forwarder. */
    static String buildQuery(String content) {
        String c = content.strip().replaceAll("(?s)^\\[Source:[^\\]]*\\]\\s*", "");
        int cut = c.length();
        int scan = Math.min(c.length(), 200);
        for (int i = 0; i < scan; i++) {
            char ch = c.charAt(i);
            if (ch == '\n' || ((ch == '.' || ch == '!' || ch == '?') && i >= 40)) {
                cut = i;
                break;
            }
        }
        String q = c.substring(0, Math.min(cut, 150)).replaceAll("\\s+", " ").strip();
        return q.length() > MAX_QUERY_LEN ? q.substring(0, MAX_QUERY_LEN) : q;
    }

    static Set<String> distinctiveTokens(String text) {
        Set<String> out = new HashSet<>();
        Matcher m = DISTINCTIVE.matcher(text);
        while (m.find()) {
            out.add(m.group().toLowerCase());
        }
        return out;
    }

    static int countOverlap(Set<String> tokens, String haystack) {
        if (tokens.isEmpty() || haystack.isBlank()) return 0;
        String hay = haystack.toLowerCase();
        int n = 0;
        for (String t : tokens) {
            if (Pattern.compile("\\b" + Pattern.quote(t) + "\\b", Pattern.UNICODE_CHARACTER_CLASS)
                    .matcher(hay).find()) {
                n++;
            }
        }
        return n;
    }

    static String registrableDomain(String url) {
        try {
            String u = url.contains("://") ? url : "http://" + url;
            String host = URI.create(u).getHost();
            if (host == null) return null;
            host = host.toLowerCase();
            if (host.startsWith("www.")) host = host.substring(4);
            String[] parts = host.split("\\.");
            return parts.length >= 2 ? parts[parts.length - 2] + "." + parts[parts.length - 1] : host;
        } catch (Exception e) {
            return null;
        }
    }

    private static Set<String> parseCsvDomains(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        return Arrays.stream(csv.split(","))
                .map(s -> s.trim().toLowerCase())
                .filter(s -> !s.isBlank())
                .collect(Collectors.toSet());
    }

    private static String safe(String s) {
        return s != null ? s : "";
    }

    /**
     * Parses a nullable Tavily {@code published_date} string (ISO-8601 date or datetime) into an
     * {@link Instant} for recency comparison. Returns {@code null} when the input is null/blank or
     * cannot be parsed — callers treat {@code null} as "oldest" (no recency preference).
     */
    static Instant parsePublishedDate(String date) {
        if (date == null || date.isBlank()) return null;
        try {
            // Take only the date portion (first 10 chars) to handle both "2026-06-15" and
            // "2026-06-15T12:34:56Z" uniformly; day-granularity is sufficient for a tiebreak.
            String datePart = date.trim().substring(0, Math.min(date.trim().length(), 10));
            return LocalDate.parse(datePart).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (Exception e) {
            return null;
        }
    }
}
