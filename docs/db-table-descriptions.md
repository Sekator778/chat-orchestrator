# Database Table Descriptions

Notes:
- Service data lives in the `bot` schema (Liquibase omits the schema in early files but later indexes refer to `bot`). Scanner data lives in `tgscan`. Tables without a schema below use the default/public schema.
- “Values” lists defaults or typical enums inferred from the changelog.
- Status:
  - ✅ — описано и выведено на фронтовый справочник
  - ⏳ — еще предстоит описать на фронте

## ✅ bot.users
| Column | Purpose | Values |
| --- | --- | --- |
| id | Internal surrogate key | BIGSERIAL PK |
| telegram_user_id | Telegram user identifier | Unique per user |
| first_name / last_name / username | Telegram profile fields | Text, optional |
| preferred_name / preferred_title | Custom display preferences | Text |
| communication_style | Desired tone/style | Default `CASUAL` |
| personality_traits | Free-form persona traits | Text |
| relationship_context | How the bot should relate to the user | Text |
| language_preference | Preferred language code | Default `uk` |
| response_length | Target response size | Default `MEDIUM` |
| ai_enabled | Whether AI replies are allowed | Default true |
| created_at / updated_at / last_interaction_at | Timestamps for lifecycle | created_at defaults now |

## ✅ bot.chat_configs
| Column | Purpose | Values |
| --- | --- | --- |
| id | Config record PK | BIGSERIAL |
| channel_chat_id | Telegram chat/channel ID | Unique per bot_instance_id |
| prompt_template | System prompt for replies | Text |
| enabled | Global toggle for auto replies | Default false |
| max_tokens / temperature | LLM generation bounds | Optional |
| max_daily_messages / current_daily_messages | Daily quota and current count | current defaults 0 |
| language | Reply language code | Default `ru` |
| context_window_size | Messages pulled into context | Default 10 |
| primary_channel_id | Preferred channel in tgscan | BIGINT |
| default_sync_depth_days | Default history depth to sync | INT |
| auto_sync_enabled | Whether auto sync is on | Default false |
| sync_enabled | Whether syncing is permitted | Default false |
| respond_to_forwarded_bot_messages | Handle forwarded bot msgs | Default false |
| created_at | Creation time | Default now |
| wait_for_human_replies_count | Required human replies before sending bot response (-1 = send immediately) | Default -1 |
| processing_phase | Channel processing state | RAW, INGESTED, LINKED, CONFIGURED, ERROR (default RAW) |
| last_phase1_at / last_phase2_at / last_phase3_at | Phase completion timestamps | TIMESTAMPTZ |
| last_processing_error | Last pipeline error message | Text |
| bot_instance_id | Multi-bot isolation key | Default `default-bot` |

## ✅ bot.context_settings
| Column | Purpose | Values |
| --- | --- | --- |
| id | PK | BIGSERIAL |
| chat_config_id | Owning chat config | UNIQUE FK to chat_configs |
| history_message_count | Max messages pulled for context | Default 10 |
| history_time_window_hours | Context lookback window | Default 24 |
| include_user_context | Include user profile in prompt | Default true |
| include_media_descriptions | Include media descriptions | Default true |
| context_compression_enabled | Allow context compression | Default false |
| max_context_tokens | Cap for compressed context | Default 2000 |
| preserve_important_messages | Keep high-importance messages | Default true |

## ✅ bot.llm_parameters
| Column | Purpose | Values |
| --- | --- | --- |
| id | PK | BIGSERIAL |
| chat_config_id | Owning chat config | UNIQUE FK |
| model_name | LLM identifier | Default `deepseek-chat` |
| temperature / top_p | Sampling settings | Defaults 0.7 / 0.9 |
| max_tokens | Generation cap | Default 1000 |
| frequency_penalty / presence_penalty | OpenAI-style penalties | Default 0.0 |
| system_prompt | System message override | Text |
| custom_instructions | Additional guardrails | Text |
| response_format | Output mode | Default `TEXT` |

## ✅ bot.rate_limits
| Column | Purpose | Values |
| --- | --- | --- |
| id | PK | BIGSERIAL |
| chat_config_id | Owning chat config | UNIQUE FK |
| max_messages_per_minute / hour / day | Rate caps | Defaults null / 20 / 100 |
| max_tokens_per_day | Token budget | Default 50,000 |
| cooldown_after_limit_minutes | Cooldown when limits hit | Default 60 |
| burst_limit / burst_window_seconds | Short-term burst control | Defaults 3 / 60 |
| user_specific_limits | Whether per-user throttling is used | Default false |

