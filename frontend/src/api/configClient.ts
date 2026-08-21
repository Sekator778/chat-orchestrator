/**
 * Configuration Client for the Visual Constructor
 *
 * Aggregates data from multiple API endpoints to provide a unified
 * configuration overview for the graph visualization.
 */

import type {
  ChannelOverview,
  EnhancedChatConfig,
  SearchConfig,
  PersonaBundle,
  ConfigValidationRequest,
  ConfigValidationResponse,
  EntityValidationResult,
} from '../types/api'
import type { DigestPersona } from '../types/digest'
import {
  fetchChannelOverview,
  fetchEnhancedConfig,
  fetchSearchConfig,
  fetchPersonaBundles,
  validateConfigs,
  validateChannel,
  validateDigestPersona as validateDigestPersonaApi,
  previewConfigValidation,
} from './client'
import { fetchPersonas } from './digestClient'

/**
 * Combined configuration overview for the graph
 */
export interface ConfigurationOverview {
  channels: ChannelOverview[]
  digestPersonas: DigestPersona[]
  botPersonas: PersonaBundle[]
}

/**
 * Full channel configuration including all nested entities
 */
export interface FullChannelConfig {
  overview: ChannelOverview
  enhanced: EnhancedChatConfig | null
  searchConfig: SearchConfig | null
}

/**
 * Fetches the complete configuration overview needed for the graph.
 * Aggregates channels, digest personas, and bot personas.
 *
 * @returns Promise with all configuration data
 * @throws Error if any required API call fails
 */
export async function fetchConfigurationOverview(): Promise<ConfigurationOverview> {
  const [channels, digestPersonas, botPersonas] = await Promise.all([
    fetchChannelOverview(),
    fetchPersonas().catch(() => [] as DigestPersona[]),
    fetchPersonaBundles().catch(() => [] as PersonaBundle[]),
  ])

  return {
    channels,
    digestPersonas,
    botPersonas,
  }
}

/**
 * Fetches full configuration for a specific channel including
 * enhanced config and search config.
 *
 * @param channelId - The channel ID to fetch configuration for
 * @returns Promise with full channel configuration
 */
export async function fetchFullChannelConfig(
  channelId: number
): Promise<FullChannelConfig> {
  const overview = await fetchChannelOverview().then(
    (channels) => channels.find((c) => c.chatId === channelId) ?? null
  )

  if (!overview) {
    throw new Error(`Channel ${channelId} not found`)
  }

  const [enhanced, searchConfig] = await Promise.all([
    overview.hasConfig
      ? fetchEnhancedConfig(channelId).catch(() => null)
      : Promise.resolve(null),
    fetchSearchConfig(channelId).catch(() => null),
  ])

  return {
    overview,
    enhanced,
    searchConfig,
  }
}

/**
 * Fetches enhanced configuration for multiple channels in parallel.
 * Useful for loading child nodes when expanding a channel.
 *
 * @param channelIds - Array of channel IDs to fetch
 * @returns Promise with map of channelId to enhanced config
 */
export async function fetchEnhancedConfigs(
  channelIds: number[]
): Promise<Map<number, EnhancedChatConfig | null>> {
  const results = await Promise.allSettled(
    channelIds.map((id) => fetchEnhancedConfig(id))
  )

  const configMap = new Map<number, EnhancedChatConfig | null>()

  results.forEach((result, index) => {
    const channelId = channelIds[index]
    if (result.status === 'fulfilled') {
      configMap.set(channelId, result.value)
    } else {
      configMap.set(channelId, null)
    }
  })

  return configMap
}

/**
 * Fetches search configurations for multiple channels in parallel.
 *
 * @param channelIds - Array of channel IDs to fetch
 * @returns Promise with map of channelId to search config
 */
export async function fetchSearchConfigs(
  channelIds: number[]
): Promise<Map<number, SearchConfig | null>> {
  const results = await Promise.allSettled(
    channelIds.map((id) => fetchSearchConfig(id))
  )

  const configMap = new Map<number, SearchConfig | null>()

  results.forEach((result, index) => {
    const channelId = channelIds[index]
    if (result.status === 'fulfilled') {
      configMap.set(channelId, result.value)
    } else {
      configMap.set(channelId, null)
    }
  })

  return configMap
}

