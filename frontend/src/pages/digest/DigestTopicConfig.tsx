import { useState, useCallback } from 'react'
import type { SourceStats, SourceDetail } from '../../types/digest'
import { fetchSourceStats } from '../../api/digestClient'
import { Section } from '../../components/Section'

/**
 * Topic configuration object.
 */
export interface TopicConfig {
  topicKeywords: string[]
  negativeKeywords: string[]
  excludedChannelIds: number[]
  minImportanceScore: number
  sourceTrustThreshold: number
  minClusterSize: number
  lookbackHours: number
  maxMessages: number
}

interface Props {
  value: TopicConfig
  onChange: (config: TopicConfig) => void
  showSourceTrust?: boolean
  showContentFilters?: boolean
  compact?: boolean
}

/**
 * Source trust filter component showing available sources.
 */
function SourceTrustFilter({
  threshold,
  onThresholdChange,
  excludedChannels,
  onExcludedChange,
}: {
  threshold: number
  onThresholdChange: (value: number) => void
  excludedChannels: number[]
  onExcludedChange: (channels: number[]) => void
}) {
  const [stats, setStats] = useState<SourceStats | null>(null)
  const [loading, setLoading] = useState(false)
  const [loaded, setLoaded] = useState(false)
  const [channelInput, setChannelInput] = useState('')

  const loadSources = useCallback(async () => {
    if (loaded) return
    setLoading(true)
    try {
      const data = await fetchSourceStats()
      setStats(data)
      setLoaded(true)
    } catch {
      setStats(null)
    } finally {
      setLoading(false)
    }
  }, [loaded])

  const handleAddExcluded = () => {
    const channelId = parseInt(channelInput.trim(), 10)
    if (isNaN(channelId) || excludedChannels.includes(channelId)) return
    onExcludedChange([...excludedChannels, channelId])
    setChannelInput('')
  }

  const handleRemoveExcluded = (channelId: number) => {
    onExcludedChange(excludedChannels.filter((id) => id !== channelId))
  }

  const handleToggleSource = (source: SourceDetail) => {
    if (excludedChannels.includes(source.channelId)) {
      onExcludedChange(excludedChannels.filter((id) => id !== source.channelId))
    } else {
      onExcludedChange([...excludedChannels, source.channelId])
    }
  }

  const filteredSources = stats?.sourceDetails.filter(
    (s) => s.trustScore >= threshold
  ) || []

  return (
    <div className="digest-source-trust">
      <div className="digest-source-trust__threshold">
        <label>
          <span>Минимальный уровень доверия: {threshold.toFixed(1)}</span>
          <input
            type="range"
            min={0}
            max={1}
            step={0.1}
            value={threshold}
            onChange={(e) => onThresholdChange(parseFloat(e.target.value))}
          />
        </label>
        <div className="digest-source-trust__scale">
          <span className="muted tiny">0.0 (низкое)</span>
          <span className="muted tiny">1.0 (высокое)</span>
        </div>
      </div>

      <div className="digest-source-trust__excluded">
        <label>
          <span>Исключенные каналы (ID)</span>
          <div className="input-line">
            <input
              type="text"
              value={channelInput}
              onChange={(e) => setChannelInput(e.target.value)}
              placeholder="ID канала..."
              onKeyDown={(e) => e.key === 'Enter' && (e.preventDefault(), handleAddExcluded())}
            />
            <button type="button" className="ghost" onClick={handleAddExcluded}>
              Добавить
            </button>
          </div>
        </label>
        {excludedChannels.length > 0 && (
          <div className="chips" style={{ marginTop: 8 }}>
            {excludedChannels.map((channelId) => (
              <span key={channelId} className="chip chip--warn">
                {channelId}
                <button
                  type="button"
                  className="chip__remove"
                  onClick={() => handleRemoveExcluded(channelId)}
                >
                  ×
                </button>
              </span>
            ))}
          </div>
        )}
      </div>

      <div className="digest-source-trust__sources">
        {!loaded && (
          <button
            type="button"
            className="ghost"
            onClick={loadSources}
            disabled={loading}
          >
            {loading ? 'Загрузка...' : 'Показать доступные источники'}
          </button>
        )}

        {loaded && stats && (
          <>
            <p className="muted tiny">
              Доступные источники ({filteredSources.length} из {stats.totalSources}):
            </p>
            <div className="digest-source-trust__list">
              {filteredSources.slice(0, 20).map((source) => (
                <div
                  key={source.channelId}
                  className={`digest-source-trust__item ${excludedChannels.includes(source.channelId) ? 'excluded' : ''}`}
                  onClick={() => handleToggleSource(source)}
                >
                  <div className="digest-source-trust__item-info">
                    <span className="digest-source-trust__item-title">
                      {source.channelTitle || `ID: ${source.channelId}`}
                    </span>
                    <span className="muted tiny">
                      {source.messageCount} сообщ. · {source.clustersContributed} кластеров
                    </span>
                  </div>
                  <div className="digest-source-trust__item-score">
                    <span
                      className={`chip tiny ${source.trustScore >= 0.7 ? 'chip--green' : source.trustScore >= 0.4 ? 'chip--outline' : 'chip--warn'}`}
                    >
                      {source.trustScore.toFixed(2)}
                    </span>
                    {source.isOfficial && (
                      <span className="chip chip--violet tiny">Офиц.</span>
                    )}
                  </div>
                </div>
              ))}
              {filteredSources.length > 20 && (
                <p className="muted tiny">
                  И ещё {filteredSources.length - 20} источников...
                </p>
              )}
            </div>
            {stats.trustDistribution && (
              <div className="digest-source-trust__distribution">
                <p className="muted tiny">Распределение доверия:</p>
                <div className="digest-source-trust__bars">
                  <div className="digest-source-trust__bar">
                    <div
                      className="digest-source-trust__bar-fill very-high"
                      style={{ width: `${(stats.trustDistribution.veryHigh / stats.totalSources) * 100}%` }}
                    />
                    <span className="tiny">Очень высокое ({stats.trustDistribution.veryHigh})</span>
                  </div>
                  <div className="digest-source-trust__bar">
                    <div
                      className="digest-source-trust__bar-fill high"
                      style={{ width: `${(stats.trustDistribution.high / stats.totalSources) * 100}%` }}
                    />
                    <span className="tiny">Высокое ({stats.trustDistribution.high})</span>
                  </div>
                  <div className="digest-source-trust__bar">
                    <div
                      className="digest-source-trust__bar-fill medium"
                      style={{ width: `${(stats.trustDistribution.medium / stats.totalSources) * 100}%` }}
                    />
                    <span className="tiny">Среднее ({stats.trustDistribution.medium})</span>
                  </div>
                  <div className="digest-source-trust__bar">
                    <div
                      className="digest-source-trust__bar-fill low"
                      style={{ width: `${(stats.trustDistribution.low / stats.totalSources) * 100}%` }}
                    />
                    <span className="tiny">Низкое ({stats.trustDistribution.low})</span>
                  </div>
                </div>
              </div>
            )}
          </>
        )}
      </div>
    </div>
  )
}

