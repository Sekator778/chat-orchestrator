package com.example.telegramuserbot.scheduler;

import com.example.telegramuserbot.domain.ChannelCandidate;
import com.example.telegramuserbot.exception.MembershipCapReachedException;
import com.example.telegramuserbot.repository.ChannelCandidateRepository;
import com.example.telegramuserbot.repository.ChannelRepository;
import com.example.telegramuserbot.repository.TelegramAccountRepository;
import com.example.telegramuserbot.service.TelegramClientManager;
import com.example.telegramuserbot.service.cleanup.TopicalDenylistService;
import com.example.telegramuserbot.telegram.TelegramClientFacade;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * F0b — Slowly joins candidate channels recorded by {@code ChannelDiscoverySearchScheduler}
 * using the collector account, and mutes each channel immediately on join so the
 * owner's account is not flooded with notifications.
 *
 * <p><strong>DISABLED BY DEFAULT.</strong>  Auto-joining channels on a real Telegram account
 * is the primary ban trigger.  The owner must opt in explicitly by setting:
 * <pre>
 *   channel-discovery.join.enabled=true
 * </pre>
 *
 * <p>Each run pulls at most {@code channel-discovery.join.batch-size} (default 2)
 * unprocessed candidates and processes them sequentially with a fixed delay between
 * joins to avoid flood-wait errors.
 *
 * <p><strong>Topical join gate:</strong> before joining, the scheduler fetches the channel
 * title via {@code GetChat} and rejects it if it matches the {@link TopicalDenylistService}
 * token list (configurable in {@code bot.app_settings} key
 * {@code discovery.join.title-denylist}). The gate is <em>fail-open</em>: if {@code GetChat}
 * errors or returns no title, the join proceeds normally.
 *
 * <p><strong>Membership-cap handling:</strong> when TDLib returns {@code CHANNELS_TOO_MUCH}
 * the account is marked "at-cap" in-memory and the join sweep stops without discarding
 * the pending candidates (so they will be retried once headroom returns). Each sweep
 * re-checks the joined count and clears the at-cap flag when headroom is available,
 * making recovery fully automatic — no restart required.
 */
@Component
@ConditionalOnProperty(name = "channel-discovery.join.enabled", havingValue = "true", matchIfMissing = false)
public final class ChannelDiscoveryJoinScheduler {

    private static final Logger log = LoggerFactory.getLogger(ChannelDiscoveryJoinScheduler.class);

    /** Mute duration accepted by Telegram for "forever" (same constant as reconciliation service). */
    private static final int MUTE_FOREVER_SECONDS = Integer.MAX_VALUE;

    /** Fixed inter-join pause to stay well below Telegram's flood-wait threshold. */
    private static final Duration INTER_JOIN_DELAY = Duration.ofSeconds(30);

    private final ChannelCandidateRepository channelCandidateRepository;
    private final ChannelRepository channelRepository;
    private final TelegramAccountRepository telegramAccountRepository;
    private final TelegramClientManager telegramClientManager;
    private final TopicalDenylistService denylistService;

    @Value("${channel-discovery.join.batch-size:2}")
    private int batchSize;

    /** Telegram's hard membership cap for regular accounts (configurable for safety margin). */
    @Value("${channel-discovery.join.membership-cap:500}")
    private int membershipCap;

    /**
     * WARN when joined-channel headroom drops below this threshold.
     * Default 25 — gives time to curate before hitting the wall.
     */
    @Value("${channel-discovery.join.cap-warn-threshold:25}")
    private int capWarnThreshold;

    /** Guards against overlapping concurrent runs. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * BotIds currently known to be at the membership cap.
     * Cleared at sweep-start when the count query shows headroom has returned,
     * making cap recovery fully automatic (no restart required).
     */
    private final Set<String> atCapBotIds = ConcurrentHashMap.newKeySet();

    public ChannelDiscoveryJoinScheduler(ChannelCandidateRepository channelCandidateRepository,
                                         ChannelRepository channelRepository,
                                         TelegramAccountRepository telegramAccountRepository,
                                         TelegramClientManager telegramClientManager,
                                         TopicalDenylistService denylistService) {
        this.channelCandidateRepository = channelCandidateRepository;
        this.channelRepository = channelRepository;
        this.telegramAccountRepository = telegramAccountRepository;
        this.telegramClientManager = telegramClientManager;
        this.denylistService = denylistService;
    }

