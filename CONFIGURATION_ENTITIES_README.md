# Configuration Entities Documentation

Complete documentation of all configuration-related domain entities and their relationships in the Telegram UserBot application.

## Documents Included

### 1. **CONFIGURATION_ENTITIES_MAP.md** (Main Reference)
Comprehensive reference document covering:
- Complete entity overview (19 configuration entities)
- Detailed field documentation for each entity
- Foreign key relationships and constraints
- Database schema organization (bot and tgscan schemas)
- Entity initialization patterns
- Configuration lifecycle
- Query examples and patterns
- Configuration constraints and validations

**Use this for**: Understanding complete entity structure, field meanings, relationships, and database design.

### 2. **CONFIGURATION_ENTITY_DIAGRAMS.md** (Visual Reference)
Visual representations including:
- Complete Entity Relationship Diagram (ER Model)
- Simplified configuration hierarchy
- Configuration dependency flow
- Configuration state machine
- Data flow from message to response
- Configuration update propagation
- Multi-tenant isolation model
- Cascade delete behavior
- Uniqueness constraints
- Configuration timeline

**Use this for**: Understanding relationships visually, data flow, and state transitions.

### 3. **CONFIGURATION_QUICK_REFERENCE.md** (Fast Lookup)
Quick reference guide including:
- Entity at a glance table
- Parent-child relationship summary
- How to query examples
- Field reference by use case
- Common configuration patterns
- Configuration initialization checklist
- Most important fields by role
- Database column types reference
- Foreign key constraints summary
- Configuration lifetime stages

**Use this for**: Quick lookups, field meanings, SQL examples, and common patterns.

---

## Core Entity Structure

### Configuration Hub

**ChatConfig** is the central configuration entity:
- One per Telegram channel/chat
- Parent to all per-chat configuration
- Controls response behavior, sync settings, processing pipeline
- Enables/disables bot for specific chat

### Configuration Children

**1:1 Unique Children** (only one per ChatConfig):
- `LlmParameters` - LLM model settings (temperature, tokens, penalties, prompts)
- `RateLimits` - Message/token quotas (limits, cooldowns, burst protection)
- `SearchConfig` - Web search settings (provider, cache, rate limits)
- `ContextSettings` - Message context configuration (history window, compression)

**N:1 Collection Children** (multiple per ChatConfig):
- `TriggerCondition` - Response trigger rules (N conditions)
- `ResponseTemplate` - Response patterns (N templates)
- `TopicRestriction` - Forbidden content rules (N restrictions)
- `PendingResponse` - Queued responses (implicit N:1)

### Global Entities (Not Per-Chat)

- `BotPersona` - Bot identity and behavior configuration
- `User` - User profiles and personalization settings
- `DigestPersona` - Digest generation personas (creates DigestHistory records)

### Root Entities

- `Channel` - Telegram channel/chat (tgscan schema, parent to ChatConfig)
- `MessageEntity` - Message storage (data entity, not configuration)

---

## Key Relationships

```
Channel (tgscan)
  ├─ 1:1 → ChatConfig (FK: channel_chat_id, UNIQUE, CASCADE)
  │   ├─ 1:1 → LlmParameters (FK: chat_config_id, UNIQUE, CASCADE)
  │   ├─ 1:1 → RateLimits (FK: chat_config_id, UNIQUE, CASCADE)
  │   ├─ 1:1 → SearchConfig (implicit via chat_id)
  │   ├─ 1:1 → ContextSettings (FK: chat_config_id, UNIQUE, CASCADE)
  │   ├─ 1:N → TriggerCondition (FK: chat_config_id, CASCADE)
  │   ├─ 1:N → ResponseTemplate (FK: chat_config_id, CASCADE)
  │   ├─ 1:N → TopicRestriction (FK: chat_config_id, CASCADE)
  │   └─ 1:N → PendingResponse (FK: chat_config_id, CASCADE)
  ├─ 1:N → MessageEntity (chat_id reference)
  └─ 1:N → SyncJob (channel_id FK)

DigestPersona (global)
  └─ 1:N → DigestHistory (persona_id FK, CASCADE)

User (global)
  └─ 1:N → MessageEntity (sender_id reference, implicit)

BotPersona (global)
  └─ Referenced by LlmServiceFacade and response generation
```

