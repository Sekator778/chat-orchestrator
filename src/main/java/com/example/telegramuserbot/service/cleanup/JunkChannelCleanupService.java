package com.example.telegramuserbot.service.cleanup;

import com.example.telegramuserbot.domain.Channel;
import com.example.telegramuserbot.dto.ChannelEngagementEntry;
import com.example.telegramuserbot.repository.ChannelRepository;
import com.example.telegramuserbot.repository.TelegramAccountRepository;
import com.example.telegramuserbot.service.TelegramClientManager;
import com.example.telegramuserbot.service.channels.ChannelActivityService;
import com.example.telegramuserbot.telegram.TelegramClientFacade;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * On-demand cleanup that LEAVES low-value or off-topic broadcast channels the collector joined.
 * Triggered via {@code AdminCleanupController}.
 *
 * <p>Two leave-paths:
 * <ol>
 *   <li><strong>Low-subscriber cleanup</strong> ({@code /api/admin/cleanup/leave-junk}):
 *       leaves channels whose known subscriber count is below a threshold.</li>
 *   <li><strong>Off-topic cleanup</strong> ({@code /api/admin/cleanup/leave-offtopic}):
 *       leaves channels whose title, username, or description matches the topical denylist
 *       read from {@link TopicalDenylistService}, regardless of subscriber count.</li>
 * </ol>
 *
 * <p><strong>Safety by design:</strong>
 * <ul>
 *   <li>Broadcast channels only — never groups (the reply plane).</li>
 *   <li>Known subs only ({@code subscribers IS NOT NULL}) for the low-sub path — unknown subs
 *       are unenriched, not low.</li>
 *   <li>Recently-joined ≥threshold channels are NOT touched (their empty harvest history is a
 *       join-recency artifact, not a quality signal — re-joining is the rate-limited ban-risk op,
 *       so we bias toward keep).</li>
 *   <li>{@code channel-cleanup.protected-chat-ids} are never left (test plane / pinned).</li>
 *   <li>{@code LeaveChat} is parked by {@link com.example.telegramuserbot.telegram.FloodWaitTelegramClientFacade}
 *       during FLOOD_WAIT backoff; a parked leave just isn't marked, so a re-run retries it.</li>
 *   <li>Leaves are sequential with a fixed inter-leave delay; {@code join_status='left'} is written
 *       as each succeeds, so the run is idempotent and resumable.</li>
 * </ul>
 */
@Service
public class JunkChannelCleanupService {

    private static final Logger log = LoggerFactory.getLogger(JunkChannelCleanupService.class);

    private final ChannelRepository channelRepository;
    private final TelegramAccountRepository telegramAccountRepository;
    private final TelegramClientManager telegramClientManager;
    private final TopicalDenylistService denylistService;
    private final ChannelActivityService channelActivityService;

    /** Chats that must NEVER be left (test plane, pinned sources). Comma-separated chat ids. */
    @Value("${channel-cleanup.protected-chat-ids:}")
    private String protectedChatIdsRaw;

    /** Seconds between consecutive LeaveChat calls — keep well below Telegram's flood threshold. */
    @Value("${channel-cleanup.inter-leave-seconds:60}")
    private long interLeaveSeconds;

    /** Guards against overlapping live runs. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    public JunkChannelCleanupService(ChannelRepository channelRepository,
                                     TelegramAccountRepository telegramAccountRepository,
                                     TelegramClientManager telegramClientManager,
                                     TopicalDenylistService denylistService,
                                     ChannelActivityService channelActivityService) {
        this.channelRepository = channelRepository;
        this.telegramAccountRepository = telegramAccountRepository;
        this.telegramClientManager = telegramClientManager;
        this.denylistService = denylistService;
        this.channelActivityService = channelActivityService;
    }

    public record Candidate(long chatId, String title, Long subscribers, String joinedAt) {}

    /**
     * Off-topic candidate includes the matched denylist token so the owner can see WHY
     * each channel was flagged in the dry-run response.
     */
    public record OffTopicCandidate(long chatId, String title, String username,
                                    Long subscribers, String joinedAt, String matchedToken) {}