/**
 * Result type for batch configuration fetch operations
 */
export interface BatchConfigResult {
  channelId: number
  enhanced: EnhancedChatConfig | null
  searchConfig: SearchConfig | null
  error: string | null
}

/**
 * Fetches full configuration for multiple channels in parallel.
 * Returns results for all channels even if some fail.
 *
 * @param channelIds - Array of channel IDs to fetch
 * @returns Promise with array of batch results
 */
export async function fetchBatchConfigs(
  channelIds: number[]
): Promise<BatchConfigResult[]> {
  const results = await Promise.allSettled(
    channelIds.map(async (id) => {
      const [enhanced, searchConfig] = await Promise.all([
        fetchEnhancedConfig(id).catch(() => null),
        fetchSearchConfig(id).catch(() => null),
      ])
      return { channelId: id, enhanced, searchConfig, error: null }
    })
  )

  return results.map((result, index) => {
    if (result.status === 'fulfilled') {
      return result.value
    }
    return {
      channelId: channelIds[index],
      enhanced: null,
      searchConfig: null,
      error: result.reason?.message ?? 'Unknown error',
    }
  })
}

/**
 * Checks if a channel has any configuration at all
 */
export function hasAnyConfig(channel: ChannelOverview): boolean {
  return channel.hasConfig === true
}

/**
 * Checks if a channel is fully configured (has config and is enabled)
 */
export function isFullyConfigured(channel: ChannelOverview): boolean {
  return channel.hasConfig && channel.enabled === true
}

/**
 * Checks if a channel needs configuration (has config but missing required parts)
 */
export function needsConfiguration(channel: ChannelOverview): boolean {
  if (!channel.hasConfig) return true
  if (!channel.enabled) return false
  return (channel.triggerCount ?? 0) === 0
}

// ============================================================================
// Server-Side Validation Functions
// ============================================================================

/**
 * Validates multiple channels and optionally digest personas
 * using server-side validation rules.
 *
 * @param channelIds - Channel IDs to validate
 * @param includeDigestPersonas - Whether to include digest persona validation
 * @param includeRelatedEntities - Whether to validate related entities (llmParams, rateLimits, etc.)
 * @returns Promise with validation response
 */
export async function validateConfiguration(
  channelIds: number[],
  includeDigestPersonas = false,
  includeRelatedEntities = true
): Promise<ConfigValidationResponse> {
  const request: ConfigValidationRequest = {
    channelIds,
    includeDigestPersonas,
    includeRelatedEntities,
  }
  return validateConfigs(request)
}

/**
 * Validates a single channel configuration
 *
 * @param channelId - The channel ID to validate
 * @returns Promise with validation result for the channel
 */
export async function validateSingleChannel(
  channelId: number
): Promise<EntityValidationResult> {
  return validateChannel(channelId)
}

/**
 * Validates a digest persona configuration
 *
 * @param personaId - The persona ID to validate
 * @returns Promise with validation result for the persona
 */
export async function validatePersona(
  personaId: number
): Promise<EntityValidationResult> {
  return validateDigestPersonaApi(personaId)
}

/**
 * Previews validation for a channel (before saving changes)
 *
 * @param channelId - The channel ID to preview validation for
 * @returns Promise with validation response
 */
export async function previewValidation(
  channelId: number
): Promise<ConfigValidationResponse> {
  return previewConfigValidation(channelId)
}

/**
 * Validates all channels in the configuration overview
 *
 * @param overview - Configuration overview to validate
 * @returns Promise with validation response for all channels
 */
export async function validateAllChannels(
  overview: ConfigurationOverview
): Promise<ConfigValidationResponse> {
  const channelIds = overview.channels
    .filter((c) => c.hasConfig)
    .map((c) => c.chatId)
  return validateConfiguration(channelIds, true, true)
}
