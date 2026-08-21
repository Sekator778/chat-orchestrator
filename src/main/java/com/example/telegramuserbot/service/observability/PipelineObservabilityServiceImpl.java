package com.example.telegramuserbot.service.observability;

import com.example.telegramuserbot.config.BotInstanceProvider;
import com.example.telegramuserbot.domain.PipelineSnapshot;
import com.example.telegramuserbot.repository.PipelineSnapshotRepository;
import com.example.telegramuserbot.service.publishing.TelegramMessageSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Implementation of the pipeline observability service.
 *
 * <p>Queries bot.messages and bot.digest_history to produce health metrics,
 * saves them to bot.pipeline_snapshots, and fires Telegram alerts on anomalies.</p>
 */
@Service
public final class PipelineObservabilityServiceImpl implements PipelineObservabilityService {

    private static final Logger log = LoggerFactory.getLogger(PipelineObservabilityServiceImpl.class);
    private static final DateTimeFormatter ALERT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int SNAPSHOT_PERIOD_HOURS = 6;

    private final DatabaseClient db;
    private final PipelineSnapshotRepository snapshots;
    private final TelegramMessageSender sender;
    private final BotInstanceProvider botInstanceProvider;

    @Value("${pipeline.observability.alert-chat-id:0}")
    private long alertChatId;

    /**
     * Constructs the observability service.
     *
     * @param db r2dbc database client for raw SQL queries
     * @param snapshots snapshot repository
     * @param sender telegram message sender for alerts
     * @param botInstanceProvider provider for the primary bot instance ID
     */
    public PipelineObservabilityServiceImpl(
            DatabaseClient db,
            PipelineSnapshotRepository snapshots,
            TelegramMessageSender sender,
            BotInstanceProvider botInstanceProvider) {
        this.db = db;
        this.snapshots = snapshots;
        this.sender = sender;
        this.botInstanceProvider = botInstanceProvider;
    }

    @Override
    public Mono<PipelineSnapshot> captureSnapshot() {
        return buildSnapshot()
                .flatMap(snapshots::save)
                .doOnNext(this::logSnapshot)
                .flatMap(this::maybeAlert)
                .timeout(Duration.ofSeconds(30));
    }

    @Override
    public Mono<Void> logScoreDistribution() {
        return db.sql("""
                        SELECT
                          COUNT(*) FILTER (WHERE importance < 0.01)            AS bucket_0,
                          COUNT(*) FILTER (WHERE importance >= 0.01 AND importance < 0.05) AS bucket_1,
                          COUNT(*) FILTER (WHERE importance >= 0.05 AND importance < 0.10) AS bucket_2,
                          COUNT(*) FILTER (WHERE importance >= 0.10)            AS bucket_3
                        FROM bot.messages
                        WHERE created_at > NOW() - INTERVAL '24 hours'
                        """)
                .fetch()
                .one()
                .doOnNext(row -> log.info(
                        "Score distribution (last 24h):%n  0.00–0.01: {:>6} messages%n  0.01–0.05: {:>6} messages%n  0.05–0.10: {:>6} messages%n  0.10+    : {:>6} messages",
                        row.get("bucket_0"), row.get("bucket_1"),
                        row.get("bucket_2"), row.get("bucket_3")))
                .timeout(Duration.ofSeconds(15))
                .then();
    }

