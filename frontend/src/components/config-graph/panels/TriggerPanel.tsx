import { useState, useCallback, type ChangeEvent } from 'react'
import { EntityPanel, EntityPanelSection, DetailRow, Field, Chip } from './EntityPanel'
import { updateTrigger, toggleTrigger, deleteTrigger } from '../../../api/client'
import { useConfigGraph } from '../hooks'
import type { TriggerNodeData } from '../../../types/graph'
import type { TriggerCondition } from '../../../types/api'

/**
 * Props for TriggerPanel component
 */
export interface TriggerPanelProps {
  data: TriggerNodeData
}

/**
 * Formats trigger type for display
 */
function formatTriggerType(type: string): string {
  return type
    .replace(/_/g, ' ')
    .toLowerCase()
    .replace(/\b\w/g, (c) => c.toUpperCase())
}

/**
 * Panel for viewing and editing trigger conditions
 */
export function TriggerPanel({ data }: TriggerPanelProps) {
  const { refreshData } = useConfigGraph()
  const trigger = data.trigger

  const [isEditing, setIsEditing] = useState(false)
  const [isSaving, setIsSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [success, setSuccess] = useState<string | null>(null)

  const [formData, setFormData] = useState<Partial<TriggerCondition>>(trigger)

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
      setFormData(trigger)
    }
    setIsEditing(!isEditing)
    setError(null)
    setSuccess(null)
  }, [isEditing, trigger])

  const handleSave = useCallback(async () => {
    setIsSaving(true)
    setError(null)
    setSuccess(null)

    try {
      await updateTrigger(trigger.id, formData)
      setSuccess('Trigger saved successfully')
      setIsEditing(false)
      await refreshData()
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to save trigger'
      setError(message)
    } finally {
      setIsSaving(false)
    }
  }, [trigger.id, formData, refreshData])

  const handleCancel = useCallback(() => {
    handleEditToggle()
  }, [handleEditToggle])

  const handleToggleActive = useCallback(async () => {
    setIsSaving(true)
    setError(null)

    try {
      await toggleTrigger(trigger.id)
      setSuccess(trigger.active ? 'Trigger deactivated' : 'Trigger activated')
      await refreshData()
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to toggle trigger'
      setError(message)
    } finally {
      setIsSaving(false)
    }
  }, [trigger.id, trigger.active, refreshData])

  const handleDelete = useCallback(async () => {
    if (!confirm('Are you sure you want to delete this trigger?')) return

    setIsSaving(true)
    setError(null)

    try {
      await deleteTrigger(trigger.id)
      setSuccess('Trigger deleted')
      await refreshData()
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to delete trigger'
      setError(message)
    } finally {
      setIsSaving(false)
    }
  }, [trigger.id, refreshData])

  // View mode
  if (!isEditing) {
    return (
      <EntityPanel
        title={data.label}
        entityType="Trigger Condition"
        status={data.status}
        editable
        isEditing={false}
        error={error}
        success={success}
        onEditToggle={handleEditToggle}
      >
        <EntityPanelSection title="Trigger Settings">
          <DetailRow label="Name">
            <Chip variant="violet">{trigger.condition_name}</Chip>
          </DetailRow>

          <DetailRow label="Type">
            <Chip variant="outline">{formatTriggerType(trigger.trigger_type)}</Chip>
          </DetailRow>

          <DetailRow label="Active">
            <Chip variant={trigger.active ? 'green' : 'red'}>
              {trigger.active ? 'Yes' : 'No'}
            </Chip>
          </DetailRow>

          {trigger.keywords && (
            <DetailRow label="Keywords">
              <span className="entity-panel__text-value">{trigger.keywords}</span>
            </DetailRow>
          )}

          <DetailRow label="Mention Required">
            <Chip variant="outline">{trigger.mention_required ? 'Yes' : 'No'}</Chip>
          </DetailRow>
        </EntityPanelSection>

        <EntityPanelSection title="Timing" collapsible defaultCollapsed>
          {trigger.time_delay_seconds !== null && (
            <DetailRow label="Time Delay">
              <Chip variant="outline">{trigger.time_delay_seconds}s</Chip>
            </DetailRow>
          )}

          {trigger.probability_percent !== null && (
            <DetailRow label="Probability">
              <Chip variant="outline">{trigger.probability_percent}%</Chip>
            </DetailRow>
          )}

          {trigger.minimum_gap_minutes !== null && (
            <DetailRow label="Minimum Gap">
              <Chip variant="outline">{trigger.minimum_gap_minutes} min</Chip>
            </DetailRow>
          )}
        </EntityPanelSection>

        <EntityPanelSection title="Schedule" collapsible defaultCollapsed>
          {trigger.active_hours_start && (
            <DetailRow label="Active Hours Start">
              <Chip variant="outline">{trigger.active_hours_start}</Chip>
            </DetailRow>
          )}

          {trigger.active_hours_end && (
            <DetailRow label="Active Hours End">
              <Chip variant="outline">{trigger.active_hours_end}</Chip>
            </DetailRow>
          )}

          {trigger.active_days_of_week && (
            <DetailRow label="Active Days">
              <span className="entity-panel__text-value">{trigger.active_days_of_week}</span>
            </DetailRow>
          )}
        </EntityPanelSection>

        <EntityPanelSection title="Actions">
          <div className="entity-panel__actions">
            <button
              type="button"
              className={`entity-panel__btn ${trigger.active ? 'entity-panel__btn--warning' : 'entity-panel__btn--success'}`}
              onClick={handleToggleActive}
              disabled={isSaving}
            >
              {trigger.active ? 'Deactivate' : 'Activate'}
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
      entityType="Trigger Condition"
      status={data.status}
      editable
      isEditing
      isSaving={isSaving}
      error={error}
      success={success}
      onSave={handleSave}
      onCancel={handleCancel}
    >
      <EntityPanelSection title="Trigger Settings">
        <Field label="Condition Name" htmlFor="condition_name">
          <input
            type="text"
            id="condition_name"
            name="condition_name"
            value={formData.condition_name ?? ''}
            onChange={handleInputChange}
            placeholder="My Trigger"
          />
        </Field>

        <Field label="Trigger Type" htmlFor="trigger_type">
          <select
            id="trigger_type"
            name="trigger_type"
            value={formData.trigger_type ?? ''}
            onChange={handleInputChange}
          >
            <option value="keyword">Keyword</option>
            <option value="mention">Mention</option>
            <option value="reply">Reply</option>
            <option value="random">Random</option>
            <option value="scheduled">Scheduled</option>
          </select>
        </Field>

        <Field label="Keywords" htmlFor="keywords" hint="Comma-separated list of keywords">
          <textarea
            id="keywords"
            name="keywords"
            value={formData.keywords ?? ''}
            onChange={handleInputChange}
            placeholder="keyword1, keyword2"
            rows={2}
          />
        </Field>

        <Field label="Mention Required" htmlFor="mention_required">
          <input
            type="checkbox"
            id="mention_required"
            name="mention_required"
            checked={formData.mention_required ?? false}
            onChange={handleInputChange}
          />
        </Field>
      </EntityPanelSection>

      <EntityPanelSection title="Timing" collapsible>
        <Field label="Time Delay (seconds)" htmlFor="time_delay_seconds">
          <input
            type="number"
            id="time_delay_seconds"
            name="time_delay_seconds"
            value={formData.time_delay_seconds ?? ''}
            onChange={handleInputChange}
            min={0}
          />
        </Field>

        <Field label="Probability (%)" htmlFor="probability_percent" hint="0-100">
          <input
            type="number"
            id="probability_percent"
            name="probability_percent"
            value={formData.probability_percent ?? ''}
            onChange={handleInputChange}
            min={0}
            max={100}
          />
        </Field>

        <Field label="Minimum Gap (minutes)" htmlFor="minimum_gap_minutes">
          <input
            type="number"
            id="minimum_gap_minutes"
            name="minimum_gap_minutes"
            value={formData.minimum_gap_minutes ?? ''}
            onChange={handleInputChange}
            min={0}
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

      <EntityPanelSection title="Schedule" collapsible>
        <Field label="Active Hours Start" htmlFor="active_hours_start" hint="e.g., 09:00">
          <input
            type="time"
            id="active_hours_start"
            name="active_hours_start"
            value={formData.active_hours_start ?? ''}
            onChange={handleInputChange}
          />
        </Field>

        <Field label="Active Hours End" htmlFor="active_hours_end" hint="e.g., 18:00">
          <input
            type="time"
            id="active_hours_end"
            name="active_hours_end"
            value={formData.active_hours_end ?? ''}
            onChange={handleInputChange}
          />
        </Field>

        <Field label="Active Days" htmlFor="active_days_of_week" hint="e.g., Mon,Tue,Wed">
          <input
            type="text"
            id="active_days_of_week"
            name="active_days_of_week"
            value={formData.active_days_of_week ?? ''}
            onChange={handleInputChange}
            placeholder="Mon,Tue,Wed,Thu,Fri"
          />
        </Field>
      </EntityPanelSection>
    </EntityPanel>
  )
}
