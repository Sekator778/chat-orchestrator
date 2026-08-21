# Configuration Entity Diagrams

> Visual representations of configuration entity relationships

---

## 1. Complete Entity Relationship Diagram (ER Model)

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                                 TGSCAN SCHEMA (Python Scanner)                          │
├─────────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                         │
│  ┌──────────────────────────────────────────┐                                          │
│  │              Channel                     │                                          │
│  ├──────────────────────────────────────────┤                                          │
│  │ id (BIGINT) [PK]                        │                                          │
│  │ chatId                                   │                                          │
│  │ username, title, description             │                                          │
│  │ bot_instance_ids (TEXT[])               │                                          │
│  │ subscribers, scores (activity,          │                                          │
│  │   influence, relevance)                 │                                          │
│  │ join_status, last_seen                  │                                          │
│  │ is_channel, can_send_messages           │                                          │
│  └──────────────────────────────────────────┘                                          │
│                         ▲                                                              │
│                         │                                                              │
└─────────────────────────┼──────────────────────────────────────────────────────────────┘
                          │
                          │ FK Constraint
                          │ UNIQUE + CASCADE
                          │
┌─────────────────────────┼──────────────────────────────────────────────────────────────┐
│                         │                    BOT SCHEMA (Main Application)             │
│                         │                                                              │
│                         │   channel_chat_id                                            │
│                         └──────────────┬─────────────────────────────────────┐          │
│                                        │                                     │          │
│                       ┌────────────────▼──────────────────────────────┐      │          │
│                       │          ChatConfig [ROOT]                  │      │          │
│                       ├───────────────────────────────────────────────┤      │          │
│                       │ id (BIGSERIAL) [PK]                        │      │          │
│                       │ channel_chat_id (BIGINT) [FK, UNIQUE]      │      │          │
│                       │ primary_channel_id (BIGINT) [FK, SET NULL] │◄─────┘          │
│                       │ enabled, language, context_window_size     │                 │
│                       │ prompt_template, max_tokens, temperature   │                 │
│                       │ sync_enabled, auto_sync_enabled            │                 │
│                       │ multi_stage_enabled, processing_phase      │                 │
│                       │ last_phase1_at, last_phase2_at, last_...   │                 │
│                       └──────────────────────────────────────────────┘                 │
│                       ▲                                                               │
│                       │                                                               │
│        ┌──────────────┴──────────────┬──────────────┬──────────────┬─────────────┐   │
│        │                             │              │              │             │   │
│        │ 1:1 UNIQUE                  │ 1:1 UNIQUE   │ 1:1 UNIQUE   │ 1:1 UNIQUE  │   │
│        │                             │              │              │             │   │
│    ┌───▼───────────┐   ┌─────────────▼──┐  ┌───────▼──────┐  ┌────▼──────────┐ │   │
│    │ LlmParameters │   │ RateLimits     │  │SearchConfig  │  │ContextSettings│ │   │
│    ├───────────────┤   ├────────────────┤  ├──────────────┤  ├────────────────┤ │   │
│    │ id (BS)       │   │ id (BS)        │  │ id (BS)      │  │ id (BS)        │ │   │
│    │ chat_config_id│   │ chat_config_id │  │ chat_id      │  │ chat_config_id │ │   │
│    │ model_name    │   │ max_msgs_/min  │  │search_enabled│  │history_msg_cnt │ │   │
│    │ temperature   │   │ max_msgs_/hour │  │auto_search   │  │history_window  │ │   │
│    │ max_tokens    │   │ max_msgs_/day  │  │search_provider  │include_context  │ │   │
│    │ top_p         │   │ max_tokens/day │  │max_results   │  │compression_en  │ │   │
│    │ frequency_pen │   │ delay_seconds  │  │cache_duration   │max_context_toks│ │   │
│    │ presence_pen  │   │ cooldown_mins  │  │rate_limit_/hour │preserve_import │ │   │
│    │ system_prompt │   │ burst_limit    │  │relevance_thresh │                │ │   │
│    │ custom_instr  │   │ burst_window   │  │search_triggers   │                │ │   │
│    │ response_fmt  │   │ user_specific  │  │                │                │ │   │
│    └───────────────┘   └────────────────┘  └──────────────┘  └────────────────┘ │   │
│                                                                                   │   │
│        ┌───────────────────────────────────────────────────────────────────┐    │   │
│        │                           1:N CHILDREN                            │    │   │
│        └───────────────────────────────────────────────────────────────────┘    │   │
│                 │                      │                      │                 │   │
│    ┌────────────▼─────────┐ ┌──────────▼───────────┐ ┌───────▼──────────────┐  │   │
│    │ TriggerCondition     │ │ ResponseTemplate    │ │ TopicRestriction    │  │   │
│    ├──────────────────────┤ ├─────────────────────┤ ├────────────────────┤  │   │
│    │ id (BS) [PK]         │ │ id (BS) [PK]        │ │ id (BS) [PK]       │  │   │
│    │ chat_config_id (FK)  │ │ chat_config_id (FK) │ │ chat_config_id (FK)│  │   │
│    │ condition_name       │ │ template_name       │ │ restriction_name   │  │   │
│    │ trigger_type         │ │ template_content    │ │ restriction_type   │  │   │
│    │ keywords             │ │ response_style      │ │ keywords           │  │   │
│    │ mention_required     │ │ response_tone       │ │ categories         │  │   │
│    │ time_delay_seconds   │ │ max_response_len    │ │ action_type        │  │   │
│    │ probability_percent  │ │ is_default          │ │ custom_response    │  │   │
│    │ active_hours_start   │ │ priority            │ │ active             │  │   │
│    │ active_hours_end     │ │ active              │ │                    │  │   │
│    │ active_days_of_week  │ └─────────────────────┘ └────────────────────┘  │   │
│    │ minimum_gap_minutes  │                                                  │   │
│    │ priority             │                                                  │   │
│    │ active               │                                                  │   │
│    │ response_length      │                                                  │   │
│    └──────────────────────┘                                                  │   │
│                                                                               │   │
│    ┌─────────────────────────────────────────────────────────────────────┐   │   │
│    │                      Global Configuration Entities                  │   │   │
│    └─────────────────────────────────────────────────────────────────────┘   │   │
│         │                              │                           │         │   │
│    ┌────▼────────────────┐ ┌──────────▼────────┐ ┌────────────────▼──────┐   │   │
│    │ BotPersona          │ │ DigestPersona     │ │ User                 │   │   │
│    ├─────────────────────┤ ├───────────────────┤ ├─────────────────────┤   │   │
│    │ id (BS) [PK]        │ │ id (BS) [PK]      │ │ id (BS) [PK]        │   │   │
│    │ bot_id              │ │ name              │ │ telegram_user_id    │   │   │
│    │ language            │ │ description       │ │ first_name          │   │   │
│    │ name                │ │ bot_id            │ │ last_name           │   │   │
│    │ description         │ │ target_channel_id │ │ username            │   │   │
│    │ behavior            │ │ enabled           │ │ preferred_name      │   │   │
│    │ traits              │ │ persona_style     │ │ preferred_title     │   │   │
│    │ limitations         │ │ schedule_cron     │ │ communication_style │   │   │
│    │ metadata            │ │ schedule_timezone │ │ personality_traits  │   │   │
│    │ created_at          │ │ active_hours_*    │ │ relationship_context│   │   │
│    │ updated_at          │ │ lookback_hours    │ │ language_preference │   │   │
│    │                     │ │ max_messages      │ │ response_length     │   │   │
│    │ [Global scope]      │ │ language          │ │ ai_enabled          │   │   │
│    │ [Shared across      │ │ min_cluster_size  │ │ created_at          │   │   │
│    │  all chats]         │ │ min_importance_s  │ │ updated_at          │   │   │
│    │                     │ │ source_trust_thr  │ │ last_interaction_at │   │   │
│    │                     │ │ excluded_chan_ids │ │                     │   │   │
│    │                     │ │ topic_keywords    │ │ [Global scope]      │   │   │
│    │                     │ │ negative_keywords │ │ [User-specific data]│   │   │
│    │                     │ │ model_name        │ └─────────────────────┘   │   │
│    │                     │ │ temperature       │                           │   │
│    │                     │ │ max_tokens        │                           │   │
│    │                     │ │ last_run_at       │                           │   │
│    │                     │ │ last_published_.. │                           │   │
│    │                     │ │ total_digests_pub │                           │   │
│    │                     │ │ created_at        │                           │   │
│    │                     │ │ updated_at        │                           │   │
│    │                     │ └───────────────────┘                           │   │
│    │                     │              │                                   │   │
│    │                     │         1:N  │                                   │   │
│    │                     │              ▼                                   │   │
│    │                     │   ┌──────────────────────┐                       │   │
│    │                     │   │ DigestHistory        │                       │   │
│    │                     │   ├──────────────────────┤                       │   │
│    │                     │   │ id (BS) [PK]         │                       │   │
│    │                     │   │ persona_id (FK)      │                       │   │
│    │                     │   │ digest_id (UNIQUE)   │                       │   │
│    │                     │   │ content              │                       │   │
│    │                     │   │ messages_included    │                       │   │
│    │                     │   │ clusters_used        │                       │   │
│    │                     │   │ generation_time_ms   │                       │   │
│    │                     │   │ published_at         │                       │   │
│    │                     │   │ telegram_message_id  │                       │   │
│    │                     │   │ status               │                       │   │
│    │                     │   │ error_message        │                       │   │
│    │                     │   │ created_at           │                       │   │
│    │                     │   └──────────────────────┘                       │   │
│    └─────────────────────┘                                                 │   │
│                                                                             │   │
│    ┌─────────────────────────────────────────────────────────────────────┐ │   │
│    │                    Operational / Data Entities                       │ │   │
│    └─────────────────────────────────────────────────────────────────────┘ │   │
│         │                      │                        │                  │   │
│    ┌────▼──────────┐  ┌────────▼──────────┐  ┌─────────▼──────────────┐   │   │
│    │ SyncJob       │  │ PendingResponse   │  │ MessageEntity (Data)   │   │   │
│    ├───────────────┤  ├───────────────────┤  ├────────────────────────┤   │   │
│    │ id (BS)       │  │ id (BS)           │  │ id (BS) [PK]           │   │   │
│    │ channel_id    │  │ chat_config_id    │  │ chat_id, message_id    │   │   │
│    │ status        │  │ pending_message   │  │ content, caption       │   │   │
│    │ sync_depth    │  │ response_text     │  │ sender_id, sender_name │   │   │
│    │ sync_from_dt  │  │ scheduled_time    │  │ media_type, media_path │   │   │
│    │ sync_to_dt    │  │ status            │  │ message_type           │   │   │
│    │ msgs_processd │  │ attempt_count     │  │ importance, consensus  │   │   │
│    │ msgs_total    │  │ last_error        │  │ novelty, views, fwds   │   │   │
│    │ error_message │  │ created_at        │  │ cluster_id, primary_   │   │   │
│    │ created_at    │  │ updated_at        │  │ content_hash, keywords │   │   │
│    │ started_at    │  └───────────────────┘  │ sync_job_id (FK)       │   │   │
│    │ completed_at  │                         │ created_at             │   │   │
│    │ created_by_id │                         │                        │   │   │
│    │ bot_instance  │                         │ [DATA entity - not     │   │   │
│    │                                         │  pure configuration]   │   │   │
│    └────────────────────────────────────────┴────────────────────────────┘   │   │
│                                                                             │   │
│    ┌───────────────────────────────────────────────────────────────────────┬─┘   │
│    │ TdLibOperation, Event, Posted, ChatMessageStats, ProblematicChat    │     │
│    │ (System-managed operational entities - not shown in detail)          │     │
│    └───────────────────────────────────────────────────────────────────────┘     │
│                                                                                   │
└───────────────────────────────────────────────────────────────────────────────────┘

