import { useEffect, useRef, useState } from 'react'
import {
  askLlmAboutChat,
  cancelSyncJob,
  exportChatHistory,
  getActiveSyncJobs,
  getAvailableChannels,
  quickScan,
  subscribeToProgress,
  type ChannelSyncInfoDto,
  type ChatAskResponseDto,
  type SyncJobDto,
  type SyncProgressDto,
} from '../../api/syncClient'

/* ─── helpers ─────────────────────────────────────────────── */

function fmt(iso: string | null) {
  if (!iso) return '—'
  return new Date(iso).toLocaleString('ru-RU', {
    day: '2-digit', month: '2-digit', year: 'numeric',
    hour: '2-digit', minute: '2-digit', second: '2-digit',
  })
}

function elapsed(startIso: string | null, endIso?: string | null): string {
  if (!startIso) return '—'
  const start = new Date(startIso).getTime()
  const end = endIso ? new Date(endIso).getTime() : Date.now()
  const ms = end - start
  if (ms < 0) return '—'
  const s = Math.floor(ms / 1000)
  if (s < 60) return `${s} с`
  const m = Math.floor(s / 60)
  if (m < 60) return `${m} мин ${s % 60} с`
  return `${Math.floor(m / 60)} ч ${m % 60} мин`
}

const STATUS_LABEL: Record<string, string> = {
  PENDING: 'Запланировано',
  IN_PROGRESS: 'Выполняется',
  COMPLETED: 'Завершено',
  FAILED: 'Ошибка',
  CANCELLED: 'Отменено',
}

const STATUS_COLOR: Record<string, string> = {
  PENDING:     '#f59e0b',
  IN_PROGRESS: '#6366f1',
  COMPLETED:   '#22c55e',
  FAILED:      '#ef4444',
  CANCELLED:   '#94a3b8',
}

const STATUS_BG: Record<string, string> = {
  PENDING:     'rgba(245,158,11,0.08)',
  IN_PROGRESS: 'rgba(99,102,241,0.08)',
  COMPLETED:   'rgba(34,197,94,0.08)',
  FAILED:      'rgba(239,68,68,0.08)',
  CANCELLED:   'rgba(148,163,184,0.08)',
}

/* ─── depth presets ───────────────────────────────────────── */

const PRESETS: { label: string; days: number | null }[] = [
  { label: '7 дн.',  days: 7   },
  { label: '30 дн.', days: 30  },
  { label: '3 мес.', days: 90  },
  { label: '6 мес.', days: 180 },
  { label: '1 год',  days: 365 },
  { label: '2 года', days: 730 },
  { label: '5 лет',  days: 1825 },
  { label: 'Всё',    days: null },
]

/* ─── main component ──────────────────────────────────────── */

