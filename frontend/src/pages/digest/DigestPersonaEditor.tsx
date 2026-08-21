import { useEffect, useState, useCallback } from 'react'
import type {
  DigestPersona,
  DigestPersonaStyle,
  DigestPublishMode,
  CreatePersonaRequest,
  GeneratedDigest,
  DigestHistory,
} from '../../types/digest'
import {
  PERSONA_STYLES,
  PUBLISH_MODES,
  LANGUAGES,
  TIMEZONES,
  SCHEDULE_PRESETS,
} from '../../types/digest'
import {
  fetchPersona,
  createPersona,
  updatePersona,
  generateTestDigest,
  fetchPersonaHistory,
  fetchPersonaSchedule,
} from '../../api/digestClient'
import { Section } from '../../components/Section'
import { DigestPreview } from '../../components/digest/DigestPreview'

type NoticeTone = 'ok' | 'warn' | 'error'

interface Notice {
  message: string
  tone: NoticeTone
}

interface Props {
  personaId?: number | null
  onSaved?: (persona: DigestPersona) => void
  onCancel?: () => void
}

type FormState = CreatePersonaRequest & {
  id?: number | null
}

const defaultFormState = (): FormState => ({
  name: '',
  description: null,
  botId: 0,
  targetChannelId: 0,
  enabled: false,
  personaStyle: 'PROFESSIONAL',
  customSystemPrompt: null,
  scheduleCron: null,
  scheduleTimezone: 'Europe/Moscow',
  activeHoursStart: null,
  activeHoursEnd: null,
  lookbackHours: 24,
  maxMessages: 10,
  language: 'ru',
  minClusterSize: 2,
  minImportanceScore: 0.0,
  sourceTrustThreshold: 0.0,
  excludedChannelIds: [],
  topicKeywords: [],
  negativeKeywords: [],
  modelName: null,
  temperature: 0.7,
  maxTokens: 1000,
  publishMode: 'DIGEST',
  randomDelayMaxMinutes: 0,
})