---

## Configuration Use Cases

### 1. Per-Chat AI Response Configuration
ChatConfig + LlmParameters + RateLimits + TriggerCondition + ResponseTemplate

### 2. Content Filtering & Restrictions
ChatConfig + TopicRestriction + SearchConfig

### 3. Message Context Management
ChatConfig + ContextSettings (controls what history is used)

### 4. Automated News Digest Generation
DigestPersona + DigestHistory (scheduled generation with persona-specific settings)

### 5. User Personalization
User entity + User communication profile (affects response style)

### 6. Bot Identity & Behavior
BotPersona (who the bot pretends to be)

### 7. Message History Synchronization
SyncJob + ChatConfig (sync_enabled, default_sync_depth_days)

---

## Field Categorization

### Control Fields
- `ChatConfig.enabled` - Master on/off
- `ChatConfig.sync_enabled` - Sync on/off
- `ChatConfig.multi_stage_enabled` - Response pipeline mode
- `ChatConfig.processing_phase` - Current processing stage

### Behavior Fields
- `LlmParameters.temperature` - Creativity control
- `LlmParameters.top_p` - Diversity control
- `RateLimits.probability_percent` - Response chance
- `ResponseTemplate.response_style` - Style selection
- `BotPersona.traits` - Personality definition

### Quota Fields
- `RateLimits.max_messages_per_*` - Rate limits
- `RateLimits.max_tokens_per_day` - Token budget
- `SearchConfig.rate_limit_per_hour` - Search quota

### Filtering Fields
- `TriggerCondition.keywords` - Keyword matching
- `TopicRestriction.keywords` - Content blocking
- `DigestPersona.excluded_channel_ids` - Channel exclusion

### Timing Fields
- `TriggerCondition.active_hours_*` - Activity window
- `TriggerCondition.minimum_gap_minutes` - Cooldown
- `TriggerCondition.time_delay_seconds` - Delay before respond
- `DigestPersona.schedule_cron` - Generation schedule

---

## Query Patterns

### Load Full Configuration
```sql
SELECT * FROM bot.chat_configs cc
LEFT JOIN bot.llm_parameters lp ON cc.id = lp.chat_config_id
LEFT JOIN bot.rate_limits rl ON cc.id = rl.chat_config_id
LEFT JOIN bot.search_configs sc ON sc.chat_id = cc.channel_chat_id
LEFT JOIN bot.context_settings cs ON cc.id = cs.chat_config_id
WHERE cc.channel_chat_id = $1
```

### Get Active Triggers
```sql
SELECT * FROM bot.trigger_conditions
WHERE chat_config_id = (SELECT id FROM bot.chat_configs WHERE channel_chat_id = $1)
  AND active = true
ORDER BY priority DESC
```

### Check Rate Limits
```sql
SELECT rl.*, cc.enabled FROM bot.rate_limits rl
JOIN bot.chat_configs cc ON rl.chat_config_id = cc.id
WHERE cc.channel_chat_id = $1
```

---

## Configuration Initialization

When a message arrives in a new channel:

1. **Channel exists** (created by Python scanner or TDLight discovery)
2. **ChatConfig auto-created** with defaults:
   - enabled = false
   - language = 'ru'
   - context_window_size = 10
3. **1:1 children auto-created**:
   - LlmParameters (deepseek-chat, 0.7 temp, 1000 tokens)
   - RateLimits (20/hour, 100/day)
   - SearchConfig (disabled)
   - ContextSettings (10 messages, 24 hours)
