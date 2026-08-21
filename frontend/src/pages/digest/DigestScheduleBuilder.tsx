import { useState, useEffect, useMemo } from 'react'
import { SCHEDULE_PRESETS, TIMEZONES } from '../../types/digest'
import { fetchPersonaSchedule } from '../../api/digestClient'
import { Section } from '../../components/Section'

/**
 * Schedule configuration object.
 */
export interface ScheduleConfig {
  cron: string | null
  timezone: string
  activeHoursStart: string | null
  activeHoursEnd: string | null
}

interface Props {
  value: ScheduleConfig
  onChange: (config: ScheduleConfig) => void
  personaId?: number | null
  showPreview?: boolean
  previewCount?: number
  compact?: boolean
}

/**
 * Cron expression parts for visual builder.
 */
interface CronParts {
  second: string
  minute: string
  hour: string
  dayOfMonth: string
  month: string
  dayOfWeek: string
}

const DEFAULT_CRON_PARTS: CronParts = {
  second: '0',
  minute: '0',
  hour: '*',
  dayOfMonth: '*',
  month: '*',
  dayOfWeek: '*',
}

function parseCron(expression: string | null): CronParts {
  if (!expression) return DEFAULT_CRON_PARTS
  const parts = expression.split(' ')
  if (parts.length !== 6) return DEFAULT_CRON_PARTS
  return {
    second: parts[0],
    minute: parts[1],
    hour: parts[2],
    dayOfMonth: parts[3],
    month: parts[4],
    dayOfWeek: parts[5],
  }
}

function buildCron(parts: CronParts): string {
  return `${parts.second} ${parts.minute} ${parts.hour} ${parts.dayOfMonth} ${parts.month} ${parts.dayOfWeek}`
}

function describeCron(expression: string | null): string {
  if (!expression) return 'Расписание не задано'
  const parts = expression.split(' ')
  if (parts.length !== 6) return 'Некорректное выражение'
  const [_second, minute, hour, dayOfMonth, month, dayOfWeek] = parts
  if (hour === '*' && minute === '0') {
    return 'Каждый час'
  }
  if (hour.startsWith('*/')) {
    const interval = parseInt(hour.slice(2), 10)
    return `Каждые ${interval} часа(ов)`
  }
  if (hour.includes(',')) {
    const hours = hour.split(',')
    return `Каждый день в ${hours.join(', ')}:00`
  }
  if (dayOfWeek === 'MON-FRI') {
    return `По будням в ${hour}:${minute.padStart(2, '0')}`
  }
  if (dayOfMonth === '*' && month === '*' && dayOfWeek === '*') {
    return `Ежедневно в ${hour.padStart(2, '0')}:${minute.padStart(2, '0')}`
  }
  return expression
}

/**
 * Standalone schedule builder component for digest configuration.
 * Features:
 * - Cron expression presets
 * - Visual cron builder
 * - Timezone selection
 * - Active hours range
 * - Next runs preview
 */
