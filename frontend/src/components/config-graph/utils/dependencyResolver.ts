/**
 * Dependency Resolver Utilities
 *
 * Analyzes configuration entities to find missing dependencies,
 * incomplete configurations, and provides suggestions for fixes.
 */

import type { ConfigStatus, EntityType, GraphNode } from '../../../types/graph'
import type {
  EnhancedChatConfig,
  LlmParameters,
  RateLimits,
  ContextSettings,
  SearchConfig,
} from '../../../types/api'
import type { DigestPersona } from '../../../types/digest'

/**
 * Represents a missing dependency or configuration issue
 */
export interface DependencyIssue {
  /** Type of issue */
  type: 'missing' | 'incomplete' | 'warning' | 'suggestion'
  /** Severity level */
  severity: 'error' | 'warning' | 'info'
  /** Human-readable message */
  message: string
  /** Entity type that has the issue */
  entityType: EntityType
  /** Field or area with the issue */
  field?: string
  /** Suggested action to fix */
  suggestion?: string
  /** Related entity ID if applicable */
  relatedEntityId?: string
}

/**
 * Validation result for an entity
 */
export interface ValidationResult {
  /** Overall validity */
  valid: boolean
  /** List of issues found */
  issues: DependencyIssue[]
  /** Suggested status based on issues */
  suggestedStatus: ConfigStatus
}

/**
 * Find all dependency issues for a chat configuration
 */
export function validateChatConfig(config: EnhancedChatConfig): ValidationResult {
  const issues: DependencyIssue[] = []

  // Check if enabled but missing critical config
  if (config.enabled) {
    if (!config.llm_parameters) {
      issues.push({
        type: 'missing',
        severity: 'error',
        message: 'LLM parameters not configured',
        entityType: 'chatConfig',
        field: 'llm_parameters',
        suggestion: 'Configure LLM parameters to enable AI responses',
      })
    }

    if (!config.prompt_template) {
      issues.push({
        type: 'incomplete',
        severity: 'warning',
        message: 'No prompt template set',
        entityType: 'chatConfig',
        field: 'prompt_template',
        suggestion: 'Add a prompt template for better response quality',
      })
    }

    if (config.trigger_conditions.length === 0) {
      issues.push({
        type: 'missing',
        severity: 'warning',
        message: 'No trigger conditions defined',
        entityType: 'chatConfig',
        field: 'trigger_conditions',
        suggestion: 'Add triggers to define when the bot should respond',
      })
    }
  }

  // Check language setting
  if (!config.language) {
    issues.push({
      type: 'incomplete',
      severity: 'info',
      message: 'Language not set',
      entityType: 'chatConfig',
      field: 'language',
      suggestion: 'Set language for better response localization',
    })
  }

  return {
    valid: issues.filter((i) => i.severity === 'error').length === 0,
    issues,
    suggestedStatus: determineSuggestedStatus(issues),
  }
}

/**
 * Find all dependency issues for LLM parameters
 */
export function validateLlmParameters(params: LlmParameters | null): ValidationResult {
  const issues: DependencyIssue[] = []

  if (!params) {
    issues.push({
      type: 'missing',
      severity: 'error',
      message: 'LLM parameters not configured',
      entityType: 'llmParams',
      suggestion: 'Configure LLM parameters to enable AI responses',
    })
    return { valid: false, issues, suggestedStatus: 'unconfigured' }
  }

  if (!params.model_name) {
    issues.push({
      type: 'missing',
      severity: 'error',
      message: 'Model name not specified',
      entityType: 'llmParams',
      field: 'model_name',
      suggestion: 'Select an LLM model (e.g., deepseek-chat)',
    })
  }

  if (params.temperature === null) {
    issues.push({
      type: 'incomplete',
      severity: 'info',
      message: 'Temperature not set (using default)',
      entityType: 'llmParams',
      field: 'temperature',
      suggestion: 'Set temperature to control response creativity',
    })
  }

  if (params.max_tokens === null) {
    issues.push({
      type: 'incomplete',
      severity: 'info',
      message: 'Max tokens not set (using default)',
      entityType: 'llmParams',
      field: 'max_tokens',
      suggestion: 'Set max tokens to control response length',
    })
  }

  if (params.temperature !== null && (params.temperature < 0 || params.temperature > 2)) {
    issues.push({
      type: 'warning',
      severity: 'warning',
      message: 'Temperature out of recommended range (0-2)',
      entityType: 'llmParams',
      field: 'temperature',
      suggestion: 'Use temperature between 0 and 2 for best results',
    })
  }

  return {
    valid: issues.filter((i) => i.severity === 'error').length === 0,
    issues,
    suggestedStatus: determineSuggestedStatus(issues),
  }
}