    /**
     * Inactive candidate includes the composite activity metrics so the owner can see WHY
     * each channel was flagged in the dry-run response.
     *
     * <p>{@code postFrequencyPerDay} — posts harvested / window-days; 0.0 = silent channel.<br>
     * {@code engagementPerSub} — avg views / subscribers; null when subscriber or view data is
     * absent (treated as "unknown engagement" → bias-to-keep; the null limb does NOT trigger a
     * leave on its own — a silent channel is already caught by postFrequencyPerDay).<br>
     * {@code reasonFlag} — human-readable flag: {@code "low_posts"}, {@code "low_engagement"},
     * or {@code "low_posts+low_engagement"}.
     */
    public record InactiveCandidate(long chatId, String title, Long subscribers, String joinedAt,
                                    double postFrequencyPerDay, Double engagementPerSub,
                                    String reasonFlag) {}

    private Set<Long> protectedChatIds() {
        if (protectedChatIdsRaw == null || protectedChatIdsRaw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(protectedChatIdsRaw.split(","))
                .map(String::trim).filter(s -> !s.isBlank())
                .map(Long::parseLong).collect(Collectors.toSet());
    }

    // -------------------------------------------------------------------------
    // Low-subscriber path (existing behavior, unchanged)
    // -------------------------------------------------------------------------

    /** Dry-run: the channels that WOULD be left, with the fields needed to spot a mis-flag. */
    public Mono<List<Candidate>> preview(int minSubscribers, int limit) {
        Set<Long> protectedIds = protectedChatIds();
        return channelRepository.findLowValueBroadcastToLeave(minSubscribers, limit)
                .filter(ch -> !protectedIds.contains(ch.getChatId()))
                .map(this::toCandidate)
                .collectList();
    }

    /**
     * Live run, fired asynchronously (the caller returns immediately). Leaves each candidate via the
     * collector account, {@code interLeaveSeconds} apart, marking {@code join_status='left'} as it goes.
     */
    public Mono<Integer> execute(int minSubscribers, int limit) {
        if (!running.compareAndSet(false, true)) {
            log.warn("[JunkCleanup] live run already in progress — ignoring");
            return Mono.just(-1);
        }
        Set<Long> protectedIds = protectedChatIds();
        return resolveCollectorClient()
                .flatMapMany(client -> channelRepository.findLowValueBroadcastToLeave(minSubscribers, limit)
                        .filter(ch -> !protectedIds.contains(ch.getChatId()))
                        .index()
                        .concatMap(indexed -> {
                            Channel ch = indexed.getT2();
                            Mono<Integer> leave = leaveAndMark(ch, client);
                            return indexed.getT1() == 0
                                    ? leave
                                    : Mono.delay(Duration.ofSeconds(interLeaveSeconds)).then(leave);
                        }))
                .reduce(0, Integer::sum)
                .doOnSuccess(n -> log.info("[JunkCleanup] live run complete — left {} channel(s)", n))
                .doFinally(sig -> running.set(false));
    }

    /** Kick a live run on a bounded-elastic thread so the HTTP caller returns immediately. */
    public void executeAsync(int minSubscribers, int limit) {
        execute(minSubscribers, limit)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        n -> { /* terminal log emitted inside execute() */ },
                        err -> log.error("[JunkCleanup] live run errored", err));
    }

    // -------------------------------------------------------------------------
    // Off-topic path (new: denylist-matching, any subscriber count)
    // -------------------------------------------------------------------------

    /**
     * Dry-run: returns joined broadcast channels whose title/username/description matches the
     * topical denylist, along with the matching token so the owner can review why each was flagged.
     */
    public Mono<List<OffTopicCandidate>> previewOffTopic() {
        Set<Long> protectedIds = protectedChatIds();
        return channelRepository.findJoinedBroadcastChannels()
                .filter(ch -> !protectedIds.contains(ch.getChatId()))
                .flatMap(ch -> {
                    Optional<String> tok = denylistService.matchedToken(
                            ch.getTitle(), ch.getUsername(), ch.getDescription());
                    if (tok.isEmpty()) {
                        return Flux.empty();
                    }
                    return Flux.just(toOffTopicCandidate(ch, tok.get()));
                })
                .collectList();
    }

    /**
     * Live off-topic run: leaves every matched channel sequentially, rate-limited.
     * Fired asynchronously via {@link #executeOffTopicAsync()}.
     */
    public Mono<Integer> executeOffTopic() {
        if (!running.compareAndSet(false, true)) {
            log.warn("[JunkCleanup] live run already in progress — ignoring");
            return Mono.just(-1);
        }
        Set<Long> protectedIds = protectedChatIds();
        return resolveCollectorClient()
                .flatMapMany(client ->
                        channelRepository.findJoinedBroadcastChannels()
                                .filter(ch -> !protectedIds.contains(ch.getChatId()))
                                .filter(ch -> denylistService.matchedToken(
                                        ch.getTitle(), ch.getUsername(), ch.getDescription()).isPresent())
                                .index()
                                .concatMap(indexed -> {
                                    Channel ch = indexed.getT2();
                                    Mono<Integer> leave = leaveAndMark(ch, client);
                                    return indexed.getT1() == 0
                                            ? leave
                                            : Mono.delay(Duration.ofSeconds(interLeaveSeconds)).then(leave);
                                }))
                .reduce(0, Integer::sum)
                .doOnSuccess(n -> log.info("[JunkCleanup] off-topic run complete — left {} channel(s)", n))
                .doFinally(sig -> running.set(false));
    }

