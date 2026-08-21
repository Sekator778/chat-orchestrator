import { useEffect, useState, useCallback } from 'react'
import type {
  DigestAnalytics as DigestAnalyticsData,
  ClusterStats,
  SourceStats,
  PersonaStats,
  ClusterInfo,
  SourceDetail,
} from '../../types/digest'
import { formatGenerationTime, formatSuccessRate } from '../../types/digest'
import { fetchFullAnalytics } from '../../api/digestClient'
import { Section } from '../../components/Section'
import { StatusIndicator } from '../../components/digest/StatusIndicator'

type NoticeTone = 'ok' | 'warn' | 'error'

interface Notice {
  message: string
  tone: NoticeTone
}

type TabId = 'overview' | 'clusters' | 'sources' | 'personas'

export function DigestAnalytics() {
  const [analytics, setAnalytics] = useState<DigestAnalyticsData | null>(null)
  const [clusterStats, setClusterStats] = useState<ClusterStats | null>(null)
  const [sourceStats, setSourceStats] = useState<SourceStats | null>(null)
  const [loading, setLoading] = useState(true)
  const [notice, setNotice] = useState<Notice | null>(null)
  const [lookbackHours, setLookbackHours] = useState(24)
  const [activeTab, setActiveTab] = useState<TabId>('overview')

  const loadData = useCallback(async () => {
    try {
      setLoading(true)
      const data = await fetchFullAnalytics(lookbackHours)
      setAnalytics(data.analytics)
      setClusterStats(data.clusterStats)
      setSourceStats(data.sourceStats)
      setNotice(null)
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Не удалось загрузить аналитику'
      setNotice({ tone: 'error', message })
    } finally {
      setLoading(false)
    }
  }, [lookbackHours])

  useEffect(() => {
    loadData()
  }, [loadData])

  const handleLookbackChange = (hours: number) => {
    setLookbackHours(hours)
  }

  if (loading) {
    return (
      <div className="digest-analytics">
        <div className="placeholder">Загрузка аналитики...</div>
      </div>
    )
  }

  return (
    <div className="digest-analytics">
      {notice && (
        <div className={`notice notice--${notice.tone}`}>
          {notice.message}
        </div>
      )}

      {/* Header */}
      <div className="digest-analytics__header">
        <div>
          <p className="eyebrow">Аналитика</p>
          <h2>Статистика системы дайджестов</h2>
          <p className="muted">Метрики генерации, публикации и производительности</p>
        </div>
        <div className="digest-analytics__controls">
          <div className="digest-analytics__lookback">
            <span className="muted tiny">Период:</span>
            <select
              value={lookbackHours}
              onChange={(e) => handleLookbackChange(Number(e.target.value))}
            >
              <option value={6}>6 часов</option>
              <option value={12}>12 часов</option>
              <option value={24}>24 часа</option>
              <option value={48}>48 часов</option>
              <option value={72}>72 часа</option>
              <option value={168}>7 дней</option>
            </select>
          </div>
          <button onClick={loadData} className="ghost" disabled={loading}>
            Обновить
          </button>
        </div>
      </div>

      {/* Tabs */}
      <div className="nav-tabs">
        <button
          className={`tab ${activeTab === 'overview' ? 'tab--active' : ''}`}
          onClick={() => setActiveTab('overview')}
        >
          Обзор
        </button>
        <button
          className={`tab ${activeTab === 'clusters' ? 'tab--active' : ''}`}
          onClick={() => setActiveTab('clusters')}
        >
          Кластеры
        </button>
        <button
          className={`tab ${activeTab === 'sources' ? 'tab--active' : ''}`}
          onClick={() => setActiveTab('sources')}
        >
          Источники
        </button>
        <button
          className={`tab ${activeTab === 'personas' ? 'tab--active' : ''}`}
          onClick={() => setActiveTab('personas')}
        >
          Персоны
        </button>
      </div>

      {/* Tab Content */}
      {activeTab === 'overview' && analytics && (
        <OverviewTab analytics={analytics} />
      )}
      {activeTab === 'clusters' && clusterStats && (
        <ClustersTab stats={clusterStats} />
      )}
      {activeTab === 'sources' && sourceStats && (
        <SourcesTab stats={sourceStats} />
      )}
      {activeTab === 'personas' && analytics && (
        <PersonasTab personaStats={analytics.personaStats} />
      )}
    </div>
  )
}

