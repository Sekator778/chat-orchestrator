import { useState, useCallback, type ChangeEvent } from 'react'
import { EntityPanel, EntityPanelSection, DetailRow, Field, Chip } from './EntityPanel'
import { updateRateLimits, resetRateLimits } from '../../../api/client'
import { useConfigGraph } from '../hooks'
import type { RateLimitsNodeData } from '../../../types/graph'
import type { RateLimits } from '../../../types/api'

/**
 * Props for RateLimitsPanel component
 */
export interface RateLimitsPanelProps {
  data: RateLimitsNodeData
  parentChatId: number
}

/**
 * Create empty rate limits with chat config id
 */
function createEmptyLimits(chatConfigId: number): RateLimits {
  return {
    id: null,
    chat_config_id: chatConfigId,
    max_messages_per_minute: null,
    max_messages_per_hour: null,
    max_messages_per_day: null,
    current_daily_messages: null,
    max_tokens_per_day: null,
    pending_response_delay_seconds: null,
    cooldown_after_limit_minutes: null,
    burst_limit: null,
    burst_window_seconds: null,
    user_specific_limits: false,
  }
}

/**
 * Panel for viewing and editing rate limits
 */
export function RateLimitsPanel({ data, parentChatId }: RateLimitsPanelProps) {
  const { refreshData } = useConfigGraph()
  const limits = data.limits

  const [isEditing, setIsEditing] = useState(false)
  const [isSaving, setIsSaving] = useState(false)
  const [isResetting, setIsResetting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [success, setSuccess] = useState<string | null>(null)

  const [formData, setFormData] = useState<RateLimits>(
    limits ?? createEmptyLimits(parentChatId)
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
      setFormData(limits ?? createEmptyLimits(parentChatId))
    }
    setIsEditing(!isEditing)
    setError(null)
    setSuccess(null)
  }, [isEditing, limits, parentChatId])

  const handleSave = useCallback(async () => {
    setIsSaving(true)
    setError(null)
    setSuccess(null)

    try {
      await updateRateLimits(parentChatId, formData)
      setSuccess('Rate limits saved successfully')
      setIsEditing(false)
      await refreshData()
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to save rate limits'
      setError(message)
    } finally {
      setIsSaving(false)
    }
  }, [parentChatId, formData, refreshData])

  const handleCancel = useCallback(() => {
    handleEditToggle()
  }, [handleEditToggle])

  const handleReset = useCallback(async () => {
    if (!confirm('Are you sure you want to reset current usage counters?')) {
      return
    }

    setIsResetting(true)
    setError(null)
    setSuccess(null)

    try {
      await resetRateLimits(parentChatId)
      setSuccess('Rate limits reset successfully')
      await refreshData()
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to reset rate limits'
      setError(message)
    } finally {
      setIsResetting(false)
    }
  }, [parentChatId, refreshData])

  // View mode
  if (!isEditing) {
    return (
      <EntityPanel
        title={data.label}
        entityType="Rate Limits"
        status={data.status}
        editable
        isEditing={false}
        error={error}
        success={success}
        onEditToggle={handleEditToggle}
        footer={
          <button
            className="ghost"
            onClick={handleReset}
            disabled={isResetting}
          >
            {isResetting ? 'Resetting...' : 'Reset Counters'}
          </button>
        }
      >
        <EntityPanelSection title="Message Limits">
          <DetailRow label="Per Minute">
            <Chip variant="outline">{limits?.max_messages_per_minute ?? 'Unlimited'}</Chip>
          </DetailRow>

          <DetailRow label="Per Hour">
            <Chip variant="outline">{limits?.max_messages_per_hour ?? 'Unlimited'}</Chip>
          </DetailRow>

          <DetailRow label="Per Day">
            <Chip variant="outline">{limits?.max_messages_per_day ?? 'Unlimited'}</Chip>
          </DetailRow>

          <DetailRow label="Current Daily">
            <Chip variant={
              limits?.current_daily_messages && limits?.max_messages_per_day &&
              limits.current_daily_messages >= limits.max_messages_per_day
                ? 'red'
                : 'outline'
            }>
              {limits?.current_daily_messages ?? 0}
            </Chip>
          </DetailRow>
        </EntityPanelSection>

        <EntityPanelSection title="Token Limits">
          <DetailRow label="Max Tokens/Day">
            <Chip variant="outline">{limits?.max_tokens_per_day ?? 'Unlimited'}</Chip>
          </DetailRow>
        </EntityPanelSection>

        <EntityPanelSection title="Burst Control" collapsible defaultCollapsed>
          <DetailRow label="Burst Limit">
            <Chip variant="outline">{limits?.burst_limit ?? 'Default'}</Chip>
          </DetailRow>

          <DetailRow label="Burst Window (sec)">
            <Chip variant="outline">{limits?.burst_window_seconds ?? 'Default'}</Chip>
          </DetailRow>
        </EntityPanelSection>

        <EntityPanelSection title="Cooldown Settings" collapsible defaultCollapsed>
          <DetailRow label="Response Delay (sec)">
            <Chip variant="outline">{limits?.pending_response_delay_seconds ?? 'Default'}</Chip>
          </DetailRow>

          <DetailRow label="Cooldown After Limit (min)">
            <Chip variant="outline">{limits?.cooldown_after_limit_minutes ?? 'Default'}</Chip>
          </DetailRow>

          <DetailRow label="User-Specific Limits">
            <Chip variant={limits?.user_specific_limits ? 'green' : 'outline'}>
              {limits?.user_specific_limits ? 'Yes' : 'No'}
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
      entityType="Rate Limits"
      status={data.status}
      editable
      isEditing
      isSaving={isSaving}
      error={error}
      success={success}
      onSave={handleSave}
      onCancel={handleCancel}
    >
      <EntityPanelSection title="Message Limits">
        <Field label="Max Messages per Minute" htmlFor="max_messages_per_minute">
          <input
            type="number"
            id="max_messages_per_minute"
            name="max_messages_per_minute"
            value={formData.max_messages_per_minute ?? ''}
            onChange={handleInputChange}
            placeholder="Unlimited"
            min={0}
          />
        </Field>

        <Field label="Max Messages per Hour" htmlFor="max_messages_per_hour">
          <input
            type="number"
            id="max_messages_per_hour"
            name="max_messages_per_hour"
            value={formData.max_messages_per_hour ?? ''}
            onChange={handleInputChange}
            placeholder="Unlimited"
            min={0}
          />
        </Field>

        <Field label="Max Messages per Day" htmlFor="max_messages_per_day">
          <input
            type="number"
            id="max_messages_per_day"
            name="max_messages_per_day"
            value={formData.max_messages_per_day ?? ''}
            onChange={handleInputChange}
            placeholder="Unlimited"
            min={0}
          />
        </Field>
      </EntityPanelSection>

      <EntityPanelSection title="Token Limits">
        <Field label="Max Tokens per Day" htmlFor="max_tokens_per_day">
          <input
            type="number"
            id="max_tokens_per_day"
            name="max_tokens_per_day"
            value={formData.max_tokens_per_day ?? ''}
            onChange={handleInputChange}
            placeholder="Unlimited"
            min={0}
          />
        </Field>
      </EntityPanelSection>

      <EntityPanelSection title="Burst Control" collapsible>
        <Field label="Burst Limit" htmlFor="burst_limit" hint="Max messages in burst window">
          <input
            type="number"
            id="burst_limit"
            name="burst_limit"
            value={formData.burst_limit ?? ''}
            onChange={handleInputChange}
            placeholder="Default"
            min={1}
          />
        </Field>

        <Field label="Burst Window (seconds)" htmlFor="burst_window_seconds">
          <input
            type="number"
            id="burst_window_seconds"
            name="burst_window_seconds"
            value={formData.burst_window_seconds ?? ''}
            onChange={handleInputChange}
            placeholder="Default"
            min={1}
          />
        </Field>
      </EntityPanelSection>

      <EntityPanelSection title="Cooldown Settings" collapsible>
        <Field label="Response Delay (seconds)" htmlFor="pending_response_delay_seconds" hint="Minimum delay before responding">
          <input
            type="number"
            id="pending_response_delay_seconds"
            name="pending_response_delay_seconds"
            value={formData.pending_response_delay_seconds ?? ''}
            onChange={handleInputChange}
            placeholder="Default"
            min={0}
          />
        </Field>

        <Field label="Cooldown After Limit (minutes)" htmlFor="cooldown_after_limit_minutes" hint="Pause after hitting limit">
          <input
            type="number"
            id="cooldown_after_limit_minutes"
            name="cooldown_after_limit_minutes"
            value={formData.cooldown_after_limit_minutes ?? ''}
            onChange={handleInputChange}
            placeholder="Default"
            min={0}
          />
        </Field>

        <Field label="User-Specific Limits" htmlFor="user_specific_limits">
          <label className="entity-panel__checkbox">
            <input
              type="checkbox"
              id="user_specific_limits"
              name="user_specific_limits"
              checked={formData.user_specific_limits ?? false}
              onChange={handleInputChange}
            />
            <span>Apply limits per user instead of globally</span>
          </label>
        </Field>
      </EntityPanelSection>
    </EntityPanel>
  )
}
