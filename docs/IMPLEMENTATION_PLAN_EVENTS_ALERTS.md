# Implementation Plan: Events & Alerts System (Stage 2)

**Generated**: 2025-10-31
**Based on**: `tasks_and_manuals/events_and_alerts_pipeline.md`

---

## Executive Summary

This document analyzes the current implementation status of the Events & Alerts system and provides a comprehensive plan to complete Stage 2 requirements.

**Current Status**: 🟡 **40% Complete** (Database layer exists, application layer missing)

**Goal**: Transform message stream into meaningful events (spikes, narratives, risks) and deliver them as alerts with minimal noise, high precision (≥70%), and low latency (≤120 sec).

---

## 1. Gap Analysis: What's Done vs What's Needed

### ✅ **COMPLETED** (Database Layer)

#### 1.1. Database Schema (changeset 017)
- ✅ `tgscan.events` table with all required fields:
  - `event_type`, `topic`, `window_start/end`, metrics, `confidence`, `severity`
  - `top_sources` (JSONB), `evidence` (JSONB), `root_cause`
  - Rate limiting fields: `rate_limit_key`, `last_alert_at`
- ✅ `tgscan.alerts` table:
  - `event_id`, `priority`, `channel`, `template`, `status`, `delivered_at`
- ✅ Indexes for performance:
  - `idx_tgscan_events_topic_window`, `idx_tgscan_events_severity`
  - Unique constraint on `alerts.event_id` (deduplication)

#### 1.2. Event Detection Logic
- ✅ `fn_detect_events()` stored procedure:
  - Sliding window analysis (configurable, default 15 minutes)
  - Baseline comparison using 12 historical windows
  - Spike ratio calculation: `current_count / baseline_average`
  - Confidence scoring: combines spike ratio, importance, source count
  - Event type classification:
    - `SPIKE` (general spikes)
    - `FUD/PANIC` (panic_ratio ≥ 0.5 or panic keywords)
    - `FOMO/LISTING` (listing triggers + spike ≥ 2x)
  - Deduplication: no duplicate events for same topic/type within 10 minutes
  - Evidence collection: top 5 messages by importance
  - Top sources: up to 5 channels by message count

#### 1.3. Alert Emission Logic
- ✅ `fn_emit_alerts()` stored procedure:
  - Converts events to alerts (1:1 mapping with unique constraint)
  - Priority mapping: `event.severity → alert.priority`
  - Template generation: formatted summary string
  - Status tracking: `pending` initially

---

### ❌ **MISSING** (Application Layer)

#### 2.1. Event Watcher Service
- ❌ No Java service calling `fn_detect_events()` periodically
- ❌ No scheduler for event detection (should run every 1-5 minutes)
- ❌ No monitoring/logging of event detection execution
- ❌ No error handling/retry logic

#### 2.2. Alert Router & Delivery Service
- ❌ No Java service calling `fn_emit_alerts()`
- ❌ No Telegram bot integration for alert delivery
- ❌ No status updates (`pending → delivered/failed`)
- ❌ No retry mechanism for failed deliveries
- ❌ No Dead Letter Queue (DLQ) for permanent failures

#### 2.3. Client Profiles & Routing
- ❌ No `client_profiles` table (topics, event_types, severity_min preferences)
- ❌ No per-client rate limiting
- ❌ No mute functionality (user suppression)
- ❌ No topic/event_type filtering per client
- ❌ No consolidated alerts (when >N events in short time)

#### 2.4. Hourly/Daily Rollups
- ❌ No `topic_hourly`, `topic_daily` tables
- ❌ No `news_hourly`, `news_daily` tables
- ❌ No aggregation functions (`fn_rollup_hour()`, `fn_rollup_day()`)
- ❌ No scheduled jobs for rollups (H+5, D+5 minutes)
- ❌ No reconciliation logic (H+65 for late messages)
- ❌ No admin summary reports (Top Topics/Top News)

#### 2.5. Feedback Loop
- ❌ No `alert_feedback` table
- ❌ No feedback buttons in alerts (👍/👎/🔕)
- ❌ No precision metrics calculation
- ❌ No quality reports (weekly precision, duplicate rate, latency)

