import type {
  BasicConfigUpdate,
  ChannelView,
  ChannelOverview,
  ContextSettings,
  EnhancedChatConfig,
  LlmParameters,
  MessageCountResponse,
  MessagePurgeRequest,
  MessagePurgeResult,
  DbQueryRequest,
  DbQueryResponse,
  DbSchema,
  DbTable,
  DbTableMeta,
  PendingResponseConfigUpdate,
  RateLimits,
  ResponseTemplate,
  SearchConfig,
  TopicRestriction,
  TriggerCondition,
  Persona,
  PersonaBundle,
  ConfigValidationRequest,
  ConfigValidationResponse,
  EntityValidationResult,
} from '../types/api'

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

export async function fetchChannels(): Promise<ChannelView[]> {
  const response = await fetch(withBase('/api/startup-sync/discover-chats'), {
    headers: { Accept: 'application/x-ndjson,text/plain' },
  })
  if (!response.ok) {
    const text = await response.text()
    throw new Error(text || 'Не удалось получить список чатов')
  }
  const raw = await response.text()
  return raw
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => JSON.parse(line) as ChannelView)
}

export const fetchChannelOverview = () =>
  apiFetch<ChannelOverview[]>('/api/admin/config/channels/overview')

export const fetchEnhancedConfig = (channelId: number) =>
  apiFetch<EnhancedChatConfig>(`/api/admin/config/channels/${channelId}/enhanced`)

export const updateBasicConfig = (channelId: number, payload: BasicConfigUpdate) =>
  apiFetch(`/api/admin/config/channels/${channelId}/basic`, {
    method: 'PUT',
    headers: jsonHeaders,
    body: JSON.stringify(payload),
  })

export const updatePendingResponseConfig = (channelId: number, payload: PendingResponseConfigUpdate) =>
  apiFetch(`/api/admin/config/channels/${channelId}/pending-response`, {
    method: 'PUT',
    headers: jsonHeaders,
    body: JSON.stringify(payload),
  })

export const fetchMessageCount = (chatId: number) =>
  apiFetch<MessageCountResponse>(`/api/admin/db/messages/${chatId}/count`)

export const purgeMessages = (payload: MessagePurgeRequest) =>
  apiFetch<MessagePurgeResult>(`/api/admin/db/messages/purge`, {
    method: 'POST',
    headers: jsonHeaders,
    body: JSON.stringify(payload),
  })

// DB Explorer (read-only)
export const fetchDbSchemas = () => apiFetch<DbSchema[]>('/api/admin/db/explorer/schemas')
export const fetchDbTables = (schema: string) =>
  apiFetch<DbTable[]>(`/api/admin/db/explorer/schemas/${schema}/tables`)
export const fetchDbTableMeta = (schema: string, table: string) =>
  apiFetch<DbTableMeta>(`/api/admin/db/explorer/schemas/${schema}/tables/${table}/meta`)
export const runDbQuery = (payload: DbQueryRequest) =>
  apiFetch<DbQueryResponse>('/api/admin/db/explorer/query', {
    method: 'POST',
    headers: jsonHeaders,
    body: JSON.stringify(payload),
  })

export const updateContextSettings = (channelId: number, payload: ContextSettings) =>
  apiFetch(`/api/admin/config/channels/${channelId}/context`, {
    method: 'PUT',
    headers: jsonHeaders,
    body: JSON.stringify(payload),
  })

export const updateLlmParameters = (channelId: number, payload: LlmParameters) =>
  apiFetch(`/api/admin/config/channels/${channelId}/llm-params`, {
    method: 'PUT',
    headers: jsonHeaders,
    body: JSON.stringify(payload),
  })

export const updateRateLimits = (channelId: number, payload: RateLimits) =>
  apiFetch(`/api/admin/config/channels/${channelId}/rate-limits`, {
    method: 'PUT',
    headers: jsonHeaders,
    body: JSON.stringify(payload),
  })

export const resetRateLimits = (channelId: number) =>
  apiFetch(`/api/admin/config/channels/${channelId}/rate-limits/reset`, {
    method: 'POST',
  })

export const createTemplate = (channelId: number, payload: Partial<ResponseTemplate>) =>
  apiFetch<ResponseTemplate>(`/api/admin/config/channels/${channelId}/templates`, {
    method: 'POST',
    headers: jsonHeaders,
    body: JSON.stringify(payload),
  })

export const updateTemplate = (templateId: number, payload: Partial<ResponseTemplate>) =>
  apiFetch<ResponseTemplate>(`/api/admin/config/templates/${templateId}`, {
    method: 'PUT',
    headers: jsonHeaders,
    body: JSON.stringify(payload),
  })

