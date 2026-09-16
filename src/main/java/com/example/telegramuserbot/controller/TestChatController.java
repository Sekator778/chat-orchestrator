package com.example.telegramuserbot.controller;

import com.example.telegramuserbot.config.ConditionalOnHttpEnabled;
import com.example.telegramuserbot.repository.MessageRepository;
import com.example.telegramuserbot.service.KafkaMessageProducerService;
import com.example.telegramuserbot.service.persistence.MessagePersistenceService;
import com.example.telegramuserbot.service.publishing.TelegramMessageSender;
import com.example.telegramuserbot.service.testing.TestChatRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The send/receive check a developer runs against the dedicated test chats.
 *
 * <p>Whether a persona can still put a message into Telegram, and whether an
 * incoming message still comes back out as a reply, cannot be answered by a unit
 * test — it needs a real account and a real chat. These endpoints drive exactly
 * that, and only inside the chats listed in {@link TestChatRegistry}: a chat id
 * that is not a designated test chat is rejected before anything is sent.
 *
 * <ul>
 *   <li>{@code POST /send} — persona posts a line into a test chat (outbound path).</li>
 *   <li>{@code POST /trigger} — replay an inbound message id through Kafka so the
 *       reply pipeline runs on demand instead of waiting for someone to type.</li>
 *   <li>{@code GET /messages} — the last rows of a test chat, for assertions.</li>
 * </ul>
 */
@RestController
@ConditionalOnHttpEnabled
@RequestMapping("/api/admin/test-chat")
@Tag(name = "Test chats", description = "Send/receive checks against the chats set aside for testing")
public class TestChatController {

    private static final Logger log = LoggerFactory.getLogger(TestChatController.class);
    private static final int MAX_MESSAGES = 50;

    private final TestChatRegistry registry;
    private final TelegramMessageSender messageSender;
    private final MessagePersistenceService persistence;
    private final KafkaMessageProducerService kafkaProducer;
    private final MessageRepository messageRepository;

    public TestChatController(TestChatRegistry registry,
                              TelegramMessageSender messageSender,
                              MessagePersistenceService persistence,
                              KafkaMessageProducerService kafkaProducer,
                              MessageRepository messageRepository) {
        this.registry = registry;
        this.messageSender = messageSender;
        this.persistence = persistence;
        this.kafkaProducer = kafkaProducer;
        this.messageRepository = messageRepository;
    }

    @GetMapping
    @Operation(summary = "Which chats may be driven by these endpoints")
    public Mono<ResponseEntity<Map<String, Object>>> listTestChats() {
        return Mono.just(ResponseEntity.ok(Map.of(
                "setting", TestChatRegistry.SETTING_NAME,
                "chatIds", registry.chatIds())));
    }

    @PostMapping("/send")
    @Operation(summary = "Send a line into a test chat as one persona (outbound path)")
    public Mono<ResponseEntity<Map<String, Object>>> send(@RequestBody SendRequest request) {
        if (request == null || request.chatId() == null || request.botId() == null || request.text() == null
                || request.text().isBlank()) {
            return badRequest("botId, chatId and text are required");
        }
        if (!registry.isTestChat(request.chatId())) {
            return refuse(request.chatId());
        }
        log.info("[TestChat] send botId={} chat={} chars={}", request.botId(), request.chatId(), request.text().length());
        // Deliberately UNPACED: HumanSendPacer waits out a human "thinking" pause of up
        // to a few minutes, which outlives the HTTP request and would only report a
        // timeout. The probe answers one question — does this account still reach
        // Telegram — and the pacing itself is covered by the reply check, which reads
        // the TG TIMING line for a real reply. The outbound guard still applies.
        return messageSender.send(request.botId(), request.chatId(), request.replyToMessageId(), request.text())
                .flatMap(sent -> persistence.persistMessage(request.botId(), request.chatId(), sent)
                        .thenReturn(sent))
                .map(sent -> ResponseEntity.ok(Map.<String, Object>of(
                        "sent", true,
                        "botId", request.botId(),
                        "chatId", request.chatId(),
                        "telegramMessageId", sent.id)))
                .switchIfEmpty(Mono.just(ResponseEntity.status(409).body(Map.of(
                        "sent", false,
                        "error", "nothing was sent — outbound guard or kill switch suppressed it"))));
    }

