import { useEffect, useState, useCallback } from 'react'
import type { DigestHistory as DigestHistoryData, DigestPersona } from '../../types/digest'
import { formatGenerationTime, contentPreview } from '../../types/digest'
import { fetchAllHistory, fetchPersonas, republishDigest } from '../../api/digestClient'
import { Section } from '../../components/Section'
import { StatusIndicator } from '../../components/digest/StatusIndicator'
import { DigestPreview } from '../../components/digest/DigestPreview'

type NoticeTone = 'ok' | 'warn' | 'error'

interface Notice {
  message: string
  tone: NoticeTone
}

type StatusFilter = 'all' | 'PUBLISHED' | 'FAILED' | 'GENERATED'

export function DigestHistory() {
  const [history, setHistory] = useState<DigestHistoryData[]>([])
  const [personas, setPersonas] = useState<DigestPersona[]>([])
  const [loading, setLoading] = useState(true)
  const [notice, setNotice] = useState<Notice | null>(null)
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('all')
  const [personaFilter, setPersonaFilter] = useState<number | 'all'>('all')
  const [searchQuery, setSearchQuery] = useState('')
  const [selectedHistory, setSelectedHistory] = useState<DigestHistoryData | null>(null)
  const [republishing, setRepublishing] = useState(false)
  const [limit, setLimit] = useState(50)

  const loadData = useCallback(async () => {
    try {
      setLoading(true)
      const [historyData, personasData] = await Promise.all([
        fetchAllHistory(limit),
        fetchPersonas(),
      ])
      setHistory(historyData)
      setPersonas(personasData)
      setNotice(null)
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Не удалось загрузить историю'
      setNotice({ tone: 'error', message })
    } finally {
      setLoading(false)
    }
  }, [limit])

  useEffect(() => {
    loadData()
  }, [loadData])

  const handleRepublish = async (digestId: string) => {
    try {
      setRepublishing(true)
      const result = await republishDigest(digestId)
      if (result.success) {
        setNotice({ tone: 'ok', message: 'Дайджест переопубликован!' })
        await loadData()
        setSelectedHistory(null)
      } else {
        setNotice({ tone: 'error', message: result.errorMessage || 'Ошибка переопубликации' })
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Ошибка переопубликации'
      setNotice({ tone: 'error', message })
    } finally {
      setRepublishing(false)
    }
  }

  const filteredHistory = history.filter((item) => {
    if (statusFilter !== 'all' && item.status !== statusFilter) return false
    if (personaFilter !== 'all' && item.personaId !== personaFilter) return false
    if (searchQuery) {
      const query = searchQuery.toLowerCase()
      const matchesContent = item.content.toLowerCase().includes(query)
      const matchesPersona = item.personaName?.toLowerCase().includes(query)
      const matchesId = item.digestId.toLowerCase().includes(query)
      if (!matchesContent && !matchesPersona && !matchesId) return false
    }
    return true
  })

  const statusCounts = {
    all: history.length,
    PUBLISHED: history.filter((h) => h.status === 'PUBLISHED').length,
    FAILED: history.filter((h) => h.status === 'FAILED').length,
    GENERATED: history.filter((h) => h.status === 'GENERATED').length,
  }

  if (loading) {
    return (
      <div className="digest-history">
        <div className="placeholder">Загрузка истории...</div>
      </div>
    )
  }

  return (
    <div className="digest-history">
      {notice && (
        <div className={`notice notice--${notice.tone}`}>
          {notice.message}
        </div>
      )}

      {/* Header */}
      <div className="digest-history__header">
        <div>
          <p className="eyebrow">История</p>
          <h2>Архив дайджестов</h2>
          <p className="muted">Просмотр и переопубликация дайджестов</p>
        </div>
        <div className="digest-history__controls">
          <button onClick={loadData} className="ghost" disabled={loading}>
            Обновить
          </button>
        </div>
      </div>

      {/* Filters */}
      <div className="digest-history__filters">
        <div className="digest-history__filter-group">
          <span className="muted tiny">Поиск:</span>
          <input
            type="text"
            placeholder="Поиск по содержимому, персоне или ID..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
          />
        </div>
        <div className="digest-history__filter-group">
          <span className="muted tiny">Статус:</span>
          <select
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value as StatusFilter)}
          >
            <option value="all">Все ({statusCounts.all})</option>
            <option value="PUBLISHED">Опубликовано ({statusCounts.PUBLISHED})</option>
            <option value="FAILED">Ошибки ({statusCounts.FAILED})</option>
            <option value="GENERATED">Сгенерировано ({statusCounts.GENERATED})</option>
          </select>
        </div>
        <div className="digest-history__filter-group">
          <span className="muted tiny">Персона:</span>
          <select
            value={personaFilter}
            onChange={(e) =>
              setPersonaFilter(e.target.value === 'all' ? 'all' : Number(e.target.value))
            }
          >
            <option value="all">Все персоны</option>
            {personas.map((persona) => (
              <option key={persona.id} value={persona.id ?? ''}>
                {persona.name}
              </option>
            ))}
          </select>
        </div>
        <div className="digest-history__filter-group">
          <span className="muted tiny">Лимит:</span>
          <select value={limit} onChange={(e) => setLimit(Number(e.target.value))}>
            <option value={25}>25</option>
            <option value={50}>50</option>
            <option value={100}>100</option>
            <option value={200}>200</option>
          </select>
        </div>
      </div>

      {/* Stats Summary */}
      <div className="digest-history__stats">
        <div className="digest-history__stat">
          <span className="digest-history__stat-value">{filteredHistory.length}</span>
          <span className="digest-history__stat-label">Найдено</span>
        </div>
        <div className="digest-history__stat digest-history__stat--green">
          <span className="digest-history__stat-value">{statusCounts.PUBLISHED}</span>
          <span className="digest-history__stat-label">Опубликовано</span>
        </div>
        <div className="digest-history__stat digest-history__stat--red">
          <span className="digest-history__stat-value">{statusCounts.FAILED}</span>
          <span className="digest-history__stat-label">Ошибки</span>
        </div>
        <div className="digest-history__stat">
          <span className="digest-history__stat-value">{statusCounts.GENERATED}</span>
          <span className="digest-history__stat-label">Сгенерировано</span>
        </div>
      </div>

      {/* History List */}
      <Section title="Записи" accent={`${filteredHistory.length} записей`}>
        {filteredHistory.length === 0 ? (
          <div className="placeholder">
            <p className="muted">Нет записей, соответствующих фильтрам</p>
          </div>
        ) : (
          <div className="digest-history__list">
            {filteredHistory.map((item) => (
              <HistoryCard
                key={item.id}
                history={item}
                onClick={() => setSelectedHistory(item)}
              />
            ))}
          </div>
        )}
      </Section>

      {/* Preview Modal */}
      {selectedHistory && (
        <DigestPreview
          history={selectedHistory}
          onClose={() => setSelectedHistory(null)}
          onRepublish={() => handleRepublish(selectedHistory.digestId)}
          republishing={republishing}
        />
      )}
    </div>
  )
}