export const deleteTemplate = (templateId: number) =>
  apiFetch(`/api/admin/config/templates/${templateId}`, { method: 'DELETE' })

export const setDefaultTemplate = (templateId: number) =>
  apiFetch<ResponseTemplate>(`/api/admin/config/templates/${templateId}/set-default`, {
    method: 'POST',
  })

export const createTrigger = (channelId: number, payload: Partial<TriggerCondition>) =>
  apiFetch<TriggerCondition>(`/api/admin/config/channels/${channelId}/triggers`, {
    method: 'POST',
    headers: jsonHeaders,
    body: JSON.stringify(payload),
  })

export const updateTrigger = (triggerId: number, payload: Partial<TriggerCondition>) =>
  apiFetch<TriggerCondition>(`/api/admin/config/triggers/${triggerId}`, {
    method: 'PUT',
    headers: jsonHeaders,
    body: JSON.stringify(payload),
  })

export const toggleTrigger = (triggerId: number) =>
  apiFetch<TriggerCondition>(`/api/admin/config/triggers/${triggerId}/toggle`, { method: 'POST' })

export const deleteTrigger = (triggerId: number) =>
  apiFetch(`/api/admin/config/triggers/${triggerId}`, { method: 'DELETE' })

export const createRestriction = (channelId: number, payload: Partial<TopicRestriction>) =>
  apiFetch<TopicRestriction>(`/api/admin/config/channels/${channelId}/restrictions`, {
    method: 'POST',
    headers: jsonHeaders,
    body: JSON.stringify(payload),
  })

export const updateRestriction = (restrictionId: number, payload: Partial<TopicRestriction>) =>
  apiFetch<TopicRestriction>(`/api/admin/config/restrictions/${restrictionId}`, {
    method: 'PUT',
    headers: jsonHeaders,
    body: JSON.stringify(payload),
  })

export const toggleRestriction = (restrictionId: number) =>
  apiFetch<TopicRestriction>(`/api/admin/config/restrictions/${restrictionId}/toggle`, {
    method: 'POST',
  })

export const deleteRestriction = (restrictionId: number) =>
  apiFetch(`/api/admin/config/restrictions/${restrictionId}`, { method: 'DELETE' })

export const fetchSearchConfig = async (chatId: number): Promise<SearchConfig | null> => {
  const response = await fetch(withBase(`/api/v1/search/config/${chatId}`))
  if (response.status === 404) {
    return null
  }
  if (!response.ok) {
    const text = await response.text()
    throw new Error(text || 'Не удалось загрузить SearchConfig')
  }
  return (await response.json()) as SearchConfig
}

export const saveSearchConfig = (config: SearchConfig) => {
  const method = config.id ? 'PUT' : 'POST'
  const url = config.id ? `/api/v1/search/config/${config.chat_id}` : '/api/v1/search/config'
  return apiFetch<SearchConfig>(url, {
    method,
    headers: jsonHeaders,
    body: JSON.stringify(config),
  })
}

// Persona
export const fetchPersonaBundles = () => apiFetch<PersonaBundle[]>('/api/admin/persona')
export const fetchPersonasForBot = (botId: string) =>
  apiFetch<Persona[]>(`/api/admin/persona/${botId}`)
export const fetchPersona = (botId: string, lang: string) =>
  apiFetch<Persona>(`/api/admin/persona/${botId}/${lang}`)
export const savePersona = (botId: string, lang: string, dto: Persona) =>
  apiFetch<Persona>(`/api/admin/persona/${botId}/${lang}`, {
    method: 'PUT',
    headers: jsonHeaders,
    body: JSON.stringify(dto),
  })

// Configuration Validation
export const validateConfigs = (payload: ConfigValidationRequest) =>
  apiFetch<ConfigValidationResponse>('/api/config/validate', {
    method: 'POST',
    headers: jsonHeaders,
    body: JSON.stringify(payload),
  })

export const validateChannel = (channelId: number) =>
  apiFetch<EntityValidationResult>(`/api/config/validate/channel/${channelId}`)

export const validateDigestPersona = (personaId: number) =>
  apiFetch<EntityValidationResult>(`/api/config/validate/digest-persona/${personaId}`)

export const previewConfigValidation = (channelId: number) =>
  apiFetch<ConfigValidationResponse>(`/api/config/validate/preview/${channelId}`)

// Digest Management - re-export from digestClient
export * from './digestClient'