#### 2.6. Monitoring & SRE
- ❌ No metrics for:
  - Events created per hour
  - Alerts sent/failed/suppressed per hour
  - Latency (t_first_msg → t_alert)
  - Precision (from feedback)
  - Duplicate rate
- ❌ No alerting for system failures (no events detected, alert delivery failures)
- ❌ No runbook documentation
- ❌ No configurable thresholds (without code deployment)

---

## 2. Implementation Plan (2-3 Sprints)

### **Sprint 1: Core Application Services** (Week 1-2)

#### Task 1.1: Event Watcher Service
**Priority**: 🔴 **P0 - Critical**

**Files to Create**:
- `src/main/java/com/example/telegramuserbot/service/events/EventDetectionService.java`
- `src/main/java/com/example/telegramuserbot/scheduler/EventDetectionScheduler.java`

**Implementation**:
```java
@Service
public class EventDetectionService {
    private final DatabaseClient databaseClient;

    public Mono<Integer> detectEvents(int windowMinutes, double minConfidence) {
        return databaseClient.sql("SELECT tgscan.fn_detect_events(:window, :conf)")
            .bind("window", windowMinutes)
            .bind("conf", minConfidence)
            .map(row -> row.get(0, Integer.class))
            .one()
            .doOnSuccess(count -> log.info("Event detection completed: {} events created", count))
            .doOnError(error -> log.error("Event detection failed", error));
    }
}

@Component
public class EventDetectionScheduler {
    @Scheduled(fixedRate = 300000) // Every 5 minutes
    public void detectEvents() {
        eventDetectionService.detectEvents(15, 0.45)
            .subscribe(/* handle result */);
    }
}
```

**Acceptance Criteria**:
- [ ] Scheduler runs every 5 minutes (configurable)
- [ ] Calls `fn_detect_events()` with configurable window and confidence
- [ ] Logs execution results (events created, errors)
- [ ] Metrics recorded: `events.detection.count`, `events.detection.latency`

---

#### Task 1.2: Alert Delivery Service
**Priority**: 🔴 **P0 - Critical**

**Files to Create**:
- `src/main/java/com/example/telegramuserbot/service/alerts/AlertDeliveryService.java`
- `src/main/java/com/example/telegramuserbot/service/alerts/TelegramAlertSender.java`
- `src/main/java/com/example/telegramuserbot/scheduler/AlertDeliveryScheduler.java`
- `src/main/java/com/example/telegramuserbot/repository/AlertRepository.java`
- `src/main/java/com/example/telegramuserbot/model/Alert.java`

**Implementation**:
```java
@Service
public class AlertDeliveryService {
    public Mono<Integer> emitAlerts(int limit) {
        // 1. Call fn_emit_alerts() to create alert records
        // 2. Fetch pending alerts
        // 3. For each alert: send via TelegramAlertSender
        // 4. Update status: delivered/failed
        // 5. Retry failed alerts (with exponential backoff)
    }
}

@Component
public class TelegramAlertSender {
    public Mono<Void> sendAlert(Alert alert) {
        // Format message using alert.template
        // Add evidence links (clickable message URLs)
        // Send to configured chat/channel
        // Add feedback buttons: 👍/👎/🔕
    }
}
```

**Acceptance Criteria**:
- [ ] Scheduler runs every 1 minute
- [ ] Calls `fn_emit_alerts()` to generate alert records
- [ ] Sends alerts via Telegram bot
- [ ] Updates `alerts.status` to `delivered` on success
- [ ] Updates `alerts.delivered_at` timestamp
- [ ] Retry logic: 3 attempts with exponential backoff (1s, 5s, 30s)
- [ ] Failed alerts marked as `failed` after exhausting retries
- [ ] Metrics: `alerts.sent.count`, `alerts.failed.count`, `alerts.delivery.latency`

---

#### Task 1.3: Entity Models & Repositories
**Priority**: 🔴 **P0 - Critical**

**Files to Create**:
- `src/main/java/com/example/telegramuserbot/model/Event.java`
- `src/main/java/com/example/telegramuserbot/model/Alert.java`
- `src/main/java/com/example/telegramuserbot/repository/EventRepository.java`
- `src/main/java/com/example/telegramuserbot/repository/AlertRepository.java`