Legend:
  BS       = BIGSERIAL (auto-incrementing PK)
  FK       = Foreign Key
  UNIQUE   = Unique constraint
  CASCADE  = Delete children when parent deleted
  SET NULL = Clear FK when parent deleted
  1:1      = One-to-one relationship
  1:N      = One-to-many relationship
  N:1      = Many-to-one relationship
```

---

## 2. Simplified Configuration Hierarchy

```
                      ┌─────────────────┐
                      │ Telegram Channel│
                      │  (tgscan.schema)│
                      │   [Root]        │
                      └────────┬────────┘
                               │
                               │ FK Constraint
                               │
                    ┌──────────▼──────────┐
                    │   ChatConfig        │
                    │ [Configuration Hub] │
                    └────────┬────────────┘
                             │
          ┌──────────────────┼──────────────────┐
          │                  │                  │
          ▼                  ▼                  ▼
    ┌──────────┐    ┌──────────────┐   ┌──────────────┐
    │1:1 Child │    │1:N Children  │   │Dependencies │
    │          │    │              │   │              │
    │LlmParams │    │Triggers      │   │Channel (FK)  │
    │RateLimits│    │Templates     │   │BotPersona    │
    │Search    │    │Restrictions  │   │User (FK)     │
    │Context   │    │              │   │              │
    └──────────┘    └──────────────┘   └──────────────┘
