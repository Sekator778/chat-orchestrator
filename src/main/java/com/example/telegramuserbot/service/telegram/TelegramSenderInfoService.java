package com.example.telegramuserbot.service.telegram;

import com.example.telegramuserbot.config.BotInstanceProvider;
import com.example.telegramuserbot.service.TelegramClientManager;
import com.example.telegramuserbot.telegram.TelegramClientFacade;
import com.example.telegramuserbot.telegram.TelegramClientLifecycleListener;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Service
public final class TelegramSenderInfoService implements TelegramClientLifecycleListener {

    private static final Logger log = LoggerFactory.getLogger(TelegramSenderInfoService.class);

    private final TelegramClientManager telegramClientManager;
    private final BotInstanceProvider botInstanceProvider;

    private final Map<Long, SenderInfo> userCache = new ConcurrentHashMap<>();
    private final Map<Long, SenderInfo> chatCache = new ConcurrentHashMap<>();
    private final Map<Long, Long> supergroupIdByChatId = new ConcurrentHashMap<>();
    private final Map<Long, String> supergroupUsernameCache = new ConcurrentHashMap<>();

    private final Map<Long, Mono<SenderInfo>> userInflight = new ConcurrentHashMap<>();
    private final Map<Long, Mono<SenderInfo>> chatInflight = new ConcurrentHashMap<>();
    private final Map<Long, Mono<String>> supergroupUsernameInflight = new ConcurrentHashMap<>();

    public TelegramSenderInfoService(TelegramClientManager telegramClientManager,
                                     BotInstanceProvider botInstanceProvider) {
        this.telegramClientManager = telegramClientManager;
        this.botInstanceProvider = botInstanceProvider;
    }

    @Override
    public void onClientReady(String botId, TelegramClientFacade client) {
        try {
            client.addUpdateHandler(TdApi.UpdateUser.class, update -> {
                if (update != null && update.user != null) {
                    SenderInfo info = fromUser(update.user);
                    if (info != null) {
                        userCache.put(update.user.id, info);
                    }
                }
            });
            client.addUpdateHandler(TdApi.UpdateNewChat.class, update -> {
                if (update != null && update.chat != null) {
                    SenderInfo info = fromChat(update.chat);
                    if (info != null) {
                        chatCache.put(update.chat.id, info);
                    }
                    if (update.chat.type instanceof TdApi.ChatTypeSupergroup sg) {
                        supergroupIdByChatId.put(update.chat.id, sg.supergroupId);
                    }
                }
            });
            client.addUpdateHandler(TdApi.UpdateChatTitle.class, update -> {
                if (update != null) {
                    chatCache.remove(update.chatId);
                }
            });
            client.addUpdateHandler(TdApi.UpdateChatPhoto.class, update -> {
                if (update != null) {
                    chatCache.remove(update.chatId);
                }
            });
            client.addUpdateHandler(TdApi.UpdateSupergroup.class, update -> {
                if (update != null && update.supergroup != null) {
                    String username = extractUsername(update.supergroup.usernames);
                    if (username != null) {
                        supergroupUsernameCache.put(update.supergroup.id, username);
                    }
                }
            });
            log.info("SenderInfo: update handlers registered for botInstanceId={}", botId);
        } catch (Exception e) {
            log.warn("SenderInfo: failed to register update handlers for botInstanceId={}: {}", botId, e.getMessage());
        }
    }

    public Mono<SenderInfo> resolve(String botInstanceId, TdApi.MessageSender sender) {
        if (sender == null) {
            return Mono.empty();
        }

        if (sender instanceof TdApi.MessageSenderUser userSender) {
            long userId = userSender.userId;
            SenderInfo cached = userCache.get(userId);
            if (cached != null) {
                return Mono.just(cached);
            }
            return userInflight.computeIfAbsent(userId, id -> fetchUser(botInstanceId, id)
                    .doOnNext(info -> {
                        if (info != null) {
                            userCache.put(id, info);
                        }
                    })
                    .doFinally(sig -> userInflight.remove(id))
                    .cache()
            );
        }

        if (sender instanceof TdApi.MessageSenderChat chatSender) {
            long chatId = chatSender.chatId;
            SenderInfo cached = chatCache.get(chatId);
            if (cached != null) {
                Long supergroupId = supergroupIdByChatId.get(chatId);
                boolean usernameMissing = cached.senderUsername == null || cached.senderUsername.isBlank();
                if (supergroupId != null && usernameMissing) {
                    return resolveSupergroupUsername(botInstanceId, supergroupId)
                            .map(username -> {
                                SenderInfo updated = new SenderInfo(cached.id, cached.kind, cached.senderName, username, null, null);
                                chatCache.put(chatId, updated);
                                return updated;
                            })
                            .defaultIfEmpty(cached);
                }
                return Mono.just(cached);
            }
            return chatInflight.computeIfAbsent(chatId, id -> fetchChat(botInstanceId, id)
                    .doOnNext(info -> {
                        if (info != null) {
                            chatCache.put(id, info);
                        }
                    })
                    .doFinally(sig -> chatInflight.remove(id))
                    .cache()
            );
        }

        return Mono.empty();
    }

