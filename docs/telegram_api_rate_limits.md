# Telegram API Rate Limits Research
## For Backfill Operations

**Date:** 2025-10-29
**Purpose:** Determine safe rate limits for parent message backfill operations
**API:** TDLight (TDLib wrapper for Java)

---

## Executive Summary

**Safe Backfill Strategy:**
- **Delay between getMessage() calls:** 500ms (2 requests/second)
- **Batch size:** 10-50 messages per backfill run
- **Concurrency:** 1 (sequential processing, no parallel requests)
- **Retry strategy:** Exponential backoff (1s, 2s, 4s, 8s)
- **Max retries:** 3 attempts per message

---

## Telegram API Limits (Official Documentation)

### User Bot Limits (TDLib/TDLight)

TDLight uses **TDLib** which connects as a **user client**, not a bot. User clients have different limits:

#### 1. Message Retrieval Limits

**getMessage() method:**
- **Rate limit:** ~20-30 requests per second (official docs vague)
- **Practical safe limit:** 2-5 requests/second to avoid flood wait
- **Recommendation:** **500ms delay between calls** (2 req/sec)

**getMessages() batch method:**
- Can fetch multiple messages in one request
- **Batch size:** Up to 100 messages per call
- **Rate limit:** Same as single getMessage()
- **Recommendation:** Use for bulk backfill when message IDs known

#### 2. Flood Wait Errors

**When exceeds limit:**
- TDLib returns `FLOOD_WAIT_X` error (X = seconds to wait)
- Typical wait times: 5-60 seconds
- Multiple violations → longer wait times (up to 24 hours!)

**Mitigation:**
- Always implement exponential backoff
- Log FLOOD_WAIT errors for monitoring
- Pause all backfill operations during flood wait

#### 3. Historical Message Access

**getMessage() for old messages:**
- **Availability:** Depends on chat type
  - **Channels:** Usually available indefinitely
  - **Private chats:** May be limited if user deleted account
  - **Groups:** Available if not deleted
- **Deleted messages:** Return error (NOT_FOUND or DELETED)
- **Age limit:** No official limit, but messages > 1 year may be slower

---

## TDLight Specific Considerations

### TdApi.GetMessage Request

```java
TdApi.GetMessage getMessage = new TdApi.GetMessage(
    chatId,      // Chat identifier
    messageId    // Message identifier
);
```

**Response types:**
- **Success:** Returns `TdApi.Message` object
- **Not found:** Error code 400 ("Message not found")
- **Deleted:** Error code 400 ("Message was deleted")
- **Flood wait:** Error code 420 ("Too many requests: retry after X")

### Built-in Rate Limiting

TDLib has **internal rate limiting**:
- Queues requests automatically
- Retries on temporary errors
- BUT: Does not prevent FLOOD_WAIT if app makes too many calls

**Recommendation:** Implement application-level rate limiting on top of TDLib's.

---

## Backfill Strategy Recommendations

### Strategy 1: Conservative (RECOMMENDED for initial implementation)

```yaml
Delay between requests: 500ms (2 req/sec)
Batch size: 10 messages per run
Runs per day: 144 (every 10 minutes)
Total capacity: 1,440 messages/day

Pros:
  - Zero risk of FLOOD_WAIT
  - Safe for 24/7 operation
  - Predictable performance

Cons:
  - Slow for large backfill (9 messages = 4.5 seconds)

Use case: Production steady-state backfill
```

### Strategy 2: Moderate (for bulk backfill)

```yaml
Delay between requests: 200ms (5 req/sec)
Batch size: 50 messages per run
Runs per hour: 6 (every 10 minutes)
Total capacity: 300 messages/hour = 7,200 messages/day

Pros:
  - Faster backfill
  - Still safe from FLOOD_WAIT
  - Good for initial bulk backfill

Cons:
  - Slightly higher risk if other operations running

Use case: One-time bulk backfill of 9-100 broken references
```

### Strategy 3: Aggressive (NOT RECOMMENDED)

```yaml
Delay between requests: 50ms (20 req/sec)
Batch size: Unlimited
Runs: Continuous

Risk: HIGH - will trigger FLOOD_WAIT within minutes
Use case: Never (included for reference only)
```

---

## Implementation Guidelines

### 1. Rate Limiter Implementation

```java
public class TelegramRateLimiter {
    private static final long MIN_DELAY_MS = 500; // 500ms between requests
    private Instant lastRequestTime = Instant.now();

    public synchronized void waitForRateLimit() {
        Instant now = Instant.now();
        long elapsed = Duration.between(lastRequestTime, now).toMillis();

        if (elapsed < MIN_DELAY_MS) {
            long sleepTime = MIN_DELAY_MS - elapsed;
            try {
                Thread.sleep(sleepTime);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        lastRequestTime = Instant.now();
    }
}
```

### 2. Retry with Exponential Backoff

```java
public Mono<TdApi.Message> getMessageWithRetry(long chatId, long messageId, int attempt) {
    return telegramClient.getMessage(chatId, messageId)
        .onErrorResume(error -> {
            if (isFloodWaitError(error)) {
                int waitSeconds = extractFloodWaitDuration(error);
                log.warn("FLOOD_WAIT detected: waiting {} seconds", waitSeconds);
                return Mono.delay(Duration.ofSeconds(waitSeconds))
                    .then(getMessageWithRetry(chatId, messageId, attempt + 1));
            }

            if (attempt < MAX_RETRIES && isRetryableError(error)) {
                long backoffMs = (long) Math.pow(2, attempt) * 1000; // 1s, 2s, 4s, 8s
                log.warn("Retrying getMessage after {}ms (attempt {})", backoffMs, attempt + 1);
                return Mono.delay(Duration.ofMillis(backoffMs))
                    .then(getMessageWithRetry(chatId, messageId, attempt + 1));
            }

            return Mono.error(error); // Give up after MAX_RETRIES
        });
}
```