    private Mono<PipelineSnapshot> buildSnapshot() {
        Mono<Long> messagesIngested = db.sql(
                        "SELECT COUNT(*) FROM bot.messages WHERE created_at > NOW() - INTERVAL '1 hour'")
                .fetch().one()
                .map(r -> toLong(r.get("count")));

        Mono<Long> scoredFresh = db.sql(
                        "SELECT COUNT(*) FROM bot.messages WHERE importance > 0 AND created_at > NOW() - INTERVAL '"
                                + SNAPSHOT_PERIOD_HOURS + " hours'")
                .fetch().one()
                .map(r -> toLong(r.get("count")));

        Mono<Double> topScore = db.sql(
                        "SELECT MAX(importance) FROM bot.messages WHERE created_at > NOW() - INTERVAL '24 hours'")
                .fetch().one()
                .map(r -> toDouble(r.get("max")));

        Mono<Double> minScore = db.sql(
                        "SELECT MIN(importance) FROM bot.messages WHERE importance > 0 AND created_at > NOW() - INTERVAL '24 hours'")
                .fetch().one()
                .map(r -> toDouble(r.get("min")));

        Mono<Long> published = db.sql(
                        "SELECT COUNT(*) FROM bot.digest_history WHERE published_at > NOW() - INTERVAL '24 hours' AND status = 'PUBLISHED'")
                .fetch().one()
                .map(r -> toLong(r.get("count")));

        Mono<Long> personas = db.sql(
                        "SELECT COUNT(DISTINCT persona_id) FROM bot.digest_history WHERE published_at > NOW() - INTERVAL '24 hours' AND status = 'PUBLISHED'")
                .fetch().one()
                .map(r -> toLong(r.get("count")));

        Mono<String> topPreview = db.sql("""
                        SELECT string_agg(preview, ' | ')
                        FROM (
                          SELECT SUBSTRING(content, 1, 80) AS preview
                          FROM bot.messages
                          WHERE created_at > NOW() - INTERVAL '24 hours'
                          ORDER BY importance DESC NULLS LAST
                          LIMIT 3
                        ) t
                        """)
                .fetch().one()
                .map(r -> r.get("string_agg") != null ? r.get("string_agg").toString() : "");

        return Mono.zip(messagesIngested, scoredFresh, topScore, minScore, published, personas, topPreview)
                .map(t -> {
                    PipelineSnapshot s = new PipelineSnapshot(SNAPSHOT_PERIOD_HOURS);
                    s.setMessagesIngested(t.getT1());
                    s.setScoredFresh(t.getT2());
                    s.setTopScore(t.getT3());
                    s.setMinNonzeroScore(t.getT4());
                    s.setPublishedToday(t.getT5());
                    s.setPersonasPublished(t.getT6().intValue());
                    s.setTopMessagesPreview(t.getT7());
                    applyAnomalyDetection(s);
                    return s;
                });
    }

    private void applyAnomalyDetection(PipelineSnapshot s) {
        List<String> reasons = new ArrayList<>();
        if (s.getMessagesIngested() == 0) {
            reasons.add("No messages ingested in last hour");
        }
        if (s.getScoredFresh() == 0) {
            reasons.add("No scored messages in last " + SNAPSHOT_PERIOD_HOURS + " hours");
        }
        if (s.getTopScore() == null || s.getTopScore() == 0.0) {
            reasons.add("Top importance score is zero");
        }
        s.setAnomaly(!reasons.isEmpty());
        s.setAnomalyReasons(reasons.toArray(new String[0]));
    }

    private void logSnapshot(PipelineSnapshot s) {
        log.info(
                "Pipeline snapshot: messages_last_hour={}, scored_fresh={}, top_score={}, published_today={}, anomaly={}",
                s.getMessagesIngested(), s.getScoredFresh(), s.getTopScore(),
                s.getPublishedToday(), s.isAnomaly());
    }

    private Mono<PipelineSnapshot> maybeAlert(PipelineSnapshot s) {
        if (!s.isAnomaly() || alertChatId == 0) {
            return Mono.just(s);
        }
        String text = buildAlertText(s);
        return sender.send(botInstanceProvider.getInstanceId(), alertChatId, text)
                .doOnSuccess(msg -> log.warn("Pipeline anomaly alert sent to chat {}", alertChatId))
                .onErrorResume(e -> {
                    log.error("Failed to send pipeline alert to chat {}", alertChatId, e);
                    return Mono.empty();
                })
                .thenReturn(s);
    }

    private String buildAlertText(PipelineSnapshot s) {
        String ts = s.getSnapshottedAt().format(ALERT_FMT);
        StringBuilder sb = new StringBuilder();
        sb.append("⚠️ Pipeline alert [").append(ts).append("]\n");
        sb.append("- Messages last hour: ").append(s.getMessagesIngested());
        if (s.getMessagesIngested() == 0) sb.append(" ← problem");
        sb.append("\n- Scored fresh (").append(SNAPSHOT_PERIOD_HOURS).append("h): ").append(s.getScoredFresh());
        sb.append("\n- Published today: ").append(s.getPublishedToday());
        sb.append("\n- Top score: ").append(s.getTopScore() != null ? String.format("%.4f", s.getTopScore()) : "null");
        if (s.getTopScore() == null || s.getTopScore() == 0.0) sb.append(" ← problem");
        if (s.getAnomalyReasons() != null && s.getAnomalyReasons().length > 0) {
            sb.append("\nReasons:");
            for (String r : s.getAnomalyReasons()) {
                sb.append("\n  • ").append(r);
            }
        }
        return sb.toString();
    }

    private long toLong(Object val) {
        if (val == null) return 0L;
        return ((Number) val).longValue();
    }

    private double toDouble(Object val) {
        if (val == null) return 0.0;
        return ((Number) val).doubleValue();
    }
}