    private Mono<SenderInfo> fetchUser(String botInstanceId, long userId) {
        TelegramClientFacade client = resolveClient(botInstanceId);
        if (client == null) {
            return Mono.empty();
        }
        return Mono.fromFuture(() -> client.send(new TdApi.GetUser(userId)))
                .cast(TdApi.User.class)
                .map(TelegramSenderInfoService::fromUser)
                .onErrorResume(error -> {
                    log.debug("SenderInfo: failed to resolve user {}: {}", userId, error.getMessage());
                    return Mono.empty();
                });
    }

    private Mono<SenderInfo> fetchChat(String botInstanceId, long chatId) {
        TelegramClientFacade client = resolveClient(botInstanceId);
        if (client == null) {
            return Mono.empty();
        }
        return Mono.fromFuture(() -> client.send(new TdApi.GetChat(chatId)))
                .cast(TdApi.Chat.class)
                .flatMap(chat -> {
                    SenderInfo base = fromChat(chat);
                    if (chat.type instanceof TdApi.ChatTypeSupergroup sg) {
                        supergroupIdByChatId.put(chat.id, sg.supergroupId);
                        return resolveSupergroupUsername(botInstanceId, sg.supergroupId)
                                .map(username -> new SenderInfo(base.id, base.kind, base.senderName, username, null, null))
                                .defaultIfEmpty(base);
                    }
                    return Mono.just(base);
                })
                .onErrorResume(error -> {
                    log.debug("SenderInfo: failed to resolve chat {}: {}", chatId, error.getMessage());
                    return Mono.empty();
                });
    }

    private TelegramClientFacade resolveClient(String botInstanceId) {
        String resolved = normalizeBotInstanceId(botInstanceId);
        TelegramClientFacade client = resolved != null ? telegramClientManager.getClient(resolved) : null;
        if (client != null) {
            return client;
        }
        return telegramClientManager.getAnyClient();
    }

    private String normalizeBotInstanceId(String raw) {
        if (raw == null || raw.isBlank() || "default-bot".equalsIgnoreCase(raw)) {
            return botInstanceProvider.getInstanceId();
        }
        return raw;
    }

    private static SenderInfo fromUser(TdApi.User user) {
        if (user == null) {
            return null;
        }
        String firstName = trimToNull(user.firstName);
        String lastName = trimToNull(user.lastName);
        String username = extractUsername(user.usernames);
        String name = displayName(firstName, lastName, username);
        return new SenderInfo(user.id, SenderKind.USER, name, username, firstName, lastName);
    }

    private static SenderInfo fromChat(TdApi.Chat chat) {
        if (chat == null) {
            return null;
        }
        String title = trimToNull(chat.title);
        return new SenderInfo(chat.id, SenderKind.CHAT, title, null, null, null);
    }

    private static String extractUsername(TdApi.Usernames usernames) {
        if (usernames == null || usernames.activeUsernames == null || usernames.activeUsernames.length == 0) {
            return null;
        }
        for (String u : usernames.activeUsernames) {
            String trimmed = trimToNull(u);
            if (trimmed != null) {
                return trimmed;
            }
        }
        return null;
    }

    private static String displayName(String firstName, String lastName, String username) {
        String full = (firstName == null ? "" : firstName) + (lastName == null ? "" : " " + lastName);
        full = full.strip();
        if (!full.isBlank()) {
            return full;
        }
        if (username != null && !username.isBlank()) {
            return username;
        }
        return null;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isBlank() ? null : trimmed;
    }

    private Mono<String> resolveSupergroupUsername(String botInstanceId, long supergroupId) {
        String cached = supergroupUsernameCache.get(supergroupId);
        if (cached != null && !cached.isBlank()) {
            return Mono.just(cached);
        }
        TelegramClientFacade client = resolveClient(botInstanceId);
        if (client == null) {
            return Mono.empty();
        }
        return supergroupUsernameInflight.computeIfAbsent(supergroupId, id -> Mono.fromFuture(() -> client.send(new TdApi.GetSupergroup(id)))
                .cast(TdApi.Supergroup.class)
                .map(sg -> extractUsername(sg.usernames))
                .doOnNext(username -> {
                    if (username != null) {
                        supergroupUsernameCache.put(id, username);
                    }
                })
                .doFinally(sig -> supergroupUsernameInflight.remove(id))
                .cache()
        ).filter(u -> u != null && !u.isBlank());
    }

    public record SenderInfo(long id,
                             SenderKind kind,
                             String senderName,
                             String senderUsername,
                             String senderFirstName,
                             String senderLastName) {

        public boolean isUser() {
            return kind == SenderKind.USER;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SenderInfo that)) return false;
            return id == that.id
                    && kind == that.kind
                    && Objects.equals(senderName, that.senderName)
                    && Objects.equals(senderUsername, that.senderUsername)
                    && Objects.equals(senderFirstName, that.senderFirstName)
                    && Objects.equals(senderLastName, that.senderLastName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, kind, senderName, senderUsername, senderFirstName, senderLastName);
        }
    }

    public enum SenderKind { USER, CHAT }
}
