import { useState, useCallback, useEffect, type ChangeEvent } from 'react'
import { EntityPanel, EntityPanelSection, DetailRow, Field, Chip } from './EntityPanel'
import {
  updatePersona,
  enablePersona,
  disablePersona,
  generateTestDigest,
  deletePersona,
  fetchPersonaHistory,
  fetchPersonaSchedule,
} from '../../../api/digestClient'
import { useConfigGraph } from '../hooks'
import type { DigestPersonaNodeData } from '../../../types/graph'
import type { UpdatePersonaRequest, DigestHistory } from '../../../types/digest'
import { PERSONA_STYLES, LANGUAGES, TIMEZONES, SCHEDULE_PRESETS } from '../../../types/digest'

/**
 * Props for DigestPersonaPanel component
 */
export interface DigestPersonaPanelProps {
  data: DigestPersonaNodeData
}

/**
 * Format date for display
 */
function formatDate(dateStr: string | null): string {
  if (!dateStr) return 'Never'
  try {
    const date = new Date(dateStr)
    return date.toLocaleString()
  } catch {
    return dateStr
  }
}

/**
 * Panel for viewing and editing digest persona configuration
 */
export function DigestPersonaPanel({ data }: DigestPersonaPanelProps) {
  const { refreshData, selectNode } = useConfigGraph()
  const persona = data.persona

  const [isEditing, setIsEditing] = useState(false)
  const [isSaving, setIsSaving] = useState(false)
  const [isTesting, setIsTesting] = useState(false)
  const [isToggling, setIsToggling] = useState(false)
  const [isDeleting, setIsDeleting] = useState(false)
  const [showDeleteConfirm, setShowDeleteConfirm] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [success, setSuccess] = useState<string | null>(null)
  const [testResult, setTestResult] = useState<string | null>(null)

  // History and schedule state
  const [history, setHistory] = useState<DigestHistory[]>([])
  const [nextRuns, setNextRuns] = useState<string[]>([])
  const [isLoadingHistory, setIsLoadingHistory] = useState(false)

  // Form state
  const [formData, setFormData] = useState<UpdatePersonaRequest>({
    name: persona.name,
    description: persona.description,
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
    modelName: persona.modelName,
    temperature: persona.temperature,
    maxTokens: persona.maxTokens,
  })

  // Local state for keywords (comma-separated)
  const [topicKeywordsText, setTopicKeywordsText] = useState(
    (persona.topicKeywords ?? []).join(', ')
  )
  const [negativeKeywordsText, setNegativeKeywordsText] = useState(
    (persona.negativeKeywords ?? []).join(', ')
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
      // Reset form
      setFormData({
        name: persona.name,
        description: persona.description,
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
        modelName: persona.modelName,
        temperature: persona.temperature,
        maxTokens: persona.maxTokens,
      })
      setTopicKeywordsText((persona.topicKeywords ?? []).join(', '))
      setNegativeKeywordsText((persona.negativeKeywords ?? []).join(', '))
    }
    setIsEditing(!isEditing)
    setError(null)
    setSuccess(null)
    setTestResult(null)
  }, [isEditing, persona])

  const handleSave = useCallback(async () => {
    if (persona.id === null) {
      setError('Cannot save persona without ID')
      return
    }

    setIsSaving(true)
    setError(null)
    setSuccess(null)

    // Parse keywords
    const topicKeywords = topicKeywordsText
      .split(',')
      .map((k) => k.trim())
      .filter((k) => k.length > 0)
    const negativeKeywords = negativeKeywordsText
      .split(',')
      .map((k) => k.trim())
      .filter((k) => k.length > 0)

    const updateData: UpdatePersonaRequest = {
      ...formData,
      topicKeywords: topicKeywords.length > 0 ? topicKeywords : undefined,
      negativeKeywords: negativeKeywords.length > 0 ? negativeKeywords : undefined,
    }

    try {
      await updatePersona(persona.id, updateData)
      setSuccess('Persona saved successfully')
      setIsEditing(false)
      await refreshData()
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to save persona'
      setError(message)
    } finally {
      setIsSaving(false)
    }
  }, [persona.id, formData, topicKeywordsText, negativeKeywordsText, refreshData])

  const handleCancel = useCallback(() => {
    handleEditToggle()
  }, [handleEditToggle])

  const handleToggleEnabled = useCallback(async () => {
    if (persona.id === null) return

    setIsToggling(true)
    setError(null)
    setSuccess(null)

    try {
      if (persona.enabled) {
        await disablePersona(persona.id)
        setSuccess('Persona disabled')
      } else {
        await enablePersona(persona.id)
        setSuccess('Persona enabled')
      }
      await refreshData()
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to toggle persona'
      setError(message)
    } finally {
      setIsToggling(false)
    }
  }, [persona.id, persona.enabled, refreshData])

  const handleTestDigest = useCallback(async () => {
    if (persona.id === null) return

    setIsTesting(true)
    setError(null)
    setTestResult(null)

    try {
      const result = await generateTestDigest(persona.id, { preview: true })
      setTestResult(result.content)
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to generate test digest'
      setError(message)
    } finally {
      setIsTesting(false)
    }
  }, [persona.id])

  const handleDelete = useCallback(async () => {
    if (persona.id === null) return

    setIsDeleting(true)
    setError(null)

    try {
      await deletePersona(persona.id)
      await refreshData()
      selectNode(null)
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to delete persona'
      setError(message)
      setShowDeleteConfirm(false)
    } finally {
      setIsDeleting(false)
    }
  }, [persona.id, refreshData, selectNode])

  const loadHistory = useCallback(async () => {
    if (persona.id === null) return

    setIsLoadingHistory(true)
    try {
      const [historyData, scheduleData] = await Promise.all([
        fetchPersonaHistory(persona.id, 10),
        fetchPersonaSchedule(persona.id, 5),
      ])
      setHistory(historyData)
      setNextRuns(scheduleData)
    } catch {
      // Silent fail - history is not critical
    } finally {
      setIsLoadingHistory(false)
    }
  }, [persona.id])

  // Load history on mount
  useEffect(() => {
    loadHistory()
  }, [loadHistory])

  // View mode
  if (!isEditing) {
    return (
      <EntityPanel
        title={data.label}
        entityType="Digest Persona"
        status={data.status}
        editable
        isEditing={false}
        error={error}
        success={success}
        onEditToggle={handleEditToggle}
        footer={
          <>
            <button
              className="ghost"
              onClick={handleToggleEnabled}
              disabled={isToggling || persona.id === null}
            >
              {isToggling ? 'Processing...' : persona.enabled ? 'Disable' : 'Enable'}
            </button>
            <button
              className="ghost"
              onClick={handleTestDigest}
              disabled={isTesting || persona.id === null}
            >
              {isTesting ? 'Testing...' : 'Test Digest'}
            </button>
            <button
              className="ghost danger"
              onClick={() => setShowDeleteConfirm(true)}
              disabled={persona.id === null}
            >
              Delete
            </button>
          </>
        }
      >
        <EntityPanelSection title="Basic Info">
          <DetailRow label="ID">
            <Chip variant="outline">{persona.id}</Chip>
          </DetailRow>

          <DetailRow label="Enabled">
            <Chip variant={persona.enabled ? 'green' : 'outline'}>
              {persona.enabled ? 'Yes' : 'No'}
            </Chip>
          </DetailRow>

          <DetailRow label="Style">
            <Chip variant="violet">
              {PERSONA_STYLES[persona.personaStyle]?.label ?? persona.personaStyle}
            </Chip>
          </DetailRow>

          <DetailRow label="Language">
            <Chip variant="outline">{persona.language.toUpperCase()}</Chip>
          </DetailRow>

          {persona.description && (
            <DetailRow label="Description">
              <span className="entity-panel__text-value">{persona.description}</span>
            </DetailRow>
          )}
        </EntityPanelSection>

        <EntityPanelSection title="Target & Bot">
          <DetailRow label="Bot ID">
            <Chip variant="outline">{persona.botId}</Chip>
          </DetailRow>

          <DetailRow label="Target Channel">
            <Chip variant="outline">{persona.targetChannelId}</Chip>
          </DetailRow>
        </EntityPanelSection>

        <EntityPanelSection title="Schedule">
          <DetailRow label="Cron Schedule">
            <Chip variant="outline">{persona.scheduleCron ?? 'Not set'}</Chip>
          </DetailRow>

          <DetailRow label="Timezone">
            <Chip variant="outline">{persona.scheduleTimezone}</Chip>
          </DetailRow>

          {(persona.activeHoursStart || persona.activeHoursEnd) && (
            <DetailRow label="Active Hours">
              <Chip variant="outline">
                {persona.activeHoursStart ?? '00:00'} - {persona.activeHoursEnd ?? '23:59'}
              </Chip>
            </DetailRow>
          )}
        </EntityPanelSection>

        <EntityPanelSection title="Content Settings" collapsible defaultCollapsed>
          <DetailRow label="Lookback Hours">
            <Chip variant="outline">{persona.lookbackHours}</Chip>
          </DetailRow>

          <DetailRow label="Max Messages">
            <Chip variant="outline">{persona.maxMessages}</Chip>
          </DetailRow>

          <DetailRow label="Min Cluster Size">
            <Chip variant="outline">{persona.minClusterSize}</Chip>
          </DetailRow>

          <DetailRow label="Min Importance">
            <Chip variant="outline">{persona.minImportanceScore}</Chip>
          </DetailRow>

          <DetailRow label="Source Trust Threshold">
            <Chip variant="outline">{persona.sourceTrustThreshold}</Chip>
          </DetailRow>
        </EntityPanelSection>

        <EntityPanelSection title="LLM Settings" collapsible defaultCollapsed>
          <DetailRow label="Model">
            <Chip variant="violet">{persona.modelName ?? 'Default'}</Chip>
          </DetailRow>

          <DetailRow label="Temperature">
            <Chip variant="outline">{persona.temperature}</Chip>
          </DetailRow>

          <DetailRow label="Max Tokens">
            <Chip variant="outline">{persona.maxTokens}</Chip>
          </DetailRow>
        </EntityPanelSection>

        {(persona.topicKeywords.length > 0 || persona.negativeKeywords.length > 0) && (
          <EntityPanelSection title="Keywords" collapsible defaultCollapsed>
            {persona.topicKeywords.length > 0 && (
              <div className="entity-panel__field">
                <span className="entity-panel__field-label">Topic Keywords</span>
                <div className="entity-panel__tags">
                  {persona.topicKeywords.map((kw, i) => (
                    <Chip key={i} variant="green">{kw}</Chip>
                  ))}
                </div>
              </div>
            )}

            {persona.negativeKeywords.length > 0 && (
              <div className="entity-panel__field">
                <span className="entity-panel__field-label">Negative Keywords</span>
                <div className="entity-panel__tags">
                  {persona.negativeKeywords.map((kw, i) => (
                    <Chip key={i} variant="red">{kw}</Chip>
                  ))}
                </div>
              </div>
            )}
          </EntityPanelSection>
        )}

        <EntityPanelSection title="Statistics" collapsible defaultCollapsed>
          <DetailRow label="Digests Published">
            <Chip variant="green">{persona.totalDigestsPublished}</Chip>
          </DetailRow>

          <DetailRow label="Last Run">
            <span className="entity-panel__text-value muted">
              {formatDate(persona.lastRunAt)}
            </span>
          </DetailRow>

          <DetailRow label="Created">
            <span className="entity-panel__text-value muted">
              {formatDate(persona.createdAt)}
            </span>
          </DetailRow>

          <DetailRow label="Updated">
            <span className="entity-panel__text-value muted">
              {formatDate(persona.updatedAt)}
            </span>
          </DetailRow>
        </EntityPanelSection>

        {testResult && (
          <EntityPanelSection title="Test Result">
            <div className="entity-panel__code-block entity-panel__code-block--long">
              {testResult}
            </div>
          </EntityPanelSection>
        )}

        {nextRuns.length > 0 && (
          <EntityPanelSection title="Upcoming Runs" collapsible>
            <div className="entity-panel__schedule-list">
              {nextRuns.map((run, index) => (
                <div key={index} className="entity-panel__schedule-item">
                  <span className="entity-panel__schedule-number">{index + 1}</span>
                  <span className="entity-panel__schedule-time">{formatDate(run)}</span>
                </div>
              ))}
            </div>
          </EntityPanelSection>
        )}

        <EntityPanelSection title="Recent History" collapsible>
          {isLoadingHistory ? (
            <p className="muted">Loading history...</p>
          ) : history.length === 0 ? (
            <p className="muted">No digest history yet</p>
          ) : (
            <div className="entity-panel__history-list">
              {history.map((entry) => (
                <div key={entry.id} className="entity-panel__history-item">
                  <div className="entity-panel__history-header">
                    <Chip
                      variant={
                        entry.status === 'PUBLISHED'
                          ? 'green'
                          : entry.status === 'FAILED'
                          ? 'red'
                          : 'outline'
                      }
                    >
                      {entry.status}
                    </Chip>
                    <span className="entity-panel__history-date muted">
                      {formatDate(entry.createdAt)}
                    </span>
                  </div>
                  <div className="entity-panel__history-meta">
                    <span>{entry.messagesIncluded} messages</span>
                    <span>{entry.clustersUsed} clusters</span>
                    <span>{entry.generationTimeMs}ms</span>
                  </div>
                  {entry.errorMessage && (
                    <p className="entity-panel__history-error muted tiny">{entry.errorMessage}</p>
                  )}
                  <p className="entity-panel__history-preview tiny">
                    {entry.content.substring(0, 150)}...
                  </p>
                </div>
              ))}
            </div>
          )}
        </EntityPanelSection>

        {showDeleteConfirm && (
          <div className="entity-panel__delete-confirm">
            <p>Are you sure you want to delete this persona?</p>
            <p className="muted tiny">This action cannot be undone.</p>
            <div className="entity-panel__delete-confirm-actions">
              <button
                className="ghost"
                onClick={() => setShowDeleteConfirm(false)}
                disabled={isDeleting}
              >
                Cancel
              </button>
              <button
                className="danger"
                onClick={handleDelete}
                disabled={isDeleting}
              >
                {isDeleting ? 'Deleting...' : 'Delete'}
              </button>
            </div>
          </div>
        )}
      </EntityPanel>
    )
  }

  // Edit mode
  return (
    <EntityPanel
      title={data.label}
      entityType="Digest Persona"
      status={data.status}
      editable
      isEditing
      isSaving={isSaving}
      error={error}
      success={success}
      onSave={handleSave}
      onCancel={handleCancel}
    >
      <EntityPanelSection title="Basic Info">
        <Field label="Name" htmlFor="name" required>
          <input
            type="text"
            id="name"
            name="name"
            value={formData.name ?? ''}
            onChange={handleInputChange}
            placeholder="Persona name"
            required
          />
        </Field>

        <Field label="Description" htmlFor="description">
          <textarea
            id="description"
            name="description"
            value={formData.description ?? ''}
            onChange={handleInputChange}
            placeholder="Optional description"
            rows={2}
          />
        </Field>

        <Field label="Style" htmlFor="personaStyle">
          <select
            id="personaStyle"
            name="personaStyle"
            value={formData.personaStyle ?? 'PROFESSIONAL'}
            onChange={handleInputChange}
          >
            {Object.entries(PERSONA_STYLES).map(([value, { label }]) => (
              <option key={value} value={value}>{label}</option>
            ))}
          </select>
        </Field>

        <Field label="Language" htmlFor="language">
          <select
            id="language"
            name="language"
            value={formData.language ?? 'ru'}
            onChange={handleInputChange}
          >
            {LANGUAGES.map(({ value, label }) => (
              <option key={value} value={value}>{label}</option>
            ))}
          </select>
        </Field>
      </EntityPanelSection>

      <EntityPanelSection title="Schedule">
        <Field label="Cron Schedule" htmlFor="scheduleCron">
          <select
            id="scheduleCron"
            name="scheduleCron"
            value={formData.scheduleCron ?? ''}
            onChange={handleInputChange}
          >
            <option value="">Manual Only</option>
            {SCHEDULE_PRESETS.map(({ value, label }) => (
              <option key={value} value={value}>{label}</option>
            ))}
          </select>
        </Field>

        <Field label="Timezone" htmlFor="scheduleTimezone">
          <select
            id="scheduleTimezone"
            name="scheduleTimezone"
            value={formData.scheduleTimezone ?? 'UTC'}
            onChange={handleInputChange}
          >
            {TIMEZONES.map(({ value, label }) => (
              <option key={value} value={value}>{label}</option>
            ))}
          </select>
        </Field>

        <Field label="Active Hours Start" htmlFor="activeHoursStart" hint="HH:mm format">
          <input
            type="time"
            id="activeHoursStart"
            name="activeHoursStart"
            value={formData.activeHoursStart ?? ''}
            onChange={handleInputChange}
          />
        </Field>

        <Field label="Active Hours End" htmlFor="activeHoursEnd" hint="HH:mm format">
          <input
            type="time"
            id="activeHoursEnd"
            name="activeHoursEnd"
            value={formData.activeHoursEnd ?? ''}
            onChange={handleInputChange}
          />
        </Field>
      </EntityPanelSection>

      <EntityPanelSection title="Content Settings" collapsible>
        <Field label="Lookback Hours" htmlFor="lookbackHours" hint="How far back to look for messages">
          <input
            type="number"
            id="lookbackHours"
            name="lookbackHours"
            value={formData.lookbackHours ?? ''}
            onChange={handleInputChange}
            placeholder="24"
            min={1}
            max={168}
          />
        </Field>

        <Field label="Max Messages" htmlFor="maxMessages" hint="Maximum messages to include">
          <input
            type="number"
            id="maxMessages"
            name="maxMessages"
            value={formData.maxMessages ?? ''}
            onChange={handleInputChange}
            placeholder="10"
            min={1}
            max={100}
          />
        </Field>

        <Field label="Min Cluster Size" htmlFor="minClusterSize">
          <input
            type="number"
            id="minClusterSize"
            name="minClusterSize"
            value={formData.minClusterSize ?? ''}
            onChange={handleInputChange}
            placeholder="2"
            min={1}
            max={20}
          />
        </Field>

        <Field label="Min Importance Score" htmlFor="minImportanceScore" hint="0.0-1.0">
          <input
            type="number"
            id="minImportanceScore"
            name="minImportanceScore"
            value={formData.minImportanceScore ?? ''}
            onChange={handleInputChange}
            placeholder="0.0"
            min={0}
            max={1}
            step={0.1}
          />
        </Field>

        <Field label="Source Trust Threshold" htmlFor="sourceTrustThreshold" hint="0.0-1.0">
          <input
            type="number"
            id="sourceTrustThreshold"
            name="sourceTrustThreshold"
            value={formData.sourceTrustThreshold ?? ''}
            onChange={handleInputChange}
            placeholder="0.0"
            min={0}
            max={1}
            step={0.1}
          />
        </Field>
      </EntityPanelSection>

      <EntityPanelSection title="Keywords" collapsible>
        <Field label="Topic Keywords" htmlFor="topicKeywords" hint="Comma-separated">
          <input
            type="text"
            id="topicKeywords"
            value={topicKeywordsText}
            onChange={(e) => setTopicKeywordsText(e.target.value)}
            placeholder="keyword1, keyword2"
          />
        </Field>

        <Field label="Negative Keywords" htmlFor="negativeKeywords" hint="Comma-separated (exclude these)">
          <input
            type="text"
            id="negativeKeywords"
            value={negativeKeywordsText}
            onChange={(e) => setNegativeKeywordsText(e.target.value)}
            placeholder="spam, ads"
          />
        </Field>
      </EntityPanelSection>

      <EntityPanelSection title="LLM Settings" collapsible>
        <Field label="Model Name" htmlFor="modelName">
          <input
            type="text"
            id="modelName"
            name="modelName"
            value={formData.modelName ?? ''}
            onChange={handleInputChange}
            placeholder="Default"
          />
        </Field>

        <Field label="Temperature" htmlFor="temperature">
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

        <Field label="Max Tokens" htmlFor="maxTokens">
          <input
            type="number"
            id="maxTokens"
            name="maxTokens"
            value={formData.maxTokens ?? ''}
            onChange={handleInputChange}
            placeholder="1000"
            min={100}
            max={8192}
          />
        </Field>
      </EntityPanelSection>

      {formData.personaStyle === 'CUSTOM' && (
        <EntityPanelSection title="Custom Prompt">
          <Field label="Custom System Prompt" htmlFor="customSystemPrompt">
            <textarea
              id="customSystemPrompt"
              name="customSystemPrompt"
              value={formData.customSystemPrompt ?? ''}
              onChange={handleInputChange}
              placeholder="Enter custom system prompt for CUSTOM style..."
              rows={6}
            />
          </Field>
        </EntityPanelSection>
      )}
    </EntityPanel>
  )
}
