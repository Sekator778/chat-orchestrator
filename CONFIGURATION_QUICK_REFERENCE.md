# Configuration Entities - Quick Reference

> Fast lookup guide for all configuration entities

---

## Entity at a Glance

| Entity | Schema | Table | Purpose | Cardinality | Key Fields |
|--------|--------|-------|---------|-------------|-----------|
| **ChatConfig** | bot | chat_configs | Configuration root per chat | 1 per Channel | channel_chat_id (FK, UNIQUE) |
| **Channel** | tgscan | channels | Telegram chat/channel root | Root | id (chatId), title, scores |
| **TriggerCondition** | bot | trigger_conditions | Response trigger rule | N per ChatConfig | chat_config_id (FK) |
| **LlmParameters** | bot | llm_parameters | LLM model settings | 1 per ChatConfig | chat_config_id (FK, UNIQUE) |
| **RateLimits** | bot | rate_limits | Message/token quotas | 1 per ChatConfig | chat_config_id (FK, UNIQUE) |
| **ResponseTemplate** | bot | response_templates | Response structure | N per ChatConfig | chat_config_id (FK) |
| **TopicRestriction** | bot | topic_restrictions | Forbidden topics | N per ChatConfig | chat_config_id (FK) |
| **SearchConfig** | bot | search_configs | Web search settings | 1 per ChatConfig | chat_id |
| **ContextSettings** | bot | context_settings | Message context config | 1 per ChatConfig | chat_config_id (FK, UNIQUE) |
| **SyncJob** | bot | sync_jobs | Sync operation tracking | N per Channel | channel_id (FK) |
| **User** | bot | users | User personalization | Global | telegram_user_id (UNIQUE) |
| **BotPersona** | bot | bot_personas | Bot identity config | Global | bot_id |
| **DigestPersona** | bot | digest_personas | Digest generation config | Global | bot_id |
| **DigestHistory** | bot | digest_history | Published digest tracking | N per DigestPersona | persona_id (FK) |
| **MessageEntity** | bot | messages | Telegram messages (data) | N per Channel | chat_id, message_id |
| **Other** | bot | events, posts, pending_response, tdlib_operations | System entities | Operational | - |

---

## Parent-Child Relationships

```
ChatConfig (Parent)
├─ 1:1 LlmParameters [UNIQUE constraint]
├─ 1:1 RateLimits [UNIQUE constraint]
├─ 1:1 ContextSettings [UNIQUE constraint]
├─ 1:1 SearchConfig [Implicit via chat_id]
├─ N:1 TriggerCondition [Cascade on delete]
├─ N:1 ResponseTemplate [Cascade on delete]
├─ N:1 TopicRestriction [Cascade on delete]
└─ N:1 PendingResponse [Cascade on delete]

Channel (Parent)
├─ 1:N ChatConfig [Cascade on delete]
├─ 1:N MessageEntity [No cascade]
└─ 1:N SyncJob [No cascade]

DigestPersona (Parent)
└─ 1:N DigestHistory [Cascade on delete]

SyncJob (Parent)
└─ 1:N MessageEntity (implicit via sync_job_id)
```

---

## How to Query

### Load All Config for a Chat

```sql
-- Get ChatConfig root
SELECT * FROM bot.chat_configs WHERE channel_chat_id = $1;

-- Get all children (can be separate queries or JOINs)
SELECT * FROM bot.llm_parameters WHERE chat_config_id = $config_id;
SELECT * FROM bot.rate_limits WHERE chat_config_id = $config_id;
SELECT * FROM bot.trigger_conditions WHERE chat_config_id = $config_id;
SELECT * FROM bot.response_templates WHERE chat_config_id = $config_id;
SELECT * FROM bot.topic_restrictions WHERE chat_config_id = $config_id;
SELECT * FROM bot.search_configs WHERE chat_id = $channel_id;
SELECT * FROM bot.context_settings WHERE chat_config_id = $config_id;
```

### Find Matching Triggers

```sql
SELECT * FROM bot.trigger_conditions
WHERE chat_config_id = $config_id AND active = true
ORDER BY priority DESC;
```

### Check Rate Limits

```sql
SELECT * FROM bot.rate_limits
WHERE chat_config_id = $config_id;
-- Then check: currentDailyMessages >= maxMessagesPerDay
```

### Get Active Digest Personas

```sql
SELECT dp.*, COUNT(dh.id) as total_digests
FROM bot.digest_personas dp
LEFT JOIN bot.digest_history dh ON dp.id = dh.persona_id
WHERE dp.enabled = true
GROUP BY dp.id
ORDER BY dp.last_run_at DESC NULLS LAST;
```