export function DigestPersonaEditor({ personaId, onSaved, onCancel }: Props) {
  const [form, setForm] = useState<FormState>(defaultFormState())
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [notice, setNotice] = useState<Notice | null>(null)
  const [activeTab, setActiveTab] = useState<'basic' | 'schedule' | 'content' | 'llm' | 'history'>('basic')
  const [previewDigest, setPreviewDigest] = useState<GeneratedDigest | null>(null)
  const [testLoading, setTestLoading] = useState(false)
  const [history, setHistory] = useState<DigestHistory[]>([])
  const [nextRuns, setNextRuns] = useState<string[]>([])

  const [keywordInput, setKeywordInput] = useState('')
  const [negativeKeywordInput, setNegativeKeywordInput] = useState('')
  const [excludedChannelInput, setExcludedChannelInput] = useState('')

  const isEditing = personaId !== null && personaId !== undefined

  const loadPersona = useCallback(async () => {
    if (!personaId) return
    try {
      setLoading(true)
      const [persona, historyData, scheduleData] = await Promise.all([
        fetchPersona(personaId),
        fetchPersonaHistory(personaId, 10),
        fetchPersonaSchedule(personaId, 5),
      ])
      setForm({
        id: persona.id,
        name: persona.name,
        description: persona.description,
        botId: persona.botId,
        targetChannelId: persona.targetChannelId,
        enabled: persona.enabled,
        personaStyle: persona.personaStyle,
        customSystemPrompt: persona.customSystemPrompt,
        scheduleCron: persona.scheduleCron,
        scheduleTimezone: persona.scheduleTimezone,
        activeHoursStart: persona.activeHoursStart,
        activeHoursEnd: persona.activeHoursEnd,
        lookbackHours: persona.lookbackHours,
        maxMessages: persona.maxMessages,
        language: persona.language,
        minClusterSize: persona.minClusterSize,
        minImportanceScore: persona.minImportanceScore,
        sourceTrustThreshold: persona.sourceTrustThreshold,
        excludedChannelIds: persona.excludedChannelIds,
        topicKeywords: persona.topicKeywords,
        negativeKeywords: persona.negativeKeywords,
        modelName: persona.modelName,
        temperature: persona.temperature,
        maxTokens: persona.maxTokens,
        publishMode: persona.publishMode ?? 'DIGEST',
        randomDelayMaxMinutes: persona.randomDelayMaxMinutes ?? 0,
      })
      setHistory(historyData)
      setNextRuns(scheduleData)
      setNotice(null)
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Не удалось загрузить персону'
      setNotice({ tone: 'error', message })
    } finally {
      setLoading(false)
    }
  }, [personaId])

  useEffect(() => {
    if (personaId) {
      loadPersona()
    } else {
      setForm(defaultFormState())
      setHistory([])
      setNextRuns([])
    }
  }, [personaId, loadPersona])

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!form.name.trim()) {
      setNotice({ tone: 'error', message: 'Укажите название персоны' })
      return
    }
    if (!form.botId) {
      setNotice({ tone: 'error', message: 'Укажите ID бота' })
      return
    }
    if (!form.targetChannelId) {
      setNotice({ tone: 'error', message: 'Укажите ID целевого канала' })
      return
    }
    try {
      setSaving(true)
      let saved: DigestPersona
      if (isEditing && personaId) {
        saved = await updatePersona(personaId, form)
        setNotice({ tone: 'ok', message: 'Персона обновлена' })
      } else {
        saved = await createPersona(form)
        setNotice({ tone: 'ok', message: 'Персона создана' })
      }
      onSaved?.(saved)
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Ошибка сохранения'
      setNotice({ tone: 'error', message })
    } finally {
      setSaving(false)
    }
  }

  const handleTestDigest = async () => {
    if (!personaId) {
      setNotice({ tone: 'warn', message: 'Сначала сохраните персону' })
      return
    }
    try {
      setTestLoading(true)
      const digest = await generateTestDigest(personaId)
      setPreviewDigest(digest)
      setNotice({ tone: 'ok', message: 'Тестовый дайджест сгенерирован' })
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Ошибка генерации'
      setNotice({ tone: 'error', message })
    } finally {
      setTestLoading(false)
    }
  }

  const handleAddKeyword = () => {
    if (!keywordInput.trim()) return
    const keywords = keywordInput.split(',').map((k) => k.trim()).filter(Boolean)
    setForm((prev) => ({
      ...prev,
      topicKeywords: [...(prev.topicKeywords || []), ...keywords.filter((k) => !prev.topicKeywords?.includes(k))],
    }))
    setKeywordInput('')
  }

  const handleRemoveKeyword = (keyword: string) => {
    setForm((prev) => ({
      ...prev,
      topicKeywords: prev.topicKeywords?.filter((k) => k !== keyword) || [],
    }))
  }

  const handleAddNegativeKeyword = () => {
    if (!negativeKeywordInput.trim()) return
    const keywords = negativeKeywordInput.split(',').map((k) => k.trim()).filter(Boolean)
    setForm((prev) => ({
      ...prev,
      negativeKeywords: [...(prev.negativeKeywords || []), ...keywords.filter((k) => !prev.negativeKeywords?.includes(k))],
    }))
    setNegativeKeywordInput('')
  }

  const handleRemoveNegativeKeyword = (keyword: string) => {
    setForm((prev) => ({
      ...prev,
      negativeKeywords: prev.negativeKeywords?.filter((k) => k !== keyword) || [],
    }))
  }

  const handleAddExcludedChannel = () => {
    const channelId = parseInt(excludedChannelInput.trim(), 10)
    if (isNaN(channelId)) return
    setForm((prev) => ({
      ...prev,
      excludedChannelIds: [...(prev.excludedChannelIds || []), channelId].filter((v, i, arr) => arr.indexOf(v) === i),
    }))
    setExcludedChannelInput('')
  }

  const handleRemoveExcludedChannel = (channelId: number) => {
    setForm((prev) => ({
      ...prev,
      excludedChannelIds: prev.excludedChannelIds?.filter((id) => id !== channelId) || [],
    }))
  }

  if (loading) {
    return (
      <div className="digest-persona-editor">
        <div className="placeholder">Загрузка персоны...</div>
      </div>
    )
  }

  return (
    <div className="digest-persona-editor">
      {notice && (
        <div className={`notice notice--${notice.tone}`}>
          {notice.message}
        </div>
      )}

      <div className="digest-persona-editor__header">
        <div>
          <p className="eyebrow">{isEditing ? 'Редактирование' : 'Создание'}</p>
          <h2>{isEditing ? form.name || 'Персона' : 'Новая персона'}</h2>
        </div>
        <div className="actions">
          {onCancel && (
            <button className="ghost" onClick={onCancel}>
              Отмена
            </button>
          )}
          {isEditing && (
            <button
              className="ghost"
              onClick={handleTestDigest}
              disabled={testLoading}
            >
              {testLoading ? 'Генерация...' : 'Тест дайджеста'}
            </button>
          )}
        </div>
      </div>

      <div className="digest-persona-editor__tabs">
        <button
          className={`tab ${activeTab === 'basic' ? 'tab--active' : ''}`}
          onClick={() => setActiveTab('basic')}
        >
          Основное
        </button>
        <button
          className={`tab ${activeTab === 'schedule' ? 'tab--active' : ''}`}
          onClick={() => setActiveTab('schedule')}
        >
          Расписание
        </button>
        <button
          className={`tab ${activeTab === 'content' ? 'tab--active' : ''}`}
          onClick={() => setActiveTab('content')}
        >
          Контент
        </button>
        <button
          className={`tab ${activeTab === 'llm' ? 'tab--active' : ''}`}
          onClick={() => setActiveTab('llm')}
        >
          LLM
        </button>
        {isEditing && (
          <button
            className={`tab ${activeTab === 'history' ? 'tab--active' : ''}`}
            onClick={() => setActiveTab('history')}
          >
            История
          </button>
        )}
      </div>

      <form onSubmit={handleSubmit}>
        {activeTab === 'basic' && (
          <Section title="Основные настройки" accent="Идентификация">
            <div className="form-grid">
              <label>
                <span>Название персоны *</span>
                <input
                  type="text"
                  value={form.name}
                  onChange={(e) => setForm((prev) => ({ ...prev, name: e.target.value }))}
                  placeholder="Например: Lead Analyst"
                  required
                />
              </label>

              <label>
                <span>ID бота *</span>
                <input
                  type="number"
                  value={form.botId || ''}
                  onChange={(e) => setForm((prev) => ({ ...prev, botId: parseInt(e.target.value, 10) || 0 }))}
                  placeholder="2000000001"
                  required
                />
              </label>

              <label>
                <span>Целевой канал *</span>
                <input
                  type="number"
                  value={form.targetChannelId || ''}
                  onChange={(e) => setForm((prev) => ({ ...prev, targetChannelId: parseInt(e.target.value, 10) || 0 }))}
                  placeholder="-1001234567890"
                  required
                />
              </label>

              <label>
                <span>Язык</span>
                <select
                  value={form.language}
                  onChange={(e) => setForm((prev) => ({ ...prev, language: e.target.value }))}
                >
                  {LANGUAGES.map((lang) => (
                    <option key={lang.value} value={lang.value}>
                      {lang.label}
                    </option>
                  ))}
                </select>
              </label>
            </div>

            <label>
              <span>Описание</span>
              <textarea
                value={form.description || ''}
                onChange={(e) => setForm((prev) => ({ ...prev, description: e.target.value || null }))}
                placeholder="Опишите назначение персоны..."
                rows={3}
              />
            </label>

            <label>
              <span>Стиль персоны</span>
              <select
                value={form.personaStyle}
                onChange={(e) => setForm((prev) => ({ ...prev, personaStyle: e.target.value as DigestPersonaStyle }))}
              >
                {Object.entries(PERSONA_STYLES).map(([value, info]) => (
                  <option key={value} value={value}>
                    {info.label}
                  </option>
                ))}
              </select>
            </label>

            <p className="muted tiny">
              {PERSONA_STYLES[form.personaStyle || 'PROFESSIONAL'].description}
            </p>

            <label>
              <span>Режим публикации</span>
              <select
                value={form.publishMode ?? 'DIGEST'}
                onChange={(e) => setForm((prev) => ({ ...prev, publishMode: e.target.value as DigestPublishMode }))}
              >
                {Object.entries(PUBLISH_MODES).map(([value, info]) => (
                  <option key={value} value={value}>
                    {info.label}
                  </option>
                ))}
              </select>
            </label>

            <p className="muted tiny">
              {PUBLISH_MODES[form.publishMode ?? 'DIGEST'].description}
            </p>

            {form.personaStyle === 'CUSTOM' && (
              <label>
                <span>Пользовательский системный промпт</span>
                <textarea
                  value={form.customSystemPrompt || ''}
                  onChange={(e) => setForm((prev) => ({ ...prev, customSystemPrompt: e.target.value || null }))}
                  placeholder="Введите системный промпт для LLM..."
                  rows={6}
                />
              </label>
            )}

            <label className="checkbox">
              <input
                type="checkbox"
                checked={form.enabled}
                onChange={(e) => setForm((prev) => ({ ...prev, enabled: e.target.checked }))}
              />
              <span>Персона активна</span>
            </label>
          </Section>
        )}

        {activeTab === 'schedule' && (
          <Section title="Расписание публикаций" accent="Автоматизация">
            <div className="form-grid">
              <label>
                <span>Cron-выражение</span>
                <input
                  type="text"
                  value={form.scheduleCron || ''}
                  onChange={(e) => setForm((prev) => ({ ...prev, scheduleCron: e.target.value || null }))}
                  placeholder="0 0 */4 * * *"
                />
              </label>

              <label>
                <span>Часовой пояс</span>
                <select
                  value={form.scheduleTimezone}
                  onChange={(e) => setForm((prev) => ({ ...prev, scheduleTimezone: e.target.value }))}
                >
                  {TIMEZONES.map((tz) => (
                    <option key={tz.value} value={tz.value}>
                      {tz.label}
                    </option>
                  ))}
                </select>
              </label>

              <label>
                <span>Макс. задержка (мин)</span>
                <input
                  type="number"
                  min={0}
                  max={60}
                  value={form.randomDelayMaxMinutes ?? 0}
                  onChange={(e) => setForm((prev) => ({ ...prev, randomDelayMaxMinutes: parseInt(e.target.value, 10) || 0 }))}
                  placeholder="0"
                />
              </label>
            </div>
            <p className="muted tiny">
              Случайная задержка публикации (0 = отключено). Помогает избежать публикации точно по расписанию.
            </p>

            <div className="digest-schedule-presets">
              <p className="muted tiny">Быстрый выбор:</p>
              <div className="chips">
                {SCHEDULE_PRESETS.map((preset) => (
                  <button
                    key={preset.value}
                    type="button"
                    className={`chip ${form.scheduleCron === preset.value ? 'chip--violet' : 'chip--outline'}`}
                    onClick={() => setForm((prev) => ({ ...prev, scheduleCron: preset.value }))}
                  >
                    {preset.label}
                  </button>
                ))}
              </div>
            </div>

            <div className="form-grid">
              <label>
                <span>Начало активных часов</span>
                <input
                  type="time"
                  value={form.activeHoursStart || ''}
                  onChange={(e) => setForm((prev) => ({ ...prev, activeHoursStart: e.target.value || null }))}
                />
              </label>

              <label>
                <span>Конец активных часов</span>
                <input
                  type="time"
                  value={form.activeHoursEnd || ''}
                  onChange={(e) => setForm((prev) => ({ ...prev, activeHoursEnd: e.target.value || null }))}
                />
              </label>
            </div>

            <p className="muted tiny">
              Оставьте пустыми для работы 24/7. Публикации будут только в указанный период.
            </p>

            {isEditing && nextRuns.length > 0 && (
              <div className="digest-next-runs">
                <p className="muted tiny">Ближайшие запуски:</p>
                <div className="digest-next-runs__list">
                  {nextRuns.map((run, index) => (
                    <div key={index} className="chip chip--outline tiny">
                      {new Date(run).toLocaleString('ru-RU')}
                    </div>
                  ))}
                </div>
              </div>
            )}
          </Section>
        )}

        {activeTab === 'content' && (
          <Section title="Настройки контента" accent="Фильтрация">
            <div className="form-grid">
              <label>
                <span>Окно просмотра (часы)</span>
                <input
                  type="number"
                  min={1}
                  max={168}
                  value={form.lookbackHours}
                  onChange={(e) => setForm((prev) => ({ ...prev, lookbackHours: parseInt(e.target.value, 10) || 24 }))}
                />
              </label>

              <label>
                <span>Макс. сообщений в дайджесте</span>
                <input
                  type="number"
                  min={1}
                  max={50}
                  value={form.maxMessages}
                  onChange={(e) => setForm((prev) => ({ ...prev, maxMessages: parseInt(e.target.value, 10) || 10 }))}
                />
              </label>

              <label>
                <span>Мин. размер кластера</span>
                <input
                  type="number"
                  min={1}
                  max={10}
                  value={form.minClusterSize}
                  onChange={(e) => setForm((prev) => ({ ...prev, minClusterSize: parseInt(e.target.value, 10) || 2 }))}
                />
              </label>

              <label>
                <span>Мин. оценка важности</span>
                <input
                  type="number"
                  min={0}
                  max={1}
                  step={0.1}
                  value={form.minImportanceScore}
                  onChange={(e) => setForm((prev) => ({ ...prev, minImportanceScore: parseFloat(e.target.value) || 0 }))}
                />
              </label>

              <label>
                <span>Мин. доверие к источнику</span>
                <input
                  type="number"
                  min={0}
                  max={1}
                  step={0.1}
                  value={form.sourceTrustThreshold}
                  onChange={(e) => setForm((prev) => ({ ...prev, sourceTrustThreshold: parseFloat(e.target.value) || 0 }))}
                />
              </label>
            </div>

            <div className="digest-keywords-section">
              <label>
                <span>Ключевые слова (фильтр тем)</span>
                <div className="input-line">
                  <input
                    type="text"
                    value={keywordInput}
                    onChange={(e) => setKeywordInput(e.target.value)}
                    placeholder="Введите слова через запятую..."
                    onKeyDown={(e) => e.key === 'Enter' && (e.preventDefault(), handleAddKeyword())}
                  />
                  <button type="button" className="ghost" onClick={handleAddKeyword}>
                    Добавить
                  </button>
                </div>
              </label>
              {form.topicKeywords && form.topicKeywords.length > 0 && (
                <div className="chips">
                  {form.topicKeywords.map((keyword) => (
                    <span key={keyword} className="chip chip--green">
                      {keyword}
                      <button
                        type="button"
                        className="chip__remove"
                        onClick={() => handleRemoveKeyword(keyword)}
                      >
                        ×
                      </button>
                    </span>
                  ))}
                </div>
              )}
            </div>

            <div className="digest-keywords-section">
              <label>
                <span>Негативные ключевые слова (исключение)</span>
                <div className="input-line">
                  <input
                    type="text"
                    value={negativeKeywordInput}
                    onChange={(e) => setNegativeKeywordInput(e.target.value)}
                    placeholder="Слова для исключения..."
                    onKeyDown={(e) => e.key === 'Enter' && (e.preventDefault(), handleAddNegativeKeyword())}
                  />
                  <button type="button" className="ghost" onClick={handleAddNegativeKeyword}>
                    Добавить
                  </button>
                </div>
              </label>
              {form.negativeKeywords && form.negativeKeywords.length > 0 && (
                <div className="chips">
                  {form.negativeKeywords.map((keyword) => (
                    <span key={keyword} className="chip chip--warn">
                      {keyword}
                      <button
                        type="button"
                        className="chip__remove"
                        onClick={() => handleRemoveNegativeKeyword(keyword)}
                      >
                        ×
                      </button>
                    </span>
                  ))}
                </div>
              )}
            </div>

            <div className="digest-keywords-section">
              <label>
                <span>Исключенные каналы (ID)</span>
                <div className="input-line">
                  <input
                    type="text"
                    value={excludedChannelInput}
                    onChange={(e) => setExcludedChannelInput(e.target.value)}
                    placeholder="ID канала..."
                    onKeyDown={(e) => e.key === 'Enter' && (e.preventDefault(), handleAddExcludedChannel())}
                  />
                  <button type="button" className="ghost" onClick={handleAddExcludedChannel}>
                    Добавить
                  </button>
                </div>
              </label>
              {form.excludedChannelIds && form.excludedChannelIds.length > 0 && (
                <div className="chips">
                  {form.excludedChannelIds.map((channelId) => (
                    <span key={channelId} className="chip chip--outline">
                      {channelId}
                      <button
                        type="button"
                        className="chip__remove"
                        onClick={() => handleRemoveExcludedChannel(channelId)}
                      >
                        ×
                      </button>
                    </span>
                  ))}
                </div>
              )}
            </div>
          </Section>
        )}

        {activeTab === 'llm' && (
          <Section title="Параметры LLM" accent="Генерация">
            <div className="form-grid">
              <label>
                <span>Модель</span>
                <input
                  type="text"
                  value={form.modelName || ''}
                  onChange={(e) => setForm((prev) => ({ ...prev, modelName: e.target.value || null }))}
                  placeholder="deepseek-chat (по умолчанию)"
                />
              </label>

              <label>
                <span>Температура ({form.temperature})</span>
                <input
                  type="range"
                  min={0}
                  max={1}
                  step={0.1}
                  value={form.temperature}
                  onChange={(e) => setForm((prev) => ({ ...prev, temperature: parseFloat(e.target.value) }))}
                />
              </label>

              <label>
                <span>Макс. токенов</span>
                <input
                  type="number"
                  min={100}
                  max={4000}
                  step={100}
                  value={form.maxTokens}
                  onChange={(e) => setForm((prev) => ({ ...prev, maxTokens: parseInt(e.target.value, 10) || 1000 }))}
                />
              </label>
            </div>

            <p className="muted tiny">
              Температура: 0 - более предсказуемый, 1 - более креативный. Рекомендуется 0.5-0.7.
            </p>
          </Section>
        )}

        {activeTab === 'history' && isEditing && (
          <Section title="История публикаций" accent="Журнал">
            {history.length === 0 ? (
              <div className="placeholder">
                <p className="muted">Нет истории публикаций</p>
              </div>
            ) : (
              <div className="digest-history-list">
                {history.map((entry) => (
                  <div key={entry.id} className="digest-history-item">
                    <div className="digest-history-item__header">
                      <span className={`chip ${entry.status === 'PUBLISHED' ? 'chip--green' : entry.status === 'FAILED' ? 'chip--warn' : 'chip--outline'}`}>
                        {entry.status}
                      </span>
                      <span className="muted tiny">
                        {new Date(entry.createdAt).toLocaleString('ru-RU')}
                      </span>
                    </div>
                    <div className="digest-history-item__meta">
                      <span className="tiny">{entry.messagesIncluded} сообщений</span>
                      <span className="tiny">{entry.clustersUsed} кластеров</span>
                      <span className="tiny">{entry.generationTimeMs}мс</span>
                    </div>
                    {entry.errorMessage && (
                      <p className="digest-history-item__error muted tiny">{entry.errorMessage}</p>
                    )}
                    <p className="digest-history-item__preview tiny">
                      {entry.content.substring(0, 200)}...
                    </p>
                  </div>
                ))}
              </div>
            )}
          </Section>
        )}

        <div className="digest-persona-editor__actions">
          <button type="submit" disabled={saving}>
            {saving ? 'Сохранение...' : isEditing ? 'Сохранить изменения' : 'Создать персону'}
          </button>
        </div>
      </form>

      {previewDigest && (
        <DigestPreview
          digest={previewDigest}
          onClose={() => setPreviewDigest(null)}
        />
      )}
    </div>
  )
}
