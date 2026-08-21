package com.example.telegramuserbot.controller;

import com.example.telegramuserbot.config.ConditionalOnHttpEnabled;
import com.example.telegramuserbot.service.TelegramClientManager;
import com.example.telegramuserbot.telegram.TelegramClientFacade;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only diagnostic: lists the live Telegram chats the primary bot account is
 * currently in (chatId + title + type), via the already-logged-in TDLib client.
 * Lets an operator discover real chat ids (e.g. find a group by title) without
 * guessing. Strictly read-only — only GetChats/GetChat, no writes.
 */
@RestController
@ConditionalOnHttpEnabled
@RequestMapping("/api/admin/dialogs")
public class DialogsDiagnosticController {

    private static final Logger log = LoggerFactory.getLogger(DialogsDiagnosticController.class);

    private final TelegramClientManager telegramClientManager;

    public DialogsDiagnosticController(TelegramClientManager telegramClientManager) {
        this.telegramClientManager = telegramClientManager;
    }

    @GetMapping
    public Mono<ResponseEntity<List<Map<String, Object>>>> listDialogs() {
        log.info("UI:listDialogs (diagnostic) requested");
        TelegramClientFacade client = telegramClientManager.getAnyClient();
        if (client == null) {
            return Mono.just(ResponseEntity.status(503).body(List.of(Map.of("error", "No Telegram client available"))));
        }
        return Mono.fromFuture(() -> client.send(new TdApi.GetChats(new TdApi.ChatListMain(), 500)))
                .cast(TdApi.Chats.class)
                .flatMapMany(chats -> Flux.fromStream(Arrays.stream(chats.chatIds).boxed()))
                .flatMap(id -> Mono.fromFuture(() -> client.send(new TdApi.GetChat(id)))
                        .cast(TdApi.Chat.class)
                        .map(c -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("chatId", c.id);
                            m.put("title", c.title);
                            m.put("type", c.type == null ? null : c.type.getClass().getSimpleName());
                            return m;
                        })
                        .onErrorResume(e -> Mono.empty()))
                .collectList()
                .map(ResponseEntity::ok)
                .onErrorResume(e -> {
                    log.warn("listDialogs failed: {}", e.toString());
                    return Mono.just(ResponseEntity.status(500).body(List.of(Map.of("error", e.toString()))));
                });
    }
}