## ✅ bot.response_templates
| Column | Purpose | Values |
| --- | --- | --- |
| id | PK | BIGSERIAL |
| chat_config_id | Owning chat config | FK |
| template_name | Friendly name | Text |
| template_content | Template body | Text |
| response_style | Style tag | Default `ADAPTIVE` |
| response_tone | Tone tag | Default `NEUTRAL` |
| max_response_length | Length hint | Default 500 |
| is_default | Marks default template | Default false |
| priority | Selection priority | Default 1 |
| active | Whether template is usable | Default true |

## ✅ bot.trigger_conditions
| Column | Purpose | Values |
| --- | --- | --- |
| id | PK | BIGSERIAL |
| chat_config_id | Owning chat config | FK |
| condition_name | Label for the trigger | Text |
| trigger_type | How trigger fires | Text |
| keywords | CSV/list of keywords | Text |
| mention_required | Require bot mention | Default false |
| time_delay_seconds | Delay before responding | Default 0 |
| probability_percent | Chance to respond | Default 100 |
| active_hours_start / active_hours_end | Time window | TIME |
| active_days_of_week | Allowed days (1-7 list) | Default `1,2,3,4,5,6,7` |
| minimum_gap_minutes | Cooldown between responses | Default 0 |
| priority | Ordering when multiple triggers match | Default 1 |
| active | Enable/disable trigger | Default true |
| response_length | Desired length for this trigger | Default `MEDIUM` |

## ✅ bot.topic_restrictions
| Column | Purpose | Values |
| --- | --- | --- |
| id | PK | BIGSERIAL |
| chat_config_id | Owning chat config | FK |
| restriction_name | Rule name | Text |
| restriction_type | Restriction category | Text |
| keywords / categories | Matching hints | Text |
| action_type | What to do when matched | Default `IGNORE` |
| custom_response | Response override | Text |
| active | Enable/disable rule | Default true |

## ✅ bot.messages
| Column | Purpose | Values |
| --- | --- | --- |
| id | Surrogate PK for bot messages | BIGSERIAL |
| chat_id | Telegram chat ID | BIGINT |
| message_id | TDLib/internal message id | BIGINT |
| telegram_message_id | Native Telegram id (if known) | BIGINT |
| content / caption | Message text/caption | Text |
| date / edit_date | Posted/edited time | TIMESTAMPTZ |
| is_outgoing | Whether sent by bot/self | Default false |
| sender_id / sender_name / sender_username / sender_first_name / sender_last_name | Sender info | Optional |
| user_id / username | Target user info | Optional |
| media_type / media_file_path / media_kind / media_path | Media metadata | Text |
| message_type | Classification | Default `USER_MESSAGE` (AI_RESPONSE etc. also used) |
| channel_id | Channel reference | BIGINT |
| imported_from_sync | Flag for sync-imported messages | Default false |
| sync_job_id | Source sync job | BIGINT |
| forward_from_chat_id | Origin chat if forwarded | BIGINT |
| reply_to_message_id / reply_to_chat_id | Reply linkage | BIGINT |
| raw_message_dump | Raw TDLib payload | Text |
| created_at | Insert timestamp | Default now |
| importance | Calculated importance score | DOUBLE PRECISION |
| content_hash | Hash for deduping/clustering | Text |
| matched_keywords | Keywords matched in text | TEXT[] |
| consensus | Cluster consensus score | DOUBLE PRECISION |
| novelty | Recency-based novelty score | DOUBLE PRECISION |
| views / forwards | Channel metrics when available | BIGINT |

## ✅ bot.response_variations
| Column | Purpose | Values |
| --- | --- | --- |
| id | PK | BIGSERIAL |
| intent_type | Intent the template serves | e.g., BOT_DENIAL, GREETING |
| communication_style | Style label | CASUAL/FRIENDLY/HUMOROUS/etc. |
| template_text | Reply template | Text |
| emotional_tone | Tone label | Text |
| formality_level | Integer scale for formality | INT |
| response_length | Length hint | Text |
| usage_count | Times used | Default 0 |
| last_used_at | Last usage time | TIMESTAMPTZ |
| enabled | Availability flag | Default true |
| weight | Selection weight | Default 10 |
| requires_context | Whether extra context is needed | Default false |
| created_at | Created timestamp | Default now |

