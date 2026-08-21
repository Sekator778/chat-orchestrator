/**
 * Digest persona style types.
 */
export type DigestPersonaStyle =
  | 'PROFESSIONAL'
  | 'IRONIC'
  | 'BREAKING_NEWS'
  | 'TECHNICAL'
  | 'CUSTOM'

/**
 * Publish mode: structured digest with header/footer or plain opinion post.
 */
export type DigestPublishMode = 'DIGEST' | 'OPINION_POST'

/**
 * Digest status types.
 */
export type DigestStatus = 'GENERATED' | 'PUBLISHED' | 'FAILED'

/**
 * Digest persona configuration.
 */
export interface DigestPersona {
  id: number | null
  name: string
  description: string | null
  botId: number
  targetChannelId: number
  enabled: boolean
  personaStyle: DigestPersonaStyle
  customSystemPrompt: string | null
  scheduleCron: string | null
  scheduleTimezone: string
  activeHoursStart: string | null
  activeHoursEnd: string | null
  lookbackHours: number
  maxMessages: number
  language: string
  minClusterSize: number
  minImportanceScore: number
  sourceTrustThreshold: number
  excludedChannelIds: number[]
  topicKeywords: string[]
  negativeKeywords: string[]
  modelName: string | null
  temperature: number
  maxTokens: number
  lastRunAt: string | null
  totalDigestsPublished: number
  createdAt: string | null
  updatedAt: string | null
  publishMode: DigestPublishMode
  randomDelayMaxMinutes: number
}

/**
 * Digest history entry.
 */
export interface DigestHistory {
  id: number
  personaId: number
  personaName: string | null
  digestId: string
  content: string
  messagesIncluded: number
  clustersUsed: number
  generationTimeMs: number
  publishedAt: string | null
  telegramMessageId: number | null
  status: DigestStatus
  errorMessage: string | null
  createdAt: string
}

/**
 * Test digest generation request.
 */
export interface DigestTestRequest {
  lookbackHours?: number
  maxMessages?: number
  preview?: boolean
}

/**
 * Generated digest result.
 */
export interface GeneratedDigest {
  digestId: string | null
  personaId: number
  personaName: string
  content: string
  messagesIncluded: number
  clustersUsed: number
  sourceSummary: string[]
  generationTimeMs: number
  generatedAt: string
}

/**
 * Published digest result.
 */
export interface PublishedDigest {
  digestId: string | null
  personaId: number
  personaName: string
  targetChannelId: number
  telegramMessageId: number | null
  content: string | null
  messagesIncluded: number
  clustersUsed: number
  generationTimeMs: number
  publishedAt: string
  success: boolean
  errorMessage: string | null
}

/**
 * Activity entry for analytics timeline.
 */
export interface ActivityEntry {
  timestamp: string
  personaName: string
  action: string
  success: boolean
  details: string
}

/**
 * Per-persona statistics.
 */
export interface PersonaStats {
  personaId: number
  personaName: string
  enabled: boolean
  totalDigests: number
  publishedDigests: number
  failedDigests: number
  successRate: number
  avgGenerationTimeMs: number
  lastRunAt: string | null
}

/**
 * Comprehensive digest analytics.
 */
export interface DigestAnalytics {
  totalPersonas: number
  activePersonas: number
  totalDigestsGenerated: number
  totalDigestsPublished: number
  overallSuccessRate: number
  averageGenerationTimeMs: number
  messagesProcessedToday: number
  clustersFormedToday: number
  digestsPublishedToday: number
  recentActivity: ActivityEntry[]
  personaStats: PersonaStats[]
  generatedAt: string
}

/**
 * Information about a specific cluster.
 */
export interface ClusterInfo {
  clusterId: string
  messageCount: number
  primaryMessagePreview: string
  avgImportance: number
  createdAt: string
}

/**
 * Cluster statistics.
 */
export interface ClusterStats {
  totalClusters: number
  clustersToday: number
  averageClusterSize: number
  deduplicationRate: number
  unclusteredMessages: number
  processingTimeMs: number
  topClusters: ClusterInfo[]
  generatedAt: string
}

/**
 * Detailed information about a source.
 */
export interface SourceDetail {
  channelId: number
  channelTitle: string
  trustScore: number
  isOfficial: boolean
  category: string | null
  messageCount: number
  clustersContributed: number
  lastMessageAt: string | null
}

/**
 * Trust score distribution.
 */
export interface TrustDistribution {
  veryHigh: number
  high: number
  medium: number
  low: number
  veryLow: number
}

/**
 * Source trust statistics.
 */
export interface SourceStats {
  totalSources: number
  highTrustSources: number
  lowTrustSources: number
  averageTrustScore: number
  sourceDetails: SourceDetail[]
  trustDistribution: TrustDistribution
  generatedAt: string
}

/**
 * Scheduler status information.
 */
export interface SchedulerStatus {
  enabled: boolean
  totalPersonas: number
  enabledPersonas: number
  lastRunAt: string | null
  digestsGeneratedToday: number
}

/**
 * Cluster trigger response.
 */
export interface ClusterTriggerResponse {
  status: string
  message: string
}

/**
 * Request payload for creating a new persona.
 */
