package com.example.telegramuserbot.service.channels.reconciliation;

import com.example.telegramuserbot.config.BotInstanceProvider;
import com.example.telegramuserbot.domain.Channel;
import com.example.telegramuserbot.repository.ChannelRepository;
import com.example.telegramuserbot.repository.TelegramAccountRepository;
import com.example.telegramuserbot.service.TelegramClientManager;
import com.example.telegramuserbot.telegram.TelegramClientFacade;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reconciles persona memberships across all joined channels.
 * Detects channels where some personas are missing and joins them
 * with throttled delays to avoid Telegram rate limits.
 */
@Service
public final class PersonaMembershipReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(PersonaMembershipReconciliationService.class);
    private static final int MUTE_FOREVER_SECONDS = Integer.MAX_VALUE;

    private final ChannelRepository channelRepository;
    private final TelegramAccountRepository telegramAccountRepository;
    private final TelegramClientManager telegramClientManager;
    private final BotInstanceProvider botInstanceProvider;

    @Value("${persona.reconciliation.batch-size:20}")
    private int batchSize;

    @Value("${persona.reconciliation.inter-channel-delay-min-seconds:5}")
    private int interChannelDelayMinSeconds;

    @Value("${persona.reconciliation.inter-channel-delay-max-seconds:15}")
    private int interChannelDelayMaxSeconds;

    @Value("${persona.reconciliation.inter-persona-delay-min-seconds:30}")
    private int interPersonaDelayMinSeconds;

    @Value("${persona.reconciliation.inter-persona-delay-max-seconds:60}")
    private int interPersonaDelayMaxSeconds;

    public PersonaMembershipReconciliationService(ChannelRepository channelRepository,
                                                   TelegramAccountRepository telegramAccountRepository,
                                                   TelegramClientManager telegramClientManager,
                                                   BotInstanceProvider botInstanceProvider) {
        this.channelRepository = channelRepository;
        this.telegramAccountRepository = telegramAccountRepository;
        this.telegramClientManager = telegramClientManager;
        this.botInstanceProvider = botInstanceProvider;
    }

    /**
     * Runs reconciliation for all personas.
     * For each persona, finds joined channels missing that persona and joins them.
     *
     * @return Mono with total number of successful joins across all personas
     */
    public Mono<Integer> reconcile() {
        List<String> allPersonas = botInstanceProvider.getInstanceIds();
        log.info("Persona reconciliation: starting for {} personas, batch={}", allPersonas.size(), batchSize);

        AtomicInteger totalJoins = new AtomicInteger(0);

        return Flux.fromIterable(allPersonas)
                .concatMap(personaId -> reconcilePersona(personaId)
                        .doOnNext(count -> {
                            totalJoins.addAndGet(count);
                            log.info("Persona reconciliation: persona={} joined {} channels", personaId, count);
                        })
                        .onErrorResume(ex -> {
                            log.error("Persona reconciliation: failed for persona={}: {}", personaId, ex.getMessage());
                            return Mono.just(0);
                        }))
                .then(Mono.fromCallable(totalJoins::get))
                .doOnSuccess(total -> log.info("Persona reconciliation: completed, total joins={}", total));
    }

    private Mono<Integer> reconcilePersona(String personaId) {
        TelegramClientFacade client = telegramClientManager.getClient(personaId);
        if (client == null) {
            log.warn("Persona reconciliation: no TDLib client for persona={}, skipping", personaId);
            return Mono.just(0);
        }

        return telegramAccountRepository.isCollector(personaId)
                .defaultIfEmpty(false)
                .flatMap(isCollector -> {
                    // Collector joins all channel types; non-collectors skip broadcast/news channels.
                    Flux<Channel> channelsToJoin = isCollector
                            ? channelRepository.findJoinedChannelsMissingPersona(personaId, batchSize)
                            : channelRepository.findJoinedNonBroadcastChannelsMissingPersona(personaId, batchSize);
                    log.debug("Persona reconciliation: persona={} isCollector={} — using {} query",
                            personaId, isCollector, isCollector ? "all-channels" : "non-broadcast");

                    return channelsToJoin
                            .index()
                            .concatMap(indexed -> {
                                Channel channel = indexed.getT2();
                                Mono<Boolean> joinOp = joinAndMuteChannel(channel, client, personaId);
                                if (indexed.getT1() == 0) {
                                    return joinOp;
                                }
                                return Mono.delay(randomInterChannelDelay()).then(joinOp);
                            })
                            .filter(Boolean::booleanValue)
                            .count()
                            .map(Long::intValue);
                });
    }

    private Mono<Boolean> joinAndMuteChannel(Channel channel, TelegramClientFacade client, String personaId) {
        long chatId = channel.getChatId();
        String username = channel.getUsername();
        log.info("Persona reconciliation: joining chat={} ({}) username={} persona={}",
                chatId, channel.getTitle(), username, personaId);

        return resolveChat(client, chatId, username)
                .then(Mono.fromFuture(() -> client.send(new TdApi.JoinChat(chatId))))
                .onErrorResume(error -> {
                    String msg = error.getMessage();
                    if (msg != null && msg.contains("USER_ALREADY_PARTICIPANT")) {
                        log.info("Persona reconciliation: persona={} already in chat={}", personaId, chatId);
                        return Mono.empty();
                    }
                    log.warn("Persona reconciliation: join failed chat={} persona={}: {}", chatId, personaId, msg);
                    return Mono.error(error);
                })
                .then(muteChannel(chatId, client, personaId))
                .then(updateBotInstanceIds(channel, personaId))
                .thenReturn(true)
                .onErrorResume(ex -> {
                    log.warn("Persona reconciliation: failed chat={} persona={}: {}", chatId, personaId, ex.getMessage());
                    return Mono.just(false);
                });
    }

    /**
     * Makes the TDLib client aware of the chat before attempting JoinChat.
     * JoinChat(chatId) fails with "Chat not found" if the client has never seen the chat.
     * SearchPublicChat(username) forces TDLib to resolve the chat first.
     */
    private Mono<Void> resolveChat(TelegramClientFacade client, long chatId, String username) {
        if (username != null && !username.isBlank()) {
            return Mono.fromFuture(() -> client.send(new TdApi.SearchPublicChat(username)))
                    .doOnSuccess(ignored -> log.debug("Persona reconciliation: resolved chat via username={}", username))
                    .onErrorResume(ex -> {
                        log.debug("Persona reconciliation: SearchPublicChat failed for {}: {}", username, ex.getMessage());
                        return Mono.empty();
                    })
                    .then();
        }
        return Mono.empty();
    }

    private Mono<Void> muteChannel(long chatId, TelegramClientFacade client, String personaId) {
        TdApi.ChatNotificationSettings settings = new TdApi.ChatNotificationSettings();
        settings.muteFor = MUTE_FOREVER_SECONDS;
        return Mono.fromFuture(() -> client.send(
                        new TdApi.SetChatNotificationSettings(chatId, settings)))
                .doOnSuccess(ignored -> log.debug("Persona reconciliation: muted chat={} persona={}", chatId, personaId))
                .onErrorResume(ex -> {
                    log.warn("Persona reconciliation: mute failed chat={} persona={}: {}", chatId, personaId, ex.getMessage());
                    return Mono.empty();
                })
                .then();
    }

    private Mono<Void> updateBotInstanceIds(Channel channel, String personaId) {
        channel.addBotInstanceId(personaId);
        return channelRepository.save(channel).then();
    }

    private Duration randomInterChannelDelay() {
        long minMs = interChannelDelayMinSeconds * 1000L;
        long maxMs = interChannelDelayMaxSeconds * 1000L;
        return Duration.ofMillis(ThreadLocalRandom.current().nextLong(minMs, maxMs + 1));
    }
}
