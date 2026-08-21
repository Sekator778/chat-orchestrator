import { useState, useCallback, type ChangeEvent } from 'react'
import { EntityPanel, EntityPanelSection, DetailRow, Field, Chip } from './EntityPanel'
import { updateLlmParameters } from '../../../api/client'
import { useConfigGraph } from '../hooks'
import type { LlmNodeData } from '../../../types/graph'
import type { LlmParameters } from '../../../types/api'

/**
 * Props for LlmPanel component
 */
export interface LlmPanelProps {
  data: LlmNodeData
  parentChatId: number
}

/**
 * Create empty LLM parameters with chat config id
 */
function createEmptyParams(chatConfigId: number): LlmParameters {
  return {
    id: null,
    chat_config_id: chatConfigId,
    model_name: null,
    temperature: null,
    max_tokens: null,
    top_p: null,
    frequency_penalty: null,
    presence_penalty: null,
    system_prompt: null,
    custom_instructions: null,
    response_format: null,
  }
}

/**
 * Panel for viewing and editing LLM parameters
 */
export function LlmPanel({ data, parentChatId }: LlmPanelProps) {
  const { refreshData } = useConfigGraph()
  const params = data.params

  const [isEditing, setIsEditing] = useState(false)
  const [isSaving, setIsSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [success, setSuccess] = useState<string | null>(null)

  // Form state - initialize with existing params or empty
  const [formData, setFormData] = useState<LlmParameters>(
    params ?? createEmptyParams(parentChatId)
  )

  const handleInputChange = useCallback((
    e: ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>
  ) => {
    const { name, value, type } = e.target
    setFormData((prev) => ({
      ...prev,
      [name]: type === 'number'
        ? value === '' ? null : Number(value)
        : value || null,
    }))
  }, [])

  const handleEditToggle = useCallback(() => {
    if (isEditing) {
      // Reset form data on cancel
      setFormData(params ?? createEmptyParams(parentChatId))
    }
    setIsEditing(!isEditing)
    setError(null)
    setSuccess(null)
  }, [isEditing, params, parentChatId])

  const handleSave = useCallback(async () => {
    setIsSaving(true)
    setError(null)
    setSuccess(null)

    try {
      await updateLlmParameters(parentChatId, formData)
      setSuccess('LLM parameters saved successfully')
      setIsEditing(false)
      await refreshData()
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to save LLM parameters'
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
        entityType="LLM Parameters"
        status={data.status}
        editable
        isEditing={false}
        error={error}
        success={success}
        onEditToggle={handleEditToggle}
      >
        <EntityPanelSection title="Model Settings">
          <DetailRow label="Model">
            <Chip variant="violet">{params?.model_name ?? 'Default'}</Chip>
          </DetailRow>

          <DetailRow label="Temperature">
            <Chip variant="outline">{params?.temperature ?? 'Default'}</Chip>
          </DetailRow>

          <DetailRow label="Max Tokens">
            <Chip variant="outline">{params?.max_tokens ?? 'Default'}</Chip>
          </DetailRow>

          <DetailRow label="Top P">
            <Chip variant="outline">{params?.top_p ?? 'Default'}</Chip>
          </DetailRow>
        </EntityPanelSection>

        <EntityPanelSection title="Penalties" collapsible defaultCollapsed>
          <DetailRow label="Frequency Penalty">
            <Chip variant="outline">{params?.frequency_penalty ?? 'Default'}</Chip>
          </DetailRow>

          <DetailRow label="Presence Penalty">
            <Chip variant="outline">{params?.presence_penalty ?? 'Default'}</Chip>
          </DetailRow>
        </EntityPanelSection>

        <EntityPanelSection title="Advanced" collapsible defaultCollapsed>
          <DetailRow label="Response Format">
            <Chip variant="outline">{params?.response_format ?? 'Default'}</Chip>
          </DetailRow>

          {params?.system_prompt && (
            <div className="entity-panel__field">
              <span className="entity-panel__field-label">System Prompt</span>
              <div className="entity-panel__code-block">
                {params.system_prompt}
              </div>
            </div>
          )}

          {params?.custom_instructions && (
            <div className="entity-panel__field">
              <span className="entity-panel__field-label">Custom Instructions</span>
              <div className="entity-panel__code-block">
                {params.custom_instructions}
              </div>
            </div>
          )}
        </EntityPanelSection>
      </EntityPanel>
    )
  }

  // Edit mode
  return (
    <EntityPanel
      title={data.label}
      entityType="LLM Parameters"
      status={data.status}
      editable
      isEditing
      isSaving={isSaving}
      error={error}
      success={success}
      onSave={handleSave}
      onCancel={handleCancel}
    >
      <EntityPanelSection title="Model Settings">
        <Field label="Model Name" htmlFor="model_name" hint="e.g., deepseek-chat, gpt-4">
          <input
            type="text"
            id="model_name"
            name="model_name"
            value={formData.model_name ?? ''}
            onChange={handleInputChange}
            placeholder="deepseek-chat"
          />
        </Field>

        <Field label="Temperature" htmlFor="temperature" hint="0.0 = deterministic, 2.0 = very creative">
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

        <Field label="Max Tokens" htmlFor="max_tokens" hint="Maximum response length">
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

        <Field label="Top P" htmlFor="top_p" hint="Nucleus sampling threshold">
          <input
            type="number"
            id="top_p"
            name="top_p"
            value={formData.top_p ?? ''}
            onChange={handleInputChange}
            placeholder="0.9"
            min={0}
            max={1}
            step={0.05}
          />
        </Field>
      </EntityPanelSection>

      <EntityPanelSection title="Penalties" collapsible>
        <Field label="Frequency Penalty" htmlFor="frequency_penalty" hint="Reduce repetition of tokens">
          <input
            type="number"
            id="frequency_penalty"
            name="frequency_penalty"
            value={formData.frequency_penalty ?? ''}
            onChange={handleInputChange}
            placeholder="0.0"
            min={-2}
            max={2}
            step={0.1}
          />
        </Field>

        <Field label="Presence Penalty" htmlFor="presence_penalty" hint="Encourage new topics">
          <input
            type="number"
            id="presence_penalty"
            name="presence_penalty"
            value={formData.presence_penalty ?? ''}
            onChange={handleInputChange}
            placeholder="0.0"
            min={-2}
            max={2}
            step={0.1}
          />
        </Field>
      </EntityPanelSection>

      <EntityPanelSection title="Advanced" collapsible>
        <Field label="Response Format" htmlFor="response_format" hint="json_object or text">
          <select
            id="response_format"
            name="response_format"
            value={formData.response_format ?? ''}
            onChange={handleInputChange}
          >
            <option value="">Default</option>
            <option value="text">Text</option>
            <option value="json_object">JSON Object</option>
          </select>
        </Field>

        <Field label="System Prompt" htmlFor="system_prompt" hint="Override default system behavior">
          <textarea
            id="system_prompt"
            name="system_prompt"
            value={formData.system_prompt ?? ''}
            onChange={handleInputChange}
            placeholder="Enter system prompt..."
            rows={4}
          />
        </Field>

        <Field label="Custom Instructions" htmlFor="custom_instructions" hint="Additional instructions for the model">
          <textarea
            id="custom_instructions"
            name="custom_instructions"
            value={formData.custom_instructions ?? ''}
            onChange={handleInputChange}
            placeholder="Enter custom instructions..."
            rows={4}
          />
        </Field>
      </EntityPanelSection>
    </EntityPanel>
  )
}
