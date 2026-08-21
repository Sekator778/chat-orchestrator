import { useState, useCallback, type ChangeEvent } from 'react'
import { EntityPanel, EntityPanelSection, DetailRow, Field, Chip } from './EntityPanel'
import { updateTemplate, deleteTemplate, setDefaultTemplate } from '../../../api/client'
import { useConfigGraph } from '../hooks'
import type { TemplateNodeData } from '../../../types/graph'
import type { ResponseTemplate } from '../../../types/api'

/**
 * Props for TemplatePanel component
 */
export interface TemplatePanelProps {
  data: TemplateNodeData
}

/**
 * Panel for viewing and editing response templates
 */
export function TemplatePanel({ data }: TemplatePanelProps) {
  const { refreshData } = useConfigGraph()
  const template = data.template

  const [isEditing, setIsEditing] = useState(false)
  const [isSaving, setIsSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [success, setSuccess] = useState<string | null>(null)

  const [formData, setFormData] = useState<Partial<ResponseTemplate>>(template)

  const handleInputChange = useCallback((
    e: ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>
  ) => {
    const { name, value, type } = e.target
    const checked = (e.target as HTMLInputElement).checked
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
      setFormData(template)
    }
    setIsEditing(!isEditing)
    setError(null)
    setSuccess(null)
  }, [isEditing, template])

  const handleSave = useCallback(async () => {
    setIsSaving(true)
    setError(null)
    setSuccess(null)

    try {
      await updateTemplate(template.id, formData)
      setSuccess('Template saved successfully')
      setIsEditing(false)
      await refreshData()
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to save template'
      setError(message)
    } finally {
      setIsSaving(false)
    }
  }, [template.id, formData, refreshData])

  const handleCancel = useCallback(() => {
    handleEditToggle()
  }, [handleEditToggle])

  const handleSetDefault = useCallback(async () => {
    setIsSaving(true)
    setError(null)

    try {
      await setDefaultTemplate(template.id)
      setSuccess('Template set as default')
      await refreshData()
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to set default template'
      setError(message)
    } finally {
      setIsSaving(false)
    }
  }, [template.id, refreshData])

  const handleDelete = useCallback(async () => {
    if (!confirm('Are you sure you want to delete this template?')) return

    setIsSaving(true)
    setError(null)

    try {
      await deleteTemplate(template.id)
      setSuccess('Template deleted')
      await refreshData()
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to delete template'
      setError(message)
    } finally {
      setIsSaving(false)
    }
  }, [template.id, refreshData])

  // View mode
  if (!isEditing) {
    return (
      <EntityPanel
        title={data.label}
        entityType="Response Template"
        status={data.status}
        editable
        isEditing={false}
        error={error}
        success={success}
        onEditToggle={handleEditToggle}
      >
        <EntityPanelSection title="Template Settings">
          <DetailRow label="Name">
            <Chip variant="violet">{template.template_name}</Chip>
          </DetailRow>

          <DetailRow label="Active">
            <Chip variant={template.active ? 'green' : 'red'}>
              {template.active ? 'Yes' : 'No'}
            </Chip>
          </DetailRow>

          <DetailRow label="Default">
            <Chip variant={template.is_default ? 'violet' : 'outline'}>
              {template.is_default ? 'Yes' : 'No'}
            </Chip>
          </DetailRow>

          {template.priority !== null && (
            <DetailRow label="Priority">
              <Chip variant="outline">{template.priority}</Chip>
            </DetailRow>
          )}
        </EntityPanelSection>

        <EntityPanelSection title="Style">
          {template.response_style && (
            <DetailRow label="Style">
              <Chip variant="outline">{template.response_style}</Chip>
            </DetailRow>
          )}

          {template.response_tone && (
            <DetailRow label="Tone">
              <Chip variant="outline">{template.response_tone}</Chip>
            </DetailRow>
          )}

          {template.max_response_length !== null && (
            <DetailRow label="Max Length">
              <Chip variant="outline">{template.max_response_length}</Chip>
            </DetailRow>
          )}
        </EntityPanelSection>

        <EntityPanelSection title="Content">
          <div className="entity-panel__field">
            <span className="entity-panel__field-label">Template Content</span>
            <div className="entity-panel__code-block">
              {template.template_content}
            </div>
          </div>
        </EntityPanelSection>

        <EntityPanelSection title="Actions">
          <div className="entity-panel__actions">
            {!template.is_default && (
              <button
                type="button"
                className="entity-panel__btn entity-panel__btn--primary"
                onClick={handleSetDefault}
                disabled={isSaving}
              >
                Set as Default
              </button>
            )}
            <button
              type="button"
              className="entity-panel__btn entity-panel__btn--danger"
              onClick={handleDelete}
              disabled={isSaving}
            >
              Delete
            </button>
          </div>
        </EntityPanelSection>
      </EntityPanel>
    )
  }

  // Edit mode
  return (
    <EntityPanel
      title={data.label}
      entityType="Response Template"
      status={data.status}
      editable
      isEditing
      isSaving={isSaving}
      error={error}
      success={success}
      onSave={handleSave}
      onCancel={handleCancel}
    >
      <EntityPanelSection title="Template Settings">
        <Field label="Template Name" htmlFor="template_name">
          <input
            type="text"
            id="template_name"
            name="template_name"
            value={formData.template_name ?? ''}
            onChange={handleInputChange}
            placeholder="My Template"
          />
        </Field>

        <Field label="Active" htmlFor="active">
          <input
            type="checkbox"
            id="active"
            name="active"
            checked={formData.active ?? false}
            onChange={handleInputChange}
          />
        </Field>

        <Field label="Priority" htmlFor="priority">
          <input
            type="number"
            id="priority"
            name="priority"
            value={formData.priority ?? ''}
            onChange={handleInputChange}
            min={0}
          />
        </Field>
      </EntityPanelSection>

      <EntityPanelSection title="Style">
        <Field label="Response Style" htmlFor="response_style">
          <select
            id="response_style"
            name="response_style"
            value={formData.response_style ?? ''}
            onChange={handleInputChange}
          >
            <option value="">Default</option>
            <option value="formal">Formal</option>
            <option value="casual">Casual</option>
            <option value="friendly">Friendly</option>
            <option value="professional">Professional</option>
            <option value="humorous">Humorous</option>
          </select>
        </Field>

        <Field label="Response Tone" htmlFor="response_tone">
          <select
            id="response_tone"
            name="response_tone"
            value={formData.response_tone ?? ''}
            onChange={handleInputChange}
          >
            <option value="">Default</option>
            <option value="neutral">Neutral</option>
            <option value="positive">Positive</option>
            <option value="empathetic">Empathetic</option>
            <option value="assertive">Assertive</option>
            <option value="encouraging">Encouraging</option>
          </select>
        </Field>

        <Field label="Max Response Length" htmlFor="max_response_length">
          <input
            type="number"
            id="max_response_length"
            name="max_response_length"
            value={formData.max_response_length ?? ''}
            onChange={handleInputChange}
            min={1}
          />
        </Field>
      </EntityPanelSection>

      <EntityPanelSection title="Content">
        <Field label="Template Content" htmlFor="template_content">
          <textarea
            id="template_content"
            name="template_content"
            value={formData.template_content ?? ''}
            onChange={handleInputChange}
            placeholder="Enter template content..."
            rows={8}
          />
        </Field>
      </EntityPanelSection>
    </EntityPanel>
  )
}