## ✅ bot.user_communication_profiles
| Column | Purpose | Values |
| --- | --- | --- |
| id | PK | BIGSERIAL |
| user_id | User being profiled | UNIQUE FK to users |
| avg_message_length / conversation_length_preference | Typical length metrics | INT / Text |
| formality_level | Formality score | INT |
| emoticon_usage_frequency | Emoji frequency | DOUBLE PRECISION |
| punctuation_style | Punctuation habits | Text |
| response_speed_preference | Quick/slow preference | Text |
| vocabulary_complexity | Complexity level | INT |
| uses_slang / uses_abbreviations | Style flags | Defaults false |
| typical_greeting_style | Greeting pattern | Text |
| most_active_time | Active daypart | Text |
| emotional_expressiveness | Expressiveness score | INT |
| humor_appreciation | Enjoys humor | Default true |
| prefers_direct_communication | Direct vs. indirect | Default false |
| message_sample_count | Samples analyzed | Default 0 |
| confidence_score | Confidence in profile | Default 0.0 |
| last_updated_at / created_at | Audit timestamps | Default now |

## ✅ bot.llm_queries
| Column | Purpose | Values |
| --- | --- | --- |
| id | PK | BIGSERIAL |
| chat_id | Chat where query originated | BIGINT |
| triggering_message_id | Message that triggered LLM | BIGINT |
| sender_id / sender_username / sender_name | Who triggered | Optional |
| trigger_excerpt | Snippet used for decision | Text |
| triggered_at / completed_at | Processing timestamps | TIMESTAMPTZ |
| status | Lifecycle state | Default `IN_PROGRESS` |
| should_respond | Whether to send reply | BOOLEAN |
| decision_intent / decision_tone | Selected intent/tone | Text |
| decision_confidence | Confidence score | DOUBLE PRECISION |
| attempt_count | Retries made | Default 0 |
| skip_reason | Why response skipped | Text |
| final_response | Response payload | Text |
| metadata | Extra diagnostic info | Text/JSON |
| created_at | Insert timestamp | Default now |

## ✅ bot.llm_query_messages
| Column | Purpose | Values |
| --- | --- | --- |
| id | PK | BIGSERIAL |
| query_id | Owning LLM query | FK to llm_queries |
| phase | Pipeline phase (prep/inference/etc.) | Text |
| attempt | Retry attempt | INT |
| sequence | Order within attempt | INT |
| role | Chat role | e.g., system/user/assistant |
| content | Message content | Text |
| metadata | Extra info | Text |
| created_at | Insert timestamp | Default now |

## ✅ bot.pending_responses
| Column | Purpose | Values |
| --- | --- | --- |
| id | PK | BIGSERIAL |
| chat_id | Chat that will receive response | BIGINT |
| triggering_message_id | Source message | BIGINT |
| prepared_response | Prepared text to send | Text |
| response_intent / response_tone / response_length | Selected style | Text |
| status | Queue state | Default `PENDING` |
| created_at / eligible_at / sent_at / expires_at | Timing for queueing and expiry | expires_at required |
| base_count | Human message count snapshot at enqueue time | Default 0 |
| required_delta | Extra human messages needed before send | Default 0 |
| bot_instance_id | Instance isolation key | Default `default-bot` |

## ✅ bot.chat_message_stats
| Column | Purpose | Values |
| --- | --- | --- |
| chat_id | Chat being tracked | PK |
| human_message_count | Count of human messages seen | Default 0 |

## ✅ bot.sync_run_log
| Column | Purpose | Values |
| --- | --- | --- |
| id | PK | BIGSERIAL |
| run_type | What kind of sync ran | e.g., full, incremental |
| status | Outcome | e.g., running/success/failed |
| started_at / finished_at | Run timing | started_at defaults now |
| channels_populated / chats_discovered / configs_created / sync_jobs_started | Metrics for the run | Defaults 0 |
| error_message | Error details if failed | Text |
| details | JSON payload with extra info | JSONB |

## ✅ bot.problematic_chats
| Column | Purpose | Values |
| --- | --- | --- |
| channel_chat_id | Chat flagged as problematic | PK |
| reason | Short reason label | Text |
| details | Extra context | Text |
| failure_count | How many times it failed | Default 1 |
| first_detected_at / last_detected_at | Detection timestamps | Defaults now |
| last_attempted_at | Last retry attempt time | TIMESTAMPTZ |
| notes | Free-form notes | Text |
| bot_instance_id | Instance isolation key | Default `default-bot` |

