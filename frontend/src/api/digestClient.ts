import type {
  DigestPersona,
  DigestHistory,
  DigestTestRequest,
  GeneratedDigest,
  PublishedDigest,
  DigestAnalytics,
  ClusterStats,
  SourceStats,
  SchedulerStatus,
  ClusterTriggerResponse,
  CreatePersonaRequest,
  UpdatePersonaRequest,
  ActivityEntry,
} from '../types/digest'

const API_BASE = import.meta.env.VITE_API_BASE ?? ''

const jsonHeaders = { 'Content-Type': 'application/json' }

const withBase = (path: string) => `${API_BASE}${path}`

async function apiFetch<T>(url: string, options?: RequestInit): Promise<T> {
  const response = await fetch(withBase(url), options)
  if (!response.ok) {
    const text = await response.text()
    throw new Error(text || `Request failed: ${response.status}`)
  }
  if (response.status === 204) {
    return undefined as T
  }
  return (await response.json()) as T
}

// ============================================================================
// Persona CRUD Operations
// ============================================================================

/**
 * Fetches all digest personas.
 */
export const fetchPersonas = (): Promise<DigestPersona[]> =>
  apiFetch<DigestPersona[]>('/api/digest/personas')

/**
 * Fetches a single persona by ID.
 */
export const fetchPersona = (id: number): Promise<DigestPersona> =>
  apiFetch<DigestPersona>(`/api/digest/personas/${id}`)

/**
 * Creates a new digest persona.
 */
export const createPersona = (data: CreatePersonaRequest): Promise<DigestPersona> =>
  apiFetch<DigestPersona>('/api/digest/personas', {
    method: 'POST',
    headers: jsonHeaders,
    body: JSON.stringify(data),
  })

/**
 * Updates an existing digest persona.
 */
export const updatePersona = (id: number, data: UpdatePersonaRequest): Promise<DigestPersona> =>
  apiFetch<DigestPersona>(`/api/digest/personas/${id}`, {
    method: 'PUT',
    headers: jsonHeaders,
    body: JSON.stringify(data),
  })

/**
 * Deletes a digest persona.
 */
export const deletePersona = (id: number): Promise<void> =>
  apiFetch<void>(`/api/digest/personas/${id}`, {
    method: 'DELETE',
  })

/**
 * Enables a digest persona.
 */
export const enablePersona = (id: number): Promise<DigestPersona> =>
  apiFetch<DigestPersona>(`/api/digest/personas/${id}/enable`, {
    method: 'POST',
  })

/**
 * Disables a digest persona.
 */
export const disablePersona = (id: number): Promise<DigestPersona> =>
  apiFetch<DigestPersona>(`/api/digest/personas/${id}/disable`, {
    method: 'POST',
  })

// ============================================================================
// Digest Operations
// ============================================================================

/**
 * Generates a test digest for a persona without publishing.
 */
export const generateTestDigest = (
  personaId: number,
  request?: DigestTestRequest
): Promise<GeneratedDigest> =>
  apiFetch<GeneratedDigest>(`/api/digest/personas/${personaId}/test`, {
    method: 'POST',
    headers: jsonHeaders,
    body: JSON.stringify(request ?? { preview: true }),
  })

/**
 * Generates and publishes a digest immediately.
 */
export const publishDigestNow = (
  personaId: number,
  lookbackHours?: number
): Promise<PublishedDigest> => {
  const params = lookbackHours ? `?lookbackHours=${lookbackHours}` : ''
  return apiFetch<PublishedDigest>(`/api/digest/personas/${personaId}/publish${params}`, {
    method: 'POST',
  })
}

/**
 * Republishes an existing digest by its ID.
 */
export const republishDigest = (digestId: string): Promise<PublishedDigest> =>
  apiFetch<PublishedDigest>(`/api/digest/republish/${digestId}`, {
    method: 'POST',
  })

// ============================================================================
// History Operations
// ============================================================================

/**
 * Fetches digest history for a specific persona.
 */
export const fetchPersonaHistory = (personaId: number, limit: number = 20): Promise<DigestHistory[]> =>
  apiFetch<DigestHistory[]>(`/api/digest/personas/${personaId}/history?limit=${limit}`)