4. **Admin configures**:
   - Adds TriggerCondition(s)
   - Adds ResponseTemplate(s)
   - Sets enabled = true
5. **Ready for operation**

---

## Configuration Uniqueness

| Entity | Uniqueness | Impact |
|--------|-----------|--------|
| ChatConfig | 1 per channel_chat_id | Only one config per chat |
| LlmParameters | 1 per chat_config_id | Only one LLM config |
| RateLimits | 1 per chat_config_id | Only one rate limit config |
| ContextSettings | 1 per chat_config_id | Only one context config |
| SearchConfig | Implicit 1 per chat_id | Only one search config |
| TriggerCondition | N per chat_config_id | Multiple triggers allowed |
| ResponseTemplate | N per chat_config_id | Multiple templates allowed, only 1 default |
| TopicRestriction | N per chat_config_id | Multiple restrictions allowed |
| DigestHistory | 1 per digest_id | Each digest has unique ID |

---

## Cascade Delete Behavior

**Deleting ChatConfig cascades to**:
- LlmParameters
- RateLimits
- SearchConfig
- ContextSettings
- TriggerCondition (all)
- ResponseTemplate (all)
- TopicRestriction (all)
- PendingResponse (all)

**Deleting Channel cascades to**:
- ChatConfig (and all above)

**Deleting DigestPersona cascades to**:
- DigestHistory (all published digests)

**MessageEntity, SyncJob, Events** are NOT cascade deleted (preserved for audit trail).

---

## Configuration Lifecycle

```
Channel Created
    ↓
ChatConfig Auto-Created (enabled=false)
    ↓
Defaults Applied (1:1 children, no N:1 children)
    ↓
Admin Configures (adds triggers, templates, restrictions)
    ↓
ChatConfig.enabled = true
    ↓
ACTIVE (processing messages, sending responses)
    ↓ (can update anytime, changes immediate)
    ↓
ChatConfig.enabled = false OR DELETE
    ↓
INACTIVE/DELETED (no new responses, history preserved)
```

---

## Integration Points

### Message Processing Pipeline
1. Message arrives → Extract chat_id
2. Load ChatConfig by channel_chat_id
3. Check if enabled, phase, and other control fields
4. Load TriggerCondition(s) → match against message
5. Load LlmParameters → call LLM with settings
6. Load ContextSettings → fetch message history
7. Load ResponseTemplate → format response
8. Check RateLimits → enforce quotas
9. Check TopicRestriction → filter content
10. Send response with delays from TriggerCondition

### Response Generation
- LlmParameters controls what model/settings LLM is called with
- ResponseTemplate controls format and tone
- TriggerCondition controls delay and probability
- RateLimits enforces sending limits
- TopicRestriction filters content

### Digest Generation
- DigestPersona defines persona, schedule, filters
- DigestHistory tracks published digests
- LLM is called with persona's LlmParameters override

---

## Database Organization

### Schema: bot
**Configuration Tables** (main management):
- chat_configs
- llm_parameters
- rate_limits
- trigger_conditions
- response_templates
- topic_restrictions
- context_settings
- search_configs

**Entity Tables**:
- users
- bot_personas
- digest_personas
- digest_history

**Operational Tables**:
- messages
- sync_jobs
- pending_responses
- events
- posts
- tdlib_operations

### Schema: tgscan
**Source Data** (Python scanner):
- channels
- messages (copy)
- channel_candidates

---

## Common Patterns

### Quiet Bot (Minimal Responses)
```
TriggerCondition.probability_percent = 25
RateLimits.max_messages_per_hour = 5
ResponseTemplate.max_response_length = 100
```

### Expert Bot (Precise & Detailed)
```
LlmParameters.temperature = 0.3
LlmParameters.top_p = 0.8
ResponseTemplate.response_style = TECHNICAL
SearchConfig.search_enabled = true
```

### Keyword-Only Bot
```
TriggerCondition.trigger_type = KEYWORD
TriggerCondition.keywords = 'bitcoin,ethereum,price'
TriggerCondition.mention_required = false
```