export interface CreatePersonaRequest {
  name: string
  description?: string | null
  botId: number
  targetChannelId: number
  enabled?: boolean
  personaStyle?: DigestPersonaStyle
  customSystemPrompt?: string | null
  scheduleCron?: string | null
  scheduleTimezone?: string
  activeHoursStart?: string | null
  activeHoursEnd?: string | null
  lookbackHours?: number
  maxMessages?: number
  language?: string
  minClusterSize?: number
  minImportanceScore?: number
  sourceTrustThreshold?: number
  excludedChannelIds?: number[]
  topicKeywords?: string[]
  negativeKeywords?: string[]
  modelName?: string | null
  temperature?: number
  maxTokens?: number
  publishMode?: DigestPublishMode
  randomDelayMaxMinutes?: number
}

/**
 * Request payload for updating a persona.
 */
export type UpdatePersonaRequest = Partial<CreatePersonaRequest>

/**
 * Publish mode options with labels.
 */
export const PUBLISH_MODES: Record<DigestPublishMode, { label: string; description: string }> = {
  DIGEST: {
    label: 'Дайджест',
    description: 'Заголовок + сводка нескольких новостей + подвал со статистикой',
  },
  OPINION_POST: {
    label: 'Мнение инвестора',
    description: '3-5 предложений от первого лица без заголовка и подвала (анти-детект)',
  },
}

/**
 * Available persona style options with labels.
 */
export const PERSONA_STYLES: Record<DigestPersonaStyle, { label: string; description: string }> = {
  PROFESSIONAL: {
    label: 'Профессиональный аналитик',
    description: 'Объективный и фактологический стиль, как у главного редактора',
  },
  IRONIC: {
    label: 'Ироничный комментатор',
    description: 'Остроумный комментарий с тонким юмором',
  },
  BREAKING_NEWS: {
    label: 'Срочные новости',
    description: 'Срочный, лаконичный стиль заголовков',
  },
  TECHNICAL: {
    label: 'Технический эксперт',
    description: 'Подробный аналитический обзор',
  },
  CUSTOM: {
    label: 'Пользовательский',
    description: 'Настраиваемый системный промпт',
  },
}

/**
 * Common language options.
 */
export const LANGUAGES = [
  { value: 'ru', label: 'Русский' },
  { value: 'en', label: 'English' },
] as const

/**
 * Common timezone options.
 */
export const TIMEZONES = [
  { value: 'UTC', label: 'UTC' },
  { value: 'Europe/Moscow', label: 'Москва (UTC+3)' },
  { value: 'Europe/Kiev', label: 'Киев (UTC+2)' },
  { value: 'Europe/London', label: 'Лондон (UTC+0/+1)' },
  { value: 'Europe/Berlin', label: 'Берлин (UTC+1/+2)' },
  { value: 'America/New_York', label: 'Нью-Йорк (UTC-5/-4)' },
  { value: 'Asia/Tokyo', label: 'Токио (UTC+9)' },
] as const

/**
 * Schedule presets for quick selection.
 */
export const SCHEDULE_PRESETS = [
  { value: '0 0 * * * *', label: 'Каждый час' },
  { value: '0 0 */2 * * *', label: 'Каждые 2 часа' },
  { value: '0 0 */4 * * *', label: 'Каждые 4 часа' },
  { value: '0 0 */6 * * *', label: 'Каждые 6 часов' },
  { value: '0 0 */12 * * *', label: 'Каждые 12 часов' },
  { value: '0 0 9 * * *', label: 'Ежедневно в 09:00' },
  { value: '0 0 9,21 * * *', label: 'В 09:00 и 21:00' },
  { value: '0 0 9 * * MON-FRI', label: 'Будни в 09:00' },
] as const

/**
 * Helper function to check if digest was published successfully.
 */
export function isPublished(history: DigestHistory): boolean {
  return history.status === 'PUBLISHED'
}

/**
 * Helper function to check if digest failed.
 */
export function isFailed(history: DigestHistory): boolean {
  return history.status === 'FAILED'
}

/**
 * Helper function to get content preview.
 */
export function contentPreview(content: string | null, maxLength: number = 100): string {
  if (!content) return ''
  if (content.length <= maxLength) return content
  return content.substring(0, maxLength) + '...'
}

/**
 * Helper to create a minimal persona for creation.
 */
export function createMinimalPersona(
  name: string,
  botId: number,
  targetChannelId: number
): CreatePersonaRequest {
  return {
    name,
    botId,
    targetChannelId,
    enabled: false,
    personaStyle: 'PROFESSIONAL',
    scheduleTimezone: 'UTC',
    lookbackHours: 24,
    maxMessages: 10,
    language: 'ru',
    minClusterSize: 2,
    minImportanceScore: 0.0,
    sourceTrustThreshold: 0.0,
    excludedChannelIds: [],
    topicKeywords: [],
    negativeKeywords: [],
    temperature: 0.7,
    maxTokens: 1000,
    publishMode: 'DIGEST',
    randomDelayMaxMinutes: 0,
  }
}

/**
 * Format generation time in human-readable format.
 */
export function formatGenerationTime(ms: number): string {
  if (ms < 1000) return `${ms}мс`
  if (ms < 60000) return `${(ms / 1000).toFixed(1)}с`
  return `${(ms / 60000).toFixed(1)}мин`
}

/**
 * Format success rate as percentage.
 */
export function formatSuccessRate(rate: number): string {
  return `${(rate * 100).toFixed(1)}%`
}
