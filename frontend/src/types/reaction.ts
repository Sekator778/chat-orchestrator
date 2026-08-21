/** Reaction execution status. */
export type ReactionStatus = 'PENDING' | 'DONE' | 'FAILED' | 'SKIPPED' | 'FLOOD_WAIT'

/** Per-persona-per-channel reaction configuration row from backend. */
export interface PersonaReactionConfig {
  id: number | null
  personaId: string
  channelId: number
  maxPerDay: number
  enabled: boolean
  createdAt: string | null
  updatedAt: string | null
}

/** Reaction execution log entry. */
export interface PersonaReactionLog {
  id: number
  personaId: string
  channelId: number
  messageId: number
  reactionEmoji: string
  scheduledAt: string
  executedAt: string | null
  status: ReactionStatus
  errorMessage: string | null
  attemptCount: number
  createdAt: string
}

/** System health stats returned by /health endpoint. */
export interface ReactionSystemHealth {
  pendingCount: number
  doneToday: number
  failedToday: number
  floodWaitToday: number
  enabledConfigs: number
  totalConfigs: number
}

/** Per-persona daily stats. */
export interface PersonaReactionStats {
  personaId: string
  doneToday: number
  failedToday: number
  floodWaitToday: number
}

/** Create config request. */
export interface CreateReactionConfigRequest {
  personaId: string
  channelId: number
  maxPerDay?: number
  enabled?: boolean
}

/** Update config request. */
export type UpdateReactionConfigRequest = Partial<Pick<CreateReactionConfigRequest, 'maxPerDay' | 'enabled'>>

/** Known bot instances. */
export const BOT_INSTANCES = [
  { id: '2000000001', name: 'Persona One' },
  { id: '2000000002', name: 'Persona Two' },
] as const

/** Status display metadata. */
export const REACTION_STATUS_MAP: Record<ReactionStatus, { label: string; chipClass: string }> = {
  PENDING:    { label: 'Ожидает',    chipClass: 'chip--outline' },
  DONE:       { label: 'Выполнено',  chipClass: 'chip--green' },
  FAILED:     { label: 'Ошибка',     chipClass: 'chip--warn' },
  SKIPPED:    { label: 'Пропущено',  chipClass: 'chip--outline' },
  FLOOD_WAIT: { label: 'Flood Wait', chipClass: 'chip--violet' },
}

/** Format ISO date string to readable local time. */
export function formatDateTime(iso: string | null): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleString('ru-RU', { dateStyle: 'short', timeStyle: 'short' })
}

/** Get persona name by ID. */
export function personaName(id: string): string {
  return BOT_INSTANCES.find(b => b.id === id)?.name ?? id
}
