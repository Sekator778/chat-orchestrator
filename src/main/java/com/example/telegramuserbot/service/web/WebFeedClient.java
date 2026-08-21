package com.example.telegramuserbot.service.web;

import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Reactive RSS/Atom feed client for the web-collector epic.
 *
 * <p>Fetches the raw feed bytes over HTTP and parses them with
 * <a href="https://rometools.github.io/rome/">Rome</a> on a {@code boundedElastic} thread
 * (Rome's {@link SyndFeedInput} / {@link XmlReader} are blocking).
 *
 * <p>Raw bytes rather than a decoded {@code String} are used so that
 * Rome's {@link XmlReader} can honour the XML prolog charset declaration
 * (e.g. {@code windows-1251} for many RU/UK feeds), which the HTTP
 * {@code Content-Type} header often omits.
 *
 * <p><b>Fail-open:</b> any fetch or parse error is caught at the tail of the
 * reactive chain, logged at WARN level, and returns {@link Flux#empty()} so that
 * one bad feed never breaks a harvest run.
 */
@Service
public class WebFeedClient {

    private static final Logger log = LoggerFactory.getLogger(WebFeedClient.class);

    /** Per-request fetch + parse timeout. */
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(15);

    private static final String USER_AGENT = "TelegramUserBot-WebCollector/1.0";

    private final WebClient webClient;

    public WebFeedClient(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder
                .defaultHeader(HttpHeaders.USER_AGENT, USER_AGENT)
                .build();
    }

    /**
     * Fetches and parses the RSS/Atom feed at {@code feedUrl}, emitting one
     * {@link WebFeedItem} per entry.
     *
     * <p>On any error (network, timeout, malformed XML, etc.) a WARN is logged
     * and {@link Flux#empty()} is returned — callers never see an error signal.
     *
     * @param feedUrl absolute URL of the RSS or Atom feed
     * @return cold {@link Flux} of parsed items, possibly empty
     */
    public Flux<WebFeedItem> fetch(String feedUrl) {
        return webClient.get()
                .uri(feedUrl)
                .retrieve()
                .bodyToMono(byte[].class)
                .timeout(FETCH_TIMEOUT)
                .flatMapMany(bytes -> Mono.fromCallable(() -> parseFeed(feedUrl, bytes))
                        .subscribeOn(Schedulers.boundedElastic())
                        .flatMapMany(Flux::fromIterable))
                .onErrorResume(ex -> {
                    log.warn("[WebFeedClient] Failed to fetch/parse feed '{}': {}", feedUrl, ex.toString());
                    return Flux.empty();
                });
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Blocking Rome parse — must be called on a boundedElastic thread.
     */
    private List<WebFeedItem> parseFeed(String feedUrl, byte[] bytes) throws Exception {
        SyndFeedInput input = new SyndFeedInput();
        try (XmlReader reader = new XmlReader(new ByteArrayInputStream(bytes))) {
            SyndFeed feed = input.build(reader);
            return feed.getEntries().stream()
                    .map(WebFeedClient::toItem)
                    .collect(Collectors.toList());
        }
    }

    private static WebFeedItem toItem(SyndEntry entry) {
        String title   = entry.getTitle();
        String link    = entry.getLink();
        String summary = extractSummary(entry);

        Instant publishedAt;
        if (entry.getPublishedDate() != null) {
            publishedAt = entry.getPublishedDate().toInstant();
        } else if (entry.getUpdatedDate() != null) {
            publishedAt = entry.getUpdatedDate().toInstant();
        } else {
            publishedAt = Instant.now();
        }

        return new WebFeedItem(title, link, summary, publishedAt);
    }

    /**
     * Returns the best available text: prefers description/summary, falls back to
     * the first content block, then {@code null}.
     */
    private static String extractSummary(SyndEntry entry) {
        if (entry.getDescription() != null && entry.getDescription().getValue() != null) {
            return entry.getDescription().getValue();
        }
        List<SyndContent> contents = entry.getContents();
        if (contents != null && !contents.isEmpty() && contents.get(0).getValue() != null) {
            return contents.get(0).getValue();
        }
        return null;
    }
}