interface OverviewTabProps {
  analytics: DigestAnalyticsData
}

function OverviewTab({ analytics }: OverviewTabProps) {
  return (
    <div className="digest-analytics__content">
      {/* Main Metrics */}
      <Section title="Ключевые метрики" accent="Сводка">
        <div className="digest-analytics__metrics">
          <div className="digest-metric-card">
            <div className="digest-metric-card__value">{analytics.totalPersonas}</div>
            <div className="digest-metric-card__label">Всего персон</div>
          </div>
          <div className="digest-metric-card digest-metric-card--highlight">
            <div className="digest-metric-card__value">{analytics.activePersonas}</div>
            <div className="digest-metric-card__label">Активных</div>
          </div>
          <div className="digest-metric-card">
            <div className="digest-metric-card__value">{analytics.totalDigestsGenerated}</div>
            <div className="digest-metric-card__label">Сгенерировано</div>
          </div>
          <div className="digest-metric-card digest-metric-card--green">
            <div className="digest-metric-card__value">{analytics.totalDigestsPublished}</div>
            <div className="digest-metric-card__label">Опубликовано</div>
          </div>
          <div className="digest-metric-card">
            <div className="digest-metric-card__value">
              {formatSuccessRate(analytics.overallSuccessRate)}
            </div>
            <div className="digest-metric-card__label">Успешность</div>
          </div>
          <div className="digest-metric-card">
            <div className="digest-metric-card__value">
              {formatGenerationTime(analytics.averageGenerationTimeMs)}
            </div>
            <div className="digest-metric-card__label">Ср. время генерации</div>
          </div>
        </div>
      </Section>

      {/* Today Stats */}
      <Section title="Сегодня" accent="Активность">
        <div className="digest-analytics__today">
          <div className="digest-analytics__today-item">
            <span className="digest-analytics__today-label">Сообщений обработано</span>
            <span className="digest-analytics__today-value">{analytics.messagesProcessedToday}</span>
          </div>
          <div className="digest-analytics__today-item">
            <span className="digest-analytics__today-label">Кластеров сформировано</span>
            <span className="digest-analytics__today-value">{analytics.clustersFormedToday}</span>
          </div>
          <div className="digest-analytics__today-item">
            <span className="digest-analytics__today-label">Дайджестов опубликовано</span>
            <span className="digest-analytics__today-value">{analytics.digestsPublishedToday}</span>
          </div>
        </div>
      </Section>

      {/* Success Rate Visualization */}
      <Section title="Показатель успешности" accent="Качество">
        <div className="digest-analytics__success-rate">
          <div className="digest-analytics__success-bar">
            <div
              className="digest-analytics__success-fill"
              style={{ width: `${analytics.overallSuccessRate * 100}%` }}
            />
          </div>
          <div className="digest-analytics__success-labels">
            <span className="muted tiny">0%</span>
            <span className="digest-analytics__success-current">
              {formatSuccessRate(analytics.overallSuccessRate)}
            </span>
            <span className="muted tiny">100%</span>
          </div>
        </div>
      </Section>
    </div>
  )
}

interface ClustersTabProps {
  stats: ClusterStats
}