```

---

## 3. Configuration Dependency Flow

```
  ┌────────────────────────────────────────────────────────────┐
  │                    Message Arrives                         │
  └────────────────┬───────────────────────────────────────────┘
                   │
                   ▼
  ┌────────────────────────────────────────────────────────────┐
  │              Extract Chat ID from Message                  │
  └────────────────┬───────────────────────────────────────────┘
                   │
                   ▼
  ┌────────────────────────────────────────────────────────────┐
  │        Load ChatConfig by channel_chat_id                  │
  │                                                            │
  │    SELECT * FROM bot.chat_configs                         │
  │    WHERE channel_chat_id = $chatId                        │
  └────────────────┬───────────────────────────────────────────┘
                   │
                   ▼
  ┌────────────────────────────────────────────────────────────┐
  │  Check: Is ChatConfig.enabled = true?                     │
  │         Is ChatConfig.sync_enabled?                       │
  │         What is processing_phase?                         │
  └────────────────┬───────────────────────────────────────────┘
                   │
              ┌────┴────┐
              │          │
         disabled    enabled
              │          │
              ▼          ▼
            STOP    Load dependencies
                         │
                    ┌────┴────┬────────┬──────────┐
                    │         │        │          │
                    ▼         ▼        ▼          ▼
            LlmParameters RateLimits Triggers Context
                    │         │        │          │
                    └────┬────┴────┬───┴──────┬───┘
                         │        │          │
                         ▼        ▼          ▼
            ┌─────────────────────────────────────┐
            │  ResponseDecisionService            │
            │  ├─ Check TriggerCondition          │
            │  ├─ Check RateLimits                │
            │  ├─ Check TopicRestriction          │
            │  └─ Determine if should respond     │
            └──────────────┬──────────────────────┘
                           │
                      Yes/No│
                      ┌─────┴────┐
                      │           │
              Don't Respond    Respond
                      │           │
                      ▼           ▼
                     STOP   ┌────────────────┐
                           │ Load Context   │
                           │ using Context  │
                           │ Settings       │
                           └────────┬───────┘
                                    │
                                    ▼
                           ┌──────────────────┐
                           │ Build Prompt:    │
                           │ - System Prompt  │
                           │ - Message History│
                           │ - User Context   │
                           └────────┬─────────┘
                                    │
                                    ▼
                           ┌──────────────────┐
                           │ Call LLM with    │
                           │ LlmParameters:   │
                           │ - model_name     │
                           │ - temperature    │
                           │ - max_tokens     │
                           │ - top_p          │
                           └────────┬─────────┘
                                    │
                                    ▼
                           ┌──────────────────┐
                           │ Format Response  │
                           │ using Template   │
                           │ & response_tone  │
                           └────────┬─────────┘
                                    │
                                    ▼
                           ┌──────────────────┐
                           │ Check RateLimits │
                           │ - Increment      │
                           │ - Check burst    │
                           │ - Check daily    │
                           └────────┬─────────┘
                                    │
                                    ▼
                           ┌──────────────────┐
                           │ Apply Delay from │
                           │ ResponseTemplate │
                           │ or Trigger       │
                           └────────┬─────────┘
                                    │
                                    ▼
                              Send Response

