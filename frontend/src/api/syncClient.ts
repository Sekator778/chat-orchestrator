const API_BASE = import.meta.env.VITE_API_BASE ?? ''

async function apiFetch<T>(url: string, options?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE}${url}`, options)
  if (!response.ok) {
    const text = await response.text().catch(() => '')
    throw new Error(text || `Request failed: ${response.status}`)
  }
  if (response.status === 204) return undefined as T
  return (await response.json()) as T
}

export interface SyncJobDto {
  id: number
  channelId: number
  channelTitle?: string
  status: 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'FAILED' | 'CANCELLED'
  messagesProcessed: number
  messagesTotal: number | null
  completionPercentage: number | null
  errorMessage: string | null
  createdAt: string
  startedAt: string | null
  completedAt: string | null
  syncDepthDays: number | null
  botInstanceId: string | null
}

export interface SyncProgressDto {
  jobId: number
  channelId: number
  status: string
  messagesProcessed: number
  messagesTotal: number | null
  currentAction: string | null
  errorMessage: string | null
}

export interface ChannelSyncInfoDto {
  channelId: number
  title: string | null
  username: string | null
  subscribers: number | null
  weight: number | null
  syncEnabled: boolean
  joinStatus: string | null
}

export const quickScan = (chatId: number, syncDepthDays: number | null) =>
  apiFetch<SyncJobDto>('/api/sync/quick-scan', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ chatId, syncDepthDays }),
  })

export const getSyncJob = (jobId: number) =>
  apiFetch<SyncJobDto>(`/api/sync/jobs/${jobId}`)

export const cancelSyncJob = (jobId: number) =>
  apiFetch<SyncJobDto>(`/api/sync/jobs/${jobId}/cancel`, { method: 'POST' })

export const getAvailableChannels = (limit = 200) =>
  apiFetch<ChannelSyncInfoDto[]>(`/api/sync/channels/available?limit=${limit}`)

export const getActiveSyncJobs = () =>
  apiFetch<SyncJobDto[]>('/api/sync/jobs/active')

export function exportChatHistory(chatId: number, days: number): void {
  const url = `${API_BASE}/api/sync/channels/${chatId}/export?days=${days}`
  const a = document.createElement('a')
  a.href = url
  a.download = `chat-${chatId}-${days}d.md`
  a.click()
}

export interface ChatAskResponseDto {
  answer: string
  chunkIndex: number
  totalChunks: number
  hasMore: boolean
}

export const askLlmAboutChat = (
  chatId: number,
  question: string,
  days: number | null,
  chunkIndex: number,
) =>
  apiFetch<ChatAskResponseDto>(`/api/sync/channels/${chatId}/ask`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ question, days, chunkIndex }),
  })

export function subscribeToProgress(
  jobId: number,
  onProgress: (p: SyncProgressDto) => void,
  onDone: () => void,
): () => void {
  const url = `${API_BASE}/api/sync/jobs/${jobId}/progress`
  const es = new EventSource(url)
  es.onmessage = (e) => {
    try {
      const p: SyncProgressDto = JSON.parse(e.data)
      onProgress(p)
      if (p.status === 'COMPLETED' || p.status === 'FAILED' || p.status === 'CANCELLED') {
        es.close()
        onDone()
      }
    } catch {
      // ignore parse errors
    }
  }
  es.onerror = () => {
    es.close()
    onDone()
  }
  return () => es.close()
}
