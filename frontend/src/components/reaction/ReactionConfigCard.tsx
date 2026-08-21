import type { PersonaReactionConfig } from '../../types/reaction'
import { personaName } from '../../types/reaction'

interface Props {
  config: PersonaReactionConfig
  onEdit?: (config: PersonaReactionConfig) => void
  onDelete?: (config: PersonaReactionConfig) => void
  onToggle?: (config: PersonaReactionConfig) => void
}

export function ReactionConfigCard({ config, onEdit, onDelete, onToggle }: Props) {
  return (
    <div className={`reaction-config-card ${config.enabled ? 'reaction-config-card--enabled' : 'reaction-config-card--disabled'}`}>
      <div className="reaction-config-card__header">
        <div className="reaction-config-card__title">
          <span className="reaction-config-card__persona">{personaName(config.personaId)}</span>
          <span className={`chip tiny ${config.enabled ? 'chip--green' : 'chip--outline'}`}>
            {config.enabled ? 'Активно' : 'Отключено'}
          </span>
        </div>
        <span className="muted tiny reaction-config-card__channel">Канал: {config.channelId}</span>
      </div>

      <div className="reaction-config-card__body">
        <div className="reaction-config-card__stat">
          <span className="muted tiny">Макс. в день</span>
          <span className="reaction-config-card__stat-value">{config.maxPerDay}</span>
        </div>
        <div className="reaction-config-card__stat">
          <span className="muted tiny">ID персоны</span>
          <span className="reaction-config-card__stat-value tiny">{config.personaId}</span>
        </div>
      </div>

      <div className="reaction-config-card__actions" onClick={(e) => e.stopPropagation()}>
        {onToggle && (
          <button className="ghost" onClick={() => onToggle(config)}>
            {config.enabled ? 'Отключить' : 'Включить'}
          </button>
        )}
        {onEdit && (
          <button className="ghost" onClick={() => onEdit(config)}>
            Изменить
          </button>
        )}
        {onDelete && (
          <button className="ghost reaction-config-card__delete-btn" onClick={() => onDelete(config)}>
            Удалить
          </button>
        )}
      </div>
    </div>
  )
}