### News Digest
```
DigestPersona.schedule_cron = '0 8 * * *'
DigestPersona.lookback_hours = 24
DigestPersona.language = 'en'
DigestPersona.min_importance_score = 0.7
```

---

## Most Important Fields

**For Enabling Responses**:
- ChatConfig.enabled
- ChatConfig.channel_chat_id

**For Response Behavior**:
- TriggerCondition.trigger_type & keywords
- LlmParameters.temperature & model_name
- ResponseTemplate.template_content & response_style
- RateLimits.max_messages_per_day

**For Content Control**:
- TopicRestriction.keywords & action_type
- SearchConfig.search_enabled
- ChatConfig.processing_phase

**For Message Context**:
- ContextSettings.history_message_count
- ContextSettings.history_time_window_hours
- ChatConfig.context_window_size

---

## Related Documentation

- **CLAUDE.md** - Development workflow and patterns
- **PROJECT_MAP.md** - Architecture overview
- **MODULES.md** - Module organization and dependencies
- **PATTERNS.md** - Design patterns used throughout

---

## File Locations (Absolute Paths)

- **Entity Classes**: `<repo-root>\src\main\java\com\example\telegramuserbot\domain\`

- **Database Schema**: `<repo-root>\src\main\resources\db\changelog\changes\`

- **Configuration Services**: `<repo-root>\src\main\java\com\example\telegramuserbot\service\`

- **Repositories**: `<repo-root>\src\main\java\com\example\telegramuserbot\repository\`

---

## Entity Classes at a Glance

| Class | File | Lines | Key Annotations |
|-------|------|-------|-----------------|
| ChatConfig | ChatConfig.java | ~200 | @Table("chat_configs"), @Id |
| Channel | Channel.java | ~250 | @Table(schema="tgscan"), Persistable<Long> |
| TriggerCondition | TriggerCondition.java | ~120 | @Table("trigger_conditions"), @Id |
| LlmParameters | LlmParameters.java | ~120 | @Table("llm_parameters"), @Id |
| RateLimits | RateLimits.java | ~140 | @Table("rate_limits"), @Id |
| ResponseTemplate | ResponseTemplate.java | ~110 | @Table("response_templates"), @Id |
| TopicRestriction | TopicRestriction.java | ~130 | @Table("topic_restrictions"), @Id |
| SearchConfig | SearchConfig.java | ~110 | @Table("search_configs"), @Id |
| ContextSettings | ContextSettings.java | ~100 | @Table("context_settings"), @Id |
| SyncJob | SyncJob.java | ~180 | @Table("sync_jobs"), @Id |
| User | User.java | ~160 | @Table("users"), @Id |
| BotPersona | BotPersona.java | ~130 | @Table(schema="bot", name="bot_personas"), @Id |
| DigestPersona | DigestPersona.java | ~260 | @Table(schema="bot", name="digest_personas"), @Id |
| DigestHistory | DigestHistory.java | ~150 | @Table(schema="bot", name="digest_history"), @Id |
| MessageEntity | MessageEntity.java | ~300 | @Table("messages"), dual field aliases |

---

## Summary

This documentation provides:

1. **Complete Entity Reference** - All 19 configuration entities with fields
2. **Relationship Diagrams** - Visual understanding of entity connections
3. **Quick Reference** - Fast lookup for common tasks and patterns
4. **Query Examples** - SQL patterns for loading configurations
5. **Configuration Patterns** - Common setups for different use cases
6. **Database Organization** - Schema structure and design

Use these three documents together:
- **CONFIGURATION_ENTITIES_MAP.md** for deep understanding
- **CONFIGURATION_ENTITY_DIAGRAMS.md** for visual relationships
- **CONFIGURATION_QUICK_REFERENCE.md** for quick lookups

For specific questions, search these documents or refer to the entity class files in the repository.