/**
 * Keywords input component.
 */
function KeywordsInput({
  label,
  keywords,
  onAdd,
  onRemove,
  chipClass = 'chip--green',
  placeholder = 'Введите слова через запятую...',
}: {
  label: string
  keywords: string[]
  onAdd: (keyword: string) => void
  onRemove: (keyword: string) => void
  chipClass?: string
  placeholder?: string
}) {
  const [input, setInput] = useState('')

  const handleAdd = () => {
    if (!input.trim()) return
    const newKeywords = input.split(',').map((k) => k.trim()).filter(Boolean)
    newKeywords.forEach((k) => {
      if (!keywords.includes(k)) onAdd(k)
    })
    setInput('')
  }

  return (
    <div className="digest-keywords-input">
      <label>
        <span>{label}</span>
        <div className="input-line">
          <input
            type="text"
            value={input}
            onChange={(e) => setInput(e.target.value)}
            placeholder={placeholder}
            onKeyDown={(e) => e.key === 'Enter' && (e.preventDefault(), handleAdd())}
          />
          <button type="button" className="ghost" onClick={handleAdd}>
            Добавить
          </button>
        </div>
      </label>
      {keywords.length > 0 && (
        <div className="chips" style={{ marginTop: 8 }}>
          {keywords.map((keyword) => (
            <span key={keyword} className={`chip ${chipClass}`}>
              {keyword}
              <button
                type="button"
                className="chip__remove"
                onClick={() => onRemove(keyword)}
              >
                ×
              </button>
            </span>
          ))}
        </div>
      )}
    </div>
  )
}

/**
 * Standalone topic configuration component for digest filtering.
 * Features:
 * - Topic keywords (include)
 * - Negative keywords (exclude)
 * - Source trust threshold with channel browser
 * - Content filtering parameters
 */
