import { useEffect, useState, useCallback } from 'react'
import type {
  DigestPersona,
  DigestAnalytics,
  SchedulerStatus,
  GeneratedDigest,
  ActivityEntry,
} from '../../types/digest'
import { formatGenerationTime, formatSuccessRate } from '../../types/digest'
import {
  fetchDashboardData,
  triggerClustering,
  triggerAllPersonas,
  generateTestDigest,
  publishDigestNow,
  enablePersona,
  disablePersona,
  fetchRecentActivity,
} from '../../api/digestClient'
import { PersonaCard } from '../../components/digest/PersonaCard'
import { StatusIndicator } from '../../components/digest/StatusIndicator'
import { Section } from '../../components/Section'

type NoticeTone = 'ok' | 'warn' | 'error'

interface Notice {
  message: string
  tone: NoticeTone
}

export function DigestDashboard() {
  const [personas, setPersonas] = useState<DigestPersona[]>([])
  const [analytics, setAnalytics] = useState<DigestAnalytics | null>(null)
  const [schedulerStatus, setSchedulerStatus] = useState<SchedulerStatus | null>(null)
  const [recentActivity, setRecentActivity] = useState<ActivityEntry[]>([])
  const [loading, setLoading] = useState(true)
  const [notice, setNotice] = useState<Notice | null>(null)
  const [actionLoading, setActionLoading] = useState<string | null>(null)
  const [previewDigest, setPreviewDigest] = useState<GeneratedDigest | null>(null)

  const loadData = useCallback(async () => {
    try {
      setLoading(true)
      const [dashboardData, activity] = await Promise.all([
        fetchDashboardData(),
        fetchRecentActivity(10),
      ])
      setPersonas(dashboardData.personas)
      setAnalytics(dashboardData.analytics)
      setSchedulerStatus(dashboardData.schedulerStatus)
      setRecentActivity(activity)
      setNotice(null)
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Не удалось загрузить данные'
      setNotice({ tone: 'error', message })
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    loadData()
  }, [loadData])

  const handleTriggerClustering = async () => {
    try {
      setActionLoading('clustering')
      const result = await triggerClustering()
      setNotice({ tone: 'ok', message: result.message || 'Кластеризация запущена' })
      await loadData()
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Ошибка запуска кластеризации'
      setNotice({ tone: 'error', message })
    } finally {
      setActionLoading(null)
    }
  }

  const handleTriggerAll = async () => {
    try {
      setActionLoading('trigger-all')
      const results = await triggerAllPersonas()
      const successCount = results.filter((r) => r.success).length
      setNotice({
        tone: successCount === results.length ? 'ok' : 'warn',
        message: `Опубликовано: ${successCount}/${results.length} дайджестов`,
      })
      await loadData()
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Ошибка запуска персон'
      setNotice({ tone: 'error', message })
    } finally {
      setActionLoading(null)
    }
  }

  const handleTestDigest = async (personaId: number) => {
    try {
      setActionLoading(`test-${personaId}`)
      const digest = await generateTestDigest(personaId)
      setPreviewDigest(digest)
      setNotice({ tone: 'ok', message: 'Тестовый дайджест сгенерирован' })
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Ошибка генерации дайджеста'
      setNotice({ tone: 'error', message })
    } finally {
      setActionLoading(null)
    }
  }

  const handlePublishNow = async (personaId: number) => {
    try {
      setActionLoading(`publish-${personaId}`)
      const result = await publishDigestNow(personaId)
      if (result.success) {
        setNotice({ tone: 'ok', message: 'Дайджест опубликован!' })
      } else {
        setNotice({ tone: 'error', message: result.errorMessage || 'Ошибка публикации' })
      }
      await loadData()
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Ошибка публикации'
      setNotice({ tone: 'error', message })
    } finally {
      setActionLoading(null)
    }
  }

  const handleTogglePersona = async (persona: DigestPersona) => {
    if (persona.id === null) return
    try {
      setActionLoading(`toggle-${persona.id}`)
      if (persona.enabled) {
        await disablePersona(persona.id)
      } else {
        await enablePersona(persona.id)
      }
      await loadData()
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Ошибка изменения статуса'
      setNotice({ tone: 'error', message })
    } finally {
      setActionLoading(null)
    }
  }

  const enabledPersonas = personas.filter((p) => p.enabled)
  const systemStatus = schedulerStatus?.enabled ? 'active' : 'paused'

  if (loading) {
    return (
      <div className="digest-dashboard">
        <div className="placeholder">Загрузка данных...</div>
      </div>
    )
  }

  return (
    <div className="digest-dashboard">
      {notice && (
        <div className={`notice notice--${notice.tone}`}>
          {notice.message}
        </div>
      )}

      {/* Header with System Status */}
      <div className="digest-dashboard__header">
        <div>
          <p className="eyebrow">Система дайджестов</p>
          <h2>Панель управления</h2>
          <p className="muted">Управление персонами и публикациями дайджестов</p>
        </div>
        <div className="digest-dashboard__header-status">
          <StatusIndicator status={systemStatus} label={schedulerStatus?.enabled ? 'Система активна' : 'Система приостановлена'} />
        </div>
      </div>

      {/* Metrics Cards */}
      <div className="digest-dashboard__metrics">
        <div className="digest-metric-card">
          <div className="digest-metric-card__value">{personas.length}</div>
          <div className="digest-metric-card__label">Всего персон</div>
        </div>
        <div className="digest-metric-card digest-metric-card--highlight">
          <div className="digest-metric-card__value">{enabledPersonas.length}</div>
          <div className="digest-metric-card__label">Активных</div>
        </div>
        <div className="digest-metric-card">
          <div className="digest-metric-card__value">
            {analytics?.digestsPublishedToday ?? 0}
          </div>
          <div className="digest-metric-card__label">Сегодня опубликовано</div>
        </div>
        <div className="digest-metric-card">
          <div className="digest-metric-card__value">
            {analytics ? formatSuccessRate(analytics.overallSuccessRate) : '—'}
          </div>
          <div className="digest-metric-card__label">Успешность</div>
        </div>
        <div className="digest-metric-card">
          <div className="digest-metric-card__value">
            {analytics?.clustersFormedToday ?? 0}
          </div>
          <div className="digest-metric-card__label">Кластеров сегодня</div>
        </div>
        <div className="digest-metric-card">
          <div className="digest-metric-card__value">
            {analytics ? formatGenerationTime(analytics.averageGenerationTimeMs) : '—'}
          </div>
          <div className="digest-metric-card__label">Ср. время генерации</div>
        </div>
      </div>

      {/* Quick Actions */}
      <div className="digest-dashboard__actions">
        <button
          onClick={handleTriggerClustering}
          disabled={actionLoading === 'clustering'}
          className="ghost"
        >
          {actionLoading === 'clustering' ? 'Кластеризация...' : 'Запустить кластеризацию'}
        </button>
        <button
          onClick={handleTriggerAll}
          disabled={actionLoading === 'trigger-all' || enabledPersonas.length === 0}
        >
          {actionLoading === 'trigger-all'
            ? 'Публикация...'
            : `Опубликовать все (${enabledPersonas.length})`}
        </button>
        <button onClick={loadData} className="ghost" disabled={loading}>
          Обновить
        </button>
      </div>

      {/* Main Layout */}
      <div className="digest-dashboard__layout">
        {/* Personas List */}
        <div className="digest-dashboard__personas">
          <Section title="Персоны" accent="Управление" description="Список настроенных персон дайджестов">
            {personas.length === 0 ? (
              <div className="placeholder">
                <p>Нет настроенных персон</p>
                <p className="muted tiny">Создайте первую персону для начала работы</p>
              </div>
            ) : (
              <div className="digest-persona-list">
                {personas.map((persona) => (
                  <PersonaCard
                    key={persona.id ?? persona.name}
                    persona={persona}
                    onEnable={() => handleTogglePersona(persona)}
                    onDisable={() => handleTogglePersona(persona)}
                    onTest={() => persona.id !== null && handleTestDigest(persona.id)}
                    onPublish={() => persona.id !== null && handlePublishNow(persona.id)}
                  />
                ))}
              </div>
            )}
          </Section>
        </div>

        {/* Activity Timeline */}
        <div className="digest-dashboard__activity">
          <Section title="Последняя активность" accent="История" description="Недавние публикации и события">
            {recentActivity.length === 0 ? (
              <div className="placeholder">
                <p className="muted">Нет активности</p>
              </div>
            ) : (
              <div className="digest-activity-list">
                {recentActivity.map((entry, index) => (
                  <div key={index} className="digest-activity-item">
                    <div className="digest-activity-item__header">
                      <span className="digest-activity-item__persona">{entry.personaName}</span>
                      <StatusIndicator
                        status={entry.success ? 'active' : 'error'}
                        label={entry.action}
                        size="small"
                      />
                    </div>
                    <p className="digest-activity-item__details muted tiny">{entry.details}</p>
                    <span className="digest-activity-item__time muted tiny">
                      {new Date(entry.timestamp).toLocaleString('ru-RU')}
                    </span>
                  </div>
                ))}
              </div>
            )}
          </Section>
        </div>
      </div>

      {/* Preview Modal */}
      {previewDigest && (
        <div className="digest-preview-modal" onClick={() => setPreviewDigest(null)}>
          <div className="digest-preview-modal__content" onClick={(e) => e.stopPropagation()}>
            <div className="digest-preview-modal__header">
              <h3>Предпросмотр дайджеста</h3>
              <button className="ghost" onClick={() => setPreviewDigest(null)}>
                Закрыть
              </button>
            </div>
            <div className="digest-preview-modal__meta">
              <span className="chip">{previewDigest.personaName}</span>
              <span className="chip chip--outline">
                {previewDigest.messagesIncluded} сообщений
              </span>
              <span className="chip chip--outline">
                {previewDigest.clustersUsed} кластеров
              </span>
              <span className="chip chip--outline">
                {formatGenerationTime(previewDigest.generationTimeMs)}
              </span>
            </div>
            <div className="digest-preview-modal__body">
              <pre>{previewDigest.content}</pre>
            </div>
            {previewDigest.sourceSummary.length > 0 && (
              <div className="digest-preview-modal__sources">
                <p className="muted tiny">Источники:</p>
                <div className="chips">
                  {previewDigest.sourceSummary.map((source, i) => (
                    <span key={i} className="chip chip--outline tiny">
                      {source}
                    </span>
                  ))}
                </div>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  )
}
