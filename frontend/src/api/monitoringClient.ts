/**
 * API client for system monitoring endpoints
 */

const API_BASE = import.meta.env.VITE_API_BASE || ''

// ============================================================
// Types
// ============================================================

export interface TdLibLogMetrics {
  totalMessages: number
  filteredMessages: number
  dialogDateWarnings: number
  filterRatio: number
}

export interface CoordinatorStatus {
  state: string
  operationInProgress: boolean
  currentOperation: string | null
  operationDuration: string
}

export interface SchedulerStatus {
  enabled: boolean
  totalPersonas: number
  enabledPersonas: number
  lastRunAt: string | null
  digestsGeneratedToday: number
  // Legacy fields for backward compatibility (may not be returned)
  schedulerEnabled?: boolean
  clusteringJobEnabled?: boolean
  digestJobEnabled?: boolean
}

export interface ScoringStatus {
  messagesWithImportance: number
  clusteredMessages: number
  channelsWithScore: number
  lastPythonRun: string
}

export interface ScoringRefreshResult {
  status: 'success' | 'error'
  message: string
  parameters?: {
    windowDays: number
    halfLifeHours: number
    limit: number
  }
  executionTimeMs: number
}

export interface SystemHealth {
  status: 'UP' | 'DOWN' | 'DEGRADED' | 'UNKNOWN'
  components: Record<string, { status: string; details?: Record<string, unknown> }>
}

export interface TelegramClientInfo {
  id: number
  name: string
  username: string
  isBot: boolean
  userType: string
  botId?: string
  status?: string
  error?: string
}

// ============================================================
// TDLib Monitoring
// ============================================================

export async function fetchTdLibLogMetrics(): Promise<TdLibLogMetrics> {
  const res = await fetch(`${API_BASE}/api/telegram-debug/tdlib-log-metrics`)
  if (!res.ok) throw new Error(`Failed to fetch TDLib metrics: ${res.status}`)
  const text = await res.text()

  // Parse the text response into structured data
  const lines = text.split('\n')
  const metrics: TdLibLogMetrics = {
    totalMessages: 0,
    filteredMessages: 0,
    dialogDateWarnings: 0,
    filterRatio: 0,
  }

  for (const line of lines) {
    if (line.includes('Total Messages:')) {
      metrics.totalMessages = parseInt(line.split(':')[1].trim()) || 0
    } else if (line.includes('Filtered Messages:')) {
      metrics.filteredMessages = parseInt(line.split(':')[1].trim()) || 0
    } else if (line.includes('Dialog Date Warnings:')) {
      metrics.dialogDateWarnings = parseInt(line.split(':')[1].trim()) || 0
    } else if (line.includes('Filter Ratio:')) {
      const match = line.match(/([\d.]+)%/)
      metrics.filterRatio = match ? parseFloat(match[1]) : 0
    }
  }

  return metrics
}

export async function resetTdLibLogMetrics(): Promise<void> {
  const res = await fetch(`${API_BASE}/api/telegram-debug/tdlib-log-metrics/reset`, {
    method: 'POST',
  })
  if (!res.ok) throw new Error(`Failed to reset metrics: ${res.status}`)
}

export async function fetchCoordinatorStatus(): Promise<CoordinatorStatus> {
  const res = await fetch(`${API_BASE}/api/telegram-debug/coordinator-status`)
  if (!res.ok) throw new Error(`Failed to fetch coordinator status: ${res.status}`)
  const text = await res.text()

  // Parse text response
  const lines = text.split('\n')
  const status: CoordinatorStatus = {
    state: 'UNKNOWN',
    operationInProgress: false,
    currentOperation: null,
    operationDuration: 'PT0S',
  }

  for (const line of lines) {
    if (line.includes('State:')) {
      status.state = line.split(':')[1].trim()
    } else if (line.includes('Operation In Progress:')) {
      status.operationInProgress = line.split(':')[1].trim() === 'true'
    } else if (line.includes('Current Operation:')) {
      const value = line.split(':')[1].trim()
      status.currentOperation = value === 'None' ? null : value
    } else if (line.includes('Operation Duration:')) {
      status.operationDuration = line.split(':').slice(1).join(':').trim()
    }
  }

  return status
}

// ============================================================
// Telegram Client Testing
// ============================================================

export async function testGetMe(): Promise<TelegramClientInfo> {
  const res = await fetch(`${API_BASE}/api/telegram-debug/test-getme`)
  if (!res.ok) throw new Error(`GetMe test failed: ${res.status}`)
  const text = await res.text()

  // Parse text response
  const info: TelegramClientInfo = {
    id: 0,
    name: '',
    username: '',
    isBot: false,
    userType: '',
  }

  for (const line of text.split('\n')) {
    if (line.startsWith('ID:')) {
      info.id = parseInt(line.split(':')[1].trim()) || 0
    } else if (line.startsWith('Name:')) {
      info.name = line.split(':')[1].trim()
    } else if (line.startsWith('Username:')) {
      info.username = line.split(':')[1].trim().replace('@', '')
    } else if (line.startsWith('Is Bot:')) {
      info.isBot = line.split(':')[1].trim() === 'true'
    } else if (line.startsWith('User Type:')) {
      info.userType = line.split(':')[1].trim()
    }
  }

  return info
}