## ✅ sync_configurations (public)
| Column | Purpose | Values |
| --- | --- | --- |
| id | PK | BIGSERIAL |
| channel_id | tgscan.channels id to sync | UNIQUE per bot_instance_id |
| default_sync_depth_days | Default history depth | INT |
| max_sync_depth_days | Cap on history depth | Default 365 |
| auto_sync_enabled | Toggle for auto jobs | Default false |
| auto_sync_interval_days | Interval between auto syncs | Default 7 |
| last_auto_sync_at | Last auto sync time | TIMESTAMPTZ |
| max_concurrent_syncs | Parallel sync cap | Default 1 |
| created_at / updated_at | Audit timestamps | Defaults now |
| bot_instance_id | Instance isolation key | Default `default-bot` |

## ✅ sync_jobs (public)
| Column | Purpose | Values |
| --- | --- | --- |
| id | PK | BIGSERIAL |
| channel_id | tgscan.channels id | FK |
| status | Job status | Default `PENDING` |
| sync_depth_days | Requested depth | INT |
| sync_from_date / sync_to_date | Date range if bounded | TIMESTAMPTZ |
| messages_processed / messages_total | Progress counters | processed default 0 |
| error_message | Failure reason | Text |
| created_at / started_at / completed_at | Job timing | created_at default now |
| created_by_user_id | Who initiated | BIGINT |
| bot_instance_id | Instance isolation key | Default `default-bot` |

## ✅ search_configs (public)
| Column | Purpose | Values |
| --- | --- | --- |
| id | PK | BIGSERIAL |
| chat_id | Chat whose searches are configured | UNIQUE |
| search_enabled | Toggle for manual search | Default false |
| auto_search_enabled | Auto-run searches | Default false |
| search_provider | Provider code | Default `GOOGLE` |
| max_results | Max results to return | Default 5 (1–20 allowed) |
| cache_duration_minutes | Cache TTL | Default 60 (1–1440 allowed) |
| rate_limit_per_hour | Search rate cap | Default 30 (1–1000 allowed) |
| include_attribution | Include source attribution | Default true |
| relevance_threshold | Score cutoff | Default 0.6 (0–1) |
| search_triggers | Trigger phrases/keywords | Text |

## ✅ search_results (public)
| Column | Purpose | Values |
| --- | --- | --- |
| id | PK | BIGSERIAL |
| query_hash | Hash for deduplication | VARCHAR(64) |
| original_query / normalized_query | Raw and normalized queries | Text |
| search_provider | Provider used | Text |
| results_json | Serialized results | Text |
| total_results | Result count | BIGINT |
| search_time_ms | Execution time | BIGINT |
| relevance_score | Overall score | DECIMAL(3,2), 0–1 |
| created_at | Cached at | Default now |
| expires_at | Cache expiry | Required |
| access_count | Times retrieved | Default 0 |
| last_accessed_at | Last read time | TIMESTAMPTZ |

## ✅ tgscan.channels
| Column | Purpose | Values |
| --- | --- | --- |
| id | Telegram channel/chat id | PK |
| username / title / description | Channel metadata | Text |
| first_seen / last_seen | Discovery timestamps | Defaults now |
| sample_message | Example content | Text |
| tags | Tag list | TEXT[] |
| score | Legacy keyword score | Default 0 |
| weight | Reliability weight | DOUBLE PRECISION |
| subscribers | Subscriber count | BIGINT |
| join_status | Join attempt status | Text |
| join_attempts / join_last_error / joined_at | Join tracking | Attempts default 0 |
| mute_status / muted_at / mute_last_error | Mute tracking | Text |
| reliability_alpha / reliability_beta | Beta dist. params | Defaults 2 |
| is_channel | True if channel vs chat | BOOLEAN |
| raw | Raw JSON payload | JSONB |
| raw_keyword_score | Pre-scoring keyword score | DOUBLE PRECISION |
| channel_score | Composite score | DOUBLE PRECISION |
| score_activity / score_influence / score_relevance | Scoring breakdown | DOUBLE PRECISION |
| last_ingestion_attempt_at | Last pipeline ingestion attempt | TIMESTAMPTZ |
| bot_instance_id | Instance isolation key | Default `default-bot` |
| can_send_messages | Whether bot can post | BOOLEAN |

## ✅ tgscan.messages
| Column | Purpose | Values |
| --- | --- | --- |
| msg_id | Message id in scanner | PK |
| channel_id | tgscan.channels FK | BIGINT |
| posted_at | Message time | TIMESTAMPTZ |
| text | Message text | Text |
| matched_keywords | Matched keywords | TEXT[] |
| views / forwards | Channel metrics | BIGINT |
| importance | Calculated importance | DOUBLE PRECISION |
| content_hash | Hash for clustering | Text |
| cluster_id | Cluster identifier | Text |
| support_count | Number of supporting channels | INT |
| consensus | Consensus score | DOUBLE PRECISION |
| novelty | Novelty score | DOUBLE PRECISION |

