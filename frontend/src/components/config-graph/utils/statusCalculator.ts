/**
 * Status Calculator Utilities
 *
 * Provides consistent status calculation for all configuration entities
 * based on their configuration state and completeness.
 */

import type { ConfigStatus } from '../../../types/graph'
import type {
  ChannelOverview,
  EnhancedChatConfig,
  LlmParameters,
  RateLimits,
  ContextSettings,
  TriggerCondition,
  ResponseTemplate,
  TopicRestriction,
  SearchConfig,
  PersonaBundle,
} from '../../../types/api'
import type { DigestPersona } from '../../../types/digest'

/**
 * Calculate status for a channel based on its configuration state
 */
export function calculateChannelStatus(channel: ChannelOverview): ConfigStatus {
  if (!channel.hasConfig) {
    return 'unconfigured'
  }
  if (!channel.enabled) {
    return 'saved'
  }
  if ((channel.triggerCount ?? 0) === 0) {
    return 'partial'
  }
  return 'configured'
}

/**
 * Calculate status for a chat configuration
 */
export function calculateChatConfigStatus(config: EnhancedChatConfig): ConfigStatus {
  if (!config.enabled) {
    return 'saved'
  }
  if (!config.llm_parameters && !config.prompt_template) {
    return 'partial'
  }
  if (config.trigger_conditions.length === 0) {
    return 'warning'
  }
  return 'configured'
}

/**
 * Calculate status for LLM parameters
 */
export function calculateLlmStatus(params: LlmParameters | null): ConfigStatus {
  if (!params) {
    return 'unconfigured'
  }
  if (!params.model_name) {
    return 'partial'
  }
  if (params.temperature === null && params.max_tokens === null) {
    return 'warning'
  }
  return 'configured'
}

/**
 * Calculate status for rate limits
 */
export function calculateRateLimitsStatus(limits: RateLimits | null): ConfigStatus {
  if (!limits) {
    return 'unconfigured'
  }
  const hasAnyLimit =
    limits.max_messages_per_hour !== null ||
    limits.max_messages_per_day !== null ||
    limits.max_tokens_per_day !== null
  if (!hasAnyLimit) {
    return 'partial'
  }
  return 'configured'
}

/**
 * Calculate status for context settings
 */
export function calculateContextSettingsStatus(settings: ContextSettings | null): ConfigStatus {
  if (!settings) {
    return 'unconfigured'
  }
  const hasConfig =
    settings.history_message_count !== null || settings.history_time_window_hours !== null
  if (!hasConfig) {
    return 'partial'
  }
  return 'configured'
}

/**
 * Calculate status for a trigger condition
 */
export function calculateTriggerStatus(trigger: TriggerCondition): ConfigStatus {
  if (!trigger.active) {
    return 'saved'
  }
  if (!trigger.trigger_type || !trigger.condition_name) {
    return 'partial'
  }
  if (!trigger.keywords && trigger.trigger_type === 'keyword') {
    return 'warning'
  }
  return 'configured'
}

/**
 * Calculate status for a response template
 */
export function calculateTemplateStatus(template: ResponseTemplate): ConfigStatus {
  if (!template.active) {
    return 'saved'
  }
  if (!template.template_content) {
    return 'partial'
  }
  return 'configured'
}

/**
 * Calculate status for a topic restriction
 */
export function calculateRestrictionStatus(restriction: TopicRestriction): ConfigStatus {
  if (!restriction.active) {
    return 'saved'
  }
  if (!restriction.restriction_type || !restriction.action_type) {
    return 'partial'
  }
  if (!restriction.keywords && !restriction.categories) {
    return 'warning'
  }
  return 'configured'
}

/**
 * Calculate status for search configuration
 */
export function calculateSearchConfigStatus(config: SearchConfig | null): ConfigStatus {
  if (!config) {
    return 'unconfigured'
  }
  if (!config.search_enabled) {
    return 'saved'
  }
  if (!config.search_provider || config.max_results === 0) {
    return 'partial'
  }
  if (config.rate_limit_per_hour === 0) {
    return 'warning'
  }
  return 'configured'
}

/**
 * Calculate status for a digest persona based on its configuration state
 */
export function calculateDigestPersonaStatus(persona: DigestPersona): ConfigStatus {
  if (!persona.enabled) {
    return 'saved'
  }
  if (!persona.scheduleCron) {
    return 'partial'
  }
  if (!persona.targetChannelId || !persona.botId) {
    return 'warning'
  }
  return 'configured'
}

/**
 * Calculate status for a bot persona bundle
 */
export function calculateBotPersonaStatus(bundle: PersonaBundle): ConfigStatus {
  if (!bundle.botId) {
    return 'unconfigured'
  }
  if (bundle.languages.length === 0) {
    return 'partial'
  }
  if (!bundle.previewName) {
    return 'warning'
  }
  return 'configured'
}

/**
 * Get status color for minimap visualization
 */
export function getStatusColor(status: ConfigStatus): string {
  switch (status) {
    case 'configured':
      return '#22c55e' // green-500
    case 'partial':
      return '#f59e0b' // amber-500
    case 'warning':
      return '#f97316' // orange-500
    case 'unconfigured':
      return '#94a3b8' // slate-400
    case 'loading':
      return '#6366f1' // indigo-500
    case 'saved':
      return '#a855f7' // purple-500
    default:
      return '#e2e8f0' // slate-200
  }
}

/**
 * Get status icon for display
 */
export function getStatusIcon(status: ConfigStatus): string {
  switch (status) {
    case 'configured':
      return '✅'
    case 'partial':
      return '🔧'
    case 'warning':
      return '⚠️'
    case 'unconfigured':
      return '❌'
    case 'loading':
      return '🔄'
    case 'saved':
      return '💾'
    default:
      return '❓'
  }
}

/**
 * Get status label for display
 */
export function getStatusLabel(status: ConfigStatus): string {
  switch (status) {
    case 'configured':
      return 'Configured'
    case 'partial':
      return 'Partial'
    case 'warning':
      return 'Warning'
    case 'unconfigured':
      return 'Not configured'
    case 'loading':
      return 'Loading'
    case 'saved':
      return 'Saved'
    default:
      return 'Unknown'
  }
}

/**
 * Count entities by status
 */
export function countByStatus(
  statuses: ConfigStatus[]
): Record<ConfigStatus, number> {
  const counts: Record<ConfigStatus, number> = {
    configured: 0,
    partial: 0,
    warning: 0,
    unconfigured: 0,
    loading: 0,
    saved: 0,
  }
  statuses.forEach((status) => {
    counts[status]++
  })
  return counts
}