### Load Message Context

```sql
SELECT * FROM bot.messages
WHERE chat_id = $chat_id
  AND date > NOW() - INTERVAL '24 hours'  -- history_time_window_hours
ORDER BY date DESC
LIMIT 10;  -- history_message_count
```

---

## Field Reference by Use Case

### Message Routing & Decision Making

**From TriggerCondition**:
- `trigger_type` - KEYWORD, REPLY, MENTION, etc.
- `keywords` - Comma-separated keywords to match
- `mention_required` - Bot must be mentioned
- `active_hours_start/end` - Time window for response
- `active_days_of_week` - Days when trigger is active
- `minimum_gap_minutes` - Cooldown between responses
- `probability_percent` - 0-100, chance to respond
- `time_delay_seconds` - Delay before responding

### Response Generation

**From LlmParameters**:
- `model_name` - 'deepseek-chat' (default)
- `temperature` - 0.0-1.0 (default 0.7), controls creativity
- `max_tokens` - Response length limit (default 1000)
- `top_p` - Diversity sampling (default 0.9)
- `frequency_penalty` - Reduce repetition (default 0.0)
- `presence_penalty` - Encourage new topics (default 0.0)
- `system_prompt` - Base instructions
- `custom_instructions` - Additional constraints
- `response_format` - TEXT, JSON, XML, MARKDOWN

**From ResponseTemplate**:
- `template_content` - Response structure with placeholders
- `response_style` - ADAPTIVE, FORMAL, CASUAL, HUMOROUS, TECHNICAL
- `response_tone` - NEUTRAL, POSITIVE, NEGATIVE, SARCASTIC, EMPATHETIC
- `max_response_length` - Character limit (default 500)
- `is_default` - Use when no trigger matches
- `priority` - Selection order if multiple match

### Rate Limiting & Quotas

**From RateLimits**:
- `max_messages_per_minute` - Hard limit
- `max_messages_per_hour` - 20 default, soft limit
- `max_messages_per_day` - 100 default, with daily reset
- `max_tokens_per_day` - 50000 default, for LLM token budget
- `current_daily_messages` - Counter (reset at midnight)
- `cooldown_after_limit_minutes` - 60 default, wait after breach
- `burst_limit` - 3 default, max consecutive messages
- `burst_window_seconds` - 60 default, sliding window
- `pending_response_delay_seconds` - Additional delay before send
- `user_specific_limits` - Apply limits per-user instead of global

### Content Filtering

**From TopicRestriction**:
- `restriction_type` - KEYWORD, PATTERN, CATEGORY, REGEX
- `keywords` - Comma-separated forbidden terms
- `categories` - Violence, adult, hate_speech, etc.
- `action_type` - IGNORE, DECLINE, CUSTOM_RESPONSE, LOG_ONLY
- `custom_response` - Reply when restricted topic detected
- `active` - Enable/disable this restriction

### Message Context

**From ContextSettings**:
- `history_message_count` - 10 default, number of previous messages
- `history_time_window_hours` - 24 default, time span to consider
- `include_user_context` - Include sender profile/preferences
- `include_media_descriptions` - Describe media in context
- `context_compression_enabled` - Compress long histories
- `max_context_tokens` - 2000 default, token budget for context
- `preserve_important_messages` - Keep high-importance messages

### Search Integration

**From SearchConfig**:
- `search_enabled` - Turn on/off
- `auto_search_enabled` - Auto-trigger search based on content
- `search_provider` - GOOGLE, BING, DUCKDUCKGO
- `max_results` - 5 default, search result count
- `cache_duration_minutes` - 60 default, cache TTL
- `rate_limit_per_hour` - 30 default, search quota
- `include_attribution` - Credit sources in response
- `relevance_threshold` - 0.6 default, minimum relevance (0-1)
- `search_triggers` - JSON patterns that trigger search

### Digest Generation

**From DigestPersona**:
- `persona_style` - PROFESSIONAL, IRONIC, BREAKING_NEWS, TECHNICAL, CUSTOM
- `schedule_cron` - Cron expression for generation timing
- `schedule_timezone` - UTC default, timezone for scheduling
- `active_hours_start/end` - Time window for publishing
- `lookback_hours` - 24 default, content lookback period
- `max_messages` - 10 default, max messages in digest
- `language` - 'en' default, digest language
- `min_cluster_size` - 2 default, minimum message cluster size
- `min_importance_score` - 0.0 default, importance threshold
- `source_trust_threshold` - 0.0 default, source quality filter
- `excluded_channel_ids` - Channels to exclude (array)
- `topic_keywords` - Inclusion keywords (array)
- `negative_keywords` - Exclusion keywords (array)
- `model_name` - Override LLM model
- `temperature` - Override LLM temperature
- `max_tokens` - Override LLM max tokens

