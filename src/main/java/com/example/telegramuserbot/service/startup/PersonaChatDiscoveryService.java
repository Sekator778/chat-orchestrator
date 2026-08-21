package com.example.telegramuserbot.service.startup;

import com.example.telegramuserbot.telegram.TelegramClientFacade;
import com.example.telegramuserbot.telegram.TelegramClientLifecycleListener;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Per-persona membership discovery: when ANY client becomes ready, list its
 * dialogs and record (persona, chat) rows in bot.persona_chat_bindings so each
 * persona can reply in its OWN chats. Broadcast channels are skipped — news
 * sources are harvested only by the collector account (decision 0.7), they are
 * not reply targets. Additive: an existing binding's reply_enabled is preserved.
 * <p>
 * After recording each binding, delegates to {@link PersonaChatDefaultConfigService}
 * to ensure a default {@code chat_configs} + {@code rate_limits} + triggers row
 * exists for the chat (idempotent skip-if-exists, so manually configured chats
 * are never overwritten).
 */
@Service
public class PersonaChatDiscoveryService implements TelegramClientLifecycleListener {

    private static final Logger log = LoggerFactory.getLogger(PersonaChatDiscoveryService.class);

    private final DatabaseClient databaseClient;
    private final PersonaChatDefaultConfigService defaultConfigService;

    @Value("${bot.persona-discovery.enabled:false}")
    private boolean enabled;
    @Value("${bot.persona-discovery.chat-limit:200}")
    private int chatLimit;

    public PersonaChatDiscoveryService(DatabaseClient databaseClient,
                                       PersonaChatDefaultConfigService defaultConfigService) {
        this.databaseClient = databaseClient;
        this.defaultConfigService = defaultConfigService;
    }

    @Override
    public void onClientReady(String botId, TelegramClientFacade client) {
        if (!enabled) {
            return;
        }
        discover(botId, client)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        count -> log.info("Persona discovery: botId={} recorded {} chat binding(s)", botId, count),
                        error -> log.warn("Persona discovery failed for botId={}: {}", botId, error.getMessage()));
    }

    Mono<Long> discover(String botId, TelegramClientFacade client) {
        return Mono.fromFuture(() -> client.send(new TdApi.GetChats(new TdApi.ChatListMain(), chatLimit)))
                .flatMapMany(chats -> Flux.fromStream(java.util.Arrays.stream(((TdApi.Chats) chats).chatIds).boxed()))
                .flatMap(chatId -> resolveReplyTarget(client, chatId), 4)
                .flatMap(chatId -> upsertBindingAndEnsureConfig(botId, chatId), 4)
                .count();
    }

    /** Emits the chatId only if it is a reply target (group / megagroup / private), not a broadcast channel. */
    private Mono<Long> resolveReplyTarget(TelegramClientFacade client, Long chatId) {
        return Mono.fromFuture(() -> client.send(new TdApi.GetChat(chatId)))
                .flatMap(obj -> {
                    TdApi.Chat chat = (TdApi.Chat) obj;
                    boolean broadcast = chat.type instanceof TdApi.ChatTypeSupergroup sg && sg.isChannel;
                    return broadcast ? Mono.empty() : Mono.just(chatId);
                })
                .onErrorResume(e -> Mono.empty());
    }

    private Mono<Long> upsertBindingAndEnsureConfig(String botId, long chatId) {
        return databaseClient.sql("""
                        INSERT INTO bot.persona_chat_bindings (bot_id, chat_id)
                        VALUES (:botId, :chatId)
                        ON CONFLICT (bot_id, chat_id) DO NOTHING
                        """)
                .bind("botId", botId)
                .bind("chatId", chatId)
                .fetch()
                .rowsUpdated()
                .thenReturn(chatId)
                .flatMap(id -> defaultConfigService.ensureDefaultConfig(id).thenReturn(id))
                .onErrorResume(e -> {
                    log.debug("Binding upsert failed for botId={} chatId={}: {}", botId, chatId, e.getMessage());
                    return Mono.empty();
                });
    }
}
