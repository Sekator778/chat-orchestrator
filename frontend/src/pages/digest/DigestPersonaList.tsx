import { useEffect, useState, useCallback } from 'react'
import type { DigestPersona } from '../../types/digest'
import {
  fetchPersonas,
  enablePersona,
  disablePersona,
} from '../../api/digestClient'
import { PersonaCard } from '../../components/digest/PersonaCard'
import { Section } from '../../components/Section'

type NoticeTone = 'ok' | 'warn' | 'error'

interface Notice {
  message: string
  tone: NoticeTone
}

interface Props {
  onSelectPersona?: (persona: DigestPersona) => void
  onCreateNew?: () => void
  selectedPersonaId?: number | null
  refreshTrigger?: number
}

export function DigestPersonaList({
  onSelectPersona,
  onCreateNew,
  selectedPersonaId,
  refreshTrigger,
}: Props) {
  const [personas, setPersonas] = useState<DigestPersona[]>([])
  const [loading, setLoading] = useState(true)
  const [notice, setNotice] = useState<Notice | null>(null)
  const [searchQuery, setSearchQuery] = useState('')
  const [filterEnabled, setFilterEnabled] = useState<boolean | null>(null)

  const loadPersonas = useCallback(async () => {
    try {
      setLoading(true)
      const data = await fetchPersonas()
      setPersonas(data)
      setNotice(null)
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Не удалось загрузить персоны'
      setNotice({ tone: 'error', message })
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    loadPersonas()
  }, [loadPersonas, refreshTrigger])

  const handleTogglePersona = async (persona: DigestPersona) => {
    if (persona.id === null) return
    try {
      if (persona.enabled) {
        await disablePersona(persona.id)
        setNotice({ tone: 'ok', message: `Персона "${persona.name}" приостановлена` })
      } else {
        await enablePersona(persona.id)
        setNotice({ tone: 'ok', message: `Персона "${persona.name}" активирована` })
      }
      await loadPersonas()
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Ошибка изменения статуса'
      setNotice({ tone: 'error', message })
    }
  }

  const filteredPersonas = personas.filter((persona) => {
    if (searchQuery) {
      const query = searchQuery.toLowerCase()
      const matchesName = persona.name.toLowerCase().includes(query)
      const matchesDescription = persona.description?.toLowerCase().includes(query)
      if (!matchesName && !matchesDescription) return false
    }
    if (filterEnabled !== null && persona.enabled !== filterEnabled) {
      return false
    }
    return true
  })

  const enabledCount = personas.filter((p) => p.enabled).length

  if (loading) {
    return (
      <div className="digest-persona-list-page">
        <div className="placeholder">Загрузка персон...</div>
      </div>
    )
  }

  return (
    <div className="digest-persona-list-page">
      {notice && (
        <div className={`notice notice--${notice.tone}`}>
          {notice.message}
        </div>
      )}

      <div className="digest-persona-list-page__header">
        <div>
          <p className="eyebrow">Управление</p>
          <h2>Персоны дайджестов</h2>
          <p className="muted">
            Всего: {personas.length} | Активных: {enabledCount}
          </p>
        </div>
        {onCreateNew && (
          <button onClick={onCreateNew}>
            + Создать персону
          </button>
        )}
      </div>

      <div className="digest-persona-list-page__filters">
        <input
          type="text"
          placeholder="Поиск по названию или описанию..."
          value={searchQuery}
          onChange={(e) => setSearchQuery(e.target.value)}
        />
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
        <button className="ghost" onClick={loadPersonas} disabled={loading}>
          Обновить
        </button>
      </div>

      <Section title="Список персон" accent="Персоны" description={`Найдено: ${filteredPersonas.length}`}>
        {filteredPersonas.length === 0 ? (
          <div className="placeholder">
            {personas.length === 0 ? (
              <>
                <p>Нет созданных персон</p>
                <p className="muted tiny">Создайте первую персону для начала работы с дайджестами</p>
                {onCreateNew && (
                  <button onClick={onCreateNew} style={{ marginTop: '12px' }}>
                    + Создать персону
                  </button>
                )}
              </>
            ) : (
              <p className="muted">Нет персон, соответствующих фильтрам</p>
            )}
          </div>
        ) : (
          <div className="digest-persona-list">
            {filteredPersonas.map((persona) => (
              <PersonaCard
                key={persona.id ?? persona.name}
                persona={persona}
                isActive={selectedPersonaId === persona.id}
                onClick={() => onSelectPersona?.(persona)}
                onEnable={() => handleTogglePersona(persona)}
                onDisable={() => handleTogglePersona(persona)}
              />
            ))}
          </div>
        )}
      </Section>
    </div>
  )
}