export function ScanPanel() {
  const [channels, setChannels] = useState<ChannelSyncInfoDto[]>([])
  const [selectedChatId, setSelectedChatId] = useState<number | ''>('')
  const [searchQuery, setSearchQuery] = useState('')
  const [syncDepthDays, setSyncDepthDays] = useState<number | null>(30)
  const [customDays, setCustomDays] = useState('')
  const [loading, setLoading] = useState(false)
  const [loadingChannels, setLoadingChannels] = useState(false)
  const [activeJobs, setActiveJobs] = useState<SyncJobDto[]>([])
  const [progress, setProgress] = useState<Record<number, SyncProgressDto>>({})
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const unsubscribeRefs = useRef<Record<number, () => void>>({})

  // LLM Q&A state
  const [askOpen, setAskOpen] = useState(false)
  const [question, setQuestion] = useState('')
  const [askLoading, setAskLoading] = useState(false)
  const [askResult, setAskResult] = useState<ChatAskResponseDto | null>(null)
  const [askError, setAskError] = useState<string | null>(null)
  const [askChunkIndex, setAskChunkIndex] = useState(0)

  useEffect(() => {
    loadChannels()
    refreshActiveJobs()
  }, [])

  function loadChannels() {
    setLoadingChannels(true)
    getAvailableChannels(300)
      .then(setChannels)
      .catch((e) => setError(e.message))
      .finally(() => setLoadingChannels(false))
  }

  function refreshActiveJobs() {
    getActiveSyncJobs()
      .then((jobs) => {
        setActiveJobs(jobs)
        jobs.forEach((job) => {
          if (!unsubscribeRefs.current[job.id]) subscribeJob(job.id)
        })
      })
      .catch(() => {})
  }

  function subscribeJob(jobId: number) {
    const unsub = subscribeToProgress(
      jobId,
      (p) => setProgress((prev) => ({ ...prev, [jobId]: p })),
      () => {
        delete unsubscribeRefs.current[jobId]
        setTimeout(() => refreshActiveJobs(), 600)
      },
    )
    unsubscribeRefs.current[jobId] = unsub
  }

  async function handleStart() {
    if (!selectedChatId) return
    setLoading(true)
    setError(null)
    setNotice(null)
    try {
      const job = await quickScan(Number(selectedChatId), syncDepthDays)
      setNotice(`Сканирование запущено — задание #${job.id}`)
      setActiveJobs((prev) => [job, ...prev.filter((j) => j.id !== job.id)])
      subscribeJob(job.id)
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Ошибка запуска')
    } finally {
      setLoading(false)
    }
  }

  async function handleCancel(jobId: number) {
    try {
      await cancelSyncJob(jobId)
      unsubscribeRefs.current[jobId]?.()
      delete unsubscribeRefs.current[jobId]
      refreshActiveJobs()
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Ошибка отмены')
    }
  }

  async function handleAsk(chunkIndex: number) {
    if (!selectedChatId || !question.trim()) return
    setAskLoading(true)
    setAskError(null)
    try {
      const result = await askLlmAboutChat(Number(selectedChatId), question.trim(), syncDepthDays, chunkIndex)
      setAskResult(result)
      setAskChunkIndex(result.chunkIndex)
    } catch (e: unknown) {
      setAskError(e instanceof Error ? e.message : 'Ошибка запроса')
    } finally {
      setAskLoading(false)
    }
  }

  function handleOpenAsk() {
    setAskOpen((v) => !v)
    setAskResult(null)
    setAskError(null)
    setAskChunkIndex(0)
  }

  const selected = channels.find((c) => c.channelId === Number(selectedChatId))
  const rawIdFromSearch = /^-?\d{5,}$/.test(searchQuery.trim()) ? Number(searchQuery.trim()) : null
  const rawIdNotInList = rawIdFromSearch !== null && !channels.some((c) => c.channelId === rawIdFromSearch)

  const filteredChannels = searchQuery.trim()
    ? channels.filter((c) =>
        String(c.channelId).includes(searchQuery.trim()) ||
        (c.title ?? '').toLowerCase().includes(searchQuery.trim().toLowerCase()) ||
        (c.username ?? '').toLowerCase().includes(searchQuery.trim().toLowerCase()),
      )
    : channels

  function handleSearchChange(value: string) {
    setSearchQuery(value)
    const parsed = /^-?\d{5,}$/.test(value.trim()) ? Number(value.trim()) : null
    if (parsed !== null) setSelectedChatId(parsed)
  }

  function handleDepthPreset(days: number | null) {
    setSyncDepthDays(days)
    setCustomDays(days !== null ? String(days) : '')
  }

  function handleCustomDaysChange(val: string) {
    setCustomDays(val)
    const n = parseInt(val, 10)
    if (!isNaN(n) && n >= 1) setSyncDepthDays(n)
  }

  const depthLabel = syncDepthDays === null
    ? 'вся история'
    : syncDepthDays <= 1   ? 'вчера'
    : syncDepthDays <= 7   ? 'неделя'
    : syncDepthDays <= 30  ? 'месяц'
    : syncDepthDays <= 90  ? '3 мес.'
    : syncDepthDays <= 365 ? 'год'
    : syncDepthDays <= 730 ? '2 года'
    : '> 2 лет'

  return (
    <div style={{ padding: '2rem', maxWidth: 860, margin: '0 auto' }}>

      {/* ── header ── */}
      <div style={{ marginBottom: '2rem' }}>
        <p className="eyebrow">Инструменты</p>
        <h2 style={{ margin: '0 0 6px' }}>Сканирование истории чата</h2>
        <p className="muted" style={{ fontSize: '0.9rem', maxWidth: 620 }}>
          Выберите канал, задайте глубину — бот-персона вытащит историю сообщений
          и сохранит осмысленные (≥&nbsp;4&nbsp;слова) в базу данных.
        </p>
      </div>

      {error  && <div className="notice notice--error">{error}</div>}
      {notice && <div className="notice notice--ok">{notice}</div>}

      {/* ── config card ── */}
      <div className="card" style={{ padding: '1.5rem', marginBottom: '1.5rem' }}>
        <div className="card__header">
          <strong style={{ fontSize: '0.95rem' }}>Параметры сканирования</strong>
        </div>

        <div style={{ display: 'grid', gap: '1rem' }}>

          {/* search */}
          <label className="field">
            <span className="field__label">Поиск по названию или Chat ID</span>
            <input
              type="text"
              className="input"
              placeholder="Введите название или -1001234567890"
              value={searchQuery}
              onChange={(e) => handleSearchChange(e.target.value)}
            />
          </label>

          {rawIdNotInList && rawIdFromSearch !== null && (
            <div style={{
              display: 'flex', alignItems: 'center', gap: '0.75rem',
              padding: '0.5rem 0.75rem', borderRadius: 8,
              background: 'rgba(99,102,241,0.07)', fontSize: '0.85rem',
            }}>
              <span className="chip chip--violet" style={{ fontFamily: 'monospace' }}>{rawIdFromSearch}</span>
              <span className="muted">ID не найден в списке — будет использован напрямую</span>
            </div>
          )}

          {/* channel select */}
          <label className="field">
            <span className="field__label">Канал / группа</span>
            <select
              value={selectedChatId}
              onChange={(e) => {
                setSelectedChatId(e.target.value === '' ? '' : Number(e.target.value))
                setSearchQuery('')
              }}
              className="input"
              disabled={loadingChannels}
            >
              <option value="">{loadingChannels ? 'Загружаем...' : '— выберите чат —'}</option>
              {filteredChannels.map((ch) => (
                <option key={ch.channelId} value={ch.channelId}>
                  {ch.title ?? ch.username ?? String(ch.channelId)}
                  {ch.subscribers ? ` (${ch.subscribers.toLocaleString()} подп.)` : ''}
                </option>
              ))}
            </select>
          </label>

          {selected && (
            <div className="chips">
              <span className="chip chip--violet">Chat ID: {selected.channelId}</span>
              {selected.joinStatus && <span className="chip chip--outline">{selected.joinStatus}</span>}
              {selected.username && <span className="chip chip--outline">@{selected.username}</span>}
            </div>
          )}

          {/* depth picker */}
          <div className="field">
            <span className="field__label">
              Глубина сканирования
              {' '}
              <span className="chip chip--outline" style={{ fontSize: '0.75rem', padding: '1px 8px' }}>
                {syncDepthDays !== null ? `${syncDepthDays} дн. — ` : ''}{depthLabel}
              </span>
            </span>

            {/* preset chips */}
            <div className="chips" style={{ marginTop: 6, flexWrap: 'wrap', gap: '6px' }}>
              {PRESETS.map((p) => {
                const active = p.days === syncDepthDays
                return (
                  <button
                    key={p.label}
                    type="button"
                    className={active ? 'chip chip--violet' : 'chip chip--outline'}
                    style={{
                      cursor: 'pointer', border: 'none',
                      fontWeight: active ? 600 : 400,
                      padding: '4px 12px', fontSize: '0.82rem',
                      transition: 'all 0.15s',
                    }}
                    onClick={() => handleDepthPreset(p.days)}
                  >
                    {p.label}
                  </button>
                )
              })}
            </div>

            {/* custom number input */}
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 8 }}>
              <input
                type="number"
                className="input"
                min={1}
                placeholder="Или введите кол-во дней вручную"
                value={customDays}
                onChange={(e) => handleCustomDaysChange(e.target.value)}
                style={{ maxWidth: 280 }}
              />
              {customDays && <span className="muted" style={{ fontSize: '0.8rem' }}>дн.</span>}
            </div>

            <div style={{ fontSize: '0.75rem', color: '#94a3b8', marginTop: 4 }}>
              «Всё» — синхронизирует историю до первого сообщения в чате.
              Telegram не предоставляет API для получения даты самого раннего сообщения заранее.
            </div>
          </div>

          {/* actions */}
          <div style={{ display: 'flex', gap: '0.5rem', flexWrap: 'wrap', paddingTop: '0.25rem' }}>
            <button
              className="btn btn--primary"
              onClick={handleStart}
              disabled={loading || !selectedChatId}
              style={{ minWidth: 180 }}
            >
              {loading ? '⏳ Запускаем...' : '▶ Начать сканирование'}
            </button>
            <button
              className="btn"
              onClick={() => exportChatHistory(Number(selectedChatId), syncDepthDays ?? 36500)}
              disabled={!selectedChatId}
              title="Экспорт истории чата в Markdown-файл"
            >
              ↓ Экспорт MD
            </button>
            <button
              className={askOpen ? 'btn btn--primary' : 'btn'}
              onClick={handleOpenAsk}
              disabled={!selectedChatId}
              title="Задать вопрос LLM по истории чата"
            >
              🔍 Спросить LLM
            </button>
            <button className="ghost" onClick={refreshActiveJobs} style={{ marginLeft: 'auto' }}>
              ↻ Обновить
            </button>
          </div>

          {/* LLM Q&A panel */}
          {askOpen && (
            <div style={{
              marginTop: '0.75rem',
              padding: '1rem 1.25rem',
              borderRadius: 10,
              background: 'rgba(99,102,241,0.05)',
              border: '1px solid rgba(99,102,241,0.18)',
            }}>
              <div style={{ fontWeight: 600, fontSize: '0.9rem', marginBottom: '0.5rem', color: '#4f46e5' }}>
                🤖 Вопрос к истории чата
              </div>
              <div style={{ fontSize: '0.78rem', color: '#64748b', marginBottom: '0.6rem' }}>
                Контекст: {syncDepthDays !== null ? `последние ${syncDepthDays} дн.` : 'вся история'} · только текст сообщений без имён и меток.
                Большие чаты разбиты на фрагменты — можно запросить следующий если ответ не найден.
              </div>
              <textarea
                className="input"
                rows={3}
                placeholder="Например: «Обсуждали ли здесь домашний сервер для работы 24/7? Что рекомендовали?»"
                value={question}
                onChange={(e) => {
                  setQuestion(e.target.value)
                  setAskResult(null)
                  setAskError(null)
                  setAskChunkIndex(0)
                }}
                style={{ width: '100%', resize: 'vertical', marginBottom: '0.5rem', boxSizing: 'border-box' }}
              />
              <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'center', flexWrap: 'wrap' }}>
                <button
                  className="btn btn--primary"
                  disabled={askLoading || !question.trim()}
                  onClick={() => handleAsk(0)}
                  style={{ minWidth: 160 }}
                >
                  {askLoading ? '⏳ Анализируем...' : '🔍 Спросить (фр. 1)'}
                </button>
                {askResult?.hasMore && (
                  <button
                    className="btn"
                    disabled={askLoading}
                    onClick={() => handleAsk(askChunkIndex + 1)}
                  >
                    → Следующий фрагмент ({askChunkIndex + 2}/{askResult.totalChunks})
                  </button>
                )}
                {askResult && (
                  <span style={{ fontSize: '0.75rem', color: '#94a3b8', marginLeft: 'auto' }}>
                    Фр. {askResult.chunkIndex + 1}/{askResult.totalChunks}
                  </span>
                )}
              </div>

              {askError && (
                <div style={{
                  marginTop: '0.6rem', fontSize: '0.82rem', color: '#ef4444',
                  background: 'rgba(239,68,68,0.07)', padding: '0.4rem 0.6rem', borderRadius: 6,
                }}>
                  {askError}
                </div>
              )}

              {askResult && (
                <div style={{
                  marginTop: '0.75rem',
                  padding: '0.75rem 1rem',
                  background: '#fff',
                  borderRadius: 8,
                  border: '1px solid #e2e8f0',
                  fontSize: '0.88rem',
                  lineHeight: 1.65,
                  color: '#1e293b',
                  whiteSpace: 'pre-wrap',
                }}>
                  {askResult.answer}
                </div>
              )}
            </div>
          )}
        </div>
      </div>

      {/* ── active jobs ── */}
      {activeJobs.length > 0 && (
        <div>
          <h3 style={{ margin: '0 0 0.75rem', fontSize: '1rem', color: '#374151' }}>
            Активные задания
            <span className="chip chip--outline" style={{ marginLeft: 8 }}>{activeJobs.length}</span>
          </h3>
          <div style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem' }}>
            {activeJobs.map((job) => (
              <JobCard
                key={job.id}
                job={job}
                live={progress[job.id]}
                onCancel={handleCancel}
              />
            ))}
          </div>
        </div>
      )}

      {activeJobs.length === 0 && (
        <div style={{
          textAlign: 'center', padding: '2.5rem 1rem',
          color: '#94a3b8', fontSize: '0.9rem',
          border: '1px dashed #e2e8f0', borderRadius: 14,
        }}>
          Нет активных заданий
        </div>
      )}

      <div style={{ marginTop: '0.75rem' }}>
        <button className="ghost" style={{ fontSize: '0.8rem' }} onClick={loadChannels} disabled={loadingChannels}>
          ↻ Обновить список каналов
        </button>
      </div>
    </div>
  )
}

