# Event Publishing System (Stage 3)

## Overview

The Event Publishing system automatically delivers detected events to configured Telegram channels/chats based on subscription rules. This is the final stage in the event detection pipeline, following Stage 1 (detection via `fn_detect_events()`) and Stage 2 (processing via EventWatcher).

## Architecture

### Complete Pipeline Flow

```
┌─────────────────────────────────────────────────────────────────┐
│ Stage 1: Event Detection (PostgreSQL Function)                  │
├─────────────────────────────────────────────────────────────────┤
│ fn_detect_events(window_minutes, min_confidence)                │
│   → Analyzes message patterns                                   │
│   → Creates events with status='new'                            │
│   → Stores in tgscan.events table                               │
└─────────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────────┐
│ Stage 2: Event Processing (EventWatcher)                        │
├─────────────────────────────────────────────────────────────────┤
│ EventWatcherScheduler (runs every 30 seconds)                   │
│   → EventWatcherService.process()                               │
│      → SELECT * FROM events WHERE status='new'                  │
│      → Filters by confidence and severity thresholds            │
│      → Transitions status: 'new' → 'ready'                      │
│      → Logs event details for monitoring                        │
└─────────────────────────────────────────────────────────────────┘
                            ↓
┌─────────────────────────────────────────────────────────────────┐
│ Stage 3: Event Publishing (EventPublisher)                      │
├─────────────────────────────────────────────────────────────────┤
│ EventPublisherScheduler (runs every 5 seconds)                  │
│   → EventPublisherService.process()                             │
│      → SELECT * FROM events WHERE status='ready'                │
│      → For each event:                                          │
│         1. Find matching subscriptions                          │
│            SELECT FROM post_subscriptions WHERE                 │
│              enabled = TRUE                                     │
│              AND event.topic ~ topic_pattern (regex)            │
│              AND event.event_type = ANY(event_types[])          │
│              AND severity_rank(event.severity) >=               │
│                  severity_rank(min_severity)                    │
│         2. Check deduplication rules                            │
│            - Idempotency: Skip if already posted                │
│            - TTL: Skip if same topic/type posted recently       │
│         3. Render message using template (RICH/SHORT)           │
│         4. Send to subscription.chat_id via Telegram API        │
│         5. Record in posted table (audit trail)                 │
│         6. Update event status: 'ready' → 'published'           │
└─────────────────────────────────────────────────────────────────┘
                            ↓
                    Telegram channels
                    receive alerts!
```

## Database Schema

### 1. Post Subscriptions Table

Defines **who** receives **what** notifications.

```sql
CREATE TABLE tgscan.post_subscriptions (
    id              BIGSERIAL PRIMARY KEY,
    chat_id         BIGINT NOT NULL,                  -- Telegram chat/channel ID
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    topic_pattern   TEXT NOT NULL,                    -- PostgreSQL regex
    event_types     TEXT[] NOT NULL DEFAULT ARRAY['SPIKE','FUD/PANIC','FOMO/LISTING'],
    min_severity    TEXT NOT NULL DEFAULT 'low',      -- 'low' | 'medium' | 'high'
    template_code   TEXT NOT NULL DEFAULT 'RICH',     -- 'RICH' | 'SHORT'
    dedupe_ttl_sec  INT NOT NULL DEFAULT 1200,        -- Deduplication window
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (chat_id, topic_pattern, template_code)
);
```

**Column Descriptions:**

- `chat_id`: Telegram chat/channel ID (can be negative for channels: `-1001234567890`)
- `enabled`: Subscription active flag (set to FALSE to pause)
- `topic_pattern`: PostgreSQL regex for topic matching (case-insensitive)
  - `'^btc$'` - Exact match for "btc"
  - `'btc|eth'` - Match "btc" OR "eth"
  - `'.*'` - Match everything
- `event_types`: Array of event types to include
  - Options: `SPIKE`, `FUD/PANIC`, `FOMO/LISTING`
- `min_severity`: Minimum severity threshold
  - `low` (1) - Receive all events
  - `medium` (2) - Only medium and high
  - `high` (3) - Only high severity
- `template_code`: Message format
  - `RICH` - Detailed format with all metrics
  - `SHORT` - Compact format for mobile
- `dedupe_ttl_sec`: Time window (seconds) to prevent duplicate posts of same topic/type

