import { useState, useCallback, type ChangeEvent } from 'react'
import { EntityPanel, EntityPanelSection, DetailRow, Field, Chip } from './EntityPanel'
import { saveSearchConfig } from '../../../api/client'
import { useConfigGraph } from '../hooks'
import type { SearchConfigNodeData } from '../../../types/graph'
import type { SearchConfig } from '../../../types/api'

/**
 * Props for SearchConfigPanel component
 */
export interface SearchConfigPanelProps {
  data: SearchConfigNodeData
  parentChatId: number
}

/**
 * Create empty search config with chat id
 */
function createEmptyConfig(chatId: number): SearchConfig {
  return {
    chat_id: chatId,
    search_enabled: false,
    auto_search_enabled: false,
    search_provider: 'duckduckgo',
    max_results: 3,
    cache_duration_minutes: 60,
    rate_limit_per_hour: 10,
    include_attribution: true,
    relevance_threshold: 0.5,
    search_triggers: null,
  }
}

/**
 * Panel for viewing and editing search configuration
 */
export function SearchConfigPanel({ data, parentChatId }: SearchConfigPanelProps) {
  const { refreshData } = useConfigGraph()
  const config = data.searchConfig

  const [isEditing, setIsEditing] = useState(false)
  const [isSaving, setIsSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [success, setSuccess] = useState<string | null>(null)

  const [formData, setFormData] = useState<SearchConfig>(
    config ?? createEmptyConfig(parentChatId)
  )

  // Local state for triggers (comma-separated string)
  const [triggersText, setTriggersText] = useState(
    (config?.search_triggers ?? []).join(', ')
  )

  const handleInputChange = useCallback((
    e: ChangeEvent<HTMLInputElement | HTMLSelectElement>
  ) => {
    const { name, value, type } = e.target
    const checked = type === 'checkbox' ? (e.target as HTMLInputElement).checked : undefined

    setFormData((prev) => ({
      ...prev,
      [name]: type === 'checkbox'
        ? checked
        : type === 'number'
          ? value === '' ? null : Number(value)
          : value || null,
    }))
  }, [])

  const handleTriggersChange = useCallback((e: ChangeEvent<HTMLInputElement>) => {
    setTriggersText(e.target.value)
    // Parse triggers on save, not on change
  }, [])

  const handleEditToggle = useCallback(() => {
    if (isEditing) {
      setFormData(config ?? createEmptyConfig(parentChatId))
      setTriggersText((config?.search_triggers ?? []).join(', '))
    }
    setIsEditing(!isEditing)
    setError(null)
    setSuccess(null)
  }, [isEditing, config, parentChatId])

  const handleSave = useCallback(async () => {
    setIsSaving(true)
    setError(null)
    setSuccess(null)

    // Parse triggers from comma-separated text
    const triggers = triggersText
      .split(',')
      .map((t) => t.trim())
      .filter((t) => t.length > 0)

    const dataToSave: SearchConfig = {
      ...formData,
      search_triggers: triggers.length > 0 ? triggers : null,
    }

    try {
      await saveSearchConfig(dataToSave)
      setSuccess('Search configuration saved successfully')
      setIsEditing(false)
      await refreshData()
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to save search configuration'
      setError(message)
    } finally {
      setIsSaving(false)
    }
  }, [formData, triggersText, refreshData])

  const handleCancel = useCallback(() => {
    handleEditToggle()
  }, [handleEditToggle])

  // View mode
  if (!isEditing) {
    return (
      <EntityPanel
        title={data.label}
        entityType="Search Config"
        status={data.status}
        editable
        isEditing={false}
        error={error}
        success={success}
        onEditToggle={handleEditToggle}
      >
        <EntityPanelSection title="Search Settings">
          <DetailRow label="Search Enabled">
            <Chip variant={config?.search_enabled ? 'green' : 'outline'}>
              {config?.search_enabled ? 'Yes' : 'No'}
            </Chip>
          </DetailRow>

          <DetailRow label="Auto-Search">
            <Chip variant={config?.auto_search_enabled ? 'green' : 'outline'}>
              {config?.auto_search_enabled ? 'Yes' : 'No'}
            </Chip>
          </DetailRow>

          <DetailRow label="Provider">
            <Chip variant="violet">{config?.search_provider ?? 'Not set'}</Chip>
          </DetailRow>

          <DetailRow label="Max Results">
            <Chip variant="outline">{config?.max_results ?? 'Default'}</Chip>
          </DetailRow>
        </EntityPanelSection>

        <EntityPanelSection title="Limits & Caching" collapsible defaultCollapsed>
          <DetailRow label="Rate Limit/Hour">
            <Chip variant="outline">{config?.rate_limit_per_hour ?? 'Unlimited'}</Chip>
          </DetailRow>

          <DetailRow label="Cache Duration (min)">
            <Chip variant="outline">{config?.cache_duration_minutes ?? 'Default'}</Chip>
          </DetailRow>

          <DetailRow label="Relevance Threshold">
            <Chip variant="outline">{config?.relevance_threshold ?? 'Default'}</Chip>
          </DetailRow>

          <DetailRow label="Include Attribution">
            <Chip variant={config?.include_attribution ? 'green' : 'outline'}>
              {config?.include_attribution ? 'Yes' : 'No'}
            </Chip>
          </DetailRow>
        </EntityPanelSection>

        {config?.search_triggers && config.search_triggers.length > 0 && (
          <EntityPanelSection title="Search Triggers" collapsible defaultCollapsed>
            <div className="entity-panel__tags">
              {config.search_triggers.map((trigger, index) => (
                <Chip key={index} variant="outline">{trigger}</Chip>
              ))}
            </div>
          </EntityPanelSection>
        )}
      </EntityPanel>
    )
  }

  // Edit mode
  return (
    <EntityPanel
      title={data.label}
      entityType="Search Config"
      status={data.status}
      editable
      isEditing
      isSaving={isSaving}
      error={error}
      success={success}
      onSave={handleSave}
      onCancel={handleCancel}
    >
      <EntityPanelSection title="Search Settings">
        <Field label="Search Enabled" htmlFor="search_enabled">
          <label className="entity-panel__checkbox">
            <input
              type="checkbox"
              id="search_enabled"
              name="search_enabled"
              checked={formData.search_enabled ?? false}
              onChange={handleInputChange}
            />
            <span>Enable web search for this chat</span>
          </label>
        </Field>

        <Field label="Auto-Search" htmlFor="auto_search_enabled">
          <label className="entity-panel__checkbox">
            <input
              type="checkbox"
              id="auto_search_enabled"
              name="auto_search_enabled"
              checked={formData.auto_search_enabled ?? false}
              onChange={handleInputChange}
            />
            <span>Automatically search when questions are detected</span>
          </label>
        </Field>

        <Field label="Search Provider" htmlFor="search_provider">
          <select
            id="search_provider"
            name="search_provider"
            value={formData.search_provider ?? 'duckduckgo'}
            onChange={handleInputChange}
          >
            <option value="duckduckgo">DuckDuckGo</option>
            <option value="google">Google</option>
            <option value="bing">Bing</option>
          </select>
        </Field>

        <Field label="Max Results" htmlFor="max_results" hint="Number of search results to include">
          <input
            type="number"
            id="max_results"
            name="max_results"
            value={formData.max_results ?? ''}
            onChange={handleInputChange}
            placeholder="3"
            min={1}
            max={10}
          />
        </Field>
      </EntityPanelSection>

      <EntityPanelSection title="Limits & Caching" collapsible>
        <Field label="Rate Limit per Hour" htmlFor="rate_limit_per_hour">
          <input
            type="number"
            id="rate_limit_per_hour"
            name="rate_limit_per_hour"
            value={formData.rate_limit_per_hour ?? ''}
            onChange={handleInputChange}
            placeholder="10"
            min={1}
            max={100}
          />
        </Field>

        <Field label="Cache Duration (minutes)" htmlFor="cache_duration_minutes">
          <input
            type="number"
            id="cache_duration_minutes"
            name="cache_duration_minutes"
            value={formData.cache_duration_minutes ?? ''}
            onChange={handleInputChange}
            placeholder="60"
            min={1}
            max={1440}
          />
        </Field>

        <Field label="Relevance Threshold" htmlFor="relevance_threshold" hint="0.0-1.0, higher = more relevant">
          <input
            type="number"
            id="relevance_threshold"
            name="relevance_threshold"
            value={formData.relevance_threshold ?? ''}
            onChange={handleInputChange}
            placeholder="0.5"
            min={0}
            max={1}
            step={0.1}
          />
        </Field>

        <Field label="Include Attribution" htmlFor="include_attribution">
          <label className="entity-panel__checkbox">
            <input
              type="checkbox"
              id="include_attribution"
              name="include_attribution"
              checked={formData.include_attribution ?? false}
              onChange={handleInputChange}
            />
            <span>Include source links in responses</span>
          </label>
        </Field>
      </EntityPanelSection>

      <EntityPanelSection title="Search Triggers" collapsible>
        <Field label="Trigger Keywords" htmlFor="search_triggers" hint="Comma-separated list of keywords that trigger search">
          <input
            type="text"
            id="search_triggers"
            name="search_triggers"
            value={triggersText}
            onChange={handleTriggersChange}
            placeholder="what is, how to, define, explain"
          />
        </Field>
      </EntityPanelSection>
    </EntityPanel>
  )
}
