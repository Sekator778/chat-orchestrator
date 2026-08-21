import type { DigestPersona } from '../../types/digest'
import { PERSONA_STYLES, PUBLISH_MODES } from '../../types/digest'
import { StatusIndicator } from './StatusIndicator'

interface Props {
  persona: DigestPersona
  isActive?: boolean
  onClick?: () => void
  onEnable?: () => void
  onDisable?: () => void
  onTest?: () => void
  onPublish?: () => void
}

function formatLastRun(lastRunAt: string | null): string {
  if (!lastRunAt) return 'Никогда'
  const date = new Date(lastRunAt)
  const now = new Date()
  const diffMs = now.getTime() - date.getTime()
  const diffMins = Math.floor(diffMs / 60000)
  if (diffMins < 1) return 'Только что'
  if (diffMins < 60) return `${diffMins} мин назад`
  const diffHours = Math.floor(diffMins / 60)
  if (diffHours < 24) return `${diffHours} ч назад`
  const diffDays = Math.floor(diffHours / 24)
  return `${diffDays} дн назад`
}

export function PersonaCard({
  persona,
  isActive = false,
  onClick,
  onEnable,
  onDisable,
  onTest,
  onPublish,
}: Props) {
  const styleInfo = PERSONA_STYLES[persona.personaStyle]
  const publishMode = persona.publishMode ?? 'DIGEST'
  const publishModeInfo = PUBLISH_MODES[publishMode]
  const statusType = persona.enabled ? 'active' : 'paused'

  return (
    <div
      className={`digest-persona-card ${isActive ? 'active' : ''}`}
      onClick={onClick}
      role={onClick ? 'button' : undefined}
      tabIndex={onClick ? 0 : undefined}
    >
      <div className="digest-persona-card__header">
        <div className="digest-persona-card__title">
          <span className="digest-persona-card__name">{persona.name}</span>
          <StatusIndicator status={statusType} size="small" />
        </div>
        <div className="digest-persona-card__style">
          <span className="chip chip--violet">{styleInfo.label}</span>
          {publishMode === 'OPINION_POST' && (
            <span className="chip chip--outline" title={publishModeInfo.description}>
              Мнение
            </span>
          )}
        </div>
      </div>

      {persona.description && (
        <p className="digest-persona-card__description muted tiny">
          {persona.description.length > 80
            ? persona.description.substring(0, 80) + '...'
            : persona.description}
        </p>
      )}

      <div className="digest-persona-card__meta">
        <div className="digest-persona-card__meta-item">
          <span className="muted tiny">Канал:</span>
          <span className="tiny">{persona.targetChannelId}</span>
        </div>
        <div className="digest-persona-card__meta-item">
          <span className="muted tiny">Язык:</span>
          <span className="tiny">{persona.language.toUpperCase()}</span>
        </div>
      </div>

      <div className="digest-persona-card__stats">
        <div className="digest-persona-card__stat">
          <span className="digest-persona-card__stat-value">{persona.totalDigestsPublished}</span>
          <span className="digest-persona-card__stat-label muted tiny">Опубликовано</span>
        </div>
        <div className="digest-persona-card__stat">
          <span className="digest-persona-card__stat-value">{formatLastRun(persona.lastRunAt)}</span>
          <span className="digest-persona-card__stat-label muted tiny">Последний запуск</span>
        </div>
      </div>

      {(onEnable || onDisable || onTest || onPublish) && (
        <div className="digest-persona-card__actions" onClick={(e) => e.stopPropagation()}>
          {persona.enabled && onDisable && (
            <button className="ghost" onClick={onDisable}>
              Пауза
            </button>
          )}
          {!persona.enabled && onEnable && (
            <button className="ghost" onClick={onEnable}>
              Включить
            </button>
          )}
          {onTest && (
            <button className="ghost" onClick={onTest}>
              Тест
            </button>
          )}
          {onPublish && persona.enabled && (
            <button onClick={onPublish}>
              Опубликовать
            </button>
          )}
        </div>
      )}
    </div>
  )
}