### 2. Posted Audit Table

Tracks **all** published events for analytics and idempotency.

```sql
CREATE TABLE tgscan.posted (
    id                  BIGSERIAL PRIMARY KEY,
    event_id            BIGINT NOT NULL REFERENCES tgscan.events(id),
    subscription_id     BIGINT NOT NULL REFERENCES tgscan.post_subscriptions(id),
    chat_id             BIGINT NOT NULL,
    message_id          BIGINT,                       -- Telegram message ID
    template_code       TEXT NOT NULL,
    status              TEXT NOT NULL DEFAULT 'sent', -- 'sent' | 'failed'
    error_message       TEXT,
    posted_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (event_id, subscription_id)                -- Idempotency
);
```

**Idempotency Constraint:** The UNIQUE constraint on `(event_id, subscription_id)` ensures each event is posted exactly once per subscription, even across application restarts.

## Subscription Management

### Method 1: Direct SQL (Current Approach)

#### Create Subscription

```sql
-- Example: Subscribe crypto alerts channel to BTC/ETH events
INSERT INTO tgscan.post_subscriptions (
    chat_id,
    enabled,
    topic_pattern,
    event_types,
    min_severity,
    template_code,
    dedupe_ttl_sec
) VALUES (
    -1001234567890,                          -- Channel ID (from Telegram)
    TRUE,                                     -- Enabled
    'btc|eth',                                -- Match BTC or ETH
    ARRAY['SPIKE', 'FOMO/LISTING'],          -- Event types
    'medium',                                 -- Medium+ severity only
    'RICH',                                   -- Detailed format
    1200                                      -- 20 min dedup window
);
```

#### List Subscriptions

```sql
-- View all active subscriptions
SELECT
    id,
    chat_id,
    topic_pattern,
    event_types,
    min_severity,
    template_code,
    dedupe_ttl_sec
FROM tgscan.post_subscriptions
WHERE enabled = TRUE
ORDER BY created_at DESC;
```

#### Update Subscription

```sql
-- Pause subscription
UPDATE tgscan.post_subscriptions
SET enabled = FALSE, updated_at = NOW()
WHERE chat_id = -1001234567890;

-- Change severity threshold
UPDATE tgscan.post_subscriptions
SET min_severity = 'high', updated_at = NOW()
WHERE chat_id = -1001234567890;

-- Update topic pattern
UPDATE tgscan.post_subscriptions
SET topic_pattern = 'btc|eth|sol', updated_at = NOW()
WHERE chat_id = -1001234567890;
```

#### Delete Subscription

```sql
-- Permanently delete subscription
DELETE FROM tgscan.post_subscriptions
WHERE chat_id = -1001234567890;
```

### Method 2: Programmatic (Future Enhancement)

**Option A: Telegram Bot Commands**

```java
// TODO: Implement SubscriptionCommandHandler
// /subscribe btc|eth SPIKE,FOMO/LISTING medium
// /unsubscribe
// /list_subscriptions
// /pause_alerts
// /resume_alerts
```

**Option B: REST API**

```java
// TODO: Implement SubscriptionController
// POST /api/subscriptions
// GET /api/subscriptions/{chatId}
// PUT /api/subscriptions/{id}
// DELETE /api/subscriptions/{id}
```

## Message Templates

### RICH Template (Detailed)

**Format:**
```
[EVENT_TYPE] TOPIC — всплеск ×{ratio} за {window}
Причина: {root_cause}
Метрики: Msg={count} | Src={sources} | Conf={confidence} | Sev={severity}
Доказательства: #1 #2 #3
```

**Example:**
```
[FOMO/LISTING] BTC — всплеск ×3.2 за 15 мин
Причина: Major exchange listing BTC tomorrow - sources confirm
Метрики: Msg=15 | Src=5 | Conf=0.87 | Sev=high
Доказательства: #1 #2 #3
```

**Use Cases:**
- Main crypto alerts channel
- Admin monitoring
- Detailed analysis requirements

### SHORT Template (Compact)

**Format:**
```
TOPIC — TYPE ×{ratio} • {confidence}
{root_cause (truncated to 120 chars)}
{link1} {link2}
```

**Example:**
```
BTC — FOMO/LISTING ×3.2 • 0.87
Major exchange listing BTC tomorrow - sources confirm multiple insiders huge volume incoming
#1 #2
```