export function DigestScheduleBuilder({
  value,
  onChange,
  personaId,
  showPreview = true,
  previewCount = 5,
  compact = false,
}: Props) {
  const [advancedMode, setAdvancedMode] = useState(false)
  const [cronParts, setCronParts] = useState<CronParts>(parseCron(value.cron))
  const [nextRuns, setNextRuns] = useState<string[]>([])
  const [loadingPreview, setLoadingPreview] = useState(false)

  useEffect(() => {
    setCronParts(parseCron(value.cron))
  }, [value.cron])

  useEffect(() => {
    if (!showPreview || !personaId || !value.cron) {
      setNextRuns([])
      return
    }
    let cancelled = false
    setLoadingPreview(true)
    fetchPersonaSchedule(personaId, previewCount)
      .then((runs) => {
        if (!cancelled) setNextRuns(runs)
      })
      .catch(() => {
        if (!cancelled) setNextRuns([])
      })
      .finally(() => {
        if (!cancelled) setLoadingPreview(false)
      })
    return () => {
      cancelled = true
    }
  }, [personaId, value.cron, value.timezone, showPreview, previewCount])

  const handlePresetSelect = (preset: string) => {
    onChange({ ...value, cron: preset })
  }

  const handleCronPartChange = (part: keyof CronParts, newValue: string) => {
    const newParts = { ...cronParts, [part]: newValue }
    setCronParts(newParts)
    onChange({ ...value, cron: buildCron(newParts) })
  }

  const handleTimezoneChange = (timezone: string) => {
    onChange({ ...value, timezone })
  }

  const handleActiveHoursChange = (field: 'activeHoursStart' | 'activeHoursEnd', time: string | null) => {
    onChange({ ...value, [field]: time || null })
  }

  const handleCronInputChange = (expression: string) => {
    onChange({ ...value, cron: expression || null })
  }

  const description = useMemo(() => describeCron(value.cron), [value.cron])

  const content = (
    <div className="digest-schedule-builder">
      {/* Cron Expression Input */}
      <div className="digest-schedule-builder__input-section">
        <label>
          <span>Cron-выражение</span>
          <div className="input-line">
            <input
              type="text"
              value={value.cron || ''}
              onChange={(e) => handleCronInputChange(e.target.value)}
              placeholder="0 0 */4 * * *"
            />
            <button
              type="button"
              className="ghost"
              onClick={() => setAdvancedMode(!advancedMode)}
            >
              {advancedMode ? 'Скрыть' : 'Построитель'}
            </button>
          </div>
        </label>
        {value.cron && (
          <p className="digest-schedule-builder__description muted tiny">{description}</p>
        )}
      </div>

      {/* Quick Presets */}
      <div className="digest-schedule-builder__presets">
        <p className="muted tiny">Быстрый выбор:</p>
        <div className="chips">
          {SCHEDULE_PRESETS.map((preset) => (
            <button
              key={preset.value}
              type="button"
              className={`chip ${value.cron === preset.value ? 'chip--violet' : 'chip--outline'}`}
              onClick={() => handlePresetSelect(preset.value)}
            >
              {preset.label}
            </button>
          ))}
        </div>
      </div>

      {/* Advanced Visual Builder */}
      {advancedMode && (
        <div className="digest-schedule-builder__advanced">
          <p className="muted tiny">Визуальный построитель:</p>
          <div className="digest-schedule-builder__parts">
            <label className="digest-schedule-builder__part">
              <span className="tiny">Секунда</span>
              <input
                type="text"
                value={cronParts.second}
                onChange={(e) => handleCronPartChange('second', e.target.value)}
                placeholder="0"
              />
            </label>
            <label className="digest-schedule-builder__part">
              <span className="tiny">Минута</span>
              <input
                type="text"
                value={cronParts.minute}
                onChange={(e) => handleCronPartChange('minute', e.target.value)}
                placeholder="0"
              />
            </label>
            <label className="digest-schedule-builder__part">
              <span className="tiny">Час</span>
              <input
                type="text"
                value={cronParts.hour}
                onChange={(e) => handleCronPartChange('hour', e.target.value)}
                placeholder="*"
              />
            </label>
            <label className="digest-schedule-builder__part">
              <span className="tiny">День месяца</span>
              <input
                type="text"
                value={cronParts.dayOfMonth}
                onChange={(e) => handleCronPartChange('dayOfMonth', e.target.value)}
                placeholder="*"
              />
            </label>
            <label className="digest-schedule-builder__part">
              <span className="tiny">Месяц</span>
              <input
                type="text"
                value={cronParts.month}
                onChange={(e) => handleCronPartChange('month', e.target.value)}
                placeholder="*"
              />
            </label>
            <label className="digest-schedule-builder__part">
              <span className="tiny">День недели</span>
              <input
                type="text"
                value={cronParts.dayOfWeek}
                onChange={(e) => handleCronPartChange('dayOfWeek', e.target.value)}
                placeholder="*"
              />
            </label>
          </div>
          <div className="digest-schedule-builder__help">
            <p className="muted tiny">
              Используйте: * (любое), */N (каждые N), N-M (диапазон), N,M (список)
            </p>
            <p className="muted tiny">
              Дни недели: MON, TUE, WED, THU, FRI, SAT, SUN или MON-FRI
            </p>
          </div>
        </div>
      )}

      {/* Timezone */}
      <div className={`digest-schedule-builder__timezone ${compact ? 'compact' : ''}`}>
        <label>
          <span>Часовой пояс</span>
          <select value={value.timezone} onChange={(e) => handleTimezoneChange(e.target.value)}>
            {TIMEZONES.map((tz) => (
              <option key={tz.value} value={tz.value}>
                {tz.label}
              </option>
            ))}
          </select>
        </label>
      </div>

      {/* Active Hours */}
      <div className="digest-schedule-builder__active-hours">
        <p className="muted tiny" style={{ marginBottom: 8 }}>
          Активные часы (оставьте пустыми для 24/7):
        </p>
        <div className="form-grid">
          <label>
            <span>Начало</span>
            <input
              type="time"
              value={value.activeHoursStart || ''}
              onChange={(e) => handleActiveHoursChange('activeHoursStart', e.target.value)}
            />
          </label>
          <label>
            <span>Конец</span>
            <input
              type="time"
              value={value.activeHoursEnd || ''}
              onChange={(e) => handleActiveHoursChange('activeHoursEnd', e.target.value)}
            />
          </label>
        </div>
        {value.activeHoursStart && value.activeHoursEnd && (
          <p className="muted tiny" style={{ marginTop: 8 }}>
            Публикации только с {value.activeHoursStart} до {value.activeHoursEnd}{' '}
            ({value.timezone})
          </p>
        )}
      </div>

      {/* Next Runs Preview */}
      {showPreview && personaId && value.cron && (
        <div className="digest-schedule-builder__preview">
          <p className="muted tiny">
            Ближайшие запуски{loadingPreview ? ' (загрузка...)' : ''}:
          </p>
          {nextRuns.length > 0 ? (
            <div className="digest-schedule-builder__runs">
              {nextRuns.map((run, index) => (
                <div key={index} className="chip chip--outline tiny">
                  {new Date(run).toLocaleString('ru-RU', {
                    day: '2-digit',
                    month: '2-digit',
                    hour: '2-digit',
                    minute: '2-digit',
                  })}
                </div>
              ))}
            </div>
          ) : (
            !loadingPreview && (
              <p className="muted tiny">Не удалось получить расписание</p>
            )
          )}
        </div>
      )}
    </div>
  )

  if (compact) {
    return content
  }

  return (
    <Section title="Расписание публикаций" accent="Автоматизация">
      {content}
    </Section>
  )
}

/**
 * Hook to manage schedule configuration state.
 */
export function useScheduleConfig(initial?: Partial<ScheduleConfig>) {
  const [config, setConfig] = useState<ScheduleConfig>({
    cron: initial?.cron ?? null,
    timezone: initial?.timezone ?? 'Europe/Moscow',
    activeHoursStart: initial?.activeHoursStart ?? null,
    activeHoursEnd: initial?.activeHoursEnd ?? null,
  })
  return [config, setConfig] as const
}
