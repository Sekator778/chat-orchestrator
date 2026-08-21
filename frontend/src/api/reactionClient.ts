import type {
  PersonaReactionConfig,
  PersonaReactionLog,
  ReactionSystemHealth,
  PersonaReactionStats,
  CreateReactionConfigRequest,
  UpdateReactionConfigRequest,
} from '../types/reaction'

const API_BASE = import.meta.env.VITE_API_BASE ?? ''
const jsonHeaders = { 'Content-Type': 'application/json' }
const withBase = (path: string) => `${API_BASE}${path}`

async function apiFetch<T>(url: string, options?: RequestInit): Promise<T> {
  const response = await fetch(withBase(url), options)
  if (!response.ok) {
    const text = await response.text()
    throw new Error(text || `Request failed: ${response.status}`)
  }
  if (response.status === 204) return undefined as T
  return (await response.json()) as T
}

// ============================================================================
// Config CRUD
// ============================================================================

/**
 * Fetches all reaction configs across all personas.
 */
export const fetchAllConfigs = (): Promise<PersonaReactionConfig[]> =>
  apiFetch<PersonaReactionConfig[]>('/api/persona/reactions/config')

/**
 * Fetches reaction configs for a specific persona.
 */
export const fetchPersonaConfigs = (personaId: string): Promise<PersonaReactionConfig[]> =>
  apiFetch<PersonaReactionConfig[]>(`/api/persona/reactions/config/${personaId}`)

/**
 * Creates a new reaction config.
 */
export const createConfig = (data: CreateReactionConfigRequest): Promise<PersonaReactionConfig> =>
  apiFetch<PersonaReactionConfig>('/api/persona/reactions/config', {
    method: 'POST',
    headers: jsonHeaders,
    body: JSON.stringify(data),
  })

/**
 * Updates an existing reaction config.
 */
export const updateConfig = (id: number, data: UpdateReactionConfigRequest): Promise<PersonaReactionConfig> =>
  apiFetch<PersonaReactionConfig>(`/api/persona/reactions/config/${id}`, {
    method: 'PUT',
    headers: jsonHeaders,
    body: JSON.stringify(data),
  })

/**
 * Deletes a reaction config by ID.
 */
export const deleteConfig = (id: number): Promise<void> =>
  apiFetch<void>(`/api/persona/reactions/config/${id}`, {
    method: 'DELETE',
  })

/**
 * Enables a reaction config.
 */
export const enableConfig = (id: number): Promise<PersonaReactionConfig> =>
  apiFetch<PersonaReactionConfig>(`/api/persona/reactions/config/${id}/enable`, {
    method: 'POST',
  })

/**
 * Disables a reaction config.
 */
export const disableConfig = (id: number): Promise<PersonaReactionConfig> =>
  apiFetch<PersonaReactionConfig>(`/api/persona/reactions/config/${id}/disable`, {
    method: 'POST',
  })

// ============================================================================
// Stats & History
// ============================================================================

/**
 * Fetches daily reaction stats for a specific persona.
 */
export const fetchPersonaStats = (personaId: string): Promise<PersonaReactionStats> =>
  apiFetch<PersonaReactionStats>(`/api/persona/reactions/stats/${personaId}`)

/**
 * Fetches reaction execution history for a specific persona.
 */
export const fetchReactionHistory = (personaId: string, limit = 50): Promise<PersonaReactionLog[]> =>
  apiFetch<PersonaReactionLog[]>(`/api/persona/reactions/stats/${personaId}/history?limit=${limit}`)

/**
 * Fetches system-wide reaction health stats.
 */
export const fetchHealth = (): Promise<ReactionSystemHealth> =>
  apiFetch<ReactionSystemHealth>('/api/persona/reactions/health')

// ============================================================================
// Convenience
// ============================================================================

/**
 * Fetches all data needed for the reaction dashboard.
 * Returns configs and health in parallel.
 */
export async function fetchDashboardData(): Promise<{
  configs: PersonaReactionConfig[]
  health: ReactionSystemHealth
}> {
  const [configs, health] = await Promise.all([fetchAllConfigs(), fetchHealth()])
  return { configs, health }
}