**Use Cases:**
- Mobile-first channels
- High-frequency alerts
- Quick scanning

## Deduplication Strategy

The system implements **two layers** of deduplication to prevent spam:

### Layer 1: Idempotency (Exact Duplicates)

**Mechanism:** UNIQUE constraint on `posted(event_id, subscription_id)`

**Prevents:**
- Same event posted twice to same subscription
- Survives application restarts
- Database-level guarantee

**Example:**
```sql
-- First post: ✅ Succeeds
INSERT INTO posted (event_id, subscription_id, chat_id, status)
VALUES (123, 1, 777, 'sent');

-- Second post attempt: ❌ Fails (duplicate key)
INSERT INTO posted (event_id, subscription_id, chat_id, status)
VALUES (123, 1, 777, 'sent');
-- ERROR: duplicate key value violates unique constraint
```

### Layer 2: TTL-based (Similar Events)

**Mechanism:** Time-based filtering in `PostedRepository.wasRecentlyPosted()`

**Query:**
```sql
SELECT COUNT(*) > 0
FROM tgscan.posted p
JOIN tgscan.events e ON e.id = p.event_id
WHERE p.chat_id = :chatId
  AND e.topic = :topic
  AND e.event_type = :eventType
  AND p.posted_at >= :since          -- NOW() - dedupe_ttl_sec
  AND p.status = 'sent'
```

**Prevents:**
- Multiple similar events within TTL window
- Example: 3 BTC SPIKE events in 20 minutes → only first one posted
- Configurable per subscription via `dedupe_ttl_sec`

**Example:**
```
Time 14:00 - BTC SPIKE event #1 → ✅ Posted (first occurrence)
Time 14:05 - BTC SPIKE event #2 → ❌ Skipped (within 20 min TTL)
Time 14:15 - BTC SPIKE event #3 → ❌ Skipped (within 20 min TTL)
Time 14:25 - BTC SPIKE event #4 → ✅ Posted (TTL expired, >20 min since #1)
```

## Configuration

### Application Properties

```yaml
# application.yml
events:
  publisher:
    enabled: true                          # Enable/disable publisher
    poll-interval-ms: 5000                 # Poll every 5 seconds
    batch-size: 10                         # Max events per cycle
```

### Environment Variables

```bash
# Enable/disable at runtime
EVENTS_PUBLISHER_ENABLED=true

# Adjust polling frequency
EVENTS_PUBLISHER_POLL_INTERVAL=5000

# Batch processing limit
EVENTS_PUBLISHER_BATCH_SIZE=10
```

## Use Cases & Examples

### Use Case 1: Crypto News Aggregator

**Scenario:** Main channel receives all crypto events

```sql
INSERT INTO tgscan.post_subscriptions
VALUES (
    DEFAULT,
    -1001111111111,                        -- Main channel ID
    TRUE,
    '.*',                                  -- All topics
    ARRAY['SPIKE', 'FUD/PANIC', 'FOMO/LISTING'],
    'low',                                 -- All severities
    'RICH',                                -- Detailed format
    1800,                                  -- 30 min dedup
    NOW(),
    NOW()
);
```

### Use Case 2: BTC-Only Premium Channel

**Scenario:** High-quality BTC alerts only

```sql
INSERT INTO tgscan.post_subscriptions
VALUES (
    DEFAULT,
    -1002222222222,                        -- Premium channel ID
    TRUE,
    '^btc$',                               -- BTC only (exact match)
    ARRAY['SPIKE', 'FOMO/LISTING'],       -- No FUD/PANIC
    'high',                                -- High severity only
    'SHORT',                               -- Compact format
    600,                                   -- 10 min dedup
    NOW(),
    NOW()
);
```

### Use Case 3: Multi-Topic Research Channel

**Scenario:** Research channel tracking BTC, ETH, SOL

```sql
INSERT INTO tgscan.post_subscriptions
VALUES (
    DEFAULT,
    -1003333333333,                        -- Research channel ID
    TRUE,
    'btc|eth|sol',                         -- Multiple topics
    ARRAY['SPIKE', 'FUD/PANIC', 'FOMO/LISTING'],
    'medium',                              -- Medium+ severity
    'RICH',                                -- Detailed for analysis
    900,                                   -- 15 min dedup
    NOW(),
    NOW()
);
```

