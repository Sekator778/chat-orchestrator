package com.example.telegramuserbot.service.publishing;

import com.example.telegramuserbot.domain.Event;
import com.example.telegramuserbot.domain.Posted;
import com.example.telegramuserbot.domain.PostSubscription;
import com.example.telegramuserbot.repository.EventRepository;
import com.example.telegramuserbot.repository.PostSubscriptionRepository;
import com.example.telegramuserbot.repository.PostedRepository;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

/**
 * Publishes ready events to Telegram chats based on subscriptions.
 * Handles matching, deduplication, rendering, sending, and audit tracking.
 */
@Service
public final class EventPublisherService {

    private static final Logger log = LoggerFactory.getLogger(EventPublisherService.class);

    private final EventRepository events;
    private final PostSubscriptionRepository subscriptions;
    private final PostedRepository posted;
    private final TelegramPostRenderer renderer;
    private final TelegramMessageSender telegram;

    /**
     * Constructs publisher with dependencies.
     *
     * @param events event repository
     * @param subscriptions subscription repository
     * @param posted posted audit repository
     * @param renderer post renderer
     * @param telegram Telegram message sender
     */
    public EventPublisherService(EventRepository events,
                                 PostSubscriptionRepository subscriptions,
                                 PostedRepository posted,
                                 TelegramPostRenderer renderer,
                                 TelegramMessageSender telegram) {
        this.events = events;
        this.subscriptions = subscriptions;
        this.posted = posted;
        this.renderer = renderer;
        this.telegram = telegram;
    }

    /**
     * Publishes all ready events to matching subscriptions.
     * Returns count of successfully published posts (not events).
     *
     * @return count of posts sent
     */
    public Mono<Integer> process() {
        return events.findByStatus("ready")
            .flatMap(this::publish)
            .reduce(0, Integer::sum)
            .doOnSuccess(count -> {
                if (count > 0) {
                    log.info("Published {} event(s) to Telegram", count);
                }
            });
    }

    /**
     * Publishes single event to all matching subscriptions.
     * Handles deduplication, rendering, sending, and status updates.
     * Returns count of posts actually sent.
     */
    private Mono<Integer> publish(Event event) {
        return subscriptions.findMatchingSubscriptions(
                event.topic(),
                event.eventType(),
                event.severity()
            )
            .filterWhen(sub -> shouldPublish(event, sub))
            .flatMap(sub -> send(event, sub))
            .collectList()
            .flatMap(results -> {
                if (results.isEmpty()) {
                    log.debug("No subscriptions matched for event {}", event.id());
                    return transition(event, "ready", "skipped", "No matching subscriptions")
                        .thenReturn(0);  // No posts sent
                }

                int postsPublished = (int) results.stream().filter(Boolean::booleanValue).count();
                if (postsPublished > 0) {
                    return transition(event, "ready", "published", null)
                        .thenReturn(postsPublished);
                } else {
                    return transition(event, "ready", "failed", "All sends failed")
                        .thenReturn(0);
                }
            });
    }

    /**
     * Checks if event should be published to subscription.
     * Applies idempotency and time-based deduplication.
     */
    private Mono<Boolean> shouldPublish(Event event, PostSubscription sub) {
        return posted.existsByEventIdAndSubscriptionId(event.id(), sub.id())
            .flatMap(alreadyPosted -> {
                if (alreadyPosted) {
                    log.debug("Event {} already posted to subscription {}", event.id(), sub.id());
                    return Mono.just(false);
                }

                LocalDateTime since = LocalDateTime.now().minusSeconds(sub.dedupeTtlSec());
                return posted.wasRecentlyPosted(sub.chatId(), event.topic(), event.eventType(), since)
                    .map(recent -> {
                        if (recent) {
                            log.debug("Event topic/type recently posted to chat {}, skipping", sub.chatId());
                        }
                        return !recent;
                    });
            });
    }

    /**
     * Renders and sends post to Telegram chat.
     * Records result in posted table.
     *
     * @return true if sent successfully, false otherwise
     */
    private Mono<Boolean> send(Event event, PostSubscription sub) {
        String html = renderer.render(event, sub.templateCode());

        return deliver(sub.chatId(), html)
            .flatMap(message -> record(event, sub, message.id, "sent", null))
            .thenReturn(true)
            .onErrorResume(error -> {
                log.error("Failed to send event {} to chat {}: {}",
                    event.id(), sub.chatId(), error.getMessage());
                return record(event, sub, null, "failed", error.getMessage())
                    .thenReturn(false);
            });
    }

    /**
     * Sends HTML message to Telegram chat.
     * Strips HTML tags for now (TODO: proper HTML entity parsing).
     */
    private Mono<TdApi.Message> deliver(Long chatId, String html) {
        String plainText = stripHtml(html);
        return telegram.send(chatId, plainText);
    }

    /**
     * Records post in audit table.
     */
    private Mono<Posted> record(Event event, PostSubscription sub, Long messageId,
                                 String status, String error) {
        Posted entry = new Posted();
        entry.setEventId(event.id());
        entry.setSubscriptionId(sub.id());
        entry.setChatId(sub.chatId());
        entry.setMessageId(messageId);
        entry.setTemplateCode(sub.templateCode());
        entry.setStatus(status);
        entry.setErrorMessage(error);
        entry.setPostedAt(LocalDateTime.now());

        return posted.save(entry);
    }

    /**
     * Transitions event status with optional error message.
     */
    private Mono<Event> transition(Event event, String expected, String next, String error) {
        if (!expected.equals(event.status())) {
            log.warn("Event {} has unexpected status: expected={}, actual={}",
                event.id(), expected, event.status());
            return Mono.just(event);
        }

        LocalDateTime now = LocalDateTime.now();
        // The check above reads a status fetched earlier in the cycle; the write below
        // is what actually decides. Both are needed: the check saves a round-trip, the
        // compare-and-set keeps two overlapping cycles from publishing the same event.
        Mono<Integer> update = error != null
            ? events.updateEventStatusWithError(event.id(), expected, next, error, now)
            : events.updateEventStatus(event.id(), expected, next, now);

        return update
            .doOnNext(updated -> {
                if (updated == 0) {
                    log.warn("Event {} moved out of '{}' before we could set '{}' - skipping",
                        event.id(), expected, next);
                }
            })
            .thenReturn(event);
    }

    /**
     * Strips HTML tags for plain text delivery.
     * TODO: Replace with proper HTML entity parsing via TdApi.ParseTextEntities
     */
    private String stripHtml(String html) {
        if (html == null) {
            return "";
        }
        return html
            .replaceAll("<b>", "**")
            .replaceAll("</b>", "**")
            .replaceAll("<i>", "_")
            .replaceAll("</i>", "_")
            .replaceAll("<a href=\"[^\"]+\">([^<]+)</a>", "$1")
            .replaceAll("<[^>]+>", "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .trim();
    }
}