interface HistoryCardProps {
  history: DigestHistoryData
  onClick: () => void
}

function HistoryCard({ history, onClick }: HistoryCardProps) {
  const statusConfig = {
    PUBLISHED: { status: 'active' as const, label: 'Опубликовано' },
    FAILED: { status: 'error' as const, label: 'Ошибка' },
    GENERATED: { status: 'idle' as const, label: 'Сгенерировано' },
  }

  const config = statusConfig[history.status]

  return (
    <div className="digest-history-card" onClick={onClick}>
      <div className="digest-history-card__header">
        <div className="digest-history-card__persona">
          {history.personaName || `Персона #${history.personaId}`}
        </div>
        <StatusIndicator status={config.status} label={config.label} size="small" />
      </div>
      <div className="digest-history-card__preview">
        {contentPreview(history.content, 150)}
      </div>
      <div className="digest-history-card__meta">
        <span className="chip chip--outline tiny">{history.messagesIncluded} сообщений</span>
        <span className="chip chip--outline tiny">{history.clustersUsed} кластеров</span>
        <span className="chip chip--outline tiny">{formatGenerationTime(history.generationTimeMs)}</span>
      </div>
      <div className="digest-history-card__footer">
        <span className="digest-history-card__id muted tiny">
          ID: {history.digestId.substring(0, 12)}...
        </span>
        <span className="digest-history-card__time muted tiny">
          {new Date(history.createdAt).toLocaleString('ru-RU')}
        </span>
      </div>
      {history.errorMessage && (
        <div className="digest-history-card__error">
          <span className="muted tiny">Ошибка: {history.errorMessage}</span>
        </div>
      )}
    </div>
  )
}