/**
 * Find all dependency issues for rate limits
 */
export function validateRateLimits(limits: RateLimits | null): ValidationResult {
  const issues: DependencyIssue[] = []

  if (!limits) {
    issues.push({
      type: 'suggestion',
      severity: 'info',
      message: 'Rate limits not configured',
      entityType: 'rateLimits',
      suggestion: 'Configure rate limits to prevent spam and manage costs',
    })
    return { valid: true, issues, suggestedStatus: 'unconfigured' }
  }

  const hasAnyLimit =
    limits.max_messages_per_hour !== null ||
    limits.max_messages_per_day !== null ||
    limits.max_tokens_per_day !== null

  if (!hasAnyLimit) {
    issues.push({
      type: 'incomplete',
      severity: 'warning',
      message: 'No rate limits defined',
      entityType: 'rateLimits',
      suggestion: 'Set at least one rate limit to control usage',
    })
  }

  if (limits.cooldown_after_limit_minutes !== null && limits.cooldown_after_limit_minutes < 0) {
    issues.push({
      type: 'warning',
      severity: 'warning',
      message: 'Invalid cooldown value',
      entityType: 'rateLimits',
      field: 'cooldown_after_limit_minutes',
      suggestion: 'Cooldown must be a positive number',
    })
  }

  return {
    valid: issues.filter((i) => i.severity === 'error').length === 0,
    issues,
    suggestedStatus: determineSuggestedStatus(issues),
  }
}

/**
 * Find all dependency issues for context settings
 */
export function validateContextSettings(settings: ContextSettings | null): ValidationResult {
  const issues: DependencyIssue[] = []

  if (!settings) {
    issues.push({
      type: 'suggestion',
      severity: 'info',
      message: 'Context settings not configured',
      entityType: 'contextSettings',
      suggestion: 'Configure context settings to control conversation memory',
    })
    return { valid: true, issues, suggestedStatus: 'unconfigured' }
  }

  if (settings.history_message_count === null && settings.history_time_window_hours === null) {
    issues.push({
      type: 'incomplete',
      severity: 'warning',
      message: 'No history limits defined',
      entityType: 'contextSettings',
      suggestion: 'Set message count or time window to limit context size',
    })
  }

  if (settings.history_message_count !== null && settings.history_message_count > 100) {
    issues.push({
      type: 'warning',
      severity: 'warning',
      message: 'Large history may increase token usage',
      entityType: 'contextSettings',
      field: 'history_message_count',
      suggestion: 'Consider limiting to 50 messages or less for efficiency',
    })
  }

  return {
    valid: issues.filter((i) => i.severity === 'error').length === 0,
    issues,
    suggestedStatus: determineSuggestedStatus(issues),
  }
}

/**
 * Find all dependency issues for search configuration
 */
export function validateSearchConfig(config: SearchConfig | null): ValidationResult {
  const issues: DependencyIssue[] = []

  if (!config) {
    issues.push({
      type: 'suggestion',
      severity: 'info',
      message: 'Search not configured',
      entityType: 'searchConfig',
      suggestion: 'Enable search to allow web lookups in responses',
    })
    return { valid: true, issues, suggestedStatus: 'unconfigured' }
  }

  if (config.search_enabled) {
    if (!config.search_provider) {
      issues.push({
        type: 'missing',
        severity: 'error',
        message: 'Search provider not selected',
        entityType: 'searchConfig',
        field: 'search_provider',
        suggestion: 'Select a search provider to enable search',
      })
    }

    if (config.max_results === 0) {
      issues.push({
        type: 'warning',
        severity: 'warning',
        message: 'Max results set to 0',
        entityType: 'searchConfig',
        field: 'max_results',
        suggestion: 'Set max results to at least 1',
      })
    }

    if (config.rate_limit_per_hour === 0) {
      issues.push({
        type: 'warning',
        severity: 'warning',
        message: 'Search rate limit is 0 (search disabled)',
        entityType: 'searchConfig',
        field: 'rate_limit_per_hour',
        suggestion: 'Set a positive rate limit to enable searches',
      })
    }
  }

  return {
    valid: issues.filter((i) => i.severity === 'error').length === 0,
    issues,
    suggestedStatus: determineSuggestedStatus(issues),
  }
}

/**
 * Find all dependency issues for digest persona
 */
