export interface ChannelView {
  chatId: number
  title: string | null
  joinStatus: string | null
  muteStatus: string | null
  lastSeen: string | null
}

export interface ChannelOverview {
  chatId: number
  title: string | null
  description?: string | null
  joinStatus: string | null
  muteStatus: string | null
  lastSeen: string | null
  channelScore: number | null
  subscribers: number | null
  hasConfig: boolean
  configChannelChatId: number | null
  enabled: boolean | null
  autoSyncEnabled: boolean | null
  language: string | null
  contextWindowSize: number | null
  processingPhase: string | null
  triggerCount: number | null
  restrictionCount: number | null
}

export interface ResponseTemplate {
  id: number
  chat_config_id: number
  template_name: string
  template_content: string
  response_style: string | null
  response_tone: string | null
  max_response_length: number | null
  is_default: boolean
  priority: number | null
  active: boolean
}

export interface TriggerCondition {
  id: number
  chat_config_id: number
  condition_name: string
  trigger_type: string
  keywords: string | null
  mention_required: boolean
  time_delay_seconds: number | null
  probability_percent: number | null
  active_hours_start: string | null
  active_hours_end: string | null
  active_days_of_week: string | null
  minimum_gap_minutes: number | null
  priority: number | null
  active: boolean
}

export interface ContextSettings {
  id: number | null
  chat_config_id: number | null
  history_message_count: number | null
  history_time_window_hours: number | null
  include_user_context: boolean
  include_media_descriptions: boolean
  context_compression_enabled: boolean
  max_context_tokens: number | null
  preserve_important_messages: boolean
}

export interface LlmParameters {
  id: number | null
  chat_config_id: number | null
  model_name: string | null
  temperature: number | null
  max_tokens: number | null
  top_p: number | null
  frequency_penalty: number | null
  presence_penalty: number | null
  system_prompt: string | null
  custom_instructions: string | null
  response_format: string | null
}

export interface RateLimits {
  id: number | null
  chat_config_id: number | null
  max_messages_per_minute: number | null
  max_messages_per_hour: number | null
  max_messages_per_day: number | null
  current_daily_messages: number | null
  max_tokens_per_day: number | null
  pending_response_delay_seconds: number | null
  cooldown_after_limit_minutes: number | null
  burst_limit: number | null
  burst_window_seconds: number | null
  user_specific_limits: boolean
}

export interface TopicRestriction {
  id: number
  chat_config_id: number
  restriction_name: string
  restriction_type: string
  keywords: string | null
  categories: string | null
  action_type: string
  custom_response: string | null
  active: boolean
}

export interface EnhancedChatConfig {
  id: number
  channel_id: number
  channel_title: string | null
  prompt_template: string | null
  enabled: boolean
  default_sync_depth_days: number | null
  auto_sync_enabled: boolean | null
  language: string | null
  primary_channel_id: number | null
  primary_channel_checked_at: string | null
  context_window_size: number | null
  respond_to_forwarded_bot_messages: boolean | null
  wait_for_human_replies_count: number | null
  sync_enabled: boolean
  max_tokens: number | null
  temperature: number | null
  response_templates: ResponseTemplate[]
  trigger_conditions: TriggerCondition[]
  context_settings: ContextSettings | null
  llm_parameters: LlmParameters | null
  rate_limits: RateLimits | null
  topic_restrictions: TopicRestriction[]
}

export interface SearchConfig {
  id?: number
  chat_id: number
  search_enabled: boolean
  auto_search_enabled: boolean
  search_provider: string
  max_results: number
  cache_duration_minutes: number
  rate_limit_per_hour: number
  include_attribution: boolean
  relevance_threshold: number
  search_triggers: string[] | null
}

export interface Persona {
  id?: number
  botId?: string
  language: string
  name: string
  description: string
  behavior: string[]
  traits: string[]
  limitations: string[]
  metadata?: Record<string, unknown> | null
  updatedAt?: string
}

export interface PersonaBundle {
  botId: string
  languages: string[]
  previewName?: string | null
  previewDescription?: string | null
  updatedAt?: string
}

export interface BasicConfigUpdate {
  prompt_template?: string | null
  enabled?: boolean
  max_tokens?: number | null
  temperature?: number | null
  language?: string | null
  primary_channel_id?: number | null
  context_window_size?: number | null
  respond_to_forwarded_bot_messages?: boolean | null
}

export interface PendingResponseConfigUpdate {
  wait_for_human_replies_count?: number | null
  pending_response_delay_seconds?: number | null
}

export interface MessageCountResponse {
  chat_id: number
  message_count: number
}

export interface MessagePurgeRequest {
  chat_id: number
  confirm_chat_id: number
}

export interface MessagePurgeResult {
  chat_id: number
  message_count_before: number
  deleted_messages: number
}

export interface DbSchema {
  name: string
}

export interface DbTable {
  name: string
  type: string
}

export interface DbColumn {
  name: string
  data_type: string
  udt_name: string
  nullable: boolean
  ordinal_position: number
  character_maximum_length: number | null
  numeric_precision: number | null
  numeric_scale: number | null
}

export interface DbTableMeta {
  schema: string
  table: string
  columns: DbColumn[]
}

export type DbFilterOp =
  | 'EQ'
  | 'NE'
  | 'GT'
  | 'GTE'
  | 'LT'
  | 'LTE'
  | 'CONTAINS'
  | 'STARTS_WITH'
  | 'ENDS_WITH'
  | 'IS_NULL'
  | 'IS_NOT_NULL'

export interface DbFilter {
  column: string
  op: DbFilterOp
  value?: unknown
}

export type SortDirection = 'ASC' | 'DESC'

export interface DbOrderBy {
  column: string
  direction?: SortDirection
}

export interface DbQueryRequest {
  schema: string
  table: string
  select?: string[]
  filters?: DbFilter[]
  order_by?: DbOrderBy[]
  limit?: number
  offset?: number
}

export interface DbQueryResponse {
  columns: string[]
  rows: unknown[][]
  sql: string
}

// Validation API Types

export type ValidationIssueSeverity = 'ERROR' | 'WARNING' | 'INFO'
export type ValidationIssueType = 'MISSING' | 'INCOMPLETE' | 'INVALID' | 'DEPENDENCY'

export interface ValidationIssue {
  type: ValidationIssueType
  severity: ValidationIssueSeverity
  message: string
  field: string | null
  suggestion: string | null
}

export interface EntityValidationResult {
  entityType: string
  entityId: string
  valid: boolean
  issues: ValidationIssue[]
}

export interface ValidationSummary {
  totalEntities: number
  validEntities: number
  invalidEntities: number
  totalIssues: number
  errorCount: number
  warningCount: number
  infoCount: number
}

export interface ConfigValidationRequest {
  channelIds: number[]
  includeDigestPersonas: boolean
  includeRelatedEntities: boolean
}

export interface ConfigValidationResponse {
  valid: boolean
  totalIssues: number
  summary: ValidationSummary
  entityResults: Record<string, EntityValidationResult>
}