**Implementation**:
```java
@Table("tgscan.events")
public record Event(
    Long id,
    String eventType,
    String topic,
    Instant windowStart,
    Instant windowEnd,
    Integer messageCount,
    Integer uniqueSources,
    Double avgImportance,
    Double panicRatio,
    Double spikeRatio,
    String topSources, // JSONB as String
    String rootCause,
    Double confidence,
    String severity,
    String evidence, // JSONB as String
    Instant createdAt,
    Instant updatedAt,
    Instant lastAlertAt,
    String rateLimitKey
) {}

public interface EventRepository extends ReactiveCrudRepository<Event, Long> {
    Flux<Event> findByCreatedAtAfterOrderByConfidenceDesc(Instant after, Pageable pageable);
}
```

---

### **Sprint 2: Client Profiles & Advanced Routing** (Week 3)

#### Task 2.1: Client Profiles Schema
**Priority**: 🟡 **P1 - High**

**Files to Create**:
- `src/main/resources/db/changelog/changes/018-client-profiles.yaml`
- `src/main/resources/db/changelog/sql/018-client-profiles.sql`

**Schema**:
```sql
CREATE TABLE tgscan.client_profiles (
    id               BIGSERIAL PRIMARY KEY,
    telegram_user_id BIGINT UNIQUE NOT NULL,
    username         TEXT,
    topics           TEXT[], -- Array of topics to monitor (e.g., '{BTC,ETH,TON}')
    event_types      TEXT[], -- Array: '{SPIKE,FUD/PANIC,FOMO/LISTING}'
    severity_min     TEXT DEFAULT 'medium', -- 'low', 'medium', 'high'
    rate_limit_per_topic INT DEFAULT 2, -- Max alerts per 30 min per topic
    mute_until       TIMESTAMPTZ,
    created_at       TIMESTAMPTZ DEFAULT now(),
    updated_at       TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE tgscan.alert_feedback (
    id               BIGSERIAL PRIMARY KEY,
    alert_id         BIGINT REFERENCES tgscan.alerts(id) ON DELETE CASCADE,
    client_id        BIGINT REFERENCES tgscan.client_profiles(id),
    feedback         TEXT, -- 'relevant', 'irrelevant', 'mute'
    comment          TEXT,
    created_at       TIMESTAMPTZ DEFAULT now()
);
```

**Acceptance Criteria**:
- [ ] Schema created and migrated
- [ ] Default profile created for admin user
- [ ] ProfileRepository implemented

---

#### Task 2.2: Smart Alert Routing
**Priority**: 🟡 **P1 - High**

**Files to Create**:
- `src/main/java/com/example/telegramuserbot/service/alerts/AlertRoutingService.java`

**Implementation**:
```java
@Service
public class AlertRoutingService {
    public Flux<Alert> routeEvent(Event event) {
        // 1. Find matching client profiles:
        //    - topic IN profile.topics (or profile.topics is empty = all)
        //    - event_type IN profile.event_types
        //    - event.severity >= profile.severity_min
        //    - profile.mute_until is null or past
        // 2. For each matching profile:
        //    - Check rate limit: count alerts for this topic in last 30 min
        //    - If under limit: create alert for this client
        //    - If over limit: skip (log suppression)
        // 3. Return created alerts
    }
}
```

**Acceptance Criteria**:
- [ ] Only relevant alerts sent to each client
- [ ] Rate limiting enforced: ≤ N alerts per 30 min per topic per client
- [ ] Muted clients receive no alerts during mute period
- [ ] Metrics: `alerts.suppressed.count` (by reason: rate_limit, mute, no_match)

---

#### Task 2.3: Alert Consolidation
**Priority**: 🟡 **P1 - High**

**Implementation**:
```java
public Mono<Alert> maybeConsolidate(List<Alert> pendingAlerts, String topic) {
    // If >N alerts (e.g., 3) for same topic within 10 min:
    //   - Create single consolidated alert
    //   - Template: "Consolidated Alert: 5 events detected for BTC in last 10 min"
    //   - Mark original alerts as 'consolidated'
    //   - Return consolidated alert
    // Else: return null (no consolidation needed)
}
```