### Use Case 4: Admin Monitoring (No Dedup)

**Scenario:** Admin chat receives everything immediately

```sql
INSERT INTO tgscan.post_subscriptions
VALUES (
    DEFAULT,
    1000000001,                             -- Admin user ID
    TRUE,
    '.*',                                  -- All topics
    ARRAY['SPIKE', 'FUD/PANIC', 'FOMO/LISTING'],
    'low',                                 -- All severities
    'RICH',                                -- Full details
    0,                                     -- NO deduplication
    NOW(),
    NOW()
);
```

### Use Case 5: Topic-Specific Channels

**Scenario:** Separate channel for each major coin

```sql
-- BTC channel
INSERT INTO tgscan.post_subscriptions
VALUES (DEFAULT, -1004444444444, TRUE, '^btc$',
        ARRAY['SPIKE', 'FOMO/LISTING'], 'medium', 'SHORT', 1200, NOW(), NOW());

-- ETH channel
INSERT INTO tgscan.post_subscriptions
VALUES (DEFAULT, -1005555555555, TRUE, '^eth$',
        ARRAY['SPIKE', 'FOMO/LISTING'], 'medium', 'SHORT', 1200, NOW(), NOW());

-- SOL channel
INSERT INTO tgscan.post_subscriptions
VALUES (DEFAULT, -1006666666666, TRUE, '^sol$',
        ARRAY['SPIKE', 'FOMO/LISTING'], 'medium', 'SHORT', 1200, NOW(), NOW());
```

## Monitoring & Analytics

### View Recent Posts

```sql
-- Posts from last hour
SELECT
    e.topic,
    e.event_type,
    e.severity,
    p.chat_id,
    p.status,
    p.posted_at
FROM tgscan.posted p
JOIN tgscan.events e ON e.id = p.event_id
WHERE p.posted_at >= NOW() - INTERVAL '1 hour'
ORDER BY p.posted_at DESC;
```

### Subscription Statistics

```sql
-- Posts per subscription (last 24h)
SELECT
    s.chat_id,
    s.topic_pattern,
    COUNT(CASE WHEN p.status = 'sent' THEN 1 END) as sent_count,
    COUNT(CASE WHEN p.status = 'failed' THEN 1 END) as failed_count,
    COUNT(*) as total_posts
FROM tgscan.post_subscriptions s
LEFT JOIN tgscan.posted p ON p.subscription_id = s.id
    AND p.posted_at >= NOW() - INTERVAL '24 hours'
WHERE s.enabled = TRUE
GROUP BY s.id, s.chat_id, s.topic_pattern
ORDER BY total_posts DESC;
```

### Success Rate

```sql
-- Overall publishing success rate
SELECT
    status,
    COUNT(*) as count,
    ROUND(100.0 * COUNT(*) / SUM(COUNT(*)) OVER(), 2) as percentage
FROM tgscan.posted
WHERE posted_at >= NOW() - INTERVAL '24 hours'
GROUP BY status;
```

### Failed Posts Analysis

```sql
-- Investigate failures
SELECT
    e.topic,
    e.event_type,
    p.chat_id,
    p.error_message,
    p.posted_at
FROM tgscan.posted p
JOIN tgscan.events e ON e.id = p.event_id
WHERE p.status = 'failed'
  AND p.posted_at >= NOW() - INTERVAL '24 hours'
ORDER BY p.posted_at DESC;
```

## Troubleshooting

### Problem: Events detected but not published

**Check 1:** Verify EventPublisher is enabled
```yaml
events:
  publisher:
    enabled: true  # Must be true
```

**Check 2:** Verify events reached 'ready' status
```sql
SELECT status, COUNT(*)
FROM tgscan.events
GROUP BY status;
-- Should see 'ready' events
```

**Check 3:** Verify active subscriptions exist
```sql
SELECT COUNT(*)
FROM tgscan.post_subscriptions
WHERE enabled = TRUE;
-- Should be > 0
```