export function DigestTopicConfig({
  value,
  onChange,
  showSourceTrust = true,
  showContentFilters = true,
  compact = false,
}: Props) {
  const handleAddTopicKeyword = (keyword: string) => {
    onChange({
      ...value,
      topicKeywords: [...value.topicKeywords, keyword],
    })
  }

  const handleRemoveTopicKeyword = (keyword: string) => {
    onChange({
      ...value,
      topicKeywords: value.topicKeywords.filter((k) => k !== keyword),
    })
  }

  const handleAddNegativeKeyword = (keyword: string) => {
    onChange({
      ...value,
      negativeKeywords: [...value.negativeKeywords, keyword],
    })
  }

  const handleRemoveNegativeKeyword = (keyword: string) => {
    onChange({
      ...value,
      negativeKeywords: value.negativeKeywords.filter((k) => k !== keyword),
    })
  }

  const handleExcludedChannelsChange = (channels: number[]) => {
    onChange({ ...value, excludedChannelIds: channels })
  }

  const handleThresholdChange = (threshold: number) => {
    onChange({ ...value, sourceTrustThreshold: threshold })
  }

  const handleContentFilterChange = (
    field: 'minImportanceScore' | 'minClusterSize' | 'lookbackHours' | 'maxMessages',
    newValue: number
  ) => {
    onChange({ ...value, [field]: newValue })
  }

  const content = (
    <div className="digest-topic-config">
      {/* Topic Keywords */}
      <div className="digest-topic-config__section">
        <KeywordsInput
          label="Ключевые слова (включить темы)"
          keywords={value.topicKeywords}
          onAdd={handleAddTopicKeyword}
          onRemove={handleRemoveTopicKeyword}
          chipClass="chip--green"
          placeholder="Введите слова через запятую..."
        />
        <p className="muted tiny" style={{ marginTop: 4 }}>
          Сообщения с этими словами будут включены в дайджест
        </p>
      </div>

      {/* Negative Keywords */}
      <div className="digest-topic-config__section">
        <KeywordsInput
          label="Негативные ключевые слова (исключить)"
          keywords={value.negativeKeywords}
          onAdd={handleAddNegativeKeyword}
          onRemove={handleRemoveNegativeKeyword}
          chipClass="chip--warn"
          placeholder="Слова для исключения..."
        />
        <p className="muted tiny" style={{ marginTop: 4 }}>
          Сообщения с этими словами будут исключены из дайджеста
        </p>
      </div>

      {/* Source Trust */}
      {showSourceTrust && (
        <div className="digest-topic-config__section">
          <h4 style={{ margin: '0 0 12px', fontSize: 14 }}>Фильтрация по источникам</h4>
          <SourceTrustFilter
            threshold={value.sourceTrustThreshold}
            onThresholdChange={handleThresholdChange}
            excludedChannels={value.excludedChannelIds}
            onExcludedChange={handleExcludedChannelsChange}
          />
        </div>
      )}

      {/* Content Filters */}
      {showContentFilters && (
        <div className="digest-topic-config__section">
          <h4 style={{ margin: '0 0 12px', fontSize: 14 }}>Параметры контента</h4>
          <div className="form-grid">
            <label>
              <span>Окно просмотра (часы)</span>
              <input
                type="number"
                min={1}
                max={168}
                value={value.lookbackHours}
                onChange={(e) => handleContentFilterChange('lookbackHours', parseInt(e.target.value, 10) || 24)}
              />
            </label>
            <label>
              <span>Макс. сообщений</span>
              <input
                type="number"
                min={1}
                max={50}
                value={value.maxMessages}
                onChange={(e) => handleContentFilterChange('maxMessages', parseInt(e.target.value, 10) || 10)}
              />
            </label>
            <label>
              <span>Мин. размер кластера</span>
              <input
                type="number"
                min={1}
                max={10}
                value={value.minClusterSize}
                onChange={(e) => handleContentFilterChange('minClusterSize', parseInt(e.target.value, 10) || 2)}
              />
            </label>
            <label>
              <span>Мин. важность ({value.minImportanceScore.toFixed(1)})</span>
              <input
                type="range"
                min={0}
                max={1}
                step={0.1}
                value={value.minImportanceScore}
                onChange={(e) => handleContentFilterChange('minImportanceScore', parseFloat(e.target.value))}
              />
            </label>
          </div>
        </div>
      )}
    </div>
  )

  if (compact) {
    return content
  }

  return (
    <Section title="Темы и фильтры контента" accent="Фильтрация">
      {content}
    </Section>
  )
}

/**
 * Hook to manage topic configuration state.
 */
export function useTopicConfig(initial?: Partial<TopicConfig>) {
  const [config, setConfig] = useState<TopicConfig>({
    topicKeywords: initial?.topicKeywords ?? [],
    negativeKeywords: initial?.negativeKeywords ?? [],
    excludedChannelIds: initial?.excludedChannelIds ?? [],
    minImportanceScore: initial?.minImportanceScore ?? 0,
    sourceTrustThreshold: initial?.sourceTrustThreshold ?? 0,
    minClusterSize: initial?.minClusterSize ?? 2,
    lookbackHours: initial?.lookbackHours ?? 24,
    maxMessages: initial?.maxMessages ?? 10,
  })
  return [config, setConfig] as const
}

/**
 * Default topic config values.
 */
export const DEFAULT_TOPIC_CONFIG: TopicConfig = {
  topicKeywords: [],
  negativeKeywords: [],
  excludedChannelIds: [],
  minImportanceScore: 0,
  sourceTrustThreshold: 0,
  minClusterSize: 2,
  lookbackHours: 24,
  maxMessages: 10,
}