**Acceptance Criteria**:
- [ ] Consolidation triggers when >3 events for same topic in 10 min
- [ ] Single alert sent instead of multiple
- [ ] Original alerts marked as `consolidated` status
- [ ] Consolidated alert contains count and summary

---

### **Sprint 3: Rollups, Feedback & Monitoring** (Week 4)

#### Task 3.1: Hourly/Daily Rollup Schema
**Priority**: 🟡 **P1 - High**

**Files to Create**:
- `src/main/resources/db/changelog/changes/019-rollups.yaml`
- `src/main/resources/db/changelog/sql/019-rollups.sql`

**Schema**:
```sql
CREATE TABLE tgscan.topic_hourly (
    id               BIGSERIAL PRIMARY KEY,
    topic            TEXT NOT NULL,
    hour             TIMESTAMPTZ NOT NULL,
    message_count    INTEGER,
    unique_sources   INTEGER,
    avg_importance   DOUBLE PRECISION,
    spike_ratio      DOUBLE PRECISION,
    top_channels     JSONB,
    created_at       TIMESTAMPTZ DEFAULT now(),
    UNIQUE (topic, hour)
);

CREATE TABLE tgscan.topic_daily (
    id               BIGSERIAL PRIMARY KEY,
    topic            TEXT NOT NULL,
    day              DATE NOT NULL,
    message_count    INTEGER,
    unique_sources   INTEGER,
    avg_importance   DOUBLE PRECISION,
    top_channels     JSONB,
    created_at       TIMESTAMPTZ DEFAULT now(),
    UNIQUE (topic, day)
);

CREATE OR REPLACE FUNCTION tgscan.fn_rollup_hour(p_hour TIMESTAMPTZ)
RETURNS INTEGER
LANGUAGE plpgsql AS $$
-- Aggregate messages from [p_hour, p_hour+1h) into topic_hourly
$$;

CREATE OR REPLACE FUNCTION tgscan.fn_rollup_day(p_day DATE)
RETURNS INTEGER
LANGUAGE plpgsql AS $$
-- Aggregate messages from day into topic_daily
$$;
```

**Acceptance Criteria**:
- [ ] Rollup functions implemented
- [ ] Idempotent: re-running for same period gives same result
- [ ] Scheduler runs at H+5 and D+5 minutes
- [ ] Reconciliation job at H+65 for late messages

---

#### Task 3.2: Admin Summary Reports
**Priority**: 🟢 **P2 - Medium**

**Implementation**:
```java
@Service
public class ReportService {
    public Mono<String> generateHourlySummary(Instant hour) {
        // Query topic_hourly for top topics
        // Format: "Top Topics (last hour): BTC (142 msgs), ETH (89 msgs), TON (64 msgs)"
        // Query events for new events created
        // Send to admin Telegram channel
    }
}
```

**Acceptance Criteria**:
- [ ] Hourly report sent to admin channel at H+10
- [ ] Daily report sent at 00:10 UTC
- [ ] Report includes: Top Topics, Top Events, Alert Stats

---

#### Task 3.3: Feedback Mechanism
**Priority**: 🟢 **P2 - Medium**

**Implementation**:
```java
@Component
public class FeedbackHandler {
    public Mono<Void> handleFeedback(long alertId, long userId, String feedback) {
        // Insert into alert_feedback
        // If feedback == 'mute': update client_profile.mute_until
        // Log metrics
    }
}
```

**Acceptance Criteria**:
- [ ] Inline buttons on alerts: 👍 Relevant | 👎 Irrelevant | 🔕 Mute 1h
- [ ] Feedback recorded in `alert_feedback` table
- [ ] Mute button updates `client_profile.mute_until`
- [ ] Weekly precision report: `relevant_count / total_feedback`

---

#### Task 3.4: Metrics & Monitoring
**Priority**: 🟡 **P1 - High**