```

---

## 4. Configuration State Machine

```
                            ┌─────────────┐
                            │ New Channel │
                            │ Created     │
                            └──────┬──────┘
                                   │
                                   ▼
                        ┌──────────────────────┐
                        │ ChatConfig AUTO-     │
                        │ CREATED (by service) │
                        │ enabled = false      │
                        └──────┬───────────────┘
                               │
                    ┌──────────┴──────────┐
                    │                     │
                    │ Auto-create 1:1 children:
                    │ ├─ LlmParameters
                    │ ├─ RateLimits
                    │ ├─ SearchConfig
                    │ └─ ContextSettings
                    │
                    ▼
         ┌──────────────────────────┐
         │ Admin Configures Chat    │
         │ ├─ Add TriggerConditions │
         │ ├─ Add ResponseTemplates │
         │ ├─ Add TopicRestrictions │
         │ └─ Edit 1:1 children     │
         └──────┬───────────────────┘
                │
                ▼
    ┌───────────────────────────┐
    │ Set enabled = true        │
    └──────┬────────────────────┘
           │
           ▼
    ┌─────────────────────────────────┐
    │   ACTIVE STATE                  │
    │ Messages trigger responses      │
    │ based on configuration          │
    │                                 │
    │ Can:                            │
    │ - Update any field              │
    │ - Add/remove triggers/templates │
    │ - Changes effective immediately │
    └──────┬────────────────────────────┘
           │
     ┌─────┴─────┐
     │            │
 Disable     Delete
     │            │
     ▼            ▼