    @PostMapping("/trigger")
    @Operation(summary = "Replay an inbound message id through the reply pipeline")
    public Mono<ResponseEntity<Map<String, Object>>> trigger(@RequestBody TriggerRequest request) {
        if (request == null || request.chatId() == null || request.messageId() == null) {
            return badRequest("chatId and messageId are required");
        }
        if (!registry.isTestChat(request.chatId())) {
            return refuse(request.chatId());
        }
        return messageRepository.findByChatIdAndMessageId(request.chatId(), request.messageId())
                .flatMap(message -> {
                    if (message.isOutgoing()) {
                        // Replaying our own message would only exercise the anti-loop guard.
                        return Mono.just(ResponseEntity.status(409).body(Map.<String, Object>of(
                                "triggered", false,
                                "error", "that message was sent by a persona; replay an incoming one")));
                    }
                    log.info("[TestChat] replaying chat={} message={} through the reply pipeline",
                            request.chatId(), request.messageId());
                    return kafkaProducer.sendNewMessageNotification(request.chatId(), request.messageId())
                            .thenReturn(ResponseEntity.ok(Map.<String, Object>of(
                                    "triggered", true,
                                    "chatId", request.chatId(),
                                    "messageId", request.messageId(),
                                    "note", "the same message id is ignored for 15 minutes after a run (idempotency)")));
                })
                .switchIfEmpty(Mono.just(ResponseEntity.status(404).body(Map.of(
                        "triggered", false,
                        "error", "no such message in this chat"))));
    }

    @GetMapping("/messages")
    @Operation(summary = "Recent rows of a test chat, newest first")
    public Mono<ResponseEntity<Object>> messages(@RequestParam long chatId,
                                                 @RequestParam(defaultValue = "10") int limit) {
        if (!registry.isTestChat(chatId)) {
            return refuse(chatId).map(response -> ResponseEntity.status(response.getStatusCode())
                    .body((Object) response.getBody()));
        }
        int capped = Math.max(1, Math.min(limit, MAX_MESSAGES));
        return messageRepository.findByChatIdOrderByDateDesc(chatId, PageRequest.of(0, capped))
                .map(message -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("messageId", message.getMessageId());
                    row.put("date", message.getDate());
                    row.put("outgoing", message.isOutgoing());
                    row.put("botId", message.getReceivedByBotId());
                    row.put("replyTo", message.getReplyToMessageId());
                    row.put("text", message.getContent());
                    return row;
                })
                .collectList()
                .map(rows -> ResponseEntity.ok((Object) rows));
    }

    private <T> Mono<ResponseEntity<T>> badRequest(String message) {
        @SuppressWarnings("unchecked")
        T body = (T) Map.of("error", message);
        return Mono.just(ResponseEntity.badRequest().body(body));
    }

    private Mono<ResponseEntity<Map<String, Object>>> refuse(long chatId) {
        List<Long> allowed = new ArrayList<>(registry.chatIds());
        log.warn("[TestChat] refused chat={} — not a designated test chat (allowed={})", chatId, allowed);
        return Mono.just(ResponseEntity.status(403).body(Map.of(
                "error", "chat " + chatId + " is not a test chat",
                "allowed", allowed,
                "hint", "add it to bot.app_settings '" + TestChatRegistry.SETTING_NAME + "' if it really is one")));
    }

    public record SendRequest(String botId, Long chatId, String text, Long replyToMessageId) { }

    public record TriggerRequest(Long chatId, Long messageId) { }
}