export async function testLoadChats(): Promise<string> {
  const res = await fetch(`${API_BASE}/api/telegram-debug/test-loadchats`, {
    method: 'POST',
  })
  return res.text()
}

export async function repairDialogState(): Promise<string> {
  const res = await fetch(`${API_BASE}/api/telegram-debug/repair-dialog-state`, {
    method: 'POST',
  })
  return res.text()
}

export async function fetchAllTelegramClients(): Promise<TelegramClientInfo[]> {
  const res = await fetch(`${API_BASE}/api/telegram-debug/clients`)
  if (!res.ok) throw new Error(`Failed to fetch clients: ${res.status}`)
  return res.json()
}

// ============================================================
// Digest Scheduler Status
// ============================================================

export async function fetchSchedulerStatus(): Promise<SchedulerStatus> {
  const res = await fetch(`${API_BASE}/api/digest/scheduler/status`)
  if (!res.ok) throw new Error(`Failed to fetch scheduler status: ${res.status}`)
  return res.json()
}

// ============================================================
// Health Check (Spring Actuator)
// ============================================================

export async function fetchSystemHealth(): Promise<SystemHealth> {
  try {
    const res = await fetch(`${API_BASE}/actuator/health`)
    if (!res.ok) {
      return { status: 'DOWN', components: {} }
    }
    const text = await res.text()
    // Check if response is JSON (not HTML error page)
    if (text.startsWith('<!') || text.startsWith('<html')) {
      console.warn('Health endpoint returned HTML, likely auth required')
      return { status: 'UNKNOWN', components: {} }
    }
    return JSON.parse(text)
  } catch (e) {
    console.error('Health check failed:', e)
    return { status: 'DOWN', components: {} }
  }
}

// ============================================================
// Bot Health (per-bot status)
// ============================================================

export interface BotStatus {
  botId: string
  primary: boolean
  initialized: boolean
  status: string
}

export interface BotHealth {
  status: 'UP' | 'DEGRADED' | 'DOWN'
  configuredCount: number
  initializedCount: number
  pendingSecondary: boolean
  bots: BotStatus[]
}

export async function fetchBotHealth(): Promise<BotHealth> {
  const res = await fetch(`${API_BASE}/api/bots/health`)
  if (!res.ok) throw new Error(`Failed to fetch bot health: ${res.status}`)
  return res.json()
}

// ============================================================
// Scoring Pipeline
// ============================================================

export async function fetchScoringStatus(): Promise<ScoringStatus> {
  const res = await fetch(`${API_BASE}/api/scoring/status`)
  if (!res.ok) throw new Error(`Failed to fetch scoring status: ${res.status}`)
  return res.json()
}

export async function triggerScoringRefresh(
  windowDays: number = 14,
  halfLifeHours: number = 12.0,
  limit: number = 500
): Promise<ScoringRefreshResult> {
  const params = new URLSearchParams({
    windowDays: windowDays.toString(),
    halfLifeHours: halfLifeHours.toString(),
    limit: limit.toString(),
  })
  const res = await fetch(`${API_BASE}/api/scoring/refresh?${params}`, {
    method: 'POST',
  })
  return res.json()
}

// ============================================================
// Combined Dashboard Data
// ============================================================

export interface MonitoringDashboard {
  tdlibMetrics: TdLibLogMetrics | null
  coordinatorStatus: CoordinatorStatus | null
  schedulerStatus: SchedulerStatus | null
  telegramClient: TelegramClientInfo | null
  telegramClients: TelegramClientInfo[]
  health: SystemHealth | null
  botHealth: BotHealth | null
  scoringStatus: ScoringStatus | null
  errors: string[]
}

export async function fetchMonitoringDashboard(): Promise<MonitoringDashboard> {
  const errors: string[] = []

  const [tdlibMetrics, coordinatorStatus, schedulerStatus, telegramClient, telegramClients, health, botHealth, scoringStatus] = await Promise.all([
    fetchTdLibLogMetrics().catch(e => { errors.push(`TDLib metrics: ${e.message}`); return null }),
    fetchCoordinatorStatus().catch(e => { errors.push(`Coordinator: ${e.message}`); return null }),
    fetchSchedulerStatus().catch(e => { errors.push(`Scheduler: ${e.message}`); return null }),
    testGetMe().catch(e => { errors.push(`Telegram client: ${e.message}`); return null }),
    fetchAllTelegramClients().catch(e => { errors.push(`All clients: ${e.message}`); return [] }),
    fetchSystemHealth().catch(e => { errors.push(`Health: ${e.message}`); return null }),
    fetchBotHealth().catch(e => { errors.push(`Bot health: ${e.message}`); return null }),
    fetchScoringStatus().catch(e => { errors.push(`Scoring: ${e.message}`); return null }),
  ])

  return {
    tdlibMetrics,
    coordinatorStatus,
    schedulerStatus,
    telegramClient,
    telegramClients: telegramClients || [],
    health,
    botHealth,
    scoringStatus,
    errors,
  }
}
