import { useState, useCallback, type ChangeEvent } from 'react'
import { EntityPanel, EntityPanelSection, DetailRow, Field, Chip } from './EntityPanel'
import { updateContextSettings } from '../../../api/client'
import { useConfigGraph } from '../hooks'
import type { ContextSettingsNodeData } from '../../../types/graph'
import type { ContextSettings } from '../../../types/api'

/**
 * Props for ContextSettingsPanel component
 */
export interface ContextSettingsPanelProps {
  data: ContextSettingsNodeData
  parentChatId: number
}

/**
 * Create empty context settings with chat config id
 */
function createEmptySettings(chatConfigId: number): ContextSettings {
  return {
    id: null,
    chat_config_id: chatConfigId,
    history_message_count: null,
    history_time_window_hours: null,
    include_user_context: false,
    include_media_descriptions: false,
    context_compression_enabled: false,
    max_context_tokens: null,
    preserve_important_messages: false,
  }
}

/**
 * Panel for viewing and editing context settings
 */
export function ContextSettingsPanel({ data, parentChatId }: ContextSettingsPanelProps) {
  const { refreshData } = useConfigGraph()
  const settings = data.settings

  const [isEditing, setIsEditing] = useState(false)
  const [isSaving, setIsSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [success, setSuccess] = useState<string | null>(null)

  const [formData, setFormData] = useState<ContextSettings>(
    settings ?? createEmptySettings(parentChatId)
  )

  const handleInputChange = useCallback((
    e: ChangeEvent<HTMLInputElement>
  ) => {
    const { name, value, type, checked } = e.target
    setFormData((prev) => ({
      ...prev,
      [name]: type === 'checkbox'
        ? checked
        : type === 'number'
          ? value === '' ? null : Number(value)
          : value || null,
    }))
  }, [])

  const handleEditToggle = useCallback(() => {
    if (isEditing) {
      setFormData(settings ?? createEmptySettings(parentChatId))
    }
    setIsEditing(!isEditing)
    setError(null)
    setSuccess(null)
  }, [isEditing, settings, parentChatId])

  const handleSave = useCallback(async () => {
    setIsSaving(true)
    setError(null)
    setSuccess(null)

    try {
      await updateContextSettings(parentChatId, formData)
      setSuccess('Context settings saved successfully')
      setIsEditing(false)
      await refreshData()
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to save context settings'
      setError(message)
    } finally {
      setIsSaving(false)
    }
  }, [parentChatId, formData, refreshData])

  const handleCancel = useCallback(() => {
    handleEditToggle()
  }, [handleEditToggle])

  // View mode
  if (!isEditing) {
    return (
      <EntityPanel
        title={data.label}
        entityType="Context Settings"
        status={data.status}
        editable
        isEditing={false}
        error={error}
        success={success}
        onEditToggle={handleEditToggle}
      >
        <EntityPanelSection title="History Settings">
          <DetailRow label="Message Count">
            <Chip variant="outline">{settings?.history_message_count ?? 'Default'}</Chip>
          </DetailRow>

          <DetailRow label="Time Window (hours)">
            <Chip variant="outline">{settings?.history_time_window_hours ?? 'Default'}</Chip>
          </DetailRow>

          <DetailRow label="Max Context Tokens">
            <Chip variant="outline">{settings?.max_context_tokens ?? 'Default'}</Chip>
          </DetailRow>
        </EntityPanelSection>

        <EntityPanelSection title="Context Options">
          <DetailRow label="Include User Context">
            <Chip variant={settings?.include_user_context ? 'green' : 'outline'}>
              {settings?.include_user_context ? 'Yes' : 'No'}
            </Chip>
          </DetailRow>

          <DetailRow label="Include Media Descriptions">
            <Chip variant={settings?.include_media_descriptions ? 'green' : 'outline'}>
              {settings?.include_media_descriptions ? 'Yes' : 'No'}
            </Chip>
          </DetailRow>

          <DetailRow label="Context Compression">
            <Chip variant={settings?.context_compression_enabled ? 'green' : 'outline'}>
              {settings?.context_compression_enabled ? 'Enabled' : 'Disabled'}
            </Chip>
          </DetailRow>

          <DetailRow label="Preserve Important Messages">
            <Chip variant={settings?.preserve_important_messages ? 'green' : 'outline'}>
              {settings?.preserve_important_messages ? 'Yes' : 'No'}
            </Chip>
          </DetailRow>
        </EntityPanelSection>
      </EntityPanel>
    )
  }

  // Edit mode
  return (
    <EntityPanel
      title={data.label}
      entityType="Context Settings"
      status={data.status}
      editable
      isEditing
      isSaving={isSaving}
      error={error}
      success={success}
      onSave={handleSave}
      onCancel={handleCancel}
    >
      <EntityPanelSection title="History Settings">
        <Field label="History Message Count" htmlFor="history_message_count" hint="Number of messages to include in context">
          <input
            type="number"
            id="history_message_count"
            name="history_message_count"
            value={formData.history_message_count ?? ''}
            onChange={handleInputChange}
            placeholder="Default"
            min={1}
            max={100}
          />
        </Field>

        <Field label="History Time Window (hours)" htmlFor="history_time_window_hours" hint="How far back to look for messages">
          <input
            type="number"
            id="history_time_window_hours"
            name="history_time_window_hours"
            value={formData.history_time_window_hours ?? ''}
            onChange={handleInputChange}
            placeholder="Default"
            min={1}
            max={168}
          />
        </Field>

        <Field label="Max Context Tokens" htmlFor="max_context_tokens" hint="Maximum tokens for context">
          <input
            type="number"
            id="max_context_tokens"
            name="max_context_tokens"
            value={formData.max_context_tokens ?? ''}
            onChange={handleInputChange}
            placeholder="Default"
            min={100}
            max={16384}
          />
        </Field>
      </EntityPanelSection>

      <EntityPanelSection title="Context Options">
        <Field label="Include User Context" htmlFor="include_user_context">
          <label className="entity-panel__checkbox">
            <input
              type="checkbox"
              id="include_user_context"
              name="include_user_context"
              checked={formData.include_user_context ?? false}
              onChange={handleInputChange}
            />
            <span>Include user profile information in context</span>
          </label>
        </Field>

        <Field label="Include Media Descriptions" htmlFor="include_media_descriptions">
          <label className="entity-panel__checkbox">
            <input
              type="checkbox"
              id="include_media_descriptions"
              name="include_media_descriptions"
              checked={formData.include_media_descriptions ?? false}
              onChange={handleInputChange}
            />
            <span>Include descriptions of images and media</span>
          </label>
        </Field>

        <Field label="Context Compression" htmlFor="context_compression_enabled">
          <label className="entity-panel__checkbox">
            <input
              type="checkbox"
              id="context_compression_enabled"
              name="context_compression_enabled"
              checked={formData.context_compression_enabled ?? false}
              onChange={handleInputChange}
            />
            <span>Compress older messages to save tokens</span>
          </label>
        </Field>

        <Field label="Preserve Important Messages" htmlFor="preserve_important_messages">
          <label className="entity-panel__checkbox">
            <input
              type="checkbox"
              id="preserve_important_messages"
              name="preserve_important_messages"
              checked={formData.preserve_important_messages ?? false}
              onChange={handleInputChange}
            />
            <span>Keep important messages uncompressed</span>
          </label>
        </Field>
      </EntityPanelSection>
    </EntityPanel>
  )
}
