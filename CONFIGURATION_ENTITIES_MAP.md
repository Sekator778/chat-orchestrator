# Configuration Entities and Relationships Map

> Last updated: 2026-01-18
> Complete entity relationship documentation for all configuration domains

## Table of Contents
1. [Entity Overview](#entity-overview)
2. [Core Configuration Hierarchy](#core-configuration-hierarchy)
3. [Entity Details with Fields](#entity-details-with-fields)
4. [Relationship Diagram](#relationship-diagram)
5. [Foreign Key References](#foreign-key-references)
6. [Database Schema Organization](#database-schema-organization)

---

## Entity Overview

### Configuration Domain Entities (19 total)

| Entity | Schema | Table | Purpose | Primary Key |
|--------|--------|-------|---------|-------------|
| **ChatConfig** | bot | chat_configs | Per-chat AI behavior configuration | id (BIGSERIAL) |
| **Channel** | tgscan | channels | Telegram chats/channels with scoring | id/chatId (BIGINT) |
| **TriggerCondition** | bot | trigger_conditions | Response trigger rules | id (BIGSERIAL) |
| **LlmParameters** | bot | llm_parameters | LLM model settings per chat | id (BIGSERIAL) |
| **RateLimits** | bot | rate_limits | Message/token quotas per chat | id (BIGSERIAL) |
| **ResponseTemplate** | bot | response_templates | Predefined response structures | id (BIGSERIAL) |
| **TopicRestriction** | bot | topic_restrictions | Forbidden topics/patterns per chat | id (BIGSERIAL) |
| **SearchConfig** | bot | search_configs | Web search settings per chat | id (BIGSERIAL) |
| **SyncJob** | bot | sync_jobs | Message sync job tracking | id (BIGSERIAL) |
| **User** | bot | users | User profiles with personalization | id (BIGSERIAL) |
| **BotPersona** | bot | bot_personas | Bot identity/behavior configuration | id (BIGSERIAL) |
| **DigestPersona** | bot | digest_personas | News digest generation personas | id (BIGSERIAL) |
| **DigestHistory** | bot | digest_history | Published digest tracking | id (BIGSERIAL) |
| **ContextSettings** | bot | context_settings | Message context configuration | id (BIGSERIAL) |
| **MessageEntity** | bot | messages | Telegram messages (data, not config) | id (BIGSERIAL) |
| **PostSubscription** | bot | post_subscriptions | Event publishing subscriptions | id (BIGSERIAL) |
| **Event** | bot | events | Detected significant events | id (BIGSERIAL) |
| **PendingResponse** | bot | pending_responses | Queued responses with timing | id (BIGSERIAL) |
| **TdLibOperation** | bot | tdlib_operations | TDLib operation coordination | id (BIGSERIAL) |

---

## Core Configuration Hierarchy

```
                         ┌─────────────┐
                         │   Channel   │
                         │  (tgscan)   │ ◄─────────┐
                         └──────┬──────┘           │
                                │                 │ Foreign Key
                                │ 1:1             │
                         ┌──────▼──────────────────┘
                         │
                    ┌────▼──────────┐
                    │  ChatConfig   │
                    │   (bot)       │ ◄───────────────────────┐
                    └────┬──────────┘                         │
                         │                              1:1   │
                    ┌────┴──────────────────┬─────────────────┼─────┬──────────────┬─────────┬─────────────┐
                    │                       │                 │     │              │         │             │
              1:N   │ 1:1                   │ 1:1         1:1  │     │              │         │             │
         ┌──────────▼─┐ ┌──────────────┐ ┌──▼────────┐ ┌─────▼──┐ │              │         │             │
         │  Triggers  │ │  LlmParams   │ │RateLimits │ │ Search │ │              │         │             │
         │ Conditions │ │              │ │ Config    │ │ Config │ │              │         │             │
         └────────────┘ └──────────────┘ └───────────┘ └────────┘ │              │         │             │
                                                                   │              │         │             │
                                        ┌──────────────────────────┘              │         │             │
                                        │                                        │         │             │
                                   1:N  │                                   1:N  │     1:N │         1:N  │
                                 ┌──────▼──────────┐ ┌──────────────┐ ┌─────────▼──┐ ┌─────▼──┐ ┌──────▼──┐
                                 │Response Tmpl    │ │Topic         │ │Context     │ │Pending │ │History  │
                                 │(optional)       │ │Restrictions  │ │Settings    │ │Responses│ │(Events) │
                                 └─────────────────┘ └──────────────┘ └────────────┘ └────────┘ └─────────┘
                    │
                    │ Primary Channel Link
                    │
         ┌──────────▼──────────┐
         │  BotPersona         │
         │  DigestPersona      │
         │  User Profile       │
         └─────────────────────┘
```

---

## Entity Details with Fields

### 1. ChatConfig (CORE CONFIGURATION)
**Table**: `bot.chat_configs` | **Type**: Configuration root | **Relationships**: 1:N parent

```java
// Primary Key
id: BIGSERIAL

// Configuration Reference
channel_chat_id: BIGINT FK → tgscan.channels(id) [UNIQUE, CASCADE]
primary_channel_id: BIGINT FK → tgscan.channels(id) [ON DELETE SET NULL]

// Basic Settings
enabled: BOOLEAN [DEFAULT: false]
language: VARCHAR(10) [DEFAULT: 'ru']
context_window_size: INTEGER [DEFAULT: 10]

// Prompt & LLM Configuration
prompt_template: TEXT
max_tokens: INTEGER
temperature: DOUBLE PRECISION

// Sync Configuration
sync_enabled: BOOLEAN [DEFAULT: false]
auto_sync_enabled: BOOLEAN [DEFAULT: false]
default_sync_depth_days: INTEGER
respond_to_forwarded_bot_messages: BOOLEAN [DEFAULT: false]
wait_for_human_replies_count: INTEGER [DEFAULT: -1]

// Multi-Stage Response Pipeline
multi_stage_enabled: BOOLEAN [DEFAULT: false]
processing_phase: ENUM(RAW, PHASE1, PHASE2, PHASE3) [DEFAULT: RAW]
last_phase1_at: TIMESTAMP WITH TIME ZONE
last_phase2_at: TIMESTAMP WITH TIME ZONE
last_phase3_at: TIMESTAMP WITH TIME ZONE
last_processing_error: TEXT

// Audit
primary_channel_checked_at: TIMESTAMP WITH TIME ZONE
created_at: TIMESTAMP WITH TIME ZONE [DEFAULT: NOW()]

// Child Dependencies (1:N)
├─ TriggerCondition (N)
├─ LlmParameters (1)
├─ RateLimits (1)
├─ ResponseTemplate (N)
├─ TopicRestriction (N)
├─ SearchConfig (1)
├─ ContextSettings (1)
└─ PendingResponse (N)
```

**Uniqueness Constraints**:
- `channel_chat_id` (UNIQUE) - One config per channel

---

### 2. Channel (MULTI-TENANT ROOT)
**Table**: `tgscan.channels` | **Type**: Root entity | **Relationships**: 1:N parent to ChatConfig

```java
// Primary Key (Telegram Chat ID)
id: BIGINT [PK] - Negative for groups/channels, positive for private chats
chatId: BIGINT [Alias]

// Identity
username: VARCHAR
title: VARCHAR
description: TEXT

// Bot Instance Association
bot_instance_id: TEXT[] [ARRAY] - Multiple bots can manage same channel
sample_message: TEXT

// Metrics & Scoring
subscribers: BIGINT
raw_keyword_score: DOUBLE PRECISION
channel_score: DOUBLE PRECISION [Primary score used by ranking]
score_activity: DOUBLE PRECISION
score_influence: DOUBLE PRECISION
score_relevance: DOUBLE PRECISION
weight: DOUBLE PRECISION [Weighting factor for multi-bot scenarios]

// State Tracking
join_status: VARCHAR [JOINED|FAILED|NOT_ATTEMPTED|...]
join_attempts: INTEGER
joined_at: TIMESTAMP WITH TIME ZONE
mute_status: VARCHAR [MUTED|UNMUTED|...]
last_seen: TIMESTAMP WITH TIME ZONE

// Processing Status
last_ingestion_attempt_at: TIMESTAMP WITH TIME ZONE
is_channel: BOOLEAN [True for channels, false for groups]
can_send_messages: BOOLEAN

// References (1:N Child to)
├─ ChatConfig (N config per channel)
├─ MessageEntity (N messages per channel)
└─ SyncJob (N sync operations per channel)
```

**Key Relationships**:
- `Channel.id` ← `ChatConfig.channel_chat_id` (FK, CASCADE)
- `Channel.id` ← `ChatConfig.primary_channel_id` (FK, SET NULL)

---

### 3. TriggerCondition (RESPONSE RULES)
**Table**: `bot.trigger_conditions` | **Type**: Configuration child | **Parent**: ChatConfig

```java
// Primary Key
id: BIGSERIAL

// Parent Reference
chat_config_id: BIGINT FK → chat_configs(id) [CASCADE]

// Trigger Definition
condition_name: VARCHAR(100)
trigger_type: ENUM(KEYWORD|REPLY|MENTION|FORWARD|TIME_BASED|PATTERN|...)
keywords: TEXT [Comma-separated or JSON array]
mention_required: BOOLEAN [DEFAULT: false]

// Response Behavior
time_delay_seconds: INTEGER [DEFAULT: 0] - Delay before responding
probability_percent: INTEGER [DEFAULT: 100] - Chance to respond (0-100)
response_length: ENUM(SHORT|MEDIUM|LONG) [DEFAULT: MEDIUM]

// Activity Windows
active_hours_start: TIME
active_hours_end: TIME
active_days_of_week: VARCHAR(50) [DEFAULT: '1,2,3,4,5,6,7'] - Monday=1, Sunday=7

// Anti-Spam
minimum_gap_minutes: INTEGER [DEFAULT: 0] - Min time between responses

// Control
priority: INTEGER [DEFAULT: 1] - Resolution order if multiple match
active: BOOLEAN [DEFAULT: true]

// Cardinality: N triggers per ChatConfig
// Indexes:
//   - idx_trigger_conditions_chat_config(chat_config_id, active)
```

**Example Triggers**:
1. Keyword match: `keywords='bitcoin,ethereum,price'`
2. Reply trigger: `trigger_type=REPLY, mention_required=true`
3. Time-based: `active_hours_start=09:00, active_hours_end=17:00`

---

### 4. LlmParameters (MODEL CONFIGURATION)
**Table**: `bot.llm_parameters` | **Type**: Configuration child | **Parent**: ChatConfig | **Cardinality**: 1:1

```java
// Primary Key
id: BIGSERIAL

// Parent Reference
chat_config_id: BIGINT FK → chat_configs(id) [UNIQUE, CASCADE]

// Model Selection
model_name: VARCHAR(100) [DEFAULT: 'deepseek-chat']

// Response Quality Parameters
temperature: DOUBLE PRECISION [DEFAULT: 0.7] - Creativity (0.0-1.0)
max_tokens: INTEGER [DEFAULT: 1000] - Response length limit
top_p: DOUBLE PRECISION [DEFAULT: 0.9] - Diversity sampling
frequency_penalty: DOUBLE PRECISION [DEFAULT: 0.0] - Reduce repetition
presence_penalty: DOUBLE PRECISION [DEFAULT: 0.0] - Encourage new topics

// Prompt Engineering
system_prompt: TEXT - Base instructions for LLM
custom_instructions: TEXT - Additional constraints

// Output Format
response_format: ENUM(TEXT|JSON|XML|MARKDOWN) [DEFAULT: TEXT]

// Cardinality: Max 1 per ChatConfig
// Unique: chat_config_id (UNIQUE)
```

**Parameter Presets**:
- Creative: temperature=0.9, top_p=0.95
- Balanced: temperature=0.7, top_p=0.9 (default)
- Precise: temperature=0.3, top_p=0.8

---

### 5. RateLimits (QUOTA MANAGEMENT)
**Table**: `bot.rate_limits` | **Type**: Configuration child | **Parent**: ChatConfig | **Cardinality**: 1:1

```java
// Primary Key
id: BIGSERIAL

// Parent Reference
chat_config_id: BIGINT FK → chat_configs(id) [UNIQUE, CASCADE]

// Message Rate Limits
max_messages_per_minute: INTEGER
max_messages_per_hour: INTEGER [DEFAULT: 20]
max_messages_per_day: INTEGER [DEFAULT: 100]
current_daily_messages: INTEGER [DEFAULT: 0] - Counter

// Token Budget
max_tokens_per_day: INTEGER [DEFAULT: 50000]

// Delay & Cooldown
pending_response_delay_seconds: INTEGER [DEFAULT: 0]
cooldown_after_limit_minutes: INTEGER [DEFAULT: 60] - Wait after hitting limit

// Burst Protection
burst_limit: INTEGER [DEFAULT: 3] - Max messages in burst
burst_window_seconds: INTEGER [DEFAULT: 60]

// User-Specific Limits
user_specific_limits: BOOLEAN [DEFAULT: false] - Apply per-user limits

// Cardinality: Max 1 per ChatConfig
// Unique: chat_config_id (UNIQUE)
```

**Enforcement Strategy**:
1. Per-minute: Hard limit (immediate reject)
2. Per-hour: Soft limit with warning
3. Per-day: Counter-based with daily reset
4. Burst: Sliding window protection
5. Cooldown: Enforced gap after limit breach

---

### 6. ResponseTemplate (RESPONSE PATTERNS)
**Table**: `bot.response_templates` | **Type**: Configuration child | **Parent**: ChatConfig | **Cardinality**: N:1

```java
// Primary Key
id: BIGSERIAL

// Parent Reference
chat_config_id: BIGINT FK → chat_configs(id) [CASCADE]

// Template Definition
template_name: VARCHAR(100)
template_content: TEXT - Contains placeholders for dynamic content

// Style Configuration
response_style: ENUM(ADAPTIVE|FORMAL|CASUAL|HUMOROUS|TECHNICAL)
response_tone: ENUM(NEUTRAL|POSITIVE|NEGATIVE|SARCASTIC|EMPATHETIC)
max_response_length: INTEGER [DEFAULT: 500]

// Template Control
is_default: BOOLEAN [DEFAULT: false] - Used when no trigger matches
priority: INTEGER [DEFAULT: 1] - Selection order
active: BOOLEAN [DEFAULT: true]

// Cardinality: N templates per ChatConfig
// Indexes:
//   - idx_response_templates_chat_config(chat_config_id, is_default)

// Placeholders (examples):
//   {{message}} - Original user message
//   {{sender}} - Sender name
//   {{time}} - Current time
//   {{context}} - Last N messages
//   {{keywords}} - Matched keywords
```

---

### 7. TopicRestriction (FORBIDDEN CONTENT)
**Table**: `bot.topic_restrictions` | **Type**: Configuration child | **Parent**: ChatConfig | **Cardinality**: N:1

```java
// Primary Key
id: BIGSERIAL

// Parent Reference
chat_config_id: BIGINT FK → chat_configs(id) [CASCADE]

// Restriction Definition
restriction_name: VARCHAR(100)
restriction_type: ENUM(KEYWORD|PATTERN|CATEGORY|REGEX)

// Blocked Content
keywords: TEXT - Comma-separated triggers
categories: TEXT - Content categories (violence, adult, etc.)

// Action on Match
action_type: ENUM(IGNORE|DECLINE|CUSTOM_RESPONSE|LOG_ONLY) [DEFAULT: IGNORE]
custom_response: TEXT - If action_type=CUSTOM_RESPONSE

// Control
active: BOOLEAN [DEFAULT: true]

// Cardinality: N restrictions per ChatConfig
// Indexes:
//   - idx_topic_restrictions_chat_config(chat_config_id, active)
```

**Restriction Examples**:
1. Keyword: `keywords='porn,nsfw'` → IGNORE
2. Pattern: `restriction_type=REGEX` → DECLINE
3. Category: `categories='violence,hate_speech'` → CUSTOM_RESPONSE

---

### 8. SearchConfig (WEB SEARCH INTEGRATION)
**Table**: `bot.search_configs` | **Type**: Configuration child | **Parent**: ChatConfig (implicit, via chat_id) | **Cardinality**: 1:1

```java
// Primary Key
id: BIGSERIAL

// Reference to Chat
chat_id: BIGINT - References ChatConfig via channel_chat_id

// Feature Toggle
search_enabled: BOOLEAN [DEFAULT: false]
auto_search_enabled: BOOLEAN [DEFAULT: false]

// Provider Configuration
search_provider: ENUM(GOOGLE|BING|DUCKDUCKGO) [DEFAULT: GOOGLE]
max_results: INTEGER [DEFAULT: 5]

// Caching Strategy
cache_duration_minutes: INTEGER [DEFAULT: 60]
rate_limit_per_hour: INTEGER [DEFAULT: 30]

// Output Configuration
include_attribution: BOOLEAN [DEFAULT: true] - Credit sources
relevance_threshold: DOUBLE PRECISION [DEFAULT: 0.6] - Min relevance (0-1)

// Trigger Configuration
search_triggers: TEXT - JSON array of patterns that trigger search

// Cardinality: Max 1 per ChatConfig
```

---

### 9. ContextSettings (MESSAGE CONTEXT)
**Table**: `bot.context_settings` | **Type**: Configuration child | **Parent**: ChatConfig | **Cardinality**: 1:1

```java
// Primary Key
id: BIGSERIAL

// Parent Reference
chat_config_id: BIGINT FK → chat_configs(id) [UNIQUE, CASCADE]

// History Window
history_message_count: INTEGER [DEFAULT: 10] - Number of previous messages
history_time_window_hours: INTEGER [DEFAULT: 24] - Time span to consider

// Content Inclusion
include_user_context: BOOLEAN [DEFAULT: true] - Include user profiles
include_media_descriptions: BOOLEAN [DEFAULT: true] - Describe media

// Context Optimization
context_compression_enabled: BOOLEAN [DEFAULT: false]
max_context_tokens: INTEGER [DEFAULT: 2000] - Token budget for context
preserve_important_messages: BOOLEAN [DEFAULT: true]

// Cardinality: Max 1 per ChatConfig
// Unique: chat_config_id (UNIQUE)
```

---

### 10. SyncJob (SYNC OPERATION TRACKING)
**Table**: `bot.sync_jobs` | **Type**: Operational entity | **Parent**: Channel

```java
// Primary Key
id: BIGSERIAL

// Reference
channel_id: BIGINT FK → tgscan.channels(id)

// Job Configuration
status: ENUM(PENDING|IN_PROGRESS|COMPLETED|FAILED) [DEFAULT: PENDING]
sync_depth_days: INTEGER - How far back to sync
sync_from_date: TIMESTAMP WITH TIME ZONE
sync_to_date: TIMESTAMP WITH TIME ZONE

// Progress Tracking
messages_processed: BIGINT [DEFAULT: 0L]
messages_total: BIGINT
error_message: TEXT

// Timing
created_at: TIMESTAMP WITH TIME ZONE [DEFAULT: NOW()]
started_at: TIMESTAMP WITH TIME ZONE
completed_at: TIMESTAMP WITH TIME ZONE

// Audit
created_by_user_id: BIGINT
bot_instance_id: VARCHAR

// Helper Method:
getCompletionPercentage(): Double
markAsStarted(): void
markAsCompleted(): void
markAsFailed(String): void
incrementProcessedMessages(): void
```

---

### 11. User (USER PERSONALIZATION)
**Table**: `bot.users` | **Type**: Configuration entity

```java
// Primary Key
id: BIGSERIAL

// Telegram Identity
telegram_user_id: BIGINT [UNIQUE]
first_name: VARCHAR
last_name: VARCHAR
username: VARCHAR

// Personalization
preferred_name: VARCHAR - Nickname to use
preferred_title: VARCHAR - Title/prefix

// Communication Profile
communication_style: ENUM(CASUAL|FORMAL|PROFESSIONAL|FRIENDLY|TECHNICAL)
personality_traits: TEXT - JSON or comma-separated
relationship_context: TEXT - How to relate (friend, colleague, etc.)

// Preferences
language_preference: VARCHAR [DEFAULT: 'uk']
response_length: ENUM(SHORT|MEDIUM|LONG) [DEFAULT: MEDIUM]
ai_enabled: BOOLEAN [DEFAULT: true]

// Timestamps
created_at: TIMESTAMP WITH TIME ZONE [DEFAULT: NOW()]
updated_at: TIMESTAMP WITH TIME ZONE
last_interaction_at: TIMESTAMP WITH TIME ZONE

// Helper Methods:
getDisplayName(): String
getFullDisplayName(): String
```

---

### 12. BotPersona (BOT IDENTITY CONFIGURATION)
**Table**: `bot.bot_personas` | **Type**: Configuration entity

```java
// Primary Key
id: BIGSERIAL

// Identity
bot_id: VARCHAR [FK] - Telegram user ID as string
language: VARCHAR - Language for this persona variant

// Persona Definition
name: VARCHAR - Persona name
description: TEXT
behavior: TEXT - JSON/newline-separated behaviors
traits: TEXT - JSON/comma-separated traits
limitations: TEXT - JSON/comma-separated constraints

// Metadata
metadata: TEXT - JSONB (stored as string)

// Timestamps
created_at: TIMESTAMP WITH TIME ZONE [DEFAULT: NOW()]
updated_at: TIMESTAMP WITH TIME ZONE

// Example Persona:
// name="TechNews Bot"
// traits="knowledgeable,helpful,concise"
// behavior="Explain technical concepts simply, provide links, acknowledge limitations"
```

---

### 13. DigestPersona (DIGEST GENERATION CONFIGURATION)
**Table**: `bot.digest_personas` | **Type**: Configuration entity | **Relationships**: 1:N to DigestHistory

```java
// Primary Key
id: BIGSERIAL

// Identity & Target
name: VARCHAR(100)
description: TEXT
bot_id: BIGINT - Telegram user ID for the bot
target_channel_id: BIGINT - Where digests are published
enabled: BOOLEAN [DEFAULT: false]

// Persona Style
persona_style: ENUM(PROFESSIONAL|IRONIC|BREAKING_NEWS|TECHNICAL|CUSTOM) [DEFAULT: PROFESSIONAL]
custom_system_prompt: TEXT - For CUSTOM style

// Schedule Configuration
schedule_cron: VARCHAR(100) - Cron expression for generation
schedule_timezone: VARCHAR(50) [DEFAULT: 'UTC']
active_hours_start: TIME
active_hours_end: TIME

// Content Configuration
lookback_hours: INTEGER [DEFAULT: 24]
max_messages: INTEGER [DEFAULT: 10]
language: VARCHAR(8) [DEFAULT: 'en']

// Message Filtering
min_cluster_size: INTEGER [DEFAULT: 2]
min_importance_score: DOUBLE PRECISION [DEFAULT: 0.0]
source_trust_threshold: DOUBLE PRECISION [DEFAULT: 0.0]
excluded_channel_ids: BIGINT[]
topic_keywords: TEXT[]
negative_keywords: TEXT[]

// LLM Settings
model_name: VARCHAR(100)
temperature: DOUBLE PRECISION [DEFAULT: 0.7]
max_tokens: INTEGER [DEFAULT: 1000]

// Tracking
last_run_at: TIMESTAMP WITH TIME ZONE
last_published_digest_id: VARCHAR(64)
total_digests_published: INTEGER [DEFAULT: 0]

// Audit
created_at: TIMESTAMP WITH TIME ZONE [DEFAULT: NOW()]
updated_at: TIMESTAMP WITH TIME ZONE [DEFAULT: NOW()]

// Helper Method:
incrementPublishedCount(): void

// Child Dependency:
├─ DigestHistory (N)
```

---

### 14. DigestHistory (DIGEST TRACKING)
**Table**: `bot.digest_history` | **Type**: Audit/History entity | **Parent**: DigestPersona

```java
// Primary Key
id: BIGSERIAL

// Parent Reference
persona_id: BIGINT FK → digest_personas(id) [CASCADE]

// Digest Identification
digest_id: VARCHAR(64) [UNIQUE] - UUID for this digest
content: TEXT - Generated digest content

// Metadata
messages_included: INTEGER
clusters_used: INTEGER
generation_time_ms: BIGINT

// Publishing Result
published_at: TIMESTAMP WITH TIME ZONE
telegram_message_id: BIGINT - Message ID after publishing

// Status & Error Handling
status: ENUM(GENERATED|PUBLISHED|FAILED) [DEFAULT: GENERATED]
error_message: TEXT

// Audit
created_at: TIMESTAMP WITH TIME ZONE [DEFAULT: NOW()]

// Indexes:
//   - idx_digest_history_persona_id(persona_id)
//   - idx_digest_history_created_at(created_at DESC)
//   - idx_digest_history_status(status)
```

---

### 15. MessageEntity (MESSAGE DATA)
**Table**: `bot.messages` | **Type**: Data entity (not pure configuration)

```java
// Primary Key
id: BIGSERIAL

// Message Identification (Dual aliases for sync compatibility)
chat_id: BIGINT / channel_id: BIGINT
message_id: BIGINT / telegram_message_id: BIGINT
messageId: BIGINT (primary field)

// Content
content: TEXT
caption: TEXT
message_type: ENUM(USER_MESSAGE|SERVICE_MESSAGE|BOT_MESSAGE|FORWARD|...)

// Media
media_type: MediaKind / media_kind: MediaKind
media_file_path: TEXT / media_path: TEXT

// Sender Information
sender_id: BIGINT / user_id: BIGINT
sender_name: VARCHAR
sender_username: VARCHAR / username: VARCHAR
sender_first_name: VARCHAR
sender_last_name: VARCHAR
is_outgoing: BOOLEAN

// Threading
forward_from_chat_id: BIGINT
reply_to_message_id: BIGINT
reply_to_chat_id: BIGINT

// Timestamps
date: TIMESTAMP WITH TIME ZONE
edit_date: TIMESTAMP WITH TIME ZONE
created_at: TIMESTAMP WITH TIME ZONE [DEFAULT: NOW()]

// Scoring & Analytics
importance: DOUBLE PRECISION
consensus: DOUBLE PRECISION
novelty: DOUBLE PRECISION
views: BIGINT
forwards: BIGINT

// Content Analysis
content_hash: VARCHAR
matched_keywords: TEXT[]
cluster_id: VARCHAR
is_primary_in_cluster: BOOLEAN

// Sync Information
imported_from_sync: BOOLEAN [DEFAULT: false]
sync_job_id: BIGINT FK → sync_jobs(id)
raw_message_dump: TEXT

// Relationships (implied):
├─ FK to tgscan.channels(id) via chat_id
└─ FK to bot.sync_jobs(id) via sync_job_id (if imported)
```

---

## Relationship Diagram

### Configuration Tree (Parent-Child)

```
┌──────────────────────────────────────────────────────────────────┐
│                        Channel (tgscan)                          │
│                                                                  │
│  - id (BIGINT) - Telegram Chat ID                              │
│  - title, username, description                                │
│  - scores (activity, influence, relevance)                     │
│  - bot_instance_ids[] - Multiple bot support                   │
│  - join_status, last_seen, can_send_messages                   │
└─────────────────────┬─────────────────────────────────────────────┘
                      │
                      │ FK: channel_chat_id
                      │ UNIQUE, CASCADE
                      ▼
┌──────────────────────────────────────────────────────────────────┐
│                    ChatConfig (bot)                              │
│                 [CONFIGURATION ROOT]                             │
│                                                                  │
│  - id (BIGSERIAL)                                              │
│  - channel_chat_id FK → Channel(id)                            │
│  - primary_channel_id FK → Channel(id) [SET NULL]              │
│  - enabled, language, context_window_size                      │
│  - sync_enabled, auto_sync_enabled, processing_phase           │
│  - multi_stage_enabled, temperature, max_tokens                │
└─────────────┬────────────┬──────────────┬──────────┬────────────┘
              │            │              │          │
    ┌─────────▼┐ ┌────────▼──┐ ┌─────────▼┐ ┌──────▼─┐
    │1:1      │ │1:1        │ │1:1       │ │1:1     │
    │         │ │           │ │          │ │        │
    │LlmParam │ │RateLimits │ │Context   │ │Search  │
    │eters    │ │           │ │Settings  │ │Config  │
    └────────┘ └───────────┘ └──────────┘ └────────┘
    
    ┌─────────────┐ ┌────────────┐ ┌──────────────┐
    │1:N         │ │1:N        │ │1:N          │
    │Triggers    │ │Response   │ │Topic        │
    │Conditions  │ │Templates  │ │Restrictions │
    └─────────────┘ └───────────┘ └─────────────┘
```

### Cross-Domain References

```
ChatConfig
    ├─ Channel (FK: channel_chat_id)
    ├─ Channel (FK: primary_channel_id)
    ├─ TriggerCondition (1:N)
    ├─ LlmParameters (1:1)
    ├─ RateLimits (1:1)
    ├─ ResponseTemplate (1:N)
    ├─ TopicRestriction (1:N)
    ├─ SearchConfig (1:1 via chat_id)
    ├─ ContextSettings (1:1)
    └─ PendingResponse (1:N)

Channel
    ├─ ChatConfig (1:N parent)
    ├─ MessageEntity (1:N data)
    └─ SyncJob (1:N operations)

SyncJob
    ├─ Channel (FK: channel_id)
    └─ MessageEntity (1:N via sync_job_id)

User
    ├─ MessageEntity (1:N via sender_id)
    └─ (Standalone personalization entity)

DigestPersona
    └─ DigestHistory (1:N)

MessageEntity
    ├─ Channel (FK: chat_id)
    ├─ SyncJob (FK: sync_job_id if imported)
    └─ (Data entity, not pure configuration)
```

---

## Foreign Key References

### Primary Foreign Keys (Configuration Dependencies)

| Source Table | Column | Target Table | Target Column | Cardinality | On Delete | Unique |
|--------------|--------|--------------|---------------|-------------|-----------|--------|
| chat_configs | channel_chat_id | tgscan.channels | id | 1:N | CASCADE | YES |
| chat_configs | primary_channel_id | tgscan.channels | id | 0..1:N | SET NULL | NO |
| trigger_conditions | chat_config_id | chat_configs | id | N:1 | CASCADE | NO |
| llm_parameters | chat_config_id | chat_configs | id | 1:1 | CASCADE | YES |
| rate_limits | chat_config_id | chat_configs | id | 1:1 | CASCADE | YES |
| response_templates | chat_config_id | chat_configs | id | N:1 | CASCADE | NO |
| topic_restrictions | chat_config_id | chat_configs | id | N:1 | CASCADE | NO |
| context_settings | chat_config_id | chat_configs | id | 1:1 | CASCADE | YES |
| sync_jobs | channel_id | tgscan.channels | id | N:1 | - | NO |
| messages | sync_job_id | sync_jobs | id | N:1 | - | NO |
| digest_history | persona_id | digest_personas | id | N:1 | CASCADE | NO |

### Implicit/Logical Foreign Keys

| Source | Target | Via Column | Type |
|--------|--------|-----------|------|
| search_configs | chat_configs | chat_id | Logical (no FK constraint) |
| messages | channels | chat_id | Logical (no FK constraint) |
| pending_responses | chat_configs | chat_config_id | Logical |

---

## Database Schema Organization

### Schema: `bot` (Main Application Configuration)

**Configuration Core** (Primary management):
- chat_configs - Root configuration entity
- llm_parameters - LLM model settings
- rate_limits - Rate limiting rules
- trigger_conditions - Response triggers
- response_templates - Response patterns
- topic_restrictions - Forbidden content rules
- context_settings - Message context configuration
- search_configs - Web search settings

**Entity Management**:
- users - User profiles for personalization
- bot_personas - Bot identity configurations

**Digest System**:
- digest_personas - Digest generation personas
- digest_history - Published digest history

**Operational Data**:
- messages - Telegram message storage
- sync_jobs - Sync operation tracking
- sync_run_logs - Detailed sync logs
- pending_responses - Response queue
- events - Detected events
- posted - Event publication tracking
- post_subscriptions - Event subscriptions

**System Management**:
- tdlib_operations - TDLib operation coordination
- chat_message_stats - Message statistics
- problematic_chats - Chat issues tracking

---

### Schema: `tgscan` (Python Scanner Data)

**Channel Management**:
- channels - Discovered channels with scoring
- messages - Message copies for ranking (duplicated from bot.messages)
- channel_candidates - Potential channels from discovery

**Storage Functions**:
- fn_refresh_all() - Recalculate rankings
- fn_recalc_importance() - Update importance scores

---

## Configuration Dependency Matrix

```
                Scope: Global
                (Single Definition)
                    │
        ┌───────────▼──────────────┐
        │   BotPersona (bot)       │
        │   DigestPersona (bot)    │
        │   User (bot)             │
        │   (Shared across chats)  │
        └──────────────────────────┘
                    │
                    │ Referenced by
                    ▼
        ┌──────────────────────────┐
        │   Scope: Per-Chat        │
        │   ChatConfig (bot)       │
        │   [Configuration Hub]    │
        └──────────────────────────┘
                    │
        ┌───────────┴────────────────────────┐
        │                                    │
        ▼                                    ▼
┌───────────────────┐            ┌──────────────────────┐
│ Configuration     │            │ Operational Data     │
│ (Static)          │            │ (Dynamic)            │
│                   │            │                      │
│ LlmParameters     │            │ PendingResponse      │
│ RateLimits        │            │ SyncJob              │
│ TriggerCondition  │            │ MessageEntity        │
│ ResponseTemplate  │            │ Event                │
│ TopicRestriction  │            │ Posted               │
│ SearchConfig      │            │ DigestHistory        │
│ ContextSettings   │            │                      │
└───────────────────┘            └──────────────────────┘
```

---

## Field Aliasing Strategy (Sync System Compatibility)

**MessageEntity** maintains dual field aliases for backward compatibility:

```java
// Primary Fields (Original)
messageId / chatId / senderId / senderUsername / mediaType / mediaFilePath

// Alias Fields (Sync System Interface)
telegramMessageId / channelId / userId / username / mediaKind / mediaPath

// Getters Prefer Aliases:
getTelegramMessageId() returns telegramMessageId ?: messageId
getUserId() returns userId ?: senderId
// etc.

// Setters Maintain Sync:
setUserId(id) sets both userId AND senderId
setTelegramMessageId(id) sets both telegramMessageId AND messageId
// Ensures both fields stay consistent
```

---

## Configuration Initialization Pattern

### Default Configuration Chain

When a message arrives in a new chat:

1. **Channel Exists** (tgscan.channels)
   - Created by Python scanner or TDLight discovery
   - Contains channel metadata, scores, bot_instance_ids

2. **ChatConfig Auto-Creation** (ChatConfigInitializationService)
   - Detects missing ChatConfig for channel_chat_id
   - Creates ChatConfig with defaults:
     - enabled=false
     - sync_enabled=false
     - language='ru'
     - context_window_size=10

3. **Child Configuration Creation** (Cascade)
   - LlmParameters (defaults: deepseek-chat, temp=0.7, tokens=1000)
   - RateLimits (defaults: 20/hr, 100/day)
   - SearchConfig (defaults: disabled)
   - ContextSettings (defaults: 10 messages, 24 hours)

4. **Strategy Selection** (DefaultStrategy/MinimalReactionStrategy)
   - Analyzes channel context
   - Creates initial TriggerCondition
   - Creates ResponseTemplate (if needed)

5. **Ready for Operation**
   - ChatConfig.enabled = true
   - First message can trigger response

---

## Configuration Lifecycle

### Create Phase
```
User Creates ChatConfig
    ↓
System creates 1:1 children (LlmParameters, RateLimits, SearchConfig, ContextSettings)
    ↓
Admin adds N:1 children (TriggerCondition, ResponseTemplate, TopicRestriction)
    ↓
ChatConfig.enabled = true
```

### Active Phase
```
MessageEntity arrives
    ↓
ResponseDecisionService queries TriggerCondition
    ↓
Selects LlmParameters, RateLimits, ResponseTemplate
    ↓
Generates response per configuration
    ↓
Respects rate limits and topic restrictions
```

### Update Phase
```
Admin edits configuration
    ↓
Changes applied immediately (or on next message)
    ↓
SyncJob triggered for historical sync (if sync_enabled=true)
    ↓
New settings apply to future messages
```

### Cleanup Phase
```
Channel removed or disabled
    ↓
ChatConfig.enabled = false
    ↓
All child records cascade delete (CASCADE constraint)
    ↓
SyncJob records remain for audit trail
```

---

## Configuration Query Patterns

### Load Full Configuration for Chat

```sql
SELECT 
    cc.*,
    lp.*,
    rl.*,
    sc.*,
    cs.*
FROM bot.chat_configs cc
LEFT JOIN bot.llm_parameters lp ON cc.id = lp.chat_config_id
LEFT JOIN bot.rate_limits rl ON cc.id = rl.chat_config_id
LEFT JOIN bot.search_configs sc ON sc.chat_id = cc.channel_chat_id
LEFT JOIN bot.context_settings cs ON cc.id = cs.chat_config_id
WHERE cc.channel_chat_id = $1
```

### Get All Triggers for Chat

```sql
SELECT * 
FROM bot.trigger_conditions 
WHERE chat_config_id = (
    SELECT id FROM bot.chat_configs WHERE channel_chat_id = $1
)
AND active = true
ORDER BY priority DESC
```

### Get Active Digest Personas

```sql
SELECT dp.*, dh.last_published_digest_id, dh.published_at
FROM bot.digest_personas dp
LEFT JOIN bot.digest_history dh ON dp.id = dh.persona_id
WHERE dp.enabled = true
ORDER BY dp.last_run_at DESC NULLS LAST
```

### Check Sync Configuration

```sql
SELECT 
    cc.channel_chat_id,
    cc.sync_enabled,
    cc.default_sync_depth_days,
    sj.status,
    sj.messages_processed,
    sj.messages_total
FROM bot.chat_configs cc
LEFT JOIN bot.sync_jobs sj ON sj.channel_id = cc.channel_chat_id
WHERE cc.sync_enabled = true
ORDER BY sj.created_at DESC NULLS LAST
```

---

## Configuration Constraints and Validations

### Business Rules

1. **ChatConfig Uniqueness**
   - Only one ChatConfig per Channel (unique on channel_chat_id)
   - Enforces 1:1 relationship with Channel

2. **1:1 Child Uniqueness**
   - LlmParameters: max 1 per ChatConfig (unique on chat_config_id)
   - RateLimits: max 1 per ChatConfig (unique on chat_config_id)
   - ContextSettings: max 1 per ChatConfig (unique on chat_config_id)
   - SearchConfig: max 1 per chat (implicit, via chat_id)

3. **Trigger Priority**
   - Multiple TriggerCondition per ChatConfig allowed
   - Resolved by priority field (lower number = higher priority)
   - All active triggers evaluated in order

4. **Rate Limit Cascade**
   - If daily limit breached: immediate cooldown
   - Cooldown period: cooldown_after_limit_minutes
   - Next day: currentDailyMessages reset to 0

5. **Template Selection**
   - Only one is_default=true per ChatConfig
   - Used as fallback when no trigger matches

6. **Digest Scheduling**
   - schedule_cron: parsed with Spring CronExpression
   - schedule_timezone: validated timezone string
   - active_hours: optional window within cron-triggered time

---

## Configuration Export/Import

### Supported Entities

**Exportable (CRUD via API)**:
- ChatConfig (all fields)
- LlmParameters
- RateLimits
- TriggerCondition (all N)
- ResponseTemplate (all N)
- TopicRestriction (all N)
- SearchConfig
- ContextSettings
- DigestPersona

**Read-Only (Reference)**:
- Channel (data only, not created via config API)
- User (data only)
- BotPersona (global, not per-chat)

**System-Managed (Not Exported)**:
- MessageEntity (data)
- SyncJob (operational)
- DigestHistory (audit)
- PendingResponse (queue)
- TdLibOperation (coordination)

### Export Format

```json
{
  "chat_config": {
    "channel_chat_id": 12345,
    "enabled": true,
    "language": "uk",
    "llm_parameters": { ... },
    "rate_limits": { ... },
    "trigger_conditions": [ ... ],
    "response_templates": [ ... ],
    "topic_restrictions": [ ... ],
    "search_config": { ... },
    "context_settings": { ... }
  }
}
```

---

## Summary: Entity Relationship Map

| Core | Type | Count | Purpose |
|------|------|-------|---------|
| **Configuration Entities** | Config Management | 8 | ChatConfig + 7 children (LlmParams, RateLimits, Triggers, Templates, Restrictions, Search, Context) |
| **Persona Entities** | Identity/Behavior | 3 | BotPersona, DigestPersona, User |
| **Data Entities** | Message Storage | 3 | MessageEntity, Channel, DigestHistory |
| **Operational Entities** | System Management | 5 | SyncJob, PendingResponse, TdLibOperation, Events, Posts |
| **Total Configuration-Related** | All | 19 | See overview table |

**Key Insight**: ChatConfig is the central hub. All per-chat configuration flows through it, with 1:1 unique children and N:1 collection children. Channel is the root identity, User/BotPersona are global, DigestPersona is standalone but creates history records.