┌──────────┐  ┌──────────────────┐
│ Inactive │  │ Cascade Delete:  │
│ No new   │  │ - All children   │
│responses │  │ - All triggers   │
└──────────┘  │ - All templates  │
              │ - All triggers   │
              │ - All history    │
              └──────────────────┘
```

---

## 5. Data Flow: Message → Configuration → Response

```
                         TDLight Update
                             │
                             ▼
                    ┌────────────────────┐
                    │ TelegramListener   │
                    │ Service            │
                    └──────┬─────────────┘
                           │
                           ▼
                    ┌────────────────────┐
                    │ Message Persisted  │
                    │ to bot.messages    │
                    └──────┬─────────────┘
                           │
                           ▼
                    ┌────────────────────┐
                    │ Kafka Producer     │
                    │ Publish Event      │
                    └──────┬─────────────┘
                           │
                           ▼
                    ┌────────────────────┐
                    │ Kafka Consumer     │
                    │ Async Processing   │
                    └──────┬─────────────┘
                           │
            ┌──────────────┴──────────────┐
            │                             │
            ▼                             ▼
   ┌────────────────────────┐  ┌──────────────────────┐
   │ Load ChatConfig        │  │ CheckTriggerCondition│
   │ by channel_chat_id     │  │ (config.triggers[]) │
   │                        │  │ - keywords match?    │
   │ Use Fields:            │  │ - mention_required?  │
   │ - enabled              │  │ - active_hours?      │
   │ - sync_enabled         │  │ - probability?       │
   │ - processing_phase     │  └──────────────────────┘
   │ - language             │
   │ - context_window_size  │
   └────┬───────────────────┘
        │
        ▼
   ┌────────────────────────┐
   │ Load Child Configs     │
   │                        │
   │ LlmParameters:         │
   │ - model_name           │
   │ - temperature          │
   │ - max_tokens           │
   │ - top_p, penalties     │
   │                        │
   │ RateLimits:            │
   │ - max_msgs_per_hour    │
   │ - max_msgs_per_day     │
   │ - burst_limit          │
   │ - tokens_per_day       │
   │                        │
   │ SearchConfig:          │
   │ - search_enabled       │
   │ - search_provider      │
   │ - max_results          │
   │                        │
   │ ContextSettings:       │
   │ - history_msg_count    │
   │ - history_window_hours │
   │ - max_context_tokens   │
   └────┬───────────────────┘
        │
        ▼
   ┌────────────────────────┐
   │ Build Message Context  │
   │                        │
   │ Fetch N previous msgs: │
   │ N = context_window_sz  │
   │                        │
   │ Within time window:    │
   │ T = history_window_hrs │
   │                        │
   │ Format with speakers   │
   └────┬───────────────────┘
        │
        ▼
   ┌────────────────────────┐
   │ Build System Prompt    │
   │                        │
   │ Use:                   │
   │ - prompt_template      │
   │ - BotPersona (name,    │
   │   traits, behavior)    │
   │ - User profile (if     │
   │   applicable)          │
   └────┬───────────────────┘
        │
        ▼
   ┌────────────────────────┐
   │ Call LLM (DeepSeek)    │
   │                        │
   │ Request:               │
   │ - model: model_name    │
   │ - temperature: T       │
   │ - max_tokens: MT       │
   │ - top_p: P             │
   │ - messages: context    │
   │ - system: prompt       │
   │                        │
   │ Timeout: DEEPSEEK_TO   │
   └────┬───────────────────┘
        │
        ▼
   ┌────────────────────────┐
   │ Post-Process Response  │
   │                        │
   │ - Select template from │
   │   response_templates[] │
   │ - Apply tone (from     │
   │   template)            │
   │ - Truncate to          │
   │   max_response_length  │
   └────┬───────────────────┘
        │
        ▼
   ┌────────────────────────┐
   │ Check TopicRestriction │
   │                        │
   │ If restricted:         │
   │ - action_type =        │
   │   IGNORE|DECLINE|      │
   │   CUSTOM_RESPONSE      │
   └────┬───────────────────┘
        │
        ▼
   ┌────────────────────────┐
   │ Apply Humanization     │
   │                        │
   │ - PersonaService       │
   │ - ResponseTiming       │
   │ - AntiDetection        │
   └────┬───────────────────┘
        │
        ▼
   ┌────────────────────────┐
   │ Queue PendingResponse  │
   │ (if delay_seconds > 0) │
   │                        │
   │ Or send immediately    │
   └────┬───────────────────┘
        │
        ▼
   ┌────────────────────────┐
   │ Send to Telegram       │
   │                        │
   │ - typing indicator     │
   │ - message text         │
   │ - formatting           │
   └────────────────────────┘

