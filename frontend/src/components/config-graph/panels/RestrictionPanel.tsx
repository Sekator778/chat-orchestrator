import { useState, useCallback, type ChangeEvent } from 'react'
import { EntityPanel, EntityPanelSection, DetailRow, Field, Chip } from './EntityPanel'
import { updateRestriction, toggleRestriction, deleteRestriction } from '../../../api/client'
import { useConfigGraph } from '../hooks'
import type { RestrictionNodeData } from '../../../types/graph'
import type { TopicRestriction } from '../../../types/api'

/**
 * Props for RestrictionPanel component
 */
export interface RestrictionPanelProps {
  data: RestrictionNodeData
}

/**
 * Formats type for display
 */
function formatType(type: string): string {
  return type
    .replace(/_/g, ' ')
    .toLowerCase()
    .replace(/\b\w/g, (c) => c.toUpperCase())
}

/**
 * Panel for viewing and editing topic restrictions
 */
export function RestrictionPanel({ data }: RestrictionPanelProps) {
  const { refreshData } = useConfigGraph()
  const restriction = data.restriction

  const [isEditing, setIsEditing] = useState(false)
  const [isSaving, setIsSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [success, setSuccess] = useState<string | null>(null)

  const [formData, setFormData] = useState<Partial<TopicRestriction>>(restriction)

  const handleInputChange = useCallback((
    e: ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>
  ) => {
    const { name, value, type } = e.target
    const checked = (e.target as HTMLInputElement).checked
    setFormData((prev) => ({
      ...prev,
      [name]: type === 'checkbox'
        ? checked
        : value || null,
    }))
  }, [])

  const handleEditToggle = useCallback(() => {
    if (isEditing) {
      setFormData(restriction)
    }
    setIsEditing(!isEditing)
    setError(null)
    setSuccess(null)
  }, [isEditing, restriction])

  const handleSave = useCallback(async () => {
    setIsSaving(true)
    setError(null)
    setSuccess(null)

    try {
      await updateRestriction(restriction.id, formData)
      setSuccess('Restriction saved successfully')
      setIsEditing(false)
      await refreshData()
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to save restriction'
      setError(message)
    } finally {
      setIsSaving(false)
    }
  }, [restriction.id, formData, refreshData])

  const handleCancel = useCallback(() => {
    handleEditToggle()
  }, [handleEditToggle])

  const handleToggleActive = useCallback(async () => {
    setIsSaving(true)
    setError(null)

    try {
      await toggleRestriction(restriction.id)
      setSuccess(restriction.active ? 'Restriction deactivated' : 'Restriction activated')
      await refreshData()
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to toggle restriction'
      setError(message)
    } finally {
      setIsSaving(false)
    }
  }, [restriction.id, restriction.active, refreshData])

  const handleDelete = useCallback(async () => {
    if (!confirm('Are you sure you want to delete this restriction?')) return

    setIsSaving(true)
    setError(null)

    try {
      await deleteRestriction(restriction.id)
      setSuccess('Restriction deleted')
      await refreshData()
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to delete restriction'
      setError(message)
    } finally {
      setIsSaving(false)
    }
  }, [restriction.id, refreshData])

  // View mode
  if (!isEditing) {
    return (
      <EntityPanel
        title={data.label}
        entityType="Topic Restriction"
        status={data.status}
        editable
        isEditing={false}
        error={error}
        success={success}
        onEditToggle={handleEditToggle}
      >
        <EntityPanelSection title="Restriction Settings">
          <DetailRow label="Name">
            <Chip variant="violet">{restriction.restriction_name}</Chip>
          </DetailRow>

          <DetailRow label="Type">
            <Chip variant="outline">{formatType(restriction.restriction_type)}</Chip>
          </DetailRow>

          <DetailRow label="Action">
            <Chip variant="red">{formatType(restriction.action_type)}</Chip>
          </DetailRow>

          <DetailRow label="Active">
            <Chip variant={restriction.active ? 'green' : 'red'}>
              {restriction.active ? 'Yes' : 'No'}
            </Chip>
          </DetailRow>
        </EntityPanelSection>

        <EntityPanelSection title="Filters">
          {restriction.keywords && (
            <DetailRow label="Keywords">
              <span className="entity-panel__text-value">{restriction.keywords}</span>
            </DetailRow>
          )}

          {restriction.categories && (
            <DetailRow label="Categories">
              <span className="entity-panel__text-value">{restriction.categories}</span>
            </DetailRow>
          )}
        </EntityPanelSection>

        {restriction.custom_response && (
          <EntityPanelSection title="Custom Response">
            <div className="entity-panel__field">
              <div className="entity-panel__code-block">
                {restriction.custom_response}
              </div>
            </div>
          </EntityPanelSection>
        )}

        <EntityPanelSection title="Actions">
          <div className="entity-panel__actions">
            <button
              type="button"
              className={`entity-panel__btn ${restriction.active ? 'entity-panel__btn--warning' : 'entity-panel__btn--success'}`}
              onClick={handleToggleActive}
              disabled={isSaving}
            >
              {restriction.active ? 'Deactivate' : 'Activate'}
            </button>
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
      entityType="Topic Restriction"
      status={data.status}
      editable
      isEditing
      isSaving={isSaving}
      error={error}
      success={success}
      onSave={handleSave}
      onCancel={handleCancel}
    >
      <EntityPanelSection title="Restriction Settings">
        <Field label="Restriction Name" htmlFor="restriction_name">
          <input
            type="text"
            id="restriction_name"
            name="restriction_name"
            value={formData.restriction_name ?? ''}
            onChange={handleInputChange}
            placeholder="My Restriction"
          />
        </Field>

        <Field label="Restriction Type" htmlFor="restriction_type">
          <select
            id="restriction_type"
            name="restriction_type"
            value={formData.restriction_type ?? ''}
            onChange={handleInputChange}
          >
            <option value="keyword">Keyword</option>
            <option value="category">Category</option>
            <option value="regex">Regex</option>
            <option value="semantic">Semantic</option>
          </select>
        </Field>

        <Field label="Action Type" htmlFor="action_type">
          <select
            id="action_type"
            name="action_type"
            value={formData.action_type ?? ''}
            onChange={handleInputChange}
          >
            <option value="ignore">Ignore</option>
            <option value="warn">Warn</option>
            <option value="block">Block</option>
            <option value="redirect">Redirect</option>
            <option value="custom">Custom Response</option>
          </select>
        </Field>
      </EntityPanelSection>

      <EntityPanelSection title="Filters">
        <Field label="Keywords" htmlFor="keywords" hint="Comma-separated list of keywords to block">
          <textarea
            id="keywords"
            name="keywords"
            value={formData.keywords ?? ''}
            onChange={handleInputChange}
            placeholder="keyword1, keyword2"
            rows={2}
          />
        </Field>

        <Field label="Categories" htmlFor="categories" hint="Comma-separated list of categories">
          <textarea
            id="categories"
            name="categories"
            value={formData.categories ?? ''}
            onChange={handleInputChange}
            placeholder="politics, religion"
            rows={2}
          />
        </Field>
      </EntityPanelSection>

      <EntityPanelSection title="Custom Response">
        <Field label="Custom Response" htmlFor="custom_response" hint="Response to send when restriction is triggered">
          <textarea
            id="custom_response"
            name="custom_response"
            value={formData.custom_response ?? ''}
            onChange={handleInputChange}
            placeholder="I cannot discuss this topic..."
            rows={4}
          />
        </Field>
      </EntityPanelSection>
    </EntityPanel>
  )
}
