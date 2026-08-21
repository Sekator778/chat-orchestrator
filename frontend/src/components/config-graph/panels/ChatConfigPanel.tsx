import { useState, useCallback, type ChangeEvent } from 'react'
import { EntityPanel, EntityPanelSection, DetailRow, Field, Chip } from './EntityPanel'
import { updateBasicConfig } from '../../../api/client'
import { useConfigGraph } from '../hooks'
import type { ChatConfigNodeData } from '../../../types/graph'
import type { BasicConfigUpdate } from '../../../types/api'

/**
 * Props for ChatConfigPanel component
 */
export interface ChatConfigPanelProps {
  data: ChatConfigNodeData
}

/**
 * Panel for viewing and editing basic chat configuration
 */
export function ChatConfigPanel({ data }: ChatConfigPanelProps) {
  const { refreshData } = useConfigGraph()
  const config = data.config

  const [isEditing, setIsEditing] = useState(false)
  const [isSaving, setIsSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [success, setSuccess] = useState<string | null>(null)

  // Form state
  const [formData, setFormData] = useState<BasicConfigUpdate>({
    enabled: config.enabled,
    language: config.language,
    max_tokens: config.max_tokens,
    temperature: config.temperature,
    context_window_size: config.context_window_size,
    respond_to_forwarded_bot_messages: config.respond_to_forwarded_bot_messages,
    prompt_template: config.prompt_template,
  })

  const handleInputChange = useCallback((
    e: ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>
  ) => {
    const { name, value, type } = e.target
    setFormData((prev) => ({
      ...prev,
      [name]: type === 'checkbox'
        ? (e.target as HTMLInputElement).checked
        : type === 'number'
          ? value === '' ? null : Number(value)
          : value || null,
    }))
  }, [])

  const handleEditToggle = useCallback(() => {
    if (isEditing) {
      // Reset form data on cancel
      setFormData({
        enabled: config.enabled,
        language: config.language,
        max_tokens: config.max_tokens,
        temperature: config.temperature,
        context_window_size: config.context_window_size,
        respond_to_forwarded_bot_messages: config.respond_to_forwarded_bot_messages,
        prompt_template: config.prompt_template,
      })
    }
    setIsEditing(!isEditing)
    setError(null)
    setSuccess(null)
  }, [isEditing, config])

  const handleSave = useCallback(async () => {
    setIsSaving(true)
    setError(null)
    setSuccess(null)

    try {
      await updateBasicConfig(config.channel_id, formData)
      setSuccess('Configuration saved successfully')
      setIsEditing(false)
      // Refresh the graph data to reflect changes
      await refreshData()
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to save configuration'
      setError(message)
    } finally {
      setIsSaving(false)
    }
  }, [config.channel_id, formData, refreshData])

  const handleCancel = useCallback(() => {
    handleEditToggle()
  }, [handleEditToggle])

  // Render view mode
  if (!isEditing) {
    return (
      <EntityPanel
        title={data.label}
        entityType="Chat Config"
        status={data.status}
        editable
        isEditing={false}
        error={error}
        success={success}
        onEditToggle={handleEditToggle}
      >
        <EntityPanelSection title="Basic Settings">
          <DetailRow label="Channel ID">
            <Chip variant="outline">{config.channel_id}</Chip>
          </DetailRow>

          <DetailRow label="Enabled">
            <Chip variant={config.enabled ? 'green' : 'outline'}>
              {config.enabled ? 'Yes' : 'No'}
            </Chip>
          </DetailRow>

          <DetailRow label="Language">
            <Chip variant="violet">{config.language?.toUpperCase() ?? 'Not set'}</Chip>
          </DetailRow>

          <DetailRow label="Sync Enabled">
            <Chip variant={config.sync_enabled ? 'green' : 'outline'}>
              {config.sync_enabled ? 'Yes' : 'No'}
            </Chip>
          </DetailRow>

          <DetailRow label="Auto-Sync">
            <Chip variant={config.auto_sync_enabled ? 'green' : 'outline'}>
              {config.auto_sync_enabled ? 'Yes' : 'No'}
            </Chip>
          </DetailRow>
        </EntityPanelSection>

        <EntityPanelSection title="LLM Settings">
          <DetailRow label="Max Tokens">
            <Chip variant="outline">{config.max_tokens ?? 'Default'}</Chip>
          </DetailRow>

          <DetailRow label="Temperature">
            <Chip variant="outline">{config.temperature ?? 'Default'}</Chip>
          </DetailRow>

          <DetailRow label="Context Window">
            <Chip variant="outline">{config.context_window_size ?? 'Default'}</Chip>
          </DetailRow>
        </EntityPanelSection>

        <EntityPanelSection title="Behavior" collapsible defaultCollapsed>
          <DetailRow label="Reply to Forwarded Bot Messages">
            <Chip variant={config.respond_to_forwarded_bot_messages ? 'green' : 'outline'}>
              {config.respond_to_forwarded_bot_messages ? 'Yes' : 'No'}
            </Chip>
          </DetailRow>

          <DetailRow label="Wait for Human Replies">
            <Chip variant="outline">{config.wait_for_human_replies_count ?? 0}</Chip>
          </DetailRow>

          {config.primary_channel_id && (
            <DetailRow label="Primary Channel">
              <Chip variant="outline">{config.primary_channel_id}</Chip>
            </DetailRow>
          )}
        </EntityPanelSection>

        {config.prompt_template && (
          <EntityPanelSection title="Prompt Template" collapsible defaultCollapsed>
            <div className="entity-panel__code-block">
              {config.prompt_template}
            </div>
          </EntityPanelSection>
        )}

        <EntityPanelSection title="Related Entities" collapsible defaultCollapsed>
          <DetailRow label="Triggers">
            <Chip variant={config.trigger_conditions.length > 0 ? 'green' : 'outline'}>
              {config.trigger_conditions.length}
            </Chip>
          </DetailRow>

          <DetailRow label="Templates">
            <Chip variant={config.response_templates.length > 0 ? 'green' : 'outline'}>
              {config.response_templates.length}
            </Chip>
          </DetailRow>

          <DetailRow label="Restrictions">
            <Chip variant={config.topic_restrictions.length > 0 ? 'amber' : 'outline'}>
              {config.topic_restrictions.length}
            </Chip>
          </DetailRow>

          <DetailRow label="LLM Params">
            <Chip variant={config.llm_parameters ? 'green' : 'outline'}>
              {config.llm_parameters ? 'Configured' : 'Default'}
            </Chip>
          </DetailRow>

          <DetailRow label="Rate Limits">
            <Chip variant={config.rate_limits ? 'green' : 'outline'}>
              {config.rate_limits ? 'Configured' : 'Default'}
            </Chip>
          </DetailRow>

          <DetailRow label="Context Settings">
            <Chip variant={config.context_settings ? 'green' : 'outline'}>
              {config.context_settings ? 'Configured' : 'Default'}
            </Chip>
          </DetailRow>
        </EntityPanelSection>
      </EntityPanel>
    )
  }

  // Render edit mode
  return (
    <EntityPanel
      title={data.label}
      entityType="Chat Config"
      status={data.status}
      editable
      isEditing
      isSaving={isSaving}
      error={error}
      success={success}
      onSave={handleSave}
      onCancel={handleCancel}
    >
      <EntityPanelSection title="Basic Settings">
        <Field label="Enabled" htmlFor="enabled">
          <label className="entity-panel__checkbox">
            <input
              type="checkbox"
              id="enabled"
              name="enabled"
              checked={formData.enabled ?? false}
              onChange={handleInputChange}
            />
            <span>Enable responses for this chat</span>
          </label>
        </Field>

        <Field label="Language" htmlFor="language" hint="Two-letter language code (en, ru, uk)">
          <input
            type="text"
            id="language"
            name="language"
            value={formData.language ?? ''}
            onChange={handleInputChange}
            placeholder="en"
            maxLength={5}
          />
        </Field>
      </EntityPanelSection>

      <EntityPanelSection title="LLM Settings">
        <Field label="Max Tokens" htmlFor="max_tokens" hint="Maximum tokens in response">
          <input
            type="number"
            id="max_tokens"
            name="max_tokens"
            value={formData.max_tokens ?? ''}
            onChange={handleInputChange}
            placeholder="2048"
            min={1}
            max={8192}
          />
        </Field>

        <Field label="Temperature" htmlFor="temperature" hint="0.0 = deterministic, 1.0 = creative">
          <input
            type="number"
            id="temperature"
            name="temperature"
            value={formData.temperature ?? ''}
            onChange={handleInputChange}
            placeholder="0.7"
            min={0}
            max={2}
            step={0.1}
          />
        </Field>

        <Field label="Context Window Size" htmlFor="context_window_size" hint="Number of messages to include in context">
          <input
            type="number"
            id="context_window_size"
            name="context_window_size"
            value={formData.context_window_size ?? ''}
            onChange={handleInputChange}
            placeholder="10"
            min={1}
            max={100}
          />
        </Field>
      </EntityPanelSection>

      <EntityPanelSection title="Behavior">
        <Field label="Respond to Forwarded Bot Messages" htmlFor="respond_to_forwarded_bot_messages">
          <label className="entity-panel__checkbox">
            <input
              type="checkbox"
              id="respond_to_forwarded_bot_messages"
              name="respond_to_forwarded_bot_messages"
              checked={formData.respond_to_forwarded_bot_messages ?? false}
              onChange={handleInputChange}
            />
            <span>Reply to messages forwarded from bots</span>
          </label>
        </Field>
      </EntityPanelSection>

      <EntityPanelSection title="Prompt Template" collapsible>
        <Field label="System Prompt" htmlFor="prompt_template" hint="Custom instructions for the AI">
          <textarea
            id="prompt_template"
            name="prompt_template"
            value={formData.prompt_template ?? ''}
            onChange={handleInputChange}
            placeholder="Enter custom system prompt..."
            rows={6}
          />
        </Field>
      </EntityPanelSection>
    </EntityPanel>
  )
}