```

---

## 6. Configuration Update Propagation

```
                     Admin Updates ChatConfig
                              │
                              ▼
                    ┌────────────────────────┐
                    │ ConfigurationService   │
                    │ .updateChatConfig()    │
                    └──────┬─────────────────┘
                           │
                    ┌──────▼──────────┐
                    │ Transaction:    │
                    │ UPDATE chat_... │
                    │ FLUSH to DB     │
                    └──────┬──────────┘
                           │
               ┌───────────┼───────────┐
               │           │           │
               │      Changed LlmParams
               │      OR RateLimits?
               │           │
               │      ┌────▼────┐
               │      │ Cascade? │
               │      └─────────┘
               │
    ┌──────────▼───────────────┐
    │ Next Incoming Message    │
    │                          │
    │ Load fresh config        │
    │ (cache invalidated)      │
    │ Apply new settings       │
    └──────────────────────────┘

Changes take effect on next message processing.

For immediate effect, reload ChatConfig cache:
  - Spring Cache invalidation
  - Redis clear (if used)
  - In-memory cache clear
```

---

## 7. Multi-Tenant Configuration Isolation

```
         ┌─────────────────────────────────────────────────────────┐
         │               Multiple Telegram Channels                │
         │           (Each with independent ChatConfig)            │
         └─────────────────────────────────────────────────────────┘
                    │                │                   │
        ┌───────────▼────┐ ┌────────▼────┐ ┌──────────▼──────┐
        │ Channel A      │ │ Channel B    │ │ Channel C       │
        │ (-123456789)   │ │ (-987654321) │ │ (555666777)     │
        └────────┬───────┘ └──────┬──────┘ └────────┬────────┘
                 │                │                │
                 │ FK             │ FK             │ FK
                 │                │                │
    ┌────────────▼─┐   ┌──────────▼───┐   ┌───────▼────────┐
    │ ChatConfig A │   │ChatConfig B   │   │ ChatConfig C   │
    ├──────────────┤   ├───────────────┤   ├────────────────┤
    │ id: 1        │   │ id: 2         │   │ id: 3          │
    │ enabled: ✓   │   │ enabled: ✗    │   │ enabled: ✓     │
    │ lang: 'uk'   │   │ lang: 'ru'    │   │ lang: 'en'     │
    │ temp: 0.7    │   │ temp: 0.9     │   │ temp: 0.5      │
    └────────┬─────┘   └──────┬────────┘   └────────┬──────┘
             │                │                    │
    ┌────────┼────────────────┼────────────────────┼──────┐
    │        │                │                    │      │
    │   ┌────▼────┐    ┌──────▼────┐    ┌────────▼───┐   │
    │   │Triggers │    │Triggers   │    │ Triggers   │   │
    │   │for A    │    │for B      │    │ for C      │   │
    │   │         │    │           │    │            │   │
    │   │ keyword:│    │keyword:   │    │ keyword:   │   │
    │   │ 'BTC'   │    │'Политика' │    │ 'AI'       │   │
    │   │keyword: │    │keyword:   │    │ keyword:   │   │
    │   │'ETH'    │    │'Спорт'    │    │ 'OpenAI'   │   │
    │   └────────┘    └───────────┘    └────────────┘   │
    │                                                     │
    │   Each ChatConfig has completely isolated        │
    │   configuration. Changing A doesn't affect B or C.│
    │   Messages from A always use Config A parameters. │
    └─────────────────────────────────────────────────────┘