    /** Kick an off-topic live run on a bounded-elastic thread so the HTTP caller returns immediately. */
    public void executeOffTopicAsync() {
        executeOffTopic()
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        n -> { /* terminal log emitted inside executeOffTopic() */ },
                        err -> log.error("[JunkCleanup] off-topic live run errored", err));
    }

    // -------------------------------------------------------------------------
    // Inactive / low-engagement path (B2: post-frequency + engagement-per-sub)
    // -------------------------------------------------------------------------

    /**
     * Selects joined broadcast channels that are inactive or low-engagement, applying:
     * <ol>
     *   <li><strong>Grace period</strong>: only considers channels joined at least
     *       {@code minDaysJoined} days ago — freshly-joined channels look dead because
     *       the harvest only captures posts <em>after</em> joining (recency-contamination
     *       lesson from #97). Unknown {@code joinedAt} → bias-to-keep → excluded.</li>
     *   <li><strong>Activity floor</strong> (OR): candidate if
     *       {@code postFrequencyPerDay < minPostsPerDay}
     *       OR ({@code engagementPerSub} is non-null AND {@code engagementPerSub < minEngagementPerSub}).
     *       The null guard on {@code engagementPerSub} prevents false positives for channels
     *       that post actively but lack view/subscriber metadata.</li>
     *   <li><strong>Protected ids</strong>: test-plane and pinned channels are never left.</li>
     * </ol>
     *
     * <p>The metrics window is set to {@code minDaysJoined} days so the window never extends
     * before a candidate's join date — avoiding recency-contamination in window form.
     *
     * @param minDaysJoined    grace period in days; channels joined more recently are excluded
     * @param minPostsPerDay   posts/day floor; below this threshold a channel is flagged
     * @param minEngagementPerSub engagement floor; below this (and non-null) a channel is flagged
     * @return reactive stream of inactive candidates (channel + metrics for dry-run transparency)
     */
    private Flux<InactiveCandidate> selectInactiveCandidates(int minDaysJoined,
                                                              double minPostsPerDay,
                                                              double minEngagementPerSub) {
        Set<Long> protectedIds = protectedChatIds();
        Instant graceCutoff = Instant.now().minus(Duration.ofDays(minDaysJoined));

        // Use minDaysJoined as the metrics window so the window doesn't extend before join date
        return channelActivityService.reportEngagement(minDaysJoined)
                .collectMap(ChannelEngagementEntry::chatId)
                .flatMapMany(metricsById ->
                        channelRepository.findJoinedBroadcastChannels()
                                .filter(ch -> !protectedIds.contains(ch.getChatId()))
                                // Grace-period guard: unknown joinedAt → keep
                                .filter(ch -> ch.getJoinedAt() != null
                                        && ch.getJoinedAt().isBefore(graceCutoff))
                                .flatMap(ch -> {
                                    ChannelEngagementEntry m = metricsById.get(ch.getChatId());
                                    if (m == null) {
                                        // Not in engagement map (shouldn't happen) → keep
                                        return Flux.empty();
                                    }

                                    boolean lowPosts = m.postFrequencyPerDay() < minPostsPerDay;
                                    // Null engagementPerSub → bias-to-keep for this limb;
                                    // an actively-posting channel with missing view data is NOT
                                    // flagged on engagement alone — it's already covered by posts.
                                    boolean lowEngagement = m.engagementPerSub() != null
                                            && m.engagementPerSub() < minEngagementPerSub;

                                    if (!lowPosts && !lowEngagement) {
                                        return Flux.empty();
                                    }

                                    String reason = lowPosts && lowEngagement
                                            ? "low_posts+low_engagement"
                                            : lowPosts ? "low_posts" : "low_engagement";

                                    return Flux.just(new InactiveCandidate(
                                            ch.getChatId(),
                                            ch.getTitle(),
                                            ch.getSubscribers(),
                                            ch.getJoinedAt().toString(),
                                            m.postFrequencyPerDay(),
                                            m.engagementPerSub(),
                                            reason));
                                }));
    }

    /**
     * Dry-run: returns joined broadcast channels that would be left due to inactivity /
     * low engagement, annotated with their metrics so the owner can review each flag.
     */
    public Mono<List<InactiveCandidate>> previewInactive(int minDaysJoined,
                                                          double minPostsPerDay,
                                                          double minEngagementPerSub) {
        return selectInactiveCandidates(minDaysJoined, minPostsPerDay, minEngagementPerSub)
                .collectList();
    }

    /**
     * Live run: leaves every inactive/low-engagement channel sequentially, rate-limited.
     * Fired asynchronously via {@link #executeInactiveAsync}.
     */
    public Mono<Integer> executeInactive(int minDaysJoined,
                                          double minPostsPerDay,
                                          double minEngagementPerSub) {
        if (!running.compareAndSet(false, true)) {
            log.warn("[JunkCleanup] live run already in progress — ignoring");
            return Mono.just(-1);
        }
        return resolveCollectorClient()
                .flatMapMany(client ->
                        selectInactiveCandidates(minDaysJoined, minPostsPerDay, minEngagementPerSub)
                                .index()
                                .concatMap(indexed -> {
                                    InactiveCandidate ic = indexed.getT2();
                                    // Reconstruct a minimal Channel shell so leaveAndMark can log
                                    Channel ch = new Channel();
                                    ch.setChatId(ic.chatId());
                                    ch.setTitle(ic.title());
                                    ch.setSubscribers(ic.subscribers());
                                    Mono<Integer> leave = leaveAndMark(ch, client);
                                    return indexed.getT1() == 0
                                            ? leave
                                            : Mono.delay(Duration.ofSeconds(interLeaveSeconds)).then(leave);
                                }))
                .reduce(0, Integer::sum)
                .doOnSuccess(n -> log.info("[JunkCleanup] inactive run complete — left {} channel(s)", n))
                .doFinally(sig -> running.set(false));
    }

    /** Kick an inactive live run on a bounded-elastic thread so the HTTP caller returns immediately. */
    public void executeInactiveAsync(int minDaysJoined, double minPostsPerDay, double minEngagementPerSub) {
        executeInactive(minDaysJoined, minPostsPerDay, minEngagementPerSub)
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        n -> { /* terminal log emitted inside executeInactive() */ },
                        err -> log.error("[JunkCleanup] inactive live run errored", err));
    }

    // -------------------------------------------------------------------------
    // Shared helpers
    // -------------------------------------------------------------------------

    private Mono<Integer> leaveAndMark(Channel ch, TelegramClientFacade client) {
        long chatId = ch.getChatId();
        return Mono.fromFuture(() -> client.send(new TdApi.LeaveChat(chatId)))
                .then(channelRepository.markChannelLeft(chatId))
                .doOnSuccess(rows -> log.info("[JunkCleanup] LEFT chatId={} subs={} title='{}'",
                        chatId, ch.getSubscribers(), ch.getTitle()))
                .thenReturn(1)
                .onErrorResume(err -> {
                    log.warn("[JunkCleanup] leave FAILED chatId={} title='{}': {} (left 'joined', will retry on re-run)",
                            chatId, ch.getTitle(), err.getMessage());
                    return Mono.just(0);
                });
    }

    private Candidate toCandidate(Channel ch) {
        return new Candidate(ch.getChatId(), ch.getTitle(), ch.getSubscribers(),
                ch.getJoinedAt() != null ? ch.getJoinedAt().toString() : null);
    }

    private OffTopicCandidate toOffTopicCandidate(Channel ch, String matchedToken) {
        return new OffTopicCandidate(
                ch.getChatId(),
                ch.getTitle(),
                ch.getUsername(),
                ch.getSubscribers(),
                ch.getJoinedAt() != null ? ch.getJoinedAt().toString() : null,
                matchedToken);
    }

    private Mono<TelegramClientFacade> resolveCollectorClient() {
        return telegramAccountRepository.findCollector()
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("[JunkCleanup] no collector account found; skipping");
                    return Mono.empty();
                }))
                .flatMap(account -> {
                    String botId = account.getBotId();
                    TelegramClientFacade client = telegramClientManager.getClient(botId);
                    if (client == null) {
                        log.warn("[JunkCleanup] collector botId={} has no active TDLib client; skipping", botId);
                        return Mono.<TelegramClientFacade>empty();
                    }
                    return Mono.just(client);
                });
    }
}