**Implementation**:
```java
@Component
public class EventMetrics {
    private final MeterRegistry registry;

    public void recordEventDetected(String eventType, String severity) {
        registry.counter("events.detected",
            "type", eventType,
            "severity", severity).increment();
    }

    public void recordAlertSent(String priority, boolean success) {
        registry.counter("alerts.sent",
            "priority", priority,
            "success", String.valueOf(success)).increment();
    }

    public void recordLatency(long firstMsgTime, long alertTime) {
        Duration latency = Duration.between(
            Instant.ofEpochMilli(firstMsgTime),
            Instant.ofEpochMilli(alertTime)
        );
        registry.timer("alerts.latency").record(latency);
    }
}
```

**Metrics to Track**:
- `events.detected.count` (by type, severity)
- `events.detection.latency` (time to run fn_detect_events)
- `alerts.sent.count` (by priority, success/failure)
- `alerts.failed.count`
- `alerts.suppressed.count` (by reason: rate_limit, mute, consolidation)
- `alerts.delivery.latency` (time from event creation to delivery)
- `alerts.feedback.precision` (weekly aggregation)
- `alerts.duplicate.rate` (duplicate events in 10 min window)

**Acceptance Criteria**:
- [ ] All metrics exposed via `/actuator/metrics`
- [ ] Prometheus/Grafana dashboard configured
- [ ] Alerts configured for:
  - No events detected in 30 minutes
  - Alert delivery failure rate > 5%
  - Latency p95 > 2 minutes

---

## 3. Testing Strategy

### 3.1. Functional Tests

**Test Cases**:
1. **Event Detection**:
   - Insert 50 messages for `BTC` topic in 5 minutes
   - Run `fn_detect_events(15, 0.45)`
   - Assert: 1 event created with `event_type=SPIKE`
   - Assert: event has 5 evidence messages, top sources populated

2. **Event Classification**:
   - Insert messages with "listing" keyword
   - Assert: `event_type=FOMO/LISTING`
   - Insert messages with "panic", "dump" keywords
   - Assert: `event_type=FUD/PANIC`

3. **Deduplication**:
   - Create event for `BTC` at T=0
   - Try to create another event for `BTC` at T+5 min
   - Assert: No duplicate event created (10 min window)

4. **Alert Delivery**:
   - Create event with `severity=high`
   - Run alert delivery
   - Assert: Telegram message sent to configured channel
   - Assert: `alert.status=delivered`, `alert.delivered_at` populated

5. **Rate Limiting**:
   - Create 3 events for `BTC` within 30 minutes
   - Assert: Client receives max 2 alerts (rate_limit_per_topic=2)
   - Assert: 3rd alert suppressed

6. **Consolidation**:
   - Create 5 events for `BTC` within 10 minutes
   - Assert: Single consolidated alert sent
   - Assert: 5 original alerts marked as `consolidated`

### 3.2. Quality Tests

**Precision Test** (Manual):
1. Run system on production data for 1 day
2. Collect all alerts sent
3. Manually review 50 random alerts
4. Label: relevant / irrelevant
5. Calculate precision: `relevant_count / 50`
6. Target: ≥ 70%

**Latency Test**:
1. Insert message with keyword at T=0
2. Wait for event detection (up to 5 min)
3. Wait for alert delivery (up to 1 min)
4. Measure: `T_alert - T_message`
5. Target: p50 ≤ 60 sec, p95 ≤ 120 sec

**Duplicate Test**:
1. Run system for 1 day
2. Group alerts by topic and 10-minute windows
3. Count duplicates (>1 alert for same topic/type in window)
4. Calculate rate: `duplicate_groups / total_alerts`
5. Target: ≤ 10%

### 3.3. Integration Tests

**Files to Create**:
- `src/test/java/com/example/telegramuserbot/integration/EventDetectionIntegrationTest.java`
- `src/test/java/com/example/telegramuserbot/integration/AlertDeliveryIntegrationTest.java`
- `src/test/java/com/example/telegramuserbot/integration/RollupIntegrationTest.java`

**Example Test**:
```java
@Test
void eventDetectionCreatesValidEvents() {
    // Arrange: Insert test messages
    for (int i = 0; i < 50; i++) {
        insertTestMessage(channelId, baseMessageId + i, "BTC listing tomorrow",
            new String[]{"btc"}, Instant.now());
    }

    // Act: Run detection
    Integer eventsCreated = eventDetectionService.detectEvents(15, 0.45).block();

    // Assert
    assertThat(eventsCreated).isEqualTo(1);

    Event event = eventRepository.findAll().blockFirst();
    assertThat(event.topic()).isEqualTo("btc");
    assertThat(event.eventType()).isEqualTo("FOMO/LISTING");
    assertThat(event.messageCount()).isEqualTo(50);
    assertThat(event.confidence()).isGreaterThan(0.7);
    assertThat(event.severity()).isEqualTo("high");
}
```