/* ─── job card ────────────────────────────────────────────── */

function JobCard({
  job,
  live,
  onCancel,
}: {
  job: SyncJobDto
  live?: SyncProgressDto
  onCancel: (id: number) => void
}) {
  const [tick, setTick] = useState(0)

  const status = live?.status ?? job.status
  const isRunning = status === 'IN_PROGRESS'
  const isActive  = status === 'PENDING' || status === 'IN_PROGRESS'

  // Live elapsed ticker
  useEffect(() => {
    if (!isRunning) return
    const id = setInterval(() => setTick((t) => t + 1), 1000)
    return () => clearInterval(id)
  }, [isRunning])

  const processed = live?.messagesProcessed ?? job.messagesProcessed ?? 0
  const total     = live?.messagesTotal ?? job.messagesTotal ?? 0
  const action    = live?.currentAction

  const color  = STATUS_COLOR[status] ?? '#94a3b8'
  const bg     = STATUS_BG[status]    ?? 'rgba(148,163,184,0.08)'
  const label  = STATUS_LABEL[status] ?? status

  const pct = total > 0 ? Math.min(100, Math.round((processed / total) * 100)) : null

  const startedAt   = job.startedAt
  const completedAt = job.completedAt
  const duration    = elapsed(startedAt, completedAt ?? (isRunning ? undefined : completedAt))

  return (
    <div
      className="card"
      style={{
        padding: '1.25rem',
        borderLeft: `3px solid ${color}`,
        background: `linear-gradient(135deg, ${bg} 0%, #fff 60%)`,
      }}
    >
      {/* top row */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 8 }}>
        <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 8 }}>
          <strong style={{ fontSize: '0.95rem' }}>Задание #{job.id}</strong>
          <span
            className="chip"
            style={{
              background: bg,
              color: color,
              border: `1px solid ${color}`,
              fontWeight: 600,
              fontSize: '0.75rem',
            }}
          >
            {isRunning && <span style={{ marginRight: 4 }}>●</span>}
            {label}
          </span>
          {job.channelTitle && (
            <span className="muted" style={{ fontSize: '0.85rem' }}>{job.channelTitle}</span>
          )}
        </div>
        {isActive && (
          <button
            className="ghost ghost--danger"
            style={{ flexShrink: 0, fontSize: '0.8rem', padding: '4px 10px' }}
            onClick={() => onCancel(job.id)}
          >
            Отмена
          </button>
        )}
      </div>

      {/* progress bar */}
      {isRunning && (
        <div style={{
          marginTop: '0.75rem',
          height: 4, borderRadius: 9999,
          background: 'rgba(99,102,241,0.12)',
          overflow: 'hidden',
        }}>
          <div
            style={{
              height: '100%',
              width: pct !== null ? `${pct}%` : '100%',
              borderRadius: 9999,
              background: 'linear-gradient(90deg, #6366f1, #a855f7)',
              transition: 'width 0.4s ease',
              animation: pct === null ? 'pulse-bar 1.6s ease-in-out infinite' : undefined,
            }}
          />
        </div>
      )}

      {/* stats grid */}
      <div style={{
        display: 'grid',
        gridTemplateColumns: 'repeat(auto-fit, minmax(130px, 1fr))',
        gap: '0.5rem 1rem',
        marginTop: '0.75rem',
      }}>
        <Stat label="Сохранено" value={processed.toLocaleString('ru-RU')} accent />
        {total > 0 && <Stat label="Просмотрено" value={total.toLocaleString('ru-RU')} />}
        <Stat label="Глубина" value={job.syncDepthDays ? `${job.syncDepthDays} дн.` : 'вся история'} />
        {pct !== null && isRunning && <Stat label="Прогресс" value={`${pct}%`} />}
      </div>

      {/* timeline */}
      <div style={{
        display: 'grid',
        gridTemplateColumns: 'repeat(auto-fit, minmax(150px, 1fr))',
        gap: '0.35rem 1rem',
        marginTop: '0.75rem',
        paddingTop: '0.75rem',
        borderTop: '1px solid rgba(0,0,0,0.05)',
        fontSize: '0.78rem',
        color: '#64748b',
      }}>
        <TimeRow icon="🕐" label="Создано"    value={fmt(job.createdAt)} />
        {startedAt   && <TimeRow icon="▶" label="Начато"     value={fmt(startedAt)} />}
        {completedAt && <TimeRow icon="✓" label="Завершено"  value={fmt(completedAt)} />}
        {(startedAt || isRunning) && (
          <TimeRow
            icon="⏱"
            label={isRunning ? 'Идёт' : 'Длительность'}
            value={
              isRunning
                ? elapsed(startedAt) + ' …'  // tick forces re-render
                : duration
            }
            key={tick}
          />
        )}
      </div>

      {/* current action */}
      {action && isRunning && (
        <div style={{
          marginTop: '0.5rem', fontSize: '0.78rem',
          color: '#6366f1', display: 'flex', alignItems: 'center', gap: 6,
        }}>
          <span style={{ animation: 'spin 1.2s linear infinite', display: 'inline-block' }}>↻</span>
          {action}
        </div>
      )}

      {/* error */}
      {status === 'FAILED' && job.errorMessage && (
        <div style={{
          marginTop: '0.5rem', fontSize: '0.8rem',
          color: '#ef4444', padding: '0.4rem 0.6rem',
          background: 'rgba(239,68,68,0.07)', borderRadius: 6,
        }}>
          {job.errorMessage}
        </div>
      )}
    </div>
  )
}

function Stat({ label, value, accent }: { label: string; value: string; accent?: boolean }) {
  return (
    <div>
      <div style={{ fontSize: '0.7rem', color: '#94a3b8', marginBottom: 1, textTransform: 'uppercase', letterSpacing: '0.06em' }}>
        {label}
      </div>
      <div style={{ fontWeight: 600, fontSize: '0.95rem', color: accent ? '#6366f1' : '#1e293b' }}>
        {value}
      </div>
    </div>
  )
}

function TimeRow({ icon, label, value }: { icon: string; label: string; value: string }) {
  return (
    <div style={{ display: 'flex', gap: 5, alignItems: 'baseline' }}>
      <span style={{ fontSize: '0.7rem' }}>{icon}</span>
      <span style={{ color: '#94a3b8', minWidth: 72 }}>{label}:</span>
      <span style={{ color: '#374151', fontVariantNumeric: 'tabular-nums' }}>{value}</span>
    </div>
  )
}
