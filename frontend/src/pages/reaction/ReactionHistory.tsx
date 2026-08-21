import { useEffect, useState, useCallback } from 'react'
import type { PersonaReactionLog } from '../../types/reaction'
import { BOT_INSTANCES, formatDateTime, personaName } from '../../types/reaction'
import { fetchReactionHistory } from '../../api/reactionClient'
import { ReactionStatusBadge } from '../../components/reaction/ReactionStatusBadge'

type NoticeTone = 'ok' | 'warn' | 'error'

interface Notice {
  message: string
  tone: NoticeTone
}

function formatDelay(scheduledAt: string, executedAt: string | null): string {
  if (!executedAt) return '—'
  const diff = new Date(executedAt).getTime() - new Date(scheduledAt).getTime()
  if (diff < 0) return '—'
  const secs = Math.floor(diff / 1000)
  if (secs < 60) return `${secs}с`
  const mins = Math.floor(secs / 60)
  return `${mins}м ${secs % 60}с`
}

export function ReactionHistory() {
  const [logs, setLogs] = useState<PersonaReactionLog[]>([])
  const [loading, setLoading] = useState(true)
  const [notice, setNotice] = useState<Notice | null>(null)
  const [filterPersona, setFilterPersona] = useState<string>(BOT_INSTANCES[0].id)

  const loadHistory = useCallback(async () => {
    try {
      setLoading(true)
      const data = await fetchReactionHistory(filterPersona, 50)
      setLogs(data)
      setNotice(null)
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Не удалось загрузить историю'
      setNotice({ tone: 'error', message })
    } finally {
      setLoading(false)
    }
  }, [filterPersona])

  useEffect(() => {
    loadHistory()
  }, [loadHistory])

  useEffect(() => {
    const interval = setInterval(() => {
      loadHistory()
    }, 30000)
    return () => clearInterval(interval)
  }, [loadHistory])

  return (
    <div className="reaction-history-page">
      {notice && (
        <div className={`notice notice--${notice.tone}`}>
          {notice.message}
        </div>
      )}

      <div className="reaction-history-page__header">
        <div>
          <p className="eyebrow">Журнал</p>
          <h2>История реакций</h2>
          <p className="muted">Обновляется автоматически каждые 30 секунд</p>
        </div>
      </div>

      <div className="reaction-history-page__filters">
        <select
          value={filterPersona}
          onChange={(e) => setFilterPersona(e.target.value)}
        >
          {BOT_INSTANCES.map(bot => (
            <option key={bot.id} value={bot.id}>{bot.name}</option>
          ))}
        </select>
        <button className="ghost" onClick={loadHistory} disabled={loading}>
          {loading ? 'Загрузка...' : 'Обновить'}
        </button>
      </div>

      {loading && logs.length === 0 ? (
        <div className="placeholder">Загрузка истории...</div>
      ) : logs.length === 0 ? (
        <div className="placeholder">
          <p className="muted">Нет записей в истории для {personaName(filterPersona)}</p>
        </div>
      ) : (
        <div style={{ overflowX: 'auto' }}>
          <table className="reaction-history-table">
            <thead>
              <tr>
                <th>Дата</th>
                <th>Персона</th>
                <th>Канал</th>
                <th>Emoji</th>
                <th>Статус</th>
                <th>Задержка</th>
                <th>Ошибка</th>
              </tr>
            </thead>
            <tbody>
              {logs.map(log => (
                <tr key={log.id}>
                  <td className="tiny">{formatDateTime(log.scheduledAt)}</td>
                  <td className="tiny">{personaName(log.personaId)}</td>
                  <td className="tiny">{log.channelId}</td>
                  <td style={{ fontSize: '1.25rem' }}>{log.reactionEmoji}</td>
                  <td>
                    <ReactionStatusBadge status={log.status} />
                  </td>
                  <td className="tiny">{formatDelay(log.scheduledAt, log.executedAt)}</td>
                  <td className="tiny muted">{log.errorMessage ?? '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