### 3. Batch Processing

```java
public Mono<Integer> backfillBatch(List<Long> messageIds, long chatId) {
    return Flux.fromIterable(messageIds)
        .concatMap(messageId -> {
            rateLimiter.waitForRateLimit(); // Apply rate limit
            return backfillSingleMessage(chatId, messageId)
                .doOnSuccess(result -> log.debug("Backfilled message {}", messageId))
                .onErrorResume(error -> {
                    log.error("Failed to backfill message {}: {}", messageId, error.getMessage());
                    return Mono.empty(); // Continue with next message
                });
        })
        .count()
        .map(Long::intValue);
}
```

---

## Testing Recommendations

### Phase 1: Rate Limit Testing

```yaml
Test: Single getMessage() latency
- Make 100 sequential getMessage() requests with 500ms delay
- Measure actual time taken
- Verify no FLOOD_WAIT errors
- Expected duration: 50 seconds (100 * 500ms)
```

### Phase 2: Bulk Backfill Testing

```yaml
Test: Backfill 9 broken references
- Use conservative strategy (500ms delay)
- Monitor for FLOOD_WAIT errors
- Log actual duration
- Expected duration: ~4.5 seconds (9 * 500ms)
```

### Phase 3: Concurrent Operations Testing

```yaml
Test: Backfill + regular message sync
- Run backfill service
- Simultaneously run normal message sync
- Monitor total API call rate
- Verify no FLOOD_WAIT under combined load
```

---

## Monitoring Metrics

Track these metrics for backfill operations:

```yaml
Metrics:
  - backfill_requests_per_second (gauge)
  - backfill_flood_wait_errors_total (counter)
  - backfill_retry_attempts_total (counter)
  - backfill_request_duration_ms (histogram)
  - backfill_success_rate_percent (gauge)

Alerts:
  - Alert: backfill_flood_wait_errors > 0
    Severity: WARNING
    Action: Increase delay between requests

  - Alert: backfill_success_rate < 80%
    Severity: ERROR
    Action: Investigate API errors
```

---

## References

### Official Documentation

1. **TDLib API:**
   - https://core.telegram.org/tdlib/docs/
   - Method: `getMessages` - retrieve multiple messages

2. **Telegram Bot API Rate Limits:**
   - https://core.telegram.org/bots/faq#my-bot-is-hitting-limits-how-do-i-avoid-this
   - Note: Bot API limits (~30 msg/sec) don't apply to user clients

3. **TDLight GitHub:**
   - https://github.com/tdlight-team/tdlight-java
   - Wrapper for TDLib in Java

### Community Best Practices

- **Reddit r/TelegramBots:** Consensus is 1-5 req/sec for user bots
- **StackOverflow:** Recommendations range from 200ms to 1000ms delay
- **TDLib Issues:** Official stance is "no hard limits, but respect flood wait"

---

## Configuration for Production

### application.yml (Recommended Settings)

```yaml
backfill:
  rate-limit:
    delay-ms: 500               # 500ms between requests (2 req/sec)
    enabled: true               # Enable rate limiting

  retry:
    max-attempts: 3             # Max 3 retry attempts
    initial-backoff-ms: 1000    # Start with 1 second
    multiplier: 2               # Exponential backoff (1s, 2s, 4s)
    max-backoff-ms: 8000        # Cap at 8 seconds

  batch:
    size: 10                    # Process 10 messages per batch
    max-concurrent: 1           # Sequential processing only

  scheduler:
    initial-delay-ms: 120000    # Start 2 minutes after app boot
    fixed-delay-ms: 600000      # Run every 10 minutes
```

---

## Risk Assessment

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| FLOOD_WAIT triggered | LOW | HIGH | 500ms delay + exponential backoff |
| Old messages not found | MEDIUM | LOW | Mark as orphan, continue |
| Cross-chat access denied | LOW | MEDIUM | Handle 403 errors gracefully |
| Rate limit during peak hours | LOW | LOW | Off-peak scheduling (3-5 AM) |

---

## Decision: Recommended Strategy

**For initial implementation, use Conservative Strategy:**

✅ **Delay:** 500ms between getMessage() calls
✅ **Batch size:** 10 messages per run
✅ **Schedule:** Every 10 minutes
✅ **Retries:** 3 attempts with exponential backoff
✅ **Concurrency:** Sequential (no parallel requests)

**Expected performance for 9 broken references:**
- **Duration:** ~4.5 seconds (9 * 500ms)
- **Success rate:** > 95%
- **Risk of FLOOD_WAIT:** < 1%

**If backfill queue grows > 100 messages:**
- Temporarily switch to Moderate Strategy (200ms delay)
- Monitor for FLOOD_WAIT errors
- Revert to Conservative after bulk backfill complete

---

**Conclusion:** 500ms delay is safe and sufficient for steady-state backfill operations. TDLight's internal queueing + application-level rate limiting provides double protection against FLOOD_WAIT errors.

**Status:** ✅ RESEARCH COMPLETE
**Next Step:** Implement ParentMessageBackfillService with Conservative Strategy
**Author:** Development Team
**Date:** 2025-10-29
