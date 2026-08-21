# News Scoring and Synthesis System - Setup Guide

> **Version**: 1.0
> **Last Updated**: 2026-01-10
> **Status**: Production Ready

## Overview

The News Scoring and Synthesis System is an advanced message aggregation and analysis platform that:

- **Scores messages** using Wilson Score + Time Decay + Source Trust algorithms
- **Clusters similar content** using SimHash deduplication (duplicate detection)
- **Synthesizes digests** using DeepSeek LLM with persona-driven styles
- **Publishes to Telegram** via configured bot personas
- **Categorizes by topic** for filtered news feeds

### System Components

```
┌─────────────────────────────────────────────────────────────┐
│                    NEWS SYSTEM ARCHITECTURE                  │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  Telegram Messages → Scoring → Clustering → Synthesis       │
│                                    ↓                         │
│                              Persona Filter                  │
│                                    ↓                         │
│                           Digest Generation                  │
│                                    ↓                         │
│                         Telegram Publishing                  │
└─────────────────────────────────────────────────────────────┘
```

**Key Services**:
- `NewsScoring Service` - Message importance scoring
- `MessageClusteringService` - Duplicate detection and grouping
- `NewsSynthesisService` - AI digest generation
- `DigestGenerationJob` - Scheduled digest publishing

---

## Prerequisites

### Required Software
- **Java 21+** (with preview features enabled)
- **PostgreSQL 15+** (with schemas: bot, tgscan)
- **Kafka** (for async message processing)
- **Docker** (optional, for Python scanner)