function ClustersTab({ stats }: ClustersTabProps) {
  return (
    <div className="digest-analytics__content">
      {/* Cluster Metrics */}
      <Section title="Статистика кластеризации" accent="Метрики">
        <div className="digest-analytics__metrics">
          <div className="digest-metric-card">
            <div className="digest-metric-card__value">{stats.totalClusters}</div>
            <div className="digest-metric-card__label">Всего кластеров</div>
          </div>
          <div className="digest-metric-card digest-metric-card--highlight">
            <div className="digest-metric-card__value">{stats.clustersToday}</div>
            <div className="digest-metric-card__label">Сегодня</div>
          </div>
          <div className="digest-metric-card">
            <div className="digest-metric-card__value">{stats.averageClusterSize.toFixed(1)}</div>
            <div className="digest-metric-card__label">Ср. размер</div>
          </div>
          <div className="digest-metric-card digest-metric-card--green">
            <div className="digest-metric-card__value">
              {(stats.deduplicationRate * 100).toFixed(1)}%
            </div>
            <div className="digest-metric-card__label">Дедупликация</div>
          </div>
          <div className="digest-metric-card">
            <div className="digest-metric-card__value">{stats.unclusteredMessages}</div>
            <div className="digest-metric-card__label">Не в кластерах</div>
          </div>
          <div className="digest-metric-card">
            <div className="digest-metric-card__value">
              {formatGenerationTime(stats.processingTimeMs)}
            </div>
            <div className="digest-metric-card__label">Время обработки</div>
          </div>
        </div>
      </Section>

      {/* Top Clusters */}
      <Section title="Топ кластеры" accent="Содержимое">
        {stats.topClusters.length === 0 ? (
          <div className="placeholder">
            <p className="muted">Нет данных о кластерах</p>
          </div>
        ) : (
          <div className="digest-analytics__clusters-list">
            {stats.topClusters.map((cluster, index) => (
              <ClusterCard key={cluster.clusterId} cluster={cluster} rank={index + 1} />
            ))}
          </div>
        )}
      </Section>
    </div>
  )
}

interface ClusterCardProps {
  cluster: ClusterInfo
  rank: number
}

function ClusterCard({ cluster, rank }: ClusterCardProps) {
  return (
    <div className="digest-cluster-card">
      <div className="digest-cluster-card__header">
        <span className="digest-cluster-card__rank">#{rank}</span>
        <span className="digest-cluster-card__id muted tiny">
          {cluster.clusterId.substring(0, 8)}...
        </span>
      </div>
      <div className="digest-cluster-card__body">
        <p className="digest-cluster-card__preview">{cluster.primaryMessagePreview}</p>
      </div>
      <div className="digest-cluster-card__footer">
        <span className="chip chip--outline tiny">{cluster.messageCount} сообщений</span>
        <span className="chip chip--outline tiny">
          Важность: {(cluster.avgImportance * 100).toFixed(0)}%
        </span>
        <span className="muted tiny">
          {new Date(cluster.createdAt).toLocaleString('ru-RU')}
        </span>
      </div>
    </div>
  )
}

interface SourcesTabProps {
  stats: SourceStats
}

function SourcesTab({ stats }: SourcesTabProps) {
  const [sortBy, setSortBy] = useState<'trust' | 'messages'>('trust')

  const sortedSources = [...stats.sourceDetails].sort((a, b) => {
    if (sortBy === 'trust') return b.trustScore - a.trustScore
    return b.messageCount - a.messageCount
  })

  return (
    <div className="digest-analytics__content">
      {/* Source Metrics */}
      <Section title="Статистика источников" accent="Метрики">
        <div className="digest-analytics__metrics">
          <div className="digest-metric-card">
            <div className="digest-metric-card__value">{stats.totalSources}</div>
            <div className="digest-metric-card__label">Всего источников</div>
          </div>
          <div className="digest-metric-card digest-metric-card--green">
            <div className="digest-metric-card__value">{stats.highTrustSources}</div>
            <div className="digest-metric-card__label">Высокое доверие</div>
          </div>
          <div className="digest-metric-card digest-metric-card--warn">
            <div className="digest-metric-card__value">{stats.lowTrustSources}</div>
            <div className="digest-metric-card__label">Низкое доверие</div>
          </div>
          <div className="digest-metric-card">
            <div className="digest-metric-card__value">
              {(stats.averageTrustScore * 100).toFixed(0)}%
            </div>
            <div className="digest-metric-card__label">Ср. доверие</div>
          </div>
        </div>
      </Section>

      {/* Trust Distribution */}
      <Section title="Распределение доверия" accent="Визуализация">
        <TrustDistributionChart distribution={stats.trustDistribution} />
      </Section>

      {/* Source List */}
      <Section title="Список источников" accent="Детали">
        <div className="digest-analytics__sources-header">
          <span className="muted tiny">Сортировка:</span>
          <select value={sortBy} onChange={(e) => setSortBy(e.target.value as 'trust' | 'messages')}>
            <option value="trust">По доверию</option>
            <option value="messages">По сообщениям</option>
          </select>
        </div>
        {sortedSources.length === 0 ? (
          <div className="placeholder">
            <p className="muted">Нет данных об источниках</p>
          </div>
        ) : (
          <div className="digest-analytics__sources-list">
            {sortedSources.slice(0, 20).map((source) => (
              <SourceCard key={source.channelId} source={source} />
            ))}
          </div>
        )}
      </Section>
    </div>
  )
}