```

---

## 8. Configuration Uniqueness Constraints

```
                        Uniqueness Rules
                        ═══════════════════════════════════
                        
    ┌─ ChatConfig ─────────────────────────────────┐
    │ UNIQUE (channel_chat_id)                     │
    │ → Only 1 config per channel                  │
    └──────────────────────────────────────────────┘
    
    ┌─ LlmParameters ───────────────────────────────┐
    │ UNIQUE (chat_config_id)                      │
    │ → Only 1 LLM config per ChatConfig (1:1)     │
    └───────────────────────────────────────────────┘
    
    ┌─ RateLimits ──────────────────────────────────┐
    │ UNIQUE (chat_config_id)                      │
    │ → Only 1 rate limit config per ChatConfig    │
    │   (1:1)                                      │
    └───────────────────────────────────────────────┘
    
    ┌─ ContextSettings ─────────────────────────────┐
    │ UNIQUE (chat_config_id)                      │
    │ → Only 1 context config per ChatConfig (1:1) │
    └───────────────────────────────────────────────┘
    
    ┌─ TriggerCondition ────────────────────────────┐
    │ NO UNIQUE constraint                         │
    │ → Multiple triggers per ChatConfig allowed   │
    │   (N:1 relationship)                         │
    │ Conflict resolution: priority field          │
    │ (lower number = higher priority)             │
    └───────────────────────────────────────────────┘
    
    ┌─ ResponseTemplate ────────────────────────────┐
    │ NO UNIQUE constraint                         │
    │ → Multiple templates per ChatConfig allowed  │
    │ Unique default: Only 1 is_default=true       │
    │ (enforced by application logic)              │
    │ Selection logic:                             │
    │ 1. Matching trigger → matching template      │
    │ 2. No match → default template (is_default)  │
    │ 3. No default → fallback template            │
    └───────────────────────────────────────────────┘
    
    ┌─ TopicRestriction ────────────────────────────┐
    │ NO UNIQUE constraint                         │
    │ → Multiple restrictions per ChatConfig       │
    │ Evaluation: All active restrictions checked  │
    │ Conflict resolution: first match wins        │
    └───────────────────────────────────────────────┘
    
    ┌─ DigestHistory ───────────────────────────────┐
    │ UNIQUE (digest_id)                           │
    │ → Each published digest has unique ID        │
    └───────────────────────────────────────────────┘
    
    ┌─ SearchConfig ────────────────────────────────┐
    │ IMPLICIT 1:1 via chat_id                     │
    │ → Only 1 search config per chat (enforced    │
    │   by application logic)                      │
    └───────────────────────────────────────────────┘