## ✅ tgscan.channel_candidates
| Column | Purpose | Values |
| --- | --- | --- |
| id | PK | BIGSERIAL |
| candidate | Discovered channel name/id | Text |
| source_channel | Channel where found | BIGINT |
| source_msg_id | Message that mentioned it | BIGINT |
| discovered_at | Discovery time | Default now |
| processed | Whether handled | Default false |
| note | Extra info | Text |

## ✅ tgscan.agg_top_messages_daily
| Column | Purpose | Values |
| --- | --- | --- |
| as_of | Snapshot timestamp | TIMESTAMPTZ |
| msg_id | Message id | PK |
| channel_id | Channel id | BIGINT |
| posted_at | Message time | TIMESTAMPTZ |
| importance / consensus / novelty | Ranking metrics | DOUBLE PRECISION |
| views / forwards | Metrics | BIGINT |
| channel_weight | Weight at snapshot | DOUBLE PRECISION |
| username / title | Channel labels | Text |
| preview | Message preview (truncated) | Text |

## ✅ tgscan.run_log
| Column | Purpose | Values |
| --- | --- | --- |
| id | PK | BIGSERIAL |
| run_at | Log timestamp | Default now |
| step | Pipeline step name | Text |
| status | Status marker | Text |
| details | Extra details | Text |

## ✅ tgscan.events
| Column | Purpose | Values |
| --- | --- | --- |
| id | PK | BIGSERIAL |
| event_type | Detected event type | SPIKE, FUD/PANIC, FOMO/LISTING, etc. |
| topic | Keyword/topic that spiked | Text |
| window_start / window_end | Detection window | TIMESTAMPTZ |
| message_count / unique_sources | Volume metrics | INT |
| avg_importance | Avg importance in window | DOUBLE PRECISION |
| panic_ratio | Share of low-importance/panic msgs | DOUBLE PRECISION |
| spike_ratio | Volume vs. baseline | DOUBLE PRECISION |
| top_sources | JSON of top channels | JSONB |
| root_cause | Summary text | Text |
| confidence | Detection confidence | DOUBLE PRECISION |
| severity | Severity label | low/medium/high/critical |
| evidence | Supporting messages | JSONB |
| created_at / updated_at | Audit timestamps | Defaults now |
| last_alert_at | Last alert time | TIMESTAMPTZ |
| rate_limit_key | Key for throttling alerts | Text |
| status | Processing status | new, ready, sent, suppressed, failed (default new) |
| processed_at | Time processed | TIMESTAMPTZ |
| processing_error | Error details | Text |

## ✅ tgscan.alerts
| Column | Purpose | Values |
| --- | --- | --- |
| id | PK | BIGSERIAL |
| event_id | Source event | FK to events |
| priority | Alert priority | Text (mirrors severity) |
| channel | Delivery channel | Text |
| template | Template used for alert body | Text |
| rate_limit_key | Key for deduping | Text |
| status | Alert status | Default `pending` |
| delivered_at | Delivery time | TIMESTAMPTZ |
| created_at | Created time | Default now |

## ✅ tgscan.post_subscriptions
| Column | Purpose | Values |
| --- | --- | --- |
| id | PK | BIGSERIAL |
| chat_id | Telegram chat/channel to notify | BIGINT |
| enabled | Whether subscription is active | Default true |
| topic_pattern | Regex for matching topics | Text |
| event_types | Subscribed event types | Default `{SPIKE,FUD/PANIC,FOMO/LISTING}` |
| min_severity | Minimum severity to send | low/medium/high (default low) |
| template_code | Output template | RICH or SHORT (default RICH) |
| dedupe_ttl_sec | Duplicate suppression window | Default 1200 |
| created_at / updated_at | Audit timestamps | Defaults now |

## ✅ tgscan.posted
| Column | Purpose | Values |
| --- | --- | --- |
| id | PK | BIGSERIAL |
| event_id | Posted event | FK to events |
| subscription_id | Subscription used | FK to post_subscriptions |
| chat_id | Destination chat | BIGINT |
| message_id | Telegram message id when sent | BIGINT |
| template_code | Template used | Text |
| status | Post status | `sent` (default) or `failed` |
| error_message | Failure reason | Text |
| posted_at | Time posted | Default now |

## ✅ tgscan.v_channel_debug (materialized view)
Columns mirror `tgscan.channels` plus aggregates (`msgs_14d`, `avg_views_14d`, `avg_fwd_14d`) to aid scoring diagnostics.
