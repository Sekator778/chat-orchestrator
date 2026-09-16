package com.example.telegramuserbot.service.orchestration;

import com.example.telegramuserbot.domain.MessageEntity;
import com.example.telegramuserbot.repository.MessageRepository;
import com.example.telegramuserbot.service.humanization.PersonaService;
import com.example.telegramuserbot.service.telegram.TelegramSelfUserIdResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Resolves whether a trigger message directly addresses one specific persona among
 * the dispatch candidates — by reply-to or by @mention/first-name in the text.
 * <p>
 * Owner rule (2026-06-10): a persona that is directly addressed ALWAYS replies,
 * bypassing the per-persona probability roll, and the other candidates stay quiet.
 * <p>
 * Rules, first hit wins:
 * <ol>
 *   <li>reply-to: the replied message is outgoing and was sent by one of the candidates;</li>
 *   <li>@username mention, unique among candidates;</li>
 *   <li>first-name whole-word mention (length &gt;= 3), unique among candidates.</li>
 * </ol>
 * Ambiguity (two or more candidates match the same rule) resolves to "nobody addressed"
 * rather than falling through to the next rule — an ambiguous mention is not evidence
 * for a single persona. Everything is fail-open: any lookup error resolves to
 * {@link Optional#empty()} so a broken lookup never blocks the whole fan-out.
 */
@Component
public class PersonaAddressResolver {

    private static final Logger log = LoggerFactory.getLogger(PersonaAddressResolver.class);

    // Unicode-aware whole-word boundary: not preceded/followed by a letter, digit or
    // underscore. Deliberately looser than \b (which is ASCII-only) so a Cyrillic name
    // like "Максим" does not match inside "Максимум".
    private static final int WORD_BOUNDARY_FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    private static final int MIN_FIRST_NAME_LENGTH = 3;

    private final MessageRepository messageRepository;
    private final TelegramSelfUserIdResolver selfUserIdResolver;
    private final PersonaService personaService;

    public PersonaAddressResolver(MessageRepository messageRepository,
                                   TelegramSelfUserIdResolver selfUserIdResolver,
                                   PersonaService personaService) {
        this.messageRepository = messageRepository;
        this.selfUserIdResolver = selfUserIdResolver;
        this.personaService = personaService;
    }

    public Mono<Optional<String>> resolveAddressed(MessageEntity trigger, List<String> candidateBotIds) {
        if (trigger == null || candidateBotIds == null || candidateBotIds.isEmpty()) {
            return Mono.just(Optional.empty());
        }
        return resolveByReplyTo(trigger, candidateBotIds)
                .map(Optional::of)
                .switchIfEmpty(Mono.defer(() -> resolveByMention(trigger, candidateBotIds)))
                .defaultIfEmpty(Optional.empty())
                .onErrorResume(error -> {
                    log.debug("[Address] Resolution failed for chat={} message={}: {}",
                            trigger.getChatId(), trigger.getMessageId(), error.getMessage());
                    return Mono.just(Optional.empty());
                });
    }

    private Mono<String> resolveByReplyTo(MessageEntity trigger, List<String> candidateBotIds) {
        Long replyToId = trigger.getReplyToMessageId();
        if (replyToId == null) {
            return Mono.empty();
        }
        return messageRepository.findByChatIdAndMessageId(trigger.getChatId(), replyToId)
                .filter(MessageEntity::isOutgoing)
                .mapNotNull(MessageEntity::getReceivedByBotId)
                .filter(candidateBotIds::contains)
                .doOnNext(botId -> log.debug("[Address] Chat {} message {} addresses {} (reply-to {})",
                        trigger.getChatId(), trigger.getMessageId(), botId, replyToId))
                .onErrorResume(error -> {
                    log.debug("[Address] reply-to lookup failed for chat={} replyTo={}: {}",
                            trigger.getChatId(), replyToId, error.getMessage());
                    return Mono.empty();
                });
    }

    private Mono<Optional<String>> resolveByMention(MessageEntity trigger, List<String> candidateBotIds) {
        String text = trigger.getContent() != null && !trigger.getContent().isBlank()
                ? trigger.getContent() : trigger.getCaption();
        if (text == null || text.isBlank()) {
            return Mono.just(Optional.empty());
        }
        return matchByUsername(text, candidateBotIds)
                .flatMap(usernameStep -> {
                    if (usernameStep.matched()) {
                        log.debug("[Address] Chat {} message {} addresses {} (@mention)",
                                trigger.getChatId(), trigger.getMessageId(), usernameStep.botId());
                        return Mono.just(Optional.of(usernameStep.botId()));
                    }
                    if (usernameStep.ambiguous()) {
                        log.debug("[Address] Chat {} message {}: ambiguous @mention, no persona addressed",
                                trigger.getChatId(), trigger.getMessageId());
                        return Mono.just(Optional.<String>empty());
                    }
                    return matchByFirstName(text, candidateBotIds).map(firstNameStep -> {
                        if (firstNameStep.matched()) {
                            log.debug("[Address] Chat {} message {} addresses {} (first-name mention)",
                                    trigger.getChatId(), trigger.getMessageId(), firstNameStep.botId());
                            return Optional.of(firstNameStep.botId());
                        }
                        if (firstNameStep.ambiguous()) {
                            log.debug("[Address] Chat {} message {}: ambiguous first-name mention, no persona addressed",
                                    trigger.getChatId(), trigger.getMessageId());
                        }
                        return Optional.<String>empty();
                    });
                });
    }

    private Mono<MatchStep> matchByUsername(String text, List<String> candidateBotIds) {
        String lowerText = text.toLowerCase(Locale.ROOT);
        return matchStep(candidateBotIds, botId -> selfUserIdResolver.resolveSelfUsername(botId)
                .map(username -> lowerText.contains("@" + username.toLowerCase(Locale.ROOT)))
                .defaultIfEmpty(false));
    }

    private Mono<MatchStep> matchByFirstName(String text, List<String> candidateBotIds) {
        return matchStep(candidateBotIds, botId -> firstNameToken(botId)
                .map(name -> name.length() >= MIN_FIRST_NAME_LENGTH && matchesWholeWord(text, name))
                .defaultIfEmpty(false));
    }

    /** The persona's first name (own Telegram first name, falling back to the display name's first token). */
    private Mono<String> firstNameToken(String botId) {
        return selfUserIdResolver.resolveSelfFirstName(botId)
                .filter(name -> !name.isBlank())
                .switchIfEmpty(Mono.defer(() -> Mono.justOrEmpty(firstToken(safeBotName(botId)))));
    }

    private String safeBotName(String botId) {
        try {
            return personaService.getBotName(botId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String firstToken(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return null;
        }
        String trimmed = displayName.trim();
        int spaceIdx = trimmed.indexOf(' ');
        return spaceIdx > 0 ? trimmed.substring(0, spaceIdx) : trimmed;
    }

    private static boolean matchesWholeWord(String text, String name) {
        Pattern pattern = Pattern.compile("(?<![\\p{L}\\p{N}_])" + Pattern.quote(name) + "(?![\\p{L}\\p{N}_])",
                WORD_BOUNDARY_FLAGS);
        return pattern.matcher(text).find();
    }

    /** Evaluates one candidate-matching predicate over all candidates, fail-open per candidate. */
    private Mono<MatchStep> matchStep(List<String> candidateBotIds, Function<String, Mono<Boolean>> predicate) {
        return Flux.fromIterable(candidateBotIds)
                .filterWhen(botId -> predicate.apply(botId)
                        .onErrorResume(error -> Mono.just(false)))
                .collectList()
                .map(MatchStep::of);
    }

    /** Outcome of matching one rule (username/first-name) across all candidates. */
    private record MatchStep(List<String> matches) {
        static MatchStep of(List<String> matches) {
            return new MatchStep(matches);
        }

        boolean matched() {
            return matches.size() == 1;
        }

        boolean ambiguous() {
            return matches.size() > 1;
        }

        String botId() {
            return matches.get(0);
        }
    }
}
