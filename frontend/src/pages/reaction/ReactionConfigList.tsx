import { useEffect, useState, useCallback } from 'react'
import type { PersonaReactionConfig } from '../../types/reaction'
import { BOT_INSTANCES } from '../../types/reaction'
import { fetchAllConfigs, enableConfig, disableConfig, deleteConfig } from '../../api/reactionClient'
import { ReactionConfigCard } from '../../components/reaction/ReactionConfigCard'

type NoticeTone = 'ok' | 'warn' | 'error'

interface Notice {
  message: string
  tone: NoticeTone
}

interface Props {
  onSelectConfig?: (config: PersonaReactionConfig) => void
  onCreateNew?: () => void
  refreshTrigger?: number
}

export function ReactionConfigList({ onSelectConfig, onCreateNew, refreshTrigger }: Props) {
  const [configs, setConfigs] = useState<PersonaReactionConfig[]>([])
  const [loading, setLoading] = useState(true)
  const [notice, setNotice] = useState<Notice | null>(null)
  const [filterPersona, setFilterPersona] = useState<string>('')
  const [filterEnabled, setFilterEnabled] = useState<boolean | null>(null)

  const loadConfigs = useCallback(async () => {
    try {
      setLoading(true)
      const data = await fetchAllConfigs()
      setConfigs(data)
      setNotice(null)
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Не удалось загрузить конфигурации'
      setNotice({ tone: 'error', message })
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    loadConfigs()
  }, [loadConfigs, refreshTrigger])

  const handleToggle = async (config: PersonaReactionConfig) => {
    if (config.id === null) return
    try {
      if (config.enabled) {
        await disableConfig(config.id)
        setNotice({ tone: 'ok', message: `Конфигурация для канала ${config.channelId} отключена` })
      } else {
        await enableConfig(config.id)
        setNotice({ tone: 'ok', message: `Конфигурация для канала ${config.channelId} включена` })
      }
      await loadConfigs()
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Ошибка изменения статуса'
      setNotice({ tone: 'error', message })
    }
  }

  const handleDelete = async (config: PersonaReactionConfig) => {
    if (config.id === null) return
    if (!confirm(`Удалить конфигурацию для канала ${config.channelId}?`)) return
    try {
      await deleteConfig(config.id)
      setNotice({ tone: 'ok', message: 'Конфигурация удалена' })
      await loadConfigs()
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Ошибка удаления'
      setNotice({ tone: 'error', message })
    }
  }

  const filteredConfigs = configs.filter(config => {
    if (filterPersona && config.personaId !== filterPersona) return false
    if (filterEnabled !== null && config.enabled !== filterEnabled) return false
    return true
  })

  const enabledCount = configs.filter(c => c.enabled).length

  if (loading) {
    return (
      <div className="reaction-config-list-page">
        <div className="placeholder">Загрузка конфигураций...</div>
      </div>
    )
  }

  return (
    <div className="reaction-config-list-page">
      {notice && (
        <div className={`notice notice--${notice.tone}`}>
          {notice.message}
        </div>
      )}

      <div className="reaction-config-list-page__header">
        <div>
          <p className="eyebrow">Управление</p>
          <h2>Конфигурации реакций</h2>
          <p className="muted">
            Всего: {configs.length} | Активных: {enabledCount}
          </p>
        </div>
        {onCreateNew && (
          <button onClick={onCreateNew}>
            + Добавить конфигурацию
          </button>
        )}
      </div>

      <div className="reaction-config-list-page__filters">
        <select
          value={filterPersona}
          onChange={(e) => setFilterPersona(e.target.value)}
        >
          <option value="">Все персоны</option>
          {BOT_INSTANCES.map(bot => (
            <option key={bot.id} value={bot.id}>{bot.name}</option>
          ))}
        </select>
        <select
          value={filterEnabled === null ? '' : filterEnabled ? 'enabled' : 'disabled'}
          onChange={(e) => {
            const val = e.target.value
            setFilterEnabled(val === '' ? null : val === 'enabled')
          }}
        >
          <option value="">Все статусы</option>
          <option value="enabled">Только активные</option>
          <option value="disabled">Только неактивные</option>
        </select>
        <button className="ghost" onClick={loadConfigs} disabled={loading}>
          Обновить
        </button>
      </div>

      {filteredConfigs.length === 0 ? (
        <div className="placeholder">
          {configs.length === 0 ? (
            <>
              <p>Нет конфигураций реакций</p>
              <p className="muted tiny">Добавьте первую конфигурацию для начала работы</p>
              {onCreateNew && (
                <button onClick={onCreateNew} style={{ marginTop: '12px' }}>
                  + Добавить конфигурацию
                </button>
              )}
            </>
          ) : (
            <p className="muted">Нет конфигураций, соответствующих фильтрам</p>
          )}
        </div>
      ) : (
        <div className="reaction-config-list">
          {filteredConfigs.map(config => (
            <ReactionConfigCard
              key={config.id ?? `${config.personaId}-${config.channelId}`}
              config={config}
              onEdit={() => onSelectConfig?.(config)}
              onDelete={() => handleDelete(config)}
              onToggle={() => handleToggle(config)}
            />
          ))}
        </div>
      )}
    </div>
  )
}