export function validateDigestPersona(persona: DigestPersona): ValidationResult {
  const issues: DependencyIssue[] = []

  if (persona.enabled) {
    if (!persona.scheduleCron) {
      issues.push({
        type: 'missing',
        severity: 'error',
        message: 'Schedule not configured',
        entityType: 'digestPersona',
        field: 'scheduleCron',
        suggestion: 'Set a cron schedule for digest generation',
      })
    }

    if (!persona.targetChannelId) {
      issues.push({
        type: 'missing',
        severity: 'error',
        message: 'Target channel not set',
        entityType: 'digestPersona',
        field: 'targetChannelId',
        suggestion: 'Select a channel to publish digests to',
      })
    }

    if (!persona.botId) {
      issues.push({
        type: 'missing',
        severity: 'error',
        message: 'Bot ID not set',
        entityType: 'digestPersona',
        field: 'botId',
        suggestion: 'Configure bot ID for digest publishing',
      })
    }

    if (!persona.personaStyle) {
      issues.push({
        type: 'incomplete',
        severity: 'warning',
        message: 'Persona style not selected',
        entityType: 'digestPersona',
        field: 'personaStyle',
        suggestion: 'Choose a style for digest presentation',
      })
    }
  }

  return {
    valid: issues.filter((i) => i.severity === 'error').length === 0,
    issues,
    suggestedStatus: determineSuggestedStatus(issues),
  }
}

/**
 * Determine suggested status based on issues found
 */
function determineSuggestedStatus(issues: DependencyIssue[]): ConfigStatus {
  const hasErrors = issues.some((i) => i.severity === 'error')
  const hasWarnings = issues.some((i) => i.severity === 'warning')
  const hasIncomplete = issues.some((i) => i.type === 'incomplete')

  if (hasErrors) {
    return 'partial'
  }
  if (hasWarnings) {
    return 'warning'
  }
  if (hasIncomplete) {
    return 'partial'
  }
  return 'configured'
}

/**
 * Find related nodes that depend on a given node
 */
export function findDependentNodes(nodeId: string, nodes: GraphNode[]): GraphNode[] {
  return nodes.filter((node) => {
    const data = node.data
    if ('parentChatId' in data && data.parentChatId?.toString() === nodeId) {
      return true
    }
    if ('chatId' in data && data.chatId?.toString() === nodeId) {
      return true
    }
    return false
  })
}

/**
 * Find nodes that a given node depends on
 */
export function findParentNodes(nodeId: string, nodes: GraphNode[]): GraphNode[] {
  const node = nodes.find((n) => n.id === nodeId)
  if (!node) return []

  const parentIds: string[] = []
  const data = node.data

  if ('parentChatId' in data && data.parentChatId) {
    parentIds.push(`channel-${data.parentChatId}`)
    parentIds.push(`chatConfig-${data.parentChatId}`)
  }

  return nodes.filter((n) => parentIds.includes(n.id))
}

/**
 * Get all issues for a set of nodes
 */
export function getAllIssues(nodes: GraphNode[]): Map<string, DependencyIssue[]> {
  const issueMap = new Map<string, DependencyIssue[]>()

  nodes.forEach((node) => {
    const data = node.data
    let result: ValidationResult | null = null

    if (node.type === 'chatConfig' && 'config' in data && data.config) {
      result = validateChatConfig(data.config as EnhancedChatConfig)
    } else if (node.type === 'llm' && 'params' in data) {
      result = validateLlmParameters(data.params as LlmParameters | null)
    } else if (node.type === 'rateLimits' && 'limits' in data) {
      result = validateRateLimits(data.limits as RateLimits | null)
    } else if (node.type === 'contextSettings' && 'settings' in data) {
      result = validateContextSettings(data.settings as ContextSettings | null)
    } else if (node.type === 'searchConfig' && 'config' in data) {
      result = validateSearchConfig(data.config as SearchConfig | null)
    } else if (node.type === 'digestPersona' && 'persona' in data) {
      result = validateDigestPersona(data.persona as DigestPersona)
    }

    if (result && result.issues.length > 0) {
      issueMap.set(node.id, result.issues)
    }
  })

  return issueMap
}

/**
 * Summarize issues by severity
 */
export function summarizeIssues(issues: DependencyIssue[]): {
  errors: number
  warnings: number
  info: number
  total: number
} {
  return {
    errors: issues.filter((i) => i.severity === 'error').length,
    warnings: issues.filter((i) => i.severity === 'warning').length,
    info: issues.filter((i) => i.severity === 'info').length,
    total: issues.length,
  }
}