interface TrustDistributionChartProps {
  distribution: {
    veryHigh: number
    high: number
    medium: number
    low: number
    veryLow: number
  }
}

function TrustDistributionChart({ distribution }: TrustDistributionChartProps) {
  const total = distribution.veryHigh + distribution.high + distribution.medium + distribution.low + distribution.veryLow
  if (total === 0) {
    return (
      <div className="placeholder">
        <p className="muted">Нет данных для отображения</p>
      </div>
    )
  }

  const getWidth = (value: number) => `${(value / total) * 100}%`

  return (
    <div className="digest-trust-chart">
      <div className="digest-trust-chart__bar">
        <div
          className="digest-trust-chart__segment digest-trust-chart__segment--very-high"
          style={{ width: getWidth(distribution.veryHigh) }}
          title={`Очень высокое: ${distribution.veryHigh}`}
        />
        <div
          className="digest-trust-chart__segment digest-trust-chart__segment--high"
          style={{ width: getWidth(distribution.high) }}
          title={`Высокое: ${distribution.high}`}
        />
        <div
          className="digest-trust-chart__segment digest-trust-chart__segment--medium"
          style={{ width: getWidth(distribution.medium) }}
          title={`Среднее: ${distribution.medium}`}
        />
        <div
          className="digest-trust-chart__segment digest-trust-chart__segment--low"
          style={{ width: getWidth(distribution.low) }}
          title={`Низкое: ${distribution.low}`}
        />
        <div
          className="digest-trust-chart__segment digest-trust-chart__segment--very-low"
          style={{ width: getWidth(distribution.veryLow) }}
          title={`Очень низкое: ${distribution.veryLow}`}
        />
      </div>
      <div className="digest-trust-chart__legend">
        <div className="digest-trust-chart__legend-item">
          <span className="digest-trust-chart__legend-color digest-trust-chart__legend-color--very-high" />
          <span>Очень высокое ({distribution.veryHigh})</span>
        </div>
        <div className="digest-trust-chart__legend-item">
          <span className="digest-trust-chart__legend-color digest-trust-chart__legend-color--high" />
          <span>Высокое ({distribution.high})</span>
        </div>
        <div className="digest-trust-chart__legend-item">
          <span className="digest-trust-chart__legend-color digest-trust-chart__legend-color--medium" />
          <span>Среднее ({distribution.medium})</span>
        </div>
        <div className="digest-trust-chart__legend-item">
          <span className="digest-trust-chart__legend-color digest-trust-chart__legend-color--low" />
          <span>Низкое ({distribution.low})</span>
        </div>
        <div className="digest-trust-chart__legend-item">
          <span className="digest-trust-chart__legend-color digest-trust-chart__legend-color--very-low" />
          <span>Очень низкое ({distribution.veryLow})</span>
        </div>
      </div>
    </div>
  )
}

interface SourceCardProps {
  source: SourceDetail
}