---

## 4. Configuration

### 4.1. Application Properties

**File**: `src/main/resources/application.yml`

```yaml
events:
  detection:
    enabled: true
    fixed-rate: 300000  # 5 minutes
    window-minutes: 15
    min-confidence: 0.45

  alerts:
    delivery:
      enabled: true
      fixed-rate: 60000  # 1 minute
      batch-size: 10
      retry-attempts: 3
      retry-backoff-ms: [1000, 5000, 30000]

    consolidation:
      enabled: true
      threshold: 3  # Consolidate if >3 events in window
      window-minutes: 10

  rollups:
    hourly:
      enabled: true
      cron: "0 5 * * * *"  # H+5 minutes
      reconcile-cron: "0 5 * * * *"  # H+65 minutes (next hour)
    daily:
      enabled: true
      cron: "0 5 0 * * *"  # D+5 minutes (00:05 UTC)

telegram:
  alerts:
    channel-id: ${TELEGRAM_ALERTS_CHANNEL_ID}  # Admin alert channel
    buttons-enabled: true
```

### 4.2. Client Profile Defaults

**File**: `src/main/resources/db/migration/data/default-client-profile.sql`

```sql
-- Default profile for admin user
INSERT INTO tgscan.client_profiles (telegram_user_id, username, topics, event_types, severity_min, rate_limit_per_topic)
VALUES (
    ${ADMIN_TELEGRAM_USER_ID},
    'admin',
    '{}', -- Empty = all topics
    '{SPIKE,FUD/PANIC,FOMO/LISTING}',
    'medium',
    2
);
```

---

## 5. Risks & Mitigation

| Risk | Impact | Likelihood | Mitigation |
|------|--------|------------|------------|
| **False positives (noise)** | High | High | Start with high min_confidence (0.6); collect feedback; tune thresholds weekly |
| **Alert delivery failures (Telegram rate limits)** | High | Medium | Implement retry with exponential backoff; add DLQ for manual intervention |
| **Database load from frequent polling** | Medium | Medium | Use indexes on `events.created_at`; consider CDC (Change Data Capture) later |
| **Missed events (detection window too large)** | High | Low | Start with 15-min window; tune based on latency metrics; consider 5-min window for critical topics |
| **LLM hallucinations in root_cause** | Medium | Medium | Always include evidence links; fallback to rule-based summary if LLM fails |
| **Spam topics (memes)** | Medium | High | Maintain stop-list; add topic quality scoring; require min confidence for low-quality topics |

---

## 6. Success Metrics (KPIs)

### Phase 1 (MVP - Week 2)
- [ ] Event detection running every 5 minutes
- [ ] At least 1 event detected per hour (on average)
- [ ] Alert delivery working (Telegram bot sends messages)
- [ ] ≥ 90% delivery success rate

### Phase 2 (Production Ready - Week 4)
- [ ] **Precision**: ≥ 70% (measured on 50-alert sample)
- [ ] **Duplicate Rate**: ≤ 10% (10-min windows)
- [ ] **Latency p50**: ≤ 60 seconds
- [ ] **Latency p95**: ≤ 120 seconds
- [ ] **Delivery Success**: ≥ 99% (daily)
- [ ] **Rate Limiting**: Working (no more than N alerts/30min/topic)
- [ ] **Consolidation**: Working (1 alert instead of >3)

### Phase 3 (Optimized - Week 6+)
- [ ] **Precision**: ≥ 80%
- [ ] **Latency p95**: ≤ 60 seconds
- [ ] **Feedback Loop**: Active (≥ 20% of alerts receive feedback)
- [ ] **Weekly Reports**: Automated precision tracking
- [ ] **Runbook**: Complete with troubleshooting guides

---

## 7. Documentation Requirements

### 7.1. Runbook (1-Page)