```

---

## 9. Cascade Delete Behavior

```
When ChatConfig is deleted:
═══════════════════════════════════════════════════════════

                    ChatConfig (deleted)
                           │
                           │ CASCADE
                           ▼
                    ┌──────────────┐
                    │ Auto-delete: │
                    │              │
                    │ ✗ LlmParams  │
                    │ ✗ RateLimits │
                    │ ✗ SearchCfg  │
                    │ ✗ ContextSet │
                    │ ✗ Triggers   │
                    │ ✗ Templates  │
                    │ ✗ Restrict.  │
                    └──────────────┘

Note: MessageEntity, SyncJob, Events, etc.
      are NOT cascade deleted
      (They reference Channel, not ChatConfig directly)

When Channel is deleted:
═══════════════════════════════════════════════════════════

                    Channel (deleted)
                           │
                           │ CASCADE (on chat_config_id FK)
                           ▼
                    ┌──────────────┐
                    │ Auto-delete: │
                    │              │
                    │ ✗ ChatConfig │
                    │   (and all   │
                    │   children)  │
                    └──────────────┘

Note: MessageEntity, SyncJob (which also reference
      Channel) may need separate cleanup
```

---

## 10. Configuration Timeline

```
Time ─────────────────────────────────────────────────────────────►

T0: Channel Discovered by Python Scanner
    └─► Channel record created in tgscan.channels
        └─► bot_instance_ids: null (or empty)
        └─► join_status: "NOT_ATTEMPTED"

T1: Bot Joins Channel (via TDLight)
    └─► Channel.join_status = "JOINED"
    └─► Channel.bot_instance_ids[] += "bot-1"
    └─► First MessageEntity arrives

T2: ChatConfigInitializationService Runs
    └─► Detects missing ChatConfig for channel
    └─► Creates ChatConfig with defaults:
        ├─ enabled = false
        ├─ sync_enabled = false
        ├─ LlmParameters created (deepseek-chat, 0.7 temp)
        ├─ RateLimits created (20/hr, 100/day)
        ├─ SearchConfig created (disabled)
        └─ ContextSettings created (10 msgs, 24 hrs)

T3: Strategy Selection (DefaultStrategy/MinimalReactionStrategy)
    └─► Analyzes channel context
    └─► Creates initial TriggerCondition
    └─► Creates ResponseTemplate (if applicable)

T4: Admin Enables & Configures
    └─► ChatConfig.enabled = true
    └─► Adds additional TriggerCondition
    └─► Adds ResponseTemplate variants
    └─► Adds TopicRestriction
    └─► Adjusts LlmParameters, RateLimits, SearchConfig

T5+: Continuous Operation
     └─► Messages processed using active configuration
     └─► Admin can update config at any time
     └─► Changes effective on next message
     └─► SyncJob can be triggered to backfill history

TN: Channel Archived or Disabled
    └─► ChatConfig.enabled = false (responses stop)
    └─► OR ChatConfig deleted (cascades all children)
    └─► MessageEntity records remain (for history)
    └─► SyncJob records remain (for audit trail)
```

---

## Summary

These diagrams show:
1. **Complete ER Model** - All entities and relationships
2. **Simplified Hierarchy** - Configuration structure
3. **Dependency Flow** - How configurations are used
4. **State Machine** - Configuration lifecycle
5. **Data Flow** - Message to response pipeline
6. **Update Propagation** - Config changes to effect
7. **Multi-Tenant Isolation** - Channel independence
8. **Uniqueness Constraints** - Data integrity rules
9. **Cascade Behavior** - Deletion side effects
10. **Configuration Timeline** - Temporal evolution

