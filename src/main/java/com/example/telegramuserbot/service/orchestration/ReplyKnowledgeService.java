package com.example.telegramuserbot.service.orchestration;

import com.example.telegramuserbot.domain.MessageEntity;
import com.example.telegramuserbot.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Supplies the background-knowledge block for reply prompts: recent
 * cluster-primary messages from the shared intelligence base, preferring items
 * topically relevant to the current conversation. Pure DB read — no LLM calls.
 * Disabled by default; empty result on any error so a knowledge hiccup can
 * never block a reply.
 */
@Service
public class ReplyKnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(ReplyKnowledgeService.class);
    private static final int MIN_TERM_LENGTH = 5;
    private static final int MAX_TERMS = 8;

    private final MessageRepository messageRepository;

    @Value("${prompt.knowledge.enabled:false}")
    private boolean enabled;
    @Value("${prompt.knowledge.window-hours:24}")
    private int windowHours;
    @Value("${prompt.knowledge.max-items:5}")
    private int maxItems;
    @Value("${prompt.knowledge.max-item-chars:200}")
    private int maxItemChars;
    @Value("${prompt.knowledge.candidate-multiplier:4}")
    private int candidateMultiplier;

    private final com.example.telegramuserbot.service.search.TavilyEnrichmentService tavilyEnrichmentService;

    public ReplyKnowledgeService(MessageRepository messageRepository,
                                 com.example.telegramuserbot.service.search.TavilyEnrichmentService tavilyEnrichmentService) {
        this.messageRepository = messageRepository;
        this.tavilyEnrichmentService = tavilyEnrichmentService;
    }

    /** Back-compat: no conversation signal, no chat scope → fall back to chatId=0 (no self-exclude). */
    public Mono<String> buildKnowledgeBlock() {
        return buildKnowledgeBlock(null, 0L);
    }

    /** Back-compat: conversation text known but chat ID not yet threaded through caller. */
    public Mono<String> buildKnowledgeBlock(String chatText) {
        return buildKnowledgeBlock(chatText, 0L);
    }

    /**
     * @param chatText recent conversation text used to bias selection toward the
     *                 current topic; null/blank → global top-N by importance
     * @param chatId   the requesting chat's ID; messages from this chat are excluded
     *                 from the knowledge candidates to prevent self-echo and cross-chat leakage
     * @return newline-separated knowledge items, or empty Mono when disabled,
     *         nothing is known, or the lookup fails
     */
    public Mono<String> buildKnowledgeBlock(String chatText, long chatId) {
        if (!enabled) {
            return Mono.empty();
        }
        Instant since = Instant.now().minus(Duration.ofHours(windowHours));
        int candidateLimit = Math.max(maxItems, maxItems * Math.max(1, candidateMultiplier));
        Set<String> terms = extractTerms(chatText);
        Mono<String> dbBlock = messageRepository.findPrimaryMessagesForDigest(since, chatId, candidateLimit)
                .collectList()
                .map(candidates -> rankByTopic(candidates, terms))
                .map(items -> String.join("\n", items))
                .onErrorResume(e -> {
                    log.warn("Knowledge block lookup failed (replying without it): {}", e.getMessage());
                    return Mono.just("");
                });
        // Web enrichment (Tavily) is a no-op unless explicitly enabled + keyed.
        Mono<String> webBlock = tavilyEnrichmentService.enrich(chatText).defaultIfEmpty("");
        return Mono.zip(dbBlock, webBlock)
                .map(t -> combine(t.getT1(), t.getT2()))
                .filter(block -> !block.isBlank());
    }

    private String combine(String dbBlock, String webBlock) {
        if (webBlock == null || webBlock.isBlank()) {
            return dbBlock;
        }
        return dbBlock.isBlank() ? webBlock : dbBlock + "\n" + webBlock;
    }

    /**
     * Keeps the candidates overlapping the conversation terms (best first); when
     * none overlap, falls back to the importance order the query already gave us.
     */
    private List<String> rankByTopic(List<MessageEntity> candidates, Set<String> terms) {
        List<String> ranked = new ArrayList<>();
        if (!terms.isEmpty()) {
            ranked = candidates.stream()
                    .map(m -> new ScoredItem(toItem(m), topicScore(m, terms)))
                    .filter(s -> s.score > 0 && !s.item.isBlank())
                    .sorted(Comparator.comparingInt((ScoredItem s) -> s.score).reversed())
                    .limit(maxItems)
                    .map(s -> s.item)
                    .toList();
        }
        if (ranked.isEmpty()) {
            ranked = candidates.stream()
                    .map(this::toItem)
                    .filter(item -> !item.isBlank())
                    .limit(maxItems)
                    .toList();
        }
        return ranked;
    }

    private int topicScore(MessageEntity message, Set<String> terms) {
        String text = (message.getContent() != null ? message.getContent() : message.getCaption());
        if (text == null) {
            return 0;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        int score = 0;
        for (String term : terms) {
            if (lower.contains(term)) {
                score++;
            }
        }
        return score;
    }

    private Set<String> extractTerms(String chatText) {
        Set<String> terms = new LinkedHashSet<>();
        if (chatText == null || chatText.isBlank()) {
            return terms;
        }
        for (String token : chatText.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{Nd}]+")) {
            if (token.length() >= MIN_TERM_LENGTH) {
                terms.add(token);
                if (terms.size() >= MAX_TERMS) {
                    break;
                }
            }
        }
        return terms;
    }

    private record ScoredItem(String item, int score) {
    }

    private String toItem(MessageEntity message) {
        String text = message.getContent() != null && !message.getContent().isBlank()
                ? message.getContent()
                : message.getCaption();
        if (text == null) {
            return "";
        }
        String singleLine = text.replaceAll("\\s+", " ").trim();
        return singleLine.length() > maxItemChars
                ? singleLine.substring(0, maxItemChars) + "…"
                : singleLine;
    }
}