function SourceCard({ source }: SourceCardProps) {
  const trustLevel =
    source.trustScore >= 0.9
      ? 'very-high'
      : source.trustScore >= 0.7
        ? 'high'
        : source.trustScore >= 0.5
          ? 'medium'
          : source.trustScore >= 0.3
            ? 'low'
            : 'very-low'

  return (
    <div className={`digest-source-card digest-source-card--${trustLevel}`}>
      <div className="digest-source-card__header">
        <span className="digest-source-card__title">
          {source.channelTitle}
          {source.isOfficial && <span className="badge badge--official">Official</span>}
        </span>
        <span className={`digest-source-card__trust digest-source-card__trust--${trustLevel}`}>
          {(source.trustScore * 100).toFixed(0)}%
        </span>
      </div>
      <div className="digest-source-card__meta">
        <span className="chip chip--outline tiny">{source.messageCount} сообщений</span>
        <span className="chip chip--outline tiny">{source.clustersContributed} кластеров</span>
        {source.category && (
          <span className="chip chip--violet tiny">{source.category}</span>
        )}
      </div>
      {source.lastMessageAt && (
        <span className="muted tiny">
          Последнее: {new Date(source.lastMessageAt).toLocaleString('ru-RU')}
        </span>
      )}
    </div>
  )
}

interface PersonasTabProps {
  personaStats: PersonaStats[]
}

function PersonasTab({ personaStats }: PersonasTabProps) {
  if (personaStats.length === 0) {
    return (
      <div className="digest-analytics__content">
        <div className="placeholder">
          <p className="muted">Нет данных о персонах</p>
        </div>
      </div>
    )
  }

  return (
    <div className="digest-analytics__content">
      <Section title="Статистика по персонам" accent="Детали">
        <div className="digest-analytics__personas-list">
          {personaStats.map((stats) => (
            <PersonaStatsCard key={stats.personaId} stats={stats} />
          ))}
        </div>
      </Section>
    </div>
  )
}

interface PersonaStatsCardProps {
  stats: PersonaStats
}

function PersonaStatsCard({ stats }: PersonaStatsCardProps) {
  return (
    <div className="digest-persona-stats-card">
      <div className="digest-persona-stats-card__header">
        <span className="digest-persona-stats-card__name">{stats.personaName}</span>
        <StatusIndicator
          status={stats.enabled ? 'active' : 'paused'}
          size="small"
        />
      </div>
      <div className="digest-persona-stats-card__metrics">
        <div className="digest-persona-stats-card__metric">
          <span className="digest-persona-stats-card__metric-value">{stats.totalDigests}</span>
          <span className="digest-persona-stats-card__metric-label">Всего</span>
        </div>
        <div className="digest-persona-stats-card__metric">
          <span className="digest-persona-stats-card__metric-value digest-persona-stats-card__metric-value--green">
            {stats.publishedDigests}
          </span>
          <span className="digest-persona-stats-card__metric-label">Опубликовано</span>
        </div>
        <div className="digest-persona-stats-card__metric">
          <span className="digest-persona-stats-card__metric-value digest-persona-stats-card__metric-value--red">
            {stats.failedDigests}
          </span>
          <span className="digest-persona-stats-card__metric-label">Ошибки</span>
        </div>
        <div className="digest-persona-stats-card__metric">
          <span className="digest-persona-stats-card__metric-value">
            {formatSuccessRate(stats.successRate)}
          </span>
          <span className="digest-persona-stats-card__metric-label">Успешность</span>
        </div>
        <div className="digest-persona-stats-card__metric">
          <span className="digest-persona-stats-card__metric-value">
            {formatGenerationTime(stats.avgGenerationTimeMs)}
          </span>
          <span className="digest-persona-stats-card__metric-label">Ср. время</span>
        </div>
      </div>
      {stats.lastRunAt && (
        <div className="digest-persona-stats-card__footer">
          <span className="muted tiny">
            Последний запуск: {new Date(stats.lastRunAt).toLocaleString('ru-RU')}
          </span>
        </div>
      )}
    </div>
  )
}
