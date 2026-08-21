import { useState, useCallback, useEffect, type ChangeEvent } from 'react'
import { EntityPanel, EntityPanelSection, DetailRow, Field, Chip } from './EntityPanel'
import { fetchPersonasForBot, fetchPersona, savePersona } from '../../../api/client'
import { useConfigGraph } from '../hooks'
import type { BotPersonaNodeData } from '../../../types/graph'
import type { Persona } from '../../../types/api'

/**
 * Props for BotPersonaPanel component
 */
export interface BotPersonaPanelProps {
  data: BotPersonaNodeData
}

/**
 * Format date for display
 */
function formatDate(dateStr: string | null | undefined): string {
  if (!dateStr) return 'Unknown'
  try {
    const date = new Date(dateStr)
    return date.toLocaleString()
  } catch {
    return dateStr
  }
}

/**
 * Panel for viewing and editing bot persona configuration
 */
export function BotPersonaPanel({ data }: BotPersonaPanelProps) {
  const { refreshData } = useConfigGraph()
  const bundle = data.bundle

  const [, setPersonas] = useState<Persona[]>([])
  const [selectedLang, setSelectedLang] = useState<string | null>(null)
  const [selectedPersona, setSelectedPersona] = useState<Persona | null>(null)
  const [isLoadingPersonas, setIsLoadingPersonas] = useState(false)
  const [isLoadingDetails, setIsLoadingDetails] = useState(false)
  const [isEditing, setIsEditing] = useState(false)
  const [isSaving, setIsSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [success, setSuccess] = useState<string | null>(null)

  // Form state
  const [formData, setFormData] = useState<Partial<Persona>>({})

  // Load all personas for this bot
  useEffect(() => {
    let mounted = true

    async function loadPersonas() {
      setIsLoadingPersonas(true)
      setError(null)

      try {
        const result = await fetchPersonasForBot(bundle.botId)
        if (mounted) {
          setPersonas(result)
          // Auto-select first language if available
          if (result.length > 0 && !selectedLang) {
            setSelectedLang(result[0].language)
          }
        }
      } catch (err) {
        if (mounted) {
          const message = err instanceof Error ? err.message : 'Failed to load personas'
          setError(message)
        }
      } finally {
        if (mounted) {
          setIsLoadingPersonas(false)
        }
      }
    }

    loadPersonas()

    return () => {
      mounted = false
    }
  }, [bundle.botId, selectedLang])

  // Load persona details when language is selected
  useEffect(() => {
    if (!selectedLang) {
      setSelectedPersona(null)
      return
    }

    let mounted = true

    async function loadPersonaDetails() {
      setIsLoadingDetails(true)
      setError(null)

      try {
        const result = await fetchPersona(bundle.botId, selectedLang as string)
        if (mounted) {
          setSelectedPersona(result)
          setFormData({
            name: result.name,
            description: result.description,
            behavior: result.behavior,
            traits: result.traits,
            limitations: result.limitations,
          })
        }
      } catch (err) {
        if (mounted) {
          const message = err instanceof Error ? err.message : 'Failed to load persona details'
          setError(message)
          setSelectedPersona(null)
        }
      } finally {
        if (mounted) {
          setIsLoadingDetails(false)
        }
      }
    }

    loadPersonaDetails()

    return () => {
      mounted = false
    }
  }, [bundle.botId, selectedLang])

  const handleLanguageChange = useCallback((e: ChangeEvent<HTMLSelectElement>) => {
    setSelectedLang(e.target.value || null)
    setIsEditing(false)
    setError(null)
    setSuccess(null)
  }, [])

  const handleInputChange = useCallback((
    e: ChangeEvent<HTMLInputElement | HTMLTextAreaElement>
  ) => {
    const { name, value } = e.target
    setFormData((prev) => ({
      ...prev,
      [name]: value || null,
    }))
  }, [])

  const handleArrayInputChange = useCallback((field: 'behavior' | 'traits' | 'limitations', value: string) => {
    const items = value.split('\n').map((item) => item.trim()).filter((item) => item.length > 0)
    setFormData((prev) => ({
      ...prev,
      [field]: items,
    }))
  }, [])

  const handleEditToggle = useCallback(() => {
    if (isEditing && selectedPersona) {
      // Reset form
      setFormData({
        name: selectedPersona.name,
        description: selectedPersona.description,
        behavior: selectedPersona.behavior,
        traits: selectedPersona.traits,
        limitations: selectedPersona.limitations,
      })
    }
    setIsEditing(!isEditing)
    setError(null)
    setSuccess(null)
  }, [isEditing, selectedPersona])

  const handleSave = useCallback(async () => {
    if (!selectedPersona || !selectedLang) return

    setIsSaving(true)
    setError(null)
    setSuccess(null)

    try {
      const updateData: Persona = {
        ...selectedPersona,
        name: formData.name ?? selectedPersona.name,
        description: formData.description ?? selectedPersona.description,
        behavior: formData.behavior ?? selectedPersona.behavior,
        traits: formData.traits ?? selectedPersona.traits,
        limitations: formData.limitations ?? selectedPersona.limitations,
      }

      await savePersona(bundle.botId, selectedLang, updateData)
      setSuccess('Persona saved successfully')
      setIsEditing(false)

      // Reload persona
      const updated = await fetchPersona(bundle.botId, selectedLang)
      setSelectedPersona(updated)
      setFormData({
        name: updated.name,
        description: updated.description,
        behavior: updated.behavior,
        traits: updated.traits,
        limitations: updated.limitations,
      })

      await refreshData()
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to save persona'
      setError(message)
    } finally {
      setIsSaving(false)
    }
  }, [bundle.botId, selectedLang, selectedPersona, formData, refreshData])

  const handleCancel = useCallback(() => {
    handleEditToggle()
  }, [handleEditToggle])

  // View mode
  if (!isEditing) {
    return (
      <EntityPanel
        title={data.label}
        entityType="Bot Persona"
        status={data.status}
        editable={selectedPersona !== null}
        isEditing={false}
        error={error}
        success={success}
        onEditToggle={handleEditToggle}
      >
        <EntityPanelSection title="Bot Info">
          <DetailRow label="Bot ID">
            <Chip variant="violet">{bundle.botId}</Chip>
          </DetailRow>

          <DetailRow label="Languages">
            <div className="entity-panel__tags">
              {bundle.languages.map((lang) => (
                <Chip key={lang} variant="outline">{lang.toUpperCase()}</Chip>
              ))}
            </div>
          </DetailRow>

          {bundle.previewName && (
            <DetailRow label="Preview Name">
              <span className="entity-panel__text-value">{bundle.previewName}</span>
            </DetailRow>
          )}

          {bundle.previewDescription && (
            <DetailRow label="Preview">
              <span className="entity-panel__text-value">{bundle.previewDescription}</span>
            </DetailRow>
          )}

          <DetailRow label="Updated">
            <span className="entity-panel__text-value muted">
              {formatDate(bundle.updatedAt)}
            </span>
          </DetailRow>
        </EntityPanelSection>

        <EntityPanelSection title="Persona Details">
          <Field label="Select Language" htmlFor="persona-language">
            <select
              id="persona-language"
              value={selectedLang ?? ''}
              onChange={handleLanguageChange}
              disabled={isLoadingPersonas}
            >
              <option value="">Select language...</option>
              {bundle.languages.map((lang) => (
                <option key={lang} value={lang}>{lang.toUpperCase()}</option>
              ))}
            </select>
          </Field>

          {isLoadingDetails && (
            <div className="entity-panel__loading">Loading persona details...</div>
          )}

          {selectedPersona && !isLoadingDetails && (
            <>
              <DetailRow label="Name">
                <span className="entity-panel__text-value">{selectedPersona.name}</span>
              </DetailRow>

              {selectedPersona.description && (
                <DetailRow label="Description">
                  <span className="entity-panel__text-value">{selectedPersona.description}</span>
                </DetailRow>
              )}

              {selectedPersona.behavior.length > 0 && (
                <div className="entity-panel__field">
                  <span className="entity-panel__field-label">Behavior</span>
                  <ul className="entity-panel__list">
                    {selectedPersona.behavior.map((item, i) => (
                      <li key={i}>{item}</li>
                    ))}
                  </ul>
                </div>
              )}

              {selectedPersona.traits.length > 0 && (
                <div className="entity-panel__field">
                  <span className="entity-panel__field-label">Traits</span>
                  <div className="entity-panel__tags">
                    {selectedPersona.traits.map((trait, i) => (
                      <Chip key={i} variant="violet">{trait}</Chip>
                    ))}
                  </div>
                </div>
              )}

              {selectedPersona.limitations.length > 0 && (
                <div className="entity-panel__field">
                  <span className="entity-panel__field-label">Limitations</span>
                  <ul className="entity-panel__list">
                    {selectedPersona.limitations.map((item, i) => (
                      <li key={i}>{item}</li>
                    ))}
                  </ul>
                </div>
              )}
            </>
          )}
        </EntityPanelSection>
      </EntityPanel>
    )
  }

  // Edit mode
  return (
    <EntityPanel
      title={data.label}
      entityType="Bot Persona"
      status={data.status}
      editable
      isEditing
      isSaving={isSaving}
      error={error}
      success={success}
      onSave={handleSave}
      onCancel={handleCancel}
    >
      <EntityPanelSection title="Persona Info">
        <Field label="Language" htmlFor="language-display">
          <Chip variant="violet">{selectedLang?.toUpperCase()}</Chip>
        </Field>

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
            placeholder="Description of the persona"
            rows={2}
          />
        </Field>
      </EntityPanelSection>

      <EntityPanelSection title="Behavior & Traits">
        <Field label="Behavior" htmlFor="behavior" hint="One item per line">
          <textarea
            id="behavior"
            name="behavior"
            value={(formData.behavior ?? []).join('\n')}
            onChange={(e) => handleArrayInputChange('behavior', e.target.value)}
            placeholder="Describe behavior patterns, one per line"
            rows={4}
          />
        </Field>

        <Field label="Traits" htmlFor="traits" hint="One trait per line">
          <textarea
            id="traits"
            name="traits"
            value={(formData.traits ?? []).join('\n')}
            onChange={(e) => handleArrayInputChange('traits', e.target.value)}
            placeholder="Personality traits, one per line"
            rows={4}
          />
        </Field>

        <Field label="Limitations" htmlFor="limitations" hint="One limitation per line">
          <textarea
            id="limitations"
            name="limitations"
            value={(formData.limitations ?? []).join('\n')}
            onChange={(e) => handleArrayInputChange('limitations', e.target.value)}
            placeholder="Things this persona should not do"
            rows={4}
          />
        </Field>
      </EntityPanelSection>
    </EntityPanel>
  )
}