**Check 4:** Check subscription matching
```sql
-- Test if subscription matches event
SELECT
    e.id as event_id,
    e.topic,
    e.event_type,
    e.severity,
    s.id as subscription_id,
    s.topic_pattern,
    s.event_types,
    s.min_severity,
    lower(e.topic) ~ lower(s.topic_pattern) as topic_matches,
    e.event_type = ANY(s.event_types) as type_matches,
    tgscan.severity_rank(e.severity) >= tgscan.severity_rank(s.min_severity) as severity_matches
FROM tgscan.events e
CROSS JOIN tgscan.post_subscriptions s
WHERE e.status = 'ready'
  AND s.enabled = TRUE;
```

### Problem: Too many duplicate posts

**Solution 1:** Increase `dedupe_ttl_sec`
```sql
UPDATE tgscan.post_subscriptions
SET dedupe_ttl_sec = 3600  -- Increase to 1 hour
WHERE chat_id = -1001234567890;
```

**Solution 2:** Increase severity threshold
```sql
UPDATE tgscan.post_subscriptions
SET min_severity = 'high'  -- Only high severity
WHERE chat_id = -1001234567890;
```

### Problem: Missing posts (over-deduplication)

**Solution:** Decrease `dedupe_ttl_sec`
```sql
UPDATE tgscan.post_subscriptions
SET dedupe_ttl_sec = 300  -- Reduce to 5 minutes
WHERE chat_id = -1001234567890;
```

### Problem: Telegram API errors

**Check application logs:**
```bash
tail -f logs/application.log | grep "EventPublisher"
```

**Common errors:**
- `Chat not found` - Verify chat_id is correct
- `Bot was kicked` - Ensure bot is member of channel
- `Not enough rights` - Bot needs admin rights to post

## Future Enhancements

### 1. User-Facing Management

- [ ] Telegram bot commands (`/subscribe`, `/unsubscribe`, `/my_alerts`)
- [ ] REST API for subscription management
- [ ] Web dashboard for analytics

### 2. Advanced Features

- [ ] Custom message templates (user-defined)
- [ ] Schedule-based publishing (quiet hours)
- [ ] Rate limiting per channel
- [ ] A/B testing different templates
- [ ] Webhook support for external integrations

### 3. Analytics

- [ ] Real-time dashboard
- [ ] Delivery metrics (open rates, click-through)
- [ ] Subscription trends
- [ ] Event effectiveness scoring

## Related Documentation

- [Event Detection](./EVENT_DETECTION.md) - Stage 1: How events are detected
- [Event Watcher](./EVENT_WATCHER.md) - Stage 2: Event processing pipeline
- [Database Schema](./DATABASE_SCHEMA.md) - Complete schema reference
- [Configuration Guide](./CONFIGURATION.md) - Application configuration

## API Reference

### EventPublisherService

```java
/**
 * Main service for publishing events to Telegram.
 */
public class EventPublisherService {

    /**
     * Processes all ready events and publishes to matching subscriptions.
     *
     * @return Mono with count of posts successfully sent
     */
    public Mono<Integer> process();
}
```

### PostSubscriptionRepository

```java
/**
 * Repository for managing post subscriptions.
 */
public interface PostSubscriptionRepository extends R2dbcRepository<PostSubscription, Long> {

    /**
     * Finds subscriptions matching event criteria.
     *
     * @param eventTopic event topic
     * @param eventType event type
     * @param eventSeverity event severity
     * @return flux of matching subscriptions
     */
    Flux<PostSubscription> findMatchingSubscriptions(
        String eventTopic,
        String eventType,
        String eventSeverity
    );
}
```

### TelegramPostRenderer

```java
/**
 * Renders events into formatted Telegram posts.
 */
public class TelegramPostRenderer {

    /**
     * Renders event using specified template.
     *
     * @param event event to render
     * @param templateCode RICH or SHORT
     * @return formatted HTML text
     */
    public String render(Event event, String templateCode);
}
```

## Conclusion

The Event Publishing system provides a robust, configurable pipeline for delivering event alerts to Telegram channels. Key features:

✅ **Flexible Subscriptions** - Regex patterns, event type filtering, severity thresholds
✅ **Two-Layer Deduplication** - Idempotency + TTL-based filtering
✅ **Multiple Templates** - RICH (detailed) and SHORT (compact) formats
✅ **Complete Audit Trail** - All posts tracked in database
✅ **Automatic Processing** - Runs every 5 seconds, no manual intervention
✅ **Production Ready** - Tested, monitored, graceful error handling

For questions or issues, see [Troubleshooting](#troubleshooting) or check application logs.