/**
 * Fetches all digest history.
 */
export const fetchAllHistory = (limit: number = 50): Promise<DigestHistory[]> =>
  apiFetch<DigestHistory[]>(`/api/digest/history?limit=${limit}`)

// ============================================================================
// Schedule Operations
// ============================================================================

/**
 * Fetches next scheduled run times for a persona.
 */
export const fetchPersonaSchedule = (personaId: number, count: number = 5): Promise<string[]> =>
  apiFetch<string[]>(`/api/digest/personas/${personaId}/schedule?count=${count}`)

/**
 * Gets the scheduler status.
 */
export const fetchSchedulerStatus = (): Promise<SchedulerStatus> =>
  apiFetch<SchedulerStatus>('/api/digest/scheduler/status')

// ============================================================================
// System Operations
// ============================================================================

/**
 * Triggers the clustering job manually.
 */
export const triggerClustering = (): Promise<ClusterTriggerResponse> =>
  apiFetch<ClusterTriggerResponse>('/api/digest/cluster-now', {
    method: 'POST',
  })

/**
 * Triggers digest generation for all enabled personas.
 */
export const triggerAllPersonas = (): Promise<PublishedDigest[]> =>
  apiFetch<PublishedDigest[]>('/api/digest/trigger-all', {
    method: 'POST',
  })

// ============================================================================
// Analytics Operations
// ============================================================================

/**
 * Fetches comprehensive digest analytics.
 */
export const fetchAnalytics = (lookbackHours: number = 24): Promise<DigestAnalytics> =>
  apiFetch<DigestAnalytics>(`/api/digest/analytics?lookbackHours=${lookbackHours}`)

/**
 * Fetches cluster statistics.
 */
export const fetchClusterStats = (lookbackHours: number = 24): Promise<ClusterStats> =>
  apiFetch<ClusterStats>(`/api/digest/analytics/clusters?lookbackHours=${lookbackHours}`)

/**
 * Fetches source trust statistics.
 */
export const fetchSourceStats = (): Promise<SourceStats> =>
  apiFetch<SourceStats>('/api/digest/analytics/sources')

/**
 * Fetches recent activity timeline.
 */
export const fetchRecentActivity = (limit: number = 20): Promise<ActivityEntry[]> =>
  apiFetch<ActivityEntry[]>(`/api/digest/analytics/activity?limit=${limit}`)

// ============================================================================
// Convenience Functions
// ============================================================================

/**
 * Fetches all data needed for the digest dashboard.
 * Returns personas, analytics, and scheduler status in parallel.
 */
export async function fetchDashboardData(): Promise<{
  personas: DigestPersona[]
  analytics: DigestAnalytics
  schedulerStatus: SchedulerStatus
}> {
  const [personas, analytics, schedulerStatus] = await Promise.all([
    fetchPersonas(),
    fetchAnalytics(),
    fetchSchedulerStatus(),
  ])
  return { personas, analytics, schedulerStatus }
}

/**
 * Fetches full persona details including history and schedule.
 */
export async function fetchPersonaDetails(
  personaId: number
): Promise<{
  persona: DigestPersona
  history: DigestHistory[]
  schedule: string[]
}> {
  const [persona, history, schedule] = await Promise.all([
    fetchPersona(personaId),
    fetchPersonaHistory(personaId),
    fetchPersonaSchedule(personaId),
  ])
  return { persona, history, schedule }
}

/**
 * Fetches all analytics data.
 */
export async function fetchFullAnalytics(lookbackHours: number = 24): Promise<{
  analytics: DigestAnalytics
  clusterStats: ClusterStats
  sourceStats: SourceStats
}> {
  const [analytics, clusterStats, sourceStats] = await Promise.all([
    fetchAnalytics(lookbackHours),
    fetchClusterStats(lookbackHours),
    fetchSourceStats(),
  ])
  return { analytics, clusterStats, sourceStats }
}

/**
 * Toggles persona enabled state.
 */
export async function togglePersonaEnabled(persona: DigestPersona): Promise<DigestPersona> {
  if (persona.id === null) {
    throw new Error('Cannot toggle persona without ID')
  }
  return persona.enabled ? disablePersona(persona.id) : enablePersona(persona.id)
}