### Bot Identity

**From BotPersona**:
- `name` - Persona name
- `description` - What the bot should be known as
- `behavior` - How it should behave
- `traits` - Personality attributes
- `limitations` - What it shouldn't do
- `language` - Language variant (can have multiple personas per bot)

### User Personalization

**From User**:
- `communication_style` - CASUAL, FORMAL, PROFESSIONAL, FRIENDLY, TECHNICAL
- `preferred_name` - Nickname to use in responses
- `preferred_title` - Title/prefix (Dr., Mr., etc.)
- `personality_traits` - How to perceive user
- `relationship_context` - Relationship type (friend, colleague, etc.)
- `language_preference` - Preferred language ('uk' default)
- `response_length` - SHORT, MEDIUM, LONG
- `ai_enabled` - Whether AI responses enabled for this user

### Sync Configuration

**From ChatConfig**:
- `sync_enabled` - Enable message history sync
- `auto_sync_enabled` - Auto-trigger sync on schedule
- `default_sync_depth_days` - How far back to sync
- `processing_phase` - RAW, PHASE1, PHASE2, PHASE3

**From SyncJob**:
- `status` - PENDING, IN_PROGRESS, COMPLETED, FAILED
- `sync_depth_days` - Configured depth for this job
- `sync_from_date` - Start of sync window
- `sync_to_date` - End of sync window
- `messages_processed` - Current progress counter
- `messages_total` - Total messages to sync
- `bot_instance_id` - Which bot instance running sync

---

## Common Configuration Patterns

### Minimal Configuration (Do Nothing)
```
ChatConfig.enabled = false
```
No responses sent, bot is dormant.

### Keyword Trigger Configuration
```
ChatConfig.enabled = true
TriggerCondition.trigger_type = KEYWORD
TriggerCondition.keywords = 'bitcoin,ethereum,price'
TriggerCondition.active = true
ResponseTemplate (any)
LlmParameters (default model)
RateLimits (defaults: 20/hr, 100/day)
```

### Quiet Bot Configuration
```
TriggerCondition.probability_percent = 25  (respond only 25% of time)
RateLimits.max_messages_per_hour = 5
RateLimits.cooldown_after_limit_minutes = 120
RateLimits.burst_limit = 1
ResponseTemplate.max_response_length = 100
```

### Expert Bot Configuration
```
LlmParameters.model_name = 'deepseek-chat'
LlmParameters.temperature = 0.3  (more precise, less creative)
LlmParameters.top_p = 0.8
BotPersona.traits = ['knowledgeable', 'precise', 'technical']
ResponseTemplate.response_style = TECHNICAL
SearchConfig.search_enabled = true
SearchConfig.search_provider = GOOGLE
```

### News Digest Configuration
```
DigestPersona.persona_style = PROFESSIONAL
DigestPersona.schedule_cron = '0 8 * * *'  (daily at 8 AM)
DigestPersona.lookback_hours = 24
DigestPersona.language = 'en'
DigestPersona.topic_keywords = ['news', 'important', 'breaking']
DigestPersona.min_importance_score = 0.7
```

---

## Configuration Initialization Checklist

When setting up a new chat configuration:

- [ ] **ChatConfig** created (auto or manual)
  - [ ] `channel_chat_id` set correctly
  - [ ] `language` matches chat language
  - [ ] `context_window_size` appropriate for chat type
  - [ ] `enabled = false` until ready

- [ ] **LlmParameters** configured
  - [ ] `model_name` = 'deepseek-chat' (or override)
  - [ ] `temperature` set appropriately (0.7 balanced, 0.3 precise, 0.9 creative)
  - [ ] `max_tokens` set for response length
  - [ ] `system_prompt` tailored to chat purpose

- [ ] **RateLimits** configured
  - [ ] `max_messages_per_hour` set (20 default)
  - [ ] `max_messages_per_day` set (100 default)
  - [ ] `burst_limit` set (3 default) for burst protection
  - [ ] `cooldown_after_limit_minutes` set (60 default)

- [ ] **ResponseTemplate** created
  - [ ] `template_content` has placeholders
  - [ ] `response_style` matches bot personality
  - [ ] `response_tone` matches chat expectations
  - [ ] Mark one as `is_default = true` if multiple templates

- [ ] **TriggerCondition** created
  - [ ] `trigger_type` chosen (KEYWORD, REPLY, MENTION, etc.)
  - [ ] `keywords` specified if keyword-based
  - [ ] `active_hours_start/end` set if time-specific
  - [ ] `priority` set correctly if multiple triggers
  - [ ] `active = true`