**File**: `docs/RUNBOOK_EVENTS_ALERTS.md`

**Sections**:
1. **Architecture Overview**: 3-paragraph summary
2. **Monitoring Dashboards**: Links to Grafana
3. **Common Issues**:
   - No events detected for 30+ minutes → Check scheduler logs, verify data in `tgscan.messages`
   - Alert delivery failures → Check Telegram bot token, rate limits
   - High latency → Check database query performance, tune detection window
4. **Emergency Contacts**: On-call rotation
5. **Rollback Procedure**: How to disable schedulers

### 7.2. Configuration Guide

**File**: `docs/CONFIG_EVENTS_ALERTS.md`

**Sections**:
- All `application.yml` parameters with descriptions
- How to tune thresholds (min_confidence, rate limits)
- How to add new client profiles
- How to configure mute periods

### 7.3. API Documentation

**File**: `docs/API_EVENTS_ALERTS.md`

**Endpoints**:
- `POST /api/events/detect` - Manual event detection trigger
- `POST /api/alerts/send` - Manual alert delivery trigger
- `GET /api/events?topic={topic}&severity={severity}` - Query events
- `GET /api/alerts?status={status}` - Query alerts
- `POST /api/feedback` - Submit alert feedback

---

## 8. Sprint Breakdown

### Sprint 1: Core Services (Week 1-2)
**Goal**: Event detection and basic alert delivery working

**Tasks**:
1. Event Detection Service (2 days)
2. Event Detection Scheduler (1 day)
3. Entity Models & Repositories (1 day)
4. Alert Delivery Service (2 days)
5. Telegram Alert Sender (2 days)
6. Alert Delivery Scheduler (1 day)
7. Integration Tests (1 day)

**Deliverable**: Events detected every 5 minutes, alerts sent to Telegram

---

### Sprint 2: Smart Routing (Week 3)
**Goal**: Client profiles, rate limiting, consolidation

**Tasks**:
1. Client Profiles Schema (1 day)
2. Client Profile Service & Repository (1 day)
3. Alert Routing Service (2 days)
4. Rate Limiting Logic (1 day)
5. Consolidation Logic (2 days)
6. Integration Tests (1 day)

**Deliverable**: Personalized alerts with anti-spam features

---

### Sprint 3: Rollups & Monitoring (Week 4)
**Goal**: Hourly/daily aggregates, feedback loop, full observability

**Tasks**:
1. Rollup Schema & Functions (2 days)
2. Rollup Services & Schedulers (1 day)
3. Admin Summary Reports (1 day)
4. Feedback Mechanism (1 day)
5. Metrics & Monitoring (2 days)
6. Documentation (Runbook, Config Guide) (1 day)

**Deliverable**: Production-ready system with full monitoring

---

## 9. Next Steps (Immediate Actions)

1. **Review this plan** with the team
2. **Set up Jira/Trello board** with tasks from sprints
3. **Create feature branch**: `feature/events-alerts-stage2`
4. **Start Sprint 1**: Begin with Task 1.1 (Event Detection Service)
5. **Set up metrics collection**: Configure Prometheus/Grafana
6. **Prepare test data**: Collect sample messages for testing

---

## 10. Acceptance Checklist (Stage 2 Complete)

**Core Functionality**:
- [ ] Events detected automatically every 5 minutes
- [ ] All event types classified correctly (SPIKE, FUD/PANIC, FOMO/LISTING)
- [ ] Alerts sent via Telegram bot
- [ ] Deduplication working (no duplicates within 10 min)

**Quality**:
- [ ] Precision ≥ 70% (measured on sample)
- [ ] Latency p95 ≤ 120 sec
- [ ] Duplicate rate ≤ 10%

**Anti-Spam**:
- [ ] Rate limiting enforced per client/topic
- [ ] Consolidation working (>3 events → 1 alert)
- [ ] Mute button functional

**Observability**:
- [ ] All metrics exposed and graphed
- [ ] Alerts configured for critical failures
- [ ] Runbook documented

**Reliability**:
- [ ] Retry logic for failed deliveries
- [ ] DLQ for permanent failures
- [ ] 99% delivery success rate

---

**END OF IMPLEMENTATION PLAN**