    /**
     * Runs the join sweep every 4 hours (default).
     * First run 10 minutes after startup to let TDLib clients finish authorizing.
     *
     * <p>At the start of each sweep the scheduler:
     * <ol>
     *   <li>Queries the joined-channel count and logs headroom (observability).</li>
     *   <li>Skips the batch if the at-cap flag is set (ground-truth gate: the flag is set
     *       when TDLib itself returns CHANNELS_TOO_MUCH, which is more accurate than the
     *       DB count because Telegram's cap counts all dialogs, not just harvested channels).</li>
     *   <li>The at-cap flag is cleared only when a join actually SUCCEEDS — meaning Telegram
     *       accepted it, proving real headroom exists. DB count drops alone are not sufficient
     *       because they only track harvested channels, not the full dialog count.</li>
     * </ol>
     */
    @Scheduled(
            fixedDelayString = "${channel-discovery.join.interval-ms:14400000}",
            initialDelayString = "${channel-discovery.join.initial-delay-ms:600000}"
    )
    public void runJoinSweep() {
        if (!running.compareAndSet(false, true)) {
            log.debug("Channel discovery join sweep already running, skipping");
            return;
        }

        log.info("Channel discovery join sweep: starting, batch-size={}", batchSize);

        resolveCollectorClient()
                .flatMap(cc -> {
                    String botId = cc.botId();
                    TelegramClientFacade client = cc.client();
                    // Always log the observability line (DB count + headroom estimate).
                    return channelRepository.countJoined()
                            .flatMap(joined -> {
                                long headroom = membershipCap - joined;
                                if (atCapBotIds.contains(botId)) {
                                    // The flag is ground truth: TDLib rejected a join previously.
                                    // The DB count may still show headroom because Telegram counts
                                    // ALL dialogs (groups, DMs, etc.), not just harvested channels.
                                    log.warn("Collector botId={}: {}/{} harvested-channels joined, headroom-estimate={} — "
                                                    + "AT MEMBERSHIP CAP (flag set by previous CHANNELS_TOO_MUCH), skipping join sweep. "
                                                    + "Leave some channels to clear the flag.",
                                            botId, joined, membershipCap, headroom);
                                    return Mono.just("at-cap");
                                }
                                if (headroom <= 0) {
                                    log.warn("Collector botId={}: {}/{} joined, headroom=0 — AT MEMBERSHIP CAP (count-based), skipping join sweep",
                                            botId, joined, membershipCap);
                                    atCapBotIds.add(botId);
                                    return Mono.just("at-cap");
                                }
                                if (headroom <= capWarnThreshold) {
                                    log.warn("Collector botId={}: {}/{} joined, headroom={} — LOW, approaching membership cap",
                                            botId, joined, membershipCap, headroom);
                                } else {
                                    log.info("Collector botId={}: {}/{} joined, headroom={}",
                                            botId, joined, membershipCap, headroom);
                                }
                                return channelCandidateRepository.findUnprocessed(batchSize)
                                        .index()
                                        .concatMap(indexed -> {
                                            ChannelCandidate candidate = indexed.getT2();
                                            Mono<Void> joinOp = processCandidate(candidate, botId, client);
                                            // insert delay before every join after the first
                                            if (indexed.getT1() == 0) {
                                                return joinOp;
                                            }
                                            return Mono.delay(INTER_JOIN_DELAY).then(joinOp);
                                        })
                                        .then(Mono.just("done"));
                            });
                })
                .doFinally(signal -> running.set(false))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        done -> log.info("Channel discovery join sweep: completed ({})", done),
                        error -> {
                            if (error instanceof MembershipCapReachedException capEx) {
                                log.warn("Channel discovery join sweep: STOPPED — collector botId={} hit membership cap (CHANNELS_TOO_MUCH) on chatId={}. "
                                                + "Candidate NOT marked processed and will be retried on next sweep.",
                                        capEx.getBotId(), capEx.getChatId());
                                atCapBotIds.add(capEx.getBotId());
                            } else if (error instanceof FloodWaitParkedException) {
                                log.warn("Channel discovery join sweep: STOPPED — FLOOD_WAIT backoff active. "
                                        + "Candidate NOT marked processed and will be retried on next sweep.");
                            } else {
                                log.error("Channel discovery join sweep: failed", error);
                            }
                            running.set(false);
                        }
                );
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    /**
     * Resolves the collector account's active TDLib client.
     * Returns a {@link CollectorClient} record carrying both botId (for cap logging)
     * and the TDLib client facade.
     * Returns empty (and logs a warning) when no collector is registered or its
     * TDLib session is not yet initialized.
     */
    private Mono<CollectorClient> resolveCollectorClient() {
        return telegramAccountRepository.findCollector()
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("Channel discovery join sweep: no collector account found; skipping");
                    return Mono.empty();
                }))
                .flatMap(account -> {
                    String botId = account.getBotId();
                    TelegramClientFacade client = telegramClientManager.getClient(botId);
                    if (client == null) {
                        log.warn("Channel discovery join sweep: collector botId={} has no active TDLib client; skipping", botId);
                        return Mono.<CollectorClient>empty();
                    }
                    log.debug("Channel discovery join sweep: using collector botId={}", botId);
                    return Mono.just(new CollectorClient(botId, client));
                });
    }

    /**
     * Processes a single candidate: topical gate → join → mute → register → mark processed.
     *
     * <p>On a permanent error (bad chat-id, private channel, etc.) the candidate is
     * still marked processed so the scheduler does not retry it on every run.
     *
     * <p>On {@link MembershipCapReachedException} the candidate is <em>NOT</em> marked
     * processed — it will be retried automatically on the next sweep once headroom returns.
     *
     * <p>On {@link FloodWaitParkedException} (FLOOD_WAIT backoff active) the candidate is
     * also <em>NOT</em> marked processed — it will be retried on the next sweep once the
     * bounded backoff has expired.
     *
     * <p>All other errors are treated as permanent failures: the candidate is marked
     * processed to avoid an infinite-retry loop.
     */
    private Mono<Void> processCandidate(ChannelCandidate candidate, String botId, TelegramClientFacade client) {
        Long id = candidate.getId();
        String candidateText = candidate.getCandidate();

        long chatId;
        try {
            chatId = Long.parseLong(candidateText);
        } catch (NumberFormatException ex) {
            log.warn("Channel discovery join sweep: candidate id={} has non-numeric value '{}', marking processed",
                    id, candidateText);
            return channelCandidateRepository.markProcessed(id).then();
        }

        log.info("Channel discovery join sweep: processing candidate id={} chatId={}", id, chatId);

        return fetchChatTitle(chatId, client)
                .flatMap(title -> {
                    Optional<String> denied = denylistService.matchedToken(title);
                    if (denied.isPresent()) {
                        log.info("Channel discovery join sweep: SKIPPING off-topic channel '{}' chatId={} (matched denylist token '{}')",
                                title, chatId, denied.get());
                        // Mark processed so the same candidate is not retried on the next sweep.
                        return channelCandidateRepository.markProcessed(id).then();
                    }
                    return doJoinMuteRegister(chatId, id, botId, client);
                });
    }

    /**
     * Fetches the channel title via {@code GetChat}. Fail-open: on any TDLib error or
     * when no title is available returns an empty string so the denylist gate does not
     * block the join on a lookup failure.
     */
    private Mono<String> fetchChatTitle(long chatId, TelegramClientFacade client) {
        return Mono.fromFuture(() -> client.send(new TdApi.GetChat(chatId)))
                .cast(TdApi.Chat.class)
                .map(chat -> chat.title != null ? chat.title : "")
                .onErrorResume(ex -> {
                    log.debug("Channel discovery join sweep: GetChat failed for chatId={} ({}), proceeding without title gate",
                            chatId, ex.getMessage());
                    return Mono.just("");
                });
    }

    /**
     * Executes the join → mute → register → mark-processed pipeline.
     *
     * <p>When the join succeeds (Telegram accepted it), the at-cap flag for this botId
     * is cleared — a successful join is ground-truth proof that real headroom exists.
     * This is the ONLY place the flag is cleared; clearing it based on the DB count
     * alone would be wrong because Telegram counts all dialogs (DMs, groups, etc.),
     * not just harvested channels.
     *
     * <p>{@link MembershipCapReachedException} and {@link FloodWaitParkedException} are
     * intentionally NOT caught by the general error handler here — they propagate to the
     * outer {@code subscribe} error handler so the candidate is NOT marked processed and
     * will be retried on the next sweep. All other errors are caught, logged at WARN,
     * and the candidate is marked processed to avoid infinite-retry loops.
     */
    private Mono<Void> doJoinMuteRegister(long chatId, Long candidateId, String botId, TelegramClientFacade client) {
        return joinChat(chatId, botId, client)
                // A successful JoinChat is ground truth that the account has headroom.
                // Clear any stale at-cap flag so the next sweep will attempt joins again.
                .doOnSuccess(v -> {
                    if (atCapBotIds.remove(botId)) {
                        log.info("Channel discovery join sweep: join succeeded for chatId={} — cap flag cleared for botId={}",
                                chatId, botId);
                    }
                })
                .then(muteChat(chatId, client))
                .then(registerChannel(chatId))
                .doOnSuccess(v -> log.info("Channel discovery join sweep: joined+muted chatId={}", chatId))
                .onErrorResume(ex -> {
                    if (ex instanceof MembershipCapReachedException) {
                        // Re-throw: do NOT mark the candidate processed.
                        return Mono.error(ex);
                    }
                    if (ex instanceof FloodWaitParkedException) {
                        // Re-throw: join is parked by FLOOD_WAIT backoff; candidate must
                        // stay unprocessed so the next sweep retries it after the backoff.
                        log.warn("Channel discovery join sweep: FLOOD_WAIT backoff active for chatId={}, will retry next sweep",
                                chatId);
                        return Mono.error(ex);
                    }
                    log.warn("Channel discovery join sweep: failed for chatId={}: {}", chatId, ex.getMessage());
                    return Mono.empty();
                })
                .then(channelCandidateRepository.markProcessed(candidateId))
                .doOnNext(rows -> log.debug("Channel discovery join sweep: marked processed id={}", candidateId))
                .then();
    }

    /**
     * Sends a {@code JoinChat} request for the given chat-id.
     *
     * <ul>
     *   <li>{@code USER_ALREADY_PARTICIPANT} — treated as success (no error).</li>
     *   <li>{@code CHANNELS_TOO_MUCH} — thrown as {@link MembershipCapReachedException}
     *       so the candidate is NOT marked processed and will be retried on the next sweep
     *       once headroom has returned.</li>
     *   <li>{@code FLOOD_WAIT} / backoff active — thrown as {@link FloodWaitParkedException}
     *       so the candidate is NOT marked processed and will be retried on the next sweep
     *       once the bounded backoff has expired.</li>
     *   <li>All other errors — re-thrown for the caller's generic error handler.</li>
     * </ul>
     */
    private Mono<Void> joinChat(long chatId, String botId, TelegramClientFacade client) {
        return Mono.fromFuture(() -> client.send(new TdApi.JoinChat(chatId)))
                .onErrorResume(ex -> {
                    String msg = ex.getMessage();
                    if (msg != null && msg.contains("USER_ALREADY_PARTICIPANT")) {
                        log.info("Channel discovery join sweep: already a participant in chatId={}", chatId);
                        return Mono.empty();
                    }
                    if (msg != null && msg.contains("CHANNELS_TOO_MUCH")) {
                        return Mono.error(new MembershipCapReachedException(botId, chatId, msg));
                    }
                    if (msg != null && msg.contains("FLOOD_WAIT")) {
                        return Mono.error(new FloodWaitParkedException(chatId, msg));
                    }
                    return Mono.error(ex);
                })
                .then();
    }

    /**
     * Mutes the chat forever so the owner's notification feed is not flooded.
     * Mirrors the exact pattern used in {@code PersonaMembershipReconciliationService}.
     * Mute failure is logged but does not abort the overall flow.
     */
    private Mono<Void> muteChat(long chatId, TelegramClientFacade client) {
        TdApi.ChatNotificationSettings settings = new TdApi.ChatNotificationSettings();
        settings.muteFor = MUTE_FOREVER_SECONDS;
        return Mono.fromFuture(() -> client.send(new TdApi.SetChatNotificationSettings(chatId, settings)))
                .doOnSuccess(ignored -> log.debug("Channel discovery join sweep: muted chatId={}", chatId))
                .onErrorResume(ex -> {
                    log.warn("Channel discovery join sweep: mute failed for chatId={}: {}", chatId, ex.getMessage());
                    return Mono.empty();
                })
                .then();
    }

    /**
     * Registers the joined channel in {@code tgscan.channels} as a broadcast source
     * so it is picked up by the ingestion pipeline.
     *
     * <p>Only inserts a placeholder row when the channel is not yet tracked — if the
     * Python scanner has already recorded the channel we leave its data intact
     * (title, score, subscribers, etc.).  The ingestion pipeline and live TDLib
     * updates will populate any missing metadata later.
     */
    private Mono<Void> registerChannel(long chatId) {
        return channelRepository.findByChatId(chatId)
                .hasElement()
                .flatMap(exists -> {
                    if (exists) {
                        log.debug("Channel discovery join sweep: chatId={} already in tgscan.channels, skipping upsert", chatId);
                        return Mono.empty();
                    }
                    // Insert a minimal placeholder; title will be filled by TDLib listener or ingestion.
                    return channelRepository.upsertBroadcastChannel(chatId, null)
                            .doOnNext(rows -> log.debug("Channel discovery join sweep: registered chatId={} (rows={})", chatId, rows))
                            .then();
                });
    }

    /**
     * Transient signal: a JoinChat was rejected by the FloodWaitTelegramClientFacade
     * because a FLOOD_WAIT backoff is currently active.  The join will succeed once
     * the bounded backoff window expires, so the candidate must NOT be marked processed
     * — the next sweep will retry it automatically.
     */
    private static final class FloodWaitParkedException extends RuntimeException {
        FloodWaitParkedException(long chatId, String detail) {
            super("FLOOD_WAIT backoff active for chatId=" + chatId + ": " + detail);
        }
    }

    /** Carrier for collector botId + TDLib client, resolved once per sweep. */
    private record CollectorClient(String botId, TelegramClientFacade client) {}
}