- [ ] **TopicRestriction** added (if needed)
  - [ ] `restriction_type` chosen
  - [ ] `keywords` or `categories` specified
  - [ ] `action_type` chosen (IGNORE, DECLINE, CUSTOM_RESPONSE)
  - [ ] `active = true`

- [ ] **SearchConfig** configured (optional)
  - [ ] `search_enabled = true/false` as needed
  - [ ] `search_provider` chosen
  - [ ] `max_results` appropriate
  - [ ] `relevance_threshold` tuned

- [ ] **ContextSettings** configured (optional)
  - [ ] `history_message_count` set (10 default)
  - [ ] `history_time_window_hours` set (24 default)
  - [ ] `max_context_tokens` appropriate for LLM

- [ ] **Final Step**: Set `ChatConfig.enabled = true` to activate

---

## Most Important Fields by Role

### For Admins Configuring Bots
1. **ChatConfig.enabled** - Master on/off switch
2. **TriggerCondition** - When to respond
3. **ResponseTemplate.template_content** - What to say
4. **LlmParameters.temperature** - How creative/precise
5. **RateLimits.max_messages_per_day** - How often to respond

### For Developers Integrating
1. **ChatConfig.channel_chat_id** - Chat identifier
2. **TriggerCondition.trigger_type** - Matching logic
3. **LlmParameters** - LLM call parameters
4. **ContextSettings** - Message context retrieval
5. **RateLimits** - Quota enforcement

### For DevOps / Monitoring
1. **ChatConfig.enabled** - Service status
2. **ChatConfig.processing_phase** - Pipeline stage
3. **RateLimits.current_daily_messages** - Usage tracking
4. **SyncJob.status** - Background job status
5. **DigestPersona.last_run_at** - Schedule compliance

---

## Database Column Types at a Glance

| Type | Used For | Examples |
|------|----------|----------|
| BIGSERIAL | Primary keys | id, chat_config_id |
| BIGINT | IDs, counts | channel_chat_id, message_id, subscribers |
| VARCHAR(n) | Short text | model_name, language, username |
| TEXT | Long text | template_content, keywords, system_prompt |
| BOOLEAN | Flags | enabled, active, search_enabled |
| DOUBLE PRECISION | Decimals | temperature, relevance_threshold |
| INTEGER | Numbers | max_tokens, context_window_size |
| TIME | Time of day | active_hours_start, active_hours_end |
| TIMESTAMP WITH TIME ZONE | Audit | created_at, updated_at, last_run_at |
| TEXT[] | Arrays | bot_instance_ids, topic_keywords |
| ENUM | Fixed choices | status, trigger_type, response_style |

---

## Foreign Key Constraints

All cascading foreign keys automatically delete children when parent is deleted:

```
chat_configs → channels (channel_chat_id)
     ├─ trigger_conditions → chat_configs (cascade)
     ├─ llm_parameters → chat_configs (cascade)
     ├─ rate_limits → chat_configs (cascade)
     ├─ response_templates → chat_configs (cascade)
     ├─ topic_restrictions → chat_configs (cascade)
     └─ context_settings → chat_configs (cascade)

digest_history → digest_personas (cascade)

sync_jobs → channels (no cascade)
messages → sync_jobs (no cascade)
```

**Impact**: Deleting a ChatConfig cascades to all its configuration children. But message history and sync jobs are preserved.

---

## Configuration Lifetime

```
T0: Channel Created
T1: ChatConfig Auto-Created (disabled)
T2: Default children created (LLM, Rate Limits, Context, Search)
T3: Admin adds Triggers & Templates
T4: ChatConfig.enabled = true (START RESPONDING)
T+: Configuration can be updated anytime (effects immediate)
T-n: ChatConfig deleted (cascades all config, keeps message history)
```

---

## Key Takeaways

1. **ChatConfig is the hub** - Everything else is organized around it
2. **1:1 children are unique per chat** - Only one LLM config, rate limits, context settings per ChatConfig
3. **N:1 children are collections** - Multiple triggers, templates, restrictions allowed
4. **Cascade deletion** - Deleting ChatConfig or DigestPersona deletes all children
5. **Global entities** - BotPersona, User, DigestPersona are not per-chat (global scope)
6. **Changes effective immediately** - Config updates apply to next message (no restart needed)
7. **Multi-tenant isolation** - Each Channel has completely independent configuration
8. **Field aliasing** - MessageEntity has backward-compatible field aliases (messageId/telegramMessageId, etc.)

