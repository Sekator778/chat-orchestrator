import { useEffect, useState, useCallback } from 'react'
import type { PersonaReactionConfig, CreateReactionConfigRequest } from '../../types/reaction'
import { BOT_INSTANCES } from '../../types/reaction'
import { createConfig, updateConfig, deleteConfig, fetchPersonaConfigs } from '../../api/reactionClient'

type NoticeTone = 'ok' | 'warn' | 'error'

interface Notice {
  message: string
  tone: NoticeTone
}

interface Props {
  configId?: number | null
  onSaved?: (config: PersonaReactionConfig) => void
  onCancel?: () => void
}

type FormState = {
  personaId: string
  channelId: number
  maxPerDay: number
  enabled: boolean
}

const defaultFormState = (): FormState => ({
  personaId: BOT_INSTANCES[0].id,
  channelId: 0,
  maxPerDay: 2,
  enabled: true,
})

export function ReactionConfigEditor({ configId, onSaved, onCancel }: Props) {
  const [form, setForm] = useState<FormState>(defaultFormState())
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const [notice, setNotice] = useState<Notice | null>(null)

  const isEditing = configId !== null && configId !== undefined

  const loadConfig = useCallback(async () => {
    if (!configId) return
    try {
      setLoading(true)
      const allConfigs = await fetchPersonaConfigs(BOT_INSTANCES[0].id)
      const found = allConfigs.find(c => c.id === configId)
      if (found) {
        setForm({
          personaId: found.personaId,
          channelId: found.channelId,
          maxPerDay: found.maxPerDay,
          enabled: found.enabled,
        })
      }
      setNotice(null)
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Не удалось загрузить конфигурацию'
      setNotice({ tone: 'error', message })
    } finally {
      setLoading(false)
    }
  }, [configId])

  useEffect(() => {
    if (isEditing) {
      loadConfig()
    } else {
      setForm(defaultFormState())
    }
  }, [configId, isEditing, loadConfig])

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!form.channelId) {
      setNotice({ tone: 'error', message: 'Укажите ID канала' })
      return
    }
    if (form.maxPerDay < 1) {
      setNotice({ tone: 'error', message: 'Минимум 1 реакция в день' })
      return
    }
    try {
      setSaving(true)
      let saved: PersonaReactionConfig
      if (isEditing && configId) {
        saved = await updateConfig(configId, { maxPerDay: form.maxPerDay, enabled: form.enabled })
        setNotice({ tone: 'ok', message: 'Конфигурация обновлена' })
      } else {
        const request: CreateReactionConfigRequest = {
          personaId: form.personaId,
          channelId: form.channelId,
          maxPerDay: form.maxPerDay,
          enabled: form.enabled,
        }
        saved = await createConfig(request)
        setNotice({ tone: 'ok', message: 'Конфигурация создана' })
      }
      onSaved?.(saved)
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Ошибка сохранения'
      setNotice({ tone: 'error', message })
    } finally {
      setSaving(false)
    }
  }

  const handleDelete = async () => {
    if (!configId) return
    if (!confirm('Удалить конфигурацию реакций?')) return
    try {
      setDeleting(true)
      await deleteConfig(configId)
      onCancel?.()
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Ошибка удаления'
      setNotice({ tone: 'error', message })
    } finally {
      setDeleting(false)
    }
  }

  if (loading) {
    return (
      <div className="reaction-config-editor">
        <div className="placeholder">Загрузка конфигурации...</div>
      </div>
    )
  }

  return (
    <div className="reaction-config-editor">
      {notice && (
        <div className={`notice notice--${notice.tone}`}>
          {notice.message}
        </div>
      )}

      <div className="reaction-config-editor__header">
        <div>
          <p className="eyebrow">{isEditing ? 'Редактирование' : 'Создание'}</p>
          <h2>{isEditing ? 'Конфигурация реакций' : 'Новая конфигурация'}</h2>
        </div>
        <div className="actions">
          {onCancel && (
            <button className="ghost" onClick={onCancel}>
              Отмена
            </button>
          )}
          {isEditing && (
            <button
              className="ghost reaction-config-editor__delete-btn"
              onClick={handleDelete}
              disabled={deleting}
            >
              {deleting ? 'Удаление...' : 'Удалить'}
            </button>
          )}
        </div>
      </div>

      <form onSubmit={handleSubmit} className="reaction-config-editor__form">
        <div className="form-grid">
          <label>
            <span>Персона *</span>
            <select
              value={form.personaId}
              onChange={(e) => setForm(prev => ({ ...prev, personaId: e.target.value }))}
              disabled={isEditing}
            >
              {BOT_INSTANCES.map(bot => (
                <option key={bot.id} value={bot.id}>{bot.name} ({bot.id})</option>
              ))}
            </select>
          </label>

          <label>
            <span>ID канала *</span>
            <input
              type="number"
              value={form.channelId || ''}
              onChange={(e) => setForm(prev => ({ ...prev, channelId: parseInt(e.target.value, 10) || 0 }))}
              placeholder="-1001234567890"
              required
              disabled={isEditing}
            />
          </label>

          <label>
            <span>Максимум реакций в день</span>
            <input
              type="number"
              min={1}
              max={20}
              value={form.maxPerDay}
              onChange={(e) => setForm(prev => ({ ...prev, maxPerDay: parseInt(e.target.value, 10) || 2 }))}
            />
          </label>
        </div>

        <label className="checkbox">
          <input
            type="checkbox"
            checked={form.enabled}
            onChange={(e) => setForm(prev => ({ ...prev, enabled: e.target.checked }))}
          />
          <span>Конфигурация активна</span>
        </label>

        <p className="muted tiny" style={{ marginTop: '8px' }}>
          Реакции ставятся с рандомной задержкой 30–180 секунд после публикации поста. Пул: 👍 60%, 🔥 30%, 💯 10%.
        </p>

        <div className="reaction-config-editor__actions">
          <button type="submit" disabled={saving}>
            {saving ? 'Сохранение...' : isEditing ? 'Сохранить изменения' : 'Создать конфигурацию'}
          </button>
        </div>
      </form>
    </div>
  )
}
