# News Digest System Activation Guide

This guide describes how to activate and test the News Digest System for production use.

## Prerequisites

1. **Database**: PostgreSQL with Liquibase migrations applied
2. **DeepSeek API**: Valid API key configured
3. **Telegram Bot**: Bot with permissions to post in target channel
4. **Message Data**: Existing messages in the database for clustering

## Environment Variables

### Required for Activation

```bash
# Enable HTTP API for frontend
APP_HTTP_ENABLED=true

# Enable clustering job
CLUSTERING_JOB_ENABLED=true

# Enable digest generation job
DIGEST_JOB_ENABLED=true

# Enable persona-based scheduling
DIGEST_PERSONA_MODE=true
DIGEST_SCHEDULER_ENABLED=true
```

### Optional Configuration

```bash
# Clustering parameters
CLUSTERING_INTERVAL_MS=3600000     # 1 hour between clustering runs
CLUSTERING_WINDOW_HOURS=24          # Look back 24 hours for messages

# Digest check interval (how often to check persona schedules)
DIGEST_CHECK_INTERVAL_MS=300000     # 5 minutes

# Scheduler window (tolerance for schedule matching)
DIGEST_SCHEDULER_WINDOW_MINUTES=5
```

## Activation Steps

### Step 1: Database Migration

Ensure the digest persona tables exist:

```sql
-- Check if tables exist
SELECT table_name FROM information_schema.tables
WHERE table_schema = 'bot' AND table_name IN ('digest_personas', 'digest_history');
```

If not present, run Liquibase migrations:

```bash
mvn liquibase:update
```

### Step 2: Create Test Persona

Create the test persona via the REST API:

```bash
curl -X POST http://localhost:8099/api/digest/personas \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Lead Analyst",
    "description": "Professional news analyst",
    "botId": 2000000001,
    "targetChannelId": -1001234567890,
    "enabled": true,
    "personaStyle": "PROFESSIONAL",
    "scheduleCron": "0 0 9,21 * * *",
    "scheduleTimezone": "Europe/Moscow",
    "activeHoursStart": "08:00",
    "activeHoursEnd": "23:00",
    "lookbackHours": 24,
    "maxMessages": 15,
    "language": "ru",
    "minClusterSize": 2,
    "minImportanceScore": 0.3,
    "sourceTrustThreshold": 0.3,
    "topicKeywords": ["новости", "политика", "экономика"],
    "negativeKeywords": ["реклама", "спам"],
    "temperature": 0.7,
    "maxTokens": 1500
  }'
```

### Step 3: Start Application

```bash
# Set environment variables
export APP_HTTP_ENABLED=true
export CLUSTERING_JOB_ENABLED=true
export DIGEST_JOB_ENABLED=true

# Start application
java -jar target/telegram-userbot-1.0.0.jar --spring.profiles.active=dev
```

### Step 4: Verify System Status

Check scheduler status:

```bash
curl http://localhost:8099/api/digest/scheduler/status
```

Expected response:
```json
{
  "schedulerEnabled": true,
  "totalPersonas": 1,
  "enabledPersonas": 1,
  "clusteringJobEnabled": true,
  "digestJobEnabled": true
}
```

### Step 5: Manual Testing

#### Trigger Clustering

```bash
curl -X POST http://localhost:8099/api/digest/cluster-now
```

#### Generate Test Digest (Preview Only)

```bash
curl -X POST http://localhost:8099/api/digest/personas/1/test \
  -H "Content-Type: application/json" \
  -d '{"preview": true, "lookbackHours": 24}'
```

#### Generate and Publish

```bash
curl -X POST http://localhost:8099/api/digest/personas/1/publish
```

### Step 6: Monitor Logs

```bash
# Watch for digest-related logs
tail -f logs/app.log | grep -E "(digest|clustering|Digest|Cluster)"
```

## Frontend Configuration Panel

Access the frontend at: `http://localhost:8099/`

Navigate to "Дайджесты" tab to:
- View dashboard with system metrics
- Create/edit/delete personas
- Configure schedules and filters
- View analytics and history
- Trigger manual digest generation

## Troubleshooting

### No Digests Generated

1. **Check persona is enabled**:
   ```sql
   SELECT id, name, enabled FROM bot.digest_personas;
   ```

2. **Check schedule evaluation**:
   ```bash
   curl http://localhost:8099/api/digest/personas/1/schedule
   ```

3. **Check for messages**:
   ```sql
   SELECT COUNT(*) FROM bot.messages
   WHERE date > NOW() - INTERVAL '24 hours';
   ```

### Publishing Fails

1. **Check bot permissions** in target channel
2. **Verify target_channel_id** is correct
3. **Check Telegram API logs** for errors

### No Clusters Formed

1. **Check clustering job is enabled**: `CLUSTERING_JOB_ENABLED=true`
2. **Check SimHash calculation** in logs
3. **Verify message content** has sufficient text

## API Endpoints Reference

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/digest/personas` | GET | List all personas |
| `/api/digest/personas` | POST | Create persona |
| `/api/digest/personas/{id}` | GET | Get persona details |
| `/api/digest/personas/{id}` | PUT | Update persona |
| `/api/digest/personas/{id}` | DELETE | Delete persona |
| `/api/digest/personas/{id}/enable` | POST | Enable persona |
| `/api/digest/personas/{id}/disable` | POST | Disable persona |
| `/api/digest/personas/{id}/test` | POST | Generate test digest |
| `/api/digest/personas/{id}/publish` | POST | Generate and publish |
| `/api/digest/personas/{id}/history` | GET | Get history |
| `/api/digest/personas/{id}/schedule` | GET | Get next runs |
| `/api/digest/cluster-now` | POST | Trigger clustering |
| `/api/digest/trigger-all` | POST | Trigger all personas |
| `/api/digest/scheduler/status` | GET | Get scheduler status |
| `/api/digest/analytics` | GET | Get analytics |
| `/api/digest/analytics/clusters` | GET | Cluster statistics |
| `/api/digest/analytics/sources` | GET | Source statistics |
| `/api/digest/history` | GET | All digest history |

## Configuration Examples

### High-Frequency Testing

```json
{
  "scheduleCron": "0 */10 * * * *",
  "lookbackHours": 1,
  "maxMessages": 5
}
```

### Daily Morning Digest

```json
{
  "scheduleCron": "0 0 8 * * *",
  "scheduleTimezone": "Europe/Moscow",
  "activeHoursStart": "07:00",
  "activeHoursEnd": "09:00",
  "lookbackHours": 24,
  "maxMessages": 20
}
```

### Breaking News Style

```json
{
  "personaStyle": "BREAKING_NEWS",
  "scheduleCron": "0 0 * * * *",
  "lookbackHours": 2,
  "maxMessages": 5,
  "minImportanceScore": 0.7
}
```