### Required API Keys
- **DeepSeek API Key** - For LLM synthesis ([https://platform.deepseek.com](https://platform.deepseek.com))
- **Telegram Bot Tokens** - One token per publishing persona ([https://t.me/BotFather](https://t.me/BotFather))

### Environment Access
- Access to test chat: **-1001234567890** (for testing)
- Admin rights to add bots to test chat

---

## Database Setup

### 1. Run Liquibase Migrations

The system uses Liquibase for schema management. All tables are created automatically on startup.

**Key changelog file**:
```
src/main/resources/db/changelog/changes/040-scoring-clustering-system.yaml
```

**Tables created**:
- `bot.messages` - Enhanced with clustering columns (`cluster_id`, `is_primary_in_cluster`)
- `tgscan.source_trust` - Channel credibility scores and categories
- `tgscan.messages` - Message copies for ranking (with `cluster_id`)

**Verify migrations**:
```bash
# Using psql
psql -h localhost -U bot_user -d news_aggregator_db

# Check tables exist
\dt bot.messages
\dt tgscan.source_trust

# Check clustering columns
\d bot.messages
```

Expected columns in `bot.messages`:
- `cluster_id VARCHAR(64)` - Groups similar messages
- `is_primary_in_cluster BOOLEAN` - True for highest-scored message in cluster

### 2. Configure Source Trust

Source trust scores determine message credibility (0.0 - 1.0 scale).

**Insert trust scores**:
```sql
-- Official exchanges (high trust)
INSERT INTO tgscan.source_trust (channel_id, trust_score, is_official, category)
VALUES
  (-1001234567890, 0.95, true, 'EXCHANGE'),  -- Binance Official
  (-1009876543210, 0.95, true, 'EXCHANGE');  -- Coinbase Official

-- Verified news channels (medium-high trust)
INSERT INTO tgscan.source_trust (channel_id, trust_score, is_official, category)
VALUES
  (-1001111111111, 0.75, false, 'NEWS'),     -- CoinDesk
  (-1002222222222, 0.75, false, 'NEWS');     -- CoinTelegraph

-- Community channels (medium trust)
INSERT INTO tgscan.source_trust (channel_id, trust_score, is_official, category)
VALUES
  (-1003333333333, 0.50, false, 'COMMUNITY'),
  (-1004444444444, 0.50, false, 'COMMUNITY');

-- Influencers (varies)
INSERT INTO tgscan.source_trust (channel_id, trust_score, is_official, category)
VALUES
  (-1005555555555, 0.60, false, 'INFLUENCER');
```

**Categories**:
- `EXCHANGE` - Official exchange announcements
- `NEWS` - News/media outlets
- `INFLUENCER` - Crypto influencers
- `PROJECT` - Project official channels
- `COMMUNITY` - Community discussions

**Note**: Replace channel IDs with actual IDs from `tgscan.channels` table.

### 3. Verify Database

**Check source trust**:
```sql
SELECT c.title, c.id, st.trust_score, st.is_official, st.category
FROM tgscan.channels c
LEFT JOIN tgscan.source_trust st ON st.channel_id = c.id
ORDER BY st.trust_score DESC NULLS LAST
LIMIT 20;
```

**Check clustering**:
```sql
SELECT cluster_id, COUNT(*) as count, MAX(is_primary_in_cluster) as has_primary
FROM bot.messages
WHERE cluster_id IS NOT NULL
GROUP BY cluster_id
ORDER BY count DESC
LIMIT 10;
```

---

## Application Configuration

### 1. Environment Variables

Create `.env` file or set environment variables:

```bash
# Telegram API
TELEGRAM_API_ID=your_api_id
TELEGRAM_API_HASH=your_api_hash
TELEGRAM_PHONE_NUMBER=+1234567890

# Database
DATABASE_HOST=localhost
DATABASE_PORT=5432
DATABASE_NAME=news_aggregator_db
DATABASE_USERNAME=bot_user
DATABASE_PASSWORD=your_password

# LLM (DeepSeek)
DEEPSEEK_API_KEY=your_deepseek_api_key
DEEPSEEK_BASE_URL=https://api.deepseek.com/v1
DEEPSEEK_TIMEOUT=30000

# Kafka (if using external)
KAFKA_BOOTSTRAP_SERVERS=localhost:9092

# HTTP API (optional)
HTTP_ENABLED=true
SERVER_PORT=8080
```

### 2. Application Properties

Edit `src/main/resources/application.yml`:

```yaml
# News System Configuration
news:
  clustering:
    enabled: true
    similarity-threshold: 0.85
    max-cluster-size: 50
    ttl-hours: 48

  scoring:
    enabled: true
    wilson-confidence: 0.95
    time-decay-factor: 0.1
    source-trust-weight: 0.3

  synthesis:
    enabled: true
    default-language: en
    max-digest-messages: 10

# Scheduled Jobs
spring:
  task:
    scheduling:
      enabled: true
      pool:
        size: 5
```

### 3. Persona Configuration

Personas are stored in `bot.bot_personas` table. Configure via:

**Option A: Database Insert**
```sql
INSERT INTO bot.bot_personas (bot_id, language, name, description, behavior, traits, limitations, metadata)
VALUES (
  '<TELEGRAM_BOT_TOKEN>',  -- Bot token
  'en',                                               -- Language
  'Crypto News Pro',                                  -- Display name
  'Professional cryptocurrency news aggregator',      -- Description
  'Provide factual, timely crypto news updates',      -- Behavior
  'professional,concise,data-driven',                 -- Traits
  'No financial advice, no speculation',              -- Limitations
  '{"target_chat": -1001234567890, "schedule": "0 0 8,20 * * *", "style": "professional", "topics": ["EXCHANGE", "NEWS"], "lookback_hours": 6, "top_clusters": 3}'  -- Metadata (JSONB)
);
```

**Option B: REST API** (requires HTTP_ENABLED=true)
```bash
curl -X PUT http://localhost:8080/api/admin/persona/BOT_TOKEN/en \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Crypto News Pro",
    "description": "Professional crypto news",
    "behavior": "Factual updates",
    "traits": "professional,concise",
    "limitations": "No advice",
    "metadata": {
      "target_chat": -1001234567890,
      "schedule": "0 0 8,20 * * *",
      "style": "professional",
      "topics": ["EXCHANGE", "NEWS"],
      "lookback_hours": 6,
      "top_clusters": 3
    }
  }'
```

**Option C: UI** (if frontend running)
- Navigate to http://localhost:8080 (or configured port)
- Go to "Persona" tab
- Click "Add Persona"
- Fill in details and metadata

**Metadata fields**:
- `target_chat` - Chat ID to publish digests
- `schedule` - Cron expression for auto-publishing
- `style` - LLM style: `professional`, `ironic`, `insider`, `strict`
- `topics` - Array of categories to include (filter)
- `lookback_hours` - How far back to look for news
- `top_clusters` - How many top clusters to include

---

## Topic Categorization

### 1. Categories Overview

Categories are stored in `tgscan.source_trust.category` field.

**Standard categories**:
- **EXCHANGE** - Exchange announcements (listings, updates, maintenance)
- **NEWS** - General crypto news from media outlets
- **INFLUENCER** - Influencer opinions and analysis
- **PROJECT** - Project-specific updates (roadmaps, releases)
- **COMMUNITY** - Community discussions and sentiment

**Custom categories** can be added by setting different values in the `category` column.

### 2. Assign Channels to Categories

```sql
-- Update existing channels
UPDATE tgscan.source_trust
SET category = 'EXCHANGE'
WHERE channel_id = -1001234567890;

-- Or insert new trust record with category
INSERT INTO tgscan.source_trust (channel_id, trust_score, is_official, category)
VALUES (-1001234567890, 0.9, true, 'EXCHANGE')
ON CONFLICT (channel_id) DO UPDATE
SET category = EXCLUDED.category;
```

### 3. Persona Topic Filtering

Personas filter news by topics via `metadata.topics` array:

```json
{
  "topics": ["EXCHANGE", "NEWS"]
}
```

**Filter logic**:
- If `topics` is empty or null → Include all messages
- If `topics` contains values → Only include messages from channels with matching category

**Update persona topics**:
```sql
UPDATE bot.bot_personas
SET metadata = jsonb_set(
  metadata,
  '{topics}',
  '["EXCHANGE", "NEWS", "PROJECT"]'::jsonb
)
WHERE bot_id = 'YOUR_BOT_TOKEN' AND language = 'en';
```

---

## Testing Workflow

### 1. Test Chat Setup

**Test chat**: `-1001234567890`

**Steps**:
1. Create bot via @BotFather
2. Get bot token: `<TELEGRAM_BOT_TOKEN>x`
3. Add bot to test chat as administrator
4. Grant bot permissions:
   - Post messages
   - Delete messages (optional)
   - Pin messages (optional)

### 2. Configure Test Personas

**Persona 1: Professional News Bot**
```sql
INSERT INTO bot.bot_personas (bot_id, language, name, description, behavior, traits, metadata)
VALUES (
  'BOT_TOKEN_1',
  'en',
  'Crypto News Pro Test',
  'Professional crypto news aggregator (TEST)',
  'Provide factual, timely news',
  'professional,concise,objective',
  '{
    "target_chat": -1001234567890,
    "style": "professional",
    "topics": ["EXCHANGE", "NEWS"],
    "lookback_hours": 6,
    "top_clusters": 3,
    "test_mode": true
  }'::jsonb
);
```

**Persona 2: Insider/Ironic Bot**
```sql
INSERT INTO bot.bot_personas (bot_id, language, name, description, behavior, traits, metadata)
VALUES (
  'BOT_TOKEN_2',
  'en',
  'Crypto Insider Test',
  'Ironic crypto insider (TEST)',
  'Provide insider takes with humor',
  'ironic,witty,insider-knowledge',
  '{
    "target_chat": -1001234567890,
    "style": "ironic",
    "topics": ["EXCHANGE", "INFLUENCER"],
    "lookback_hours": 12,
    "top_clusters": 5,
    "test_mode": true
  }'::jsonb
);
```

### 3. Manual Testing Steps

**Step 1: Verify Messages Collected**
```sql
SELECT COUNT(*), MIN(date), MAX(date)
FROM bot.messages
WHERE chat_id IN (
  SELECT id FROM tgscan.channels WHERE join_status = 'JOINED'
);
```

**Step 2: Check Scoring**
```sql
SELECT
  m.message_id,
  m.content,
  m.importance,
  c.title as channel_name,
  st.trust_score,
  st.category
FROM bot.messages m
JOIN tgscan.channels c ON c.id = m.chat_id
LEFT JOIN tgscan.source_trust st ON st.channel_id = m.chat_id
WHERE m.date > NOW() - INTERVAL '24 hours'
ORDER BY m.importance DESC NULLS LAST
LIMIT 10;
```

**Step 3: Verify Clustering**
```sql
SELECT
  cluster_id,
  COUNT(*) as message_count,
  MAX(is_primary_in_cluster) as has_primary,
  MAX(importance) as top_score
FROM bot.messages
WHERE cluster_id IS NOT NULL
  AND date > NOW() - INTERVAL '24 hours'
GROUP BY cluster_id
ORDER BY top_score DESC NULLS LAST
LIMIT 10;
```

**Step 4: Manual Digest Generation**

Via REST API:
```bash
curl -X POST http://localhost:8080/api/news/digest/generate \
  -H "Content-Type: application/json" \
  -d '{
    "persona_id": "BOT_TOKEN_1",
    "language": "en",
    "lookback_hours": 6,
    "top_clusters": 3,
    "test_mode": true
  }'
```

Via Java service (programmatically):
```java
NewsSynthesisService synthesisService = ...; // Inject
Duration window = Duration.ofHours(6);
int maxMessages = 10;
String language = "en";

String digest = synthesisService.generateDigest(window, maxMessages, language)
    .block(Duration.ofSeconds(30));

System.out.println(digest);
```

**Step 5: Publish to Test Chat**

The digest will be automatically published if persona has `target_chat` set.

Verify in Telegram:
- Open test chat `-1001234567890`
- Check for bot message
- Verify formatting and content quality

**Step 6: Quality Verification**

✅ **Check**:
- [ ] Digest contains relevant news
- [ ] No duplicate information
- [ ] Proper formatting (Markdown or HTML)
- [ ] Appropriate tone for persona
- [ ] Topics match persona filter
- [ ] Sources are credited
- [ ] Timestamps are recent

---

## Production Deployment

### 1. Enable Scheduled Jobs

The system includes scheduled jobs for automatic digest generation.

**Configuration**:
```yaml
# application-prod.yml
news:
  jobs:
    clustering:
      enabled: true
      cron: "0 */15 * * * *"  # Every 15 minutes

    digest-generation:
      enabled: true
      cron: "0 0 8,20 * * *"  # 8 AM and 8 PM daily
```

**Job classes**:
- `MessageClusteringJob` - Clusters new messages
- `DigestGenerationJob` - Generates and publishes digests

**Monitor job execution**:
```bash
# Check logs
tail -f logs/application.log | grep "DigestGenerationJob"

# Or via database (if job logging enabled)
SELECT * FROM bot.sync_run_log
WHERE job_type = 'DIGEST_GENERATION'
ORDER BY started_at DESC
LIMIT 10;
```

### 2. Monitor System

**Key metrics to track**:

1. **Message ingestion rate**
   ```sql
   SELECT
     DATE_TRUNC('hour', date) as hour,
     COUNT(*) as messages
   FROM bot.messages
   WHERE date > NOW() - INTERVAL '24 hours'
   GROUP BY hour
   ORDER BY hour;
   ```

2. **Clustering effectiveness**
   ```sql
   SELECT
     COUNT(DISTINCT cluster_id) as clusters,
     COUNT(*) as total_messages,
     ROUND(COUNT(DISTINCT cluster_id)::numeric / COUNT(*)::numeric * 100, 2) as cluster_ratio
   FROM bot.messages
   WHERE cluster_id IS NOT NULL
     AND date > NOW() - INTERVAL '24 hours';
   ```

3. **Source trust coverage**
   ```sql
   SELECT
     CASE WHEN st.channel_id IS NOT NULL THEN 'Has Trust' ELSE 'No Trust' END as status,
     COUNT(DISTINCT c.id) as channel_count
   FROM tgscan.channels c
   LEFT JOIN tgscan.source_trust st ON st.channel_id = c.id
   WHERE c.join_status = 'JOINED'
   GROUP BY status;
   ```

4. **Digest generation success rate**
   - Check logs for errors
   - Monitor Telegram API rate limits
   - Verify bots are not blocked

### 3. Troubleshooting

#### Issue: No messages being scored

**Diagnosis**:
```sql
-- Check if messages exist
SELECT COUNT(*) FROM bot.messages WHERE date > NOW() - INTERVAL '1 hour';

-- Check if importance is null
SELECT COUNT(*) FROM bot.messages
WHERE date > NOW() - INTERVAL '1 hour' AND importance IS NULL;
```

**Fix**:
- Verify `news.scoring.enabled=true` in config
- Check logs for scoring errors
- Manually trigger scoring (if service available)

#### Issue: Clustering not working

**Diagnosis**:
```sql
SELECT COUNT(*) FROM bot.messages
WHERE date > NOW() - INTERVAL '1 hour' AND cluster_id IS NULL;
```

**Fix**:
- Verify `news.clustering.enabled=true`
- Check similarity threshold (may be too strict)
- Review logs for `MessageClusteringService` errors

#### Issue: Digest not publishing

**Diagnosis**:
- Check bot token is valid
- Verify bot is in target chat
- Check bot permissions in chat
- Review Telegram API errors in logs

**Fix**:
- Re-add bot to chat
- Grant necessary permissions
- Check rate limits (Telegram API)
- Verify `target_chat` in persona metadata

#### Issue: Poor digest quality

**Symptoms**:
- Irrelevant content
- Duplicate information
- Wrong language
- Incorrect tone

**Fix**:
- Adjust `lookback_hours` (may be too broad)
- Reduce `top_clusters` (may include low-quality)
- Update persona `style` and `traits`
- Improve source trust scores
- Filter topics more precisely

---

## UI Access

### Starting the Frontend

The system includes a React-based admin UI.

**Development mode**:
```bash
cd frontend
npm install
npm run dev
```

Access at: http://localhost:5173 (Vite default)

**Production build**:
```bash
cd frontend
npm run build
# Serve dist/ folder via nginx or Spring Boot static resources
```

### UI Features

**Available pages**:
1. **Config** - Chat configuration management
2. **Database** - Database explorer
3. **Persona** - Bot persona CRUD (✅ For news system)
4. **Explorer** - Message and channel exploration

**Persona Management**:
- Navigate to "Persona" tab
- View all configured personas
- Edit metadata (schedule, topics, style)
- Test digest generation
- Monitor publication status

**Source Trust Management** (via Database Explorer):
- Query `tgscan.source_trust` table
- Update trust scores
- Assign categories
- View channel statistics

### API Endpoints (Swagger)

If `HTTP_ENABLED=true`, Swagger UI is available:

**Access**: http://localhost:8080/swagger-ui.html

**Key endpoints**:
- `GET /api/admin/persona` - List all personas
- `GET /api/admin/persona/{botId}` - Personas for specific bot
- `PUT /api/admin/persona/{botId}/{lang}` - Create/update persona
- `GET /api/admin/persona/{botId}/{lang}` - Get specific persona

---

## Configuration Examples

See `config-examples/` directory for:

1. **application-test.yml.example** - Test environment config
2. **personas-example.json** - Example persona configurations
3. **source-trust-example.sql** - Example trust score setup
4. **.env.example** - Environment variables template

---

## Appendix

### A. Persona Style Guide

**Professional**:
- Objective, fact-based reporting
- Formal language
- No emojis or slang
- Cites sources

**Ironic**:
- Sarcastic tone
- Playful language
- Uses emojis sparingly
- Points out absurdities

**Insider**:
- Industry jargon
- Assumes expert knowledge
- Technical details
- No hand-holding

**Strict**:
- Extremely formal
- Regulatory focus
- Legal language
- Risk disclaimers

### B. Database Schema Quick Reference

```
tgscan.source_trust
├── channel_id (PK) → references tgscan.channels(id)
├── trust_score (0.0 - 1.0)
├── is_official (boolean)
├── category (VARCHAR: EXCHANGE, NEWS, etc.)
├── manual_override (boolean)
├── created_at
└── last_updated

bot.messages
├── ... (existing columns)
├── cluster_id (VARCHAR 64)
└── is_primary_in_cluster (BOOLEAN)

bot.bot_personas
├── id (PK)
├── bot_id (unique with language)
├── language (en, ru, uk, etc.)
├── name, description, behavior, traits, limitations
└── metadata (JSONB)
    ├── target_chat (BIGINT)
    ├── schedule (CRON)
    ├── style (professional, ironic, insider, strict)
    ├── topics (ARRAY of categories)
    ├── lookback_hours (INT)
    └── top_clusters (INT)
```

### C. Useful SQL Queries

**Top scored messages (last 24h)**:
```sql
SELECT m.content, m.importance, c.title, st.trust_score
FROM bot.messages m
JOIN tgscan.channels c ON c.id = m.chat_id
LEFT JOIN tgscan.source_trust st ON st.channel_id = m.chat_id
WHERE m.date > NOW() - INTERVAL '24 hours'
ORDER BY m.importance DESC NULLS LAST
LIMIT 20;
```

**Cluster summary**:
```sql
SELECT
  cluster_id,
  COUNT(*) as msg_count,
  MAX(importance) as top_score,
  MIN(date) as first_seen,
  MAX(date) as last_seen,
  STRING_AGG(DISTINCT c.title, ', ') as sources
FROM bot.messages m
JOIN tgscan.channels c ON c.id = m.chat_id
WHERE cluster_id IS NOT NULL
  AND date > NOW() - INTERVAL '24 hours'
GROUP BY cluster_id
ORDER BY top_score DESC NULLS LAST;
```

**Personas with metadata**:
```sql
SELECT
  bot_id,
  language,
  name,
  metadata->>'style' as style,
  metadata->>'target_chat' as target_chat,
  metadata->>'topics' as topics
FROM bot.bot_personas
ORDER BY bot_id, language;
```

---

## Support

For issues or questions:
- Check logs: `logs/application.log`
- Review database state with SQL queries above
- Verify configuration in `application.yml`
- Test with manual API calls

---

**END OF SETUP GUIDE**
