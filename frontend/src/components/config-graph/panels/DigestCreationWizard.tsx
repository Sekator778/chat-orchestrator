import { useState, useCallback, type ChangeEvent } from 'react'
import { createPersona } from '../../../api/digestClient'
import { useConfigGraph } from '../hooks'
import type { DigestPersonaStyle, CreatePersonaRequest, DigestPersona } from '../../../types/digest'
import { PERSONA_STYLES, LANGUAGES, TIMEZONES, SCHEDULE_PRESETS } from '../../../types/digest'
import type { ChannelOverview } from '../../../types/api'

/**
 * Props for DigestCreationWizard component
 */
export interface DigestCreationWizardProps {
  /** Available channels for target selection */
  channels: ChannelOverview[]
  /** Callback when wizard is closed/cancelled */
  onClose: () => void
  /** Callback when persona is successfully created */
  onCreated: (persona: DigestPersona) => void
}

/**
 * Wizard steps
 */
type WizardStep = 'basics' | 'target' | 'schedule' | 'content' | 'review'

const WIZARD_STEPS: { id: WizardStep; label: string }[] = [
  { id: 'basics', label: 'Basics' },
  { id: 'target', label: 'Target' },
  { id: 'schedule', label: 'Schedule' },
  { id: 'content', label: 'Content' },
  { id: 'review', label: 'Review' },
]

/**
 * Default form values
 */
function defaultFormData(): CreatePersonaRequest {
  return {
    name: '',
    description: null,
    botId: 0,
    targetChannelId: 0,
    enabled: false,
    personaStyle: 'PROFESSIONAL',
    customSystemPrompt: null,
    scheduleCron: null,
    scheduleTimezone: 'Europe/Moscow',
    activeHoursStart: null,
    activeHoursEnd: null,
    lookbackHours: 24,
    maxMessages: 10,
    language: 'ru',
    minClusterSize: 2,
    minImportanceScore: 0.0,
    sourceTrustThreshold: 0.0,
    excludedChannelIds: [],
    topicKeywords: [],
    negativeKeywords: [],
    modelName: null,
    temperature: 0.7,
    maxTokens: 1000,
  }
}

/**
 * Wizard component for creating new digest personas
 * Guides users through a step-by-step configuration process
 */
export function DigestCreationWizard({ channels, onClose, onCreated }: DigestCreationWizardProps) {
  const { refreshData } = useConfigGraph()
  const [currentStep, setCurrentStep] = useState<WizardStep>('basics')
  const [formData, setFormData] = useState<CreatePersonaRequest>(defaultFormData())
  const [isCreating, setIsCreating] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [topicKeywordsText, setTopicKeywordsText] = useState('')
  const [negativeKeywordsText, setNegativeKeywordsText] = useState('')

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

  const handleCheckboxChange = useCallback((e: ChangeEvent<HTMLInputElement>) => {
    const { name, checked } = e.target
    setFormData((prev) => ({
      ...prev,
      [name]: checked,
    }))
  }, [])

  const goToStep = useCallback((step: WizardStep) => {
    setCurrentStep(step)
    setError(null)
  }, [])

  const nextStep = useCallback(() => {
    const stepIndex = WIZARD_STEPS.findIndex((s) => s.id === currentStep)
    if (stepIndex < WIZARD_STEPS.length - 1) {
      goToStep(WIZARD_STEPS[stepIndex + 1].id)
    }
  }, [currentStep, goToStep])

  const prevStep = useCallback(() => {
    const stepIndex = WIZARD_STEPS.findIndex((s) => s.id === currentStep)
    if (stepIndex > 0) {
      goToStep(WIZARD_STEPS[stepIndex - 1].id)
    }
  }, [currentStep, goToStep])

  const validateBasics = useCallback(() => {
    if (!formData.name?.trim()) {
      setError('Name is required')
      return false
    }
    return true
  }, [formData.name])

  const validateTarget = useCallback(() => {
    if (!formData.botId) {
      setError('Bot ID is required')
      return false
    }
    if (!formData.targetChannelId) {
      setError('Target channel is required')
      return false
    }
    return true
  }, [formData.botId, formData.targetChannelId])

  const handleNext = useCallback(() => {
    setError(null)
    if (currentStep === 'basics' && !validateBasics()) return
    if (currentStep === 'target' && !validateTarget()) return
    nextStep()
  }, [currentStep, validateBasics, validateTarget, nextStep])

  const handleCreate = useCallback(async () => {
    if (!validateBasics() || !validateTarget()) return
    setIsCreating(true)
    setError(null)
    const topicKeywords = topicKeywordsText
      .split(',')
      .map((k) => k.trim())
      .filter((k) => k.length > 0)
    const negativeKeywords = negativeKeywordsText
      .split(',')
      .map((k) => k.trim())
      .filter((k) => k.length > 0)
    const createData: CreatePersonaRequest = {
      ...formData,
      topicKeywords: topicKeywords.length > 0 ? topicKeywords : undefined,
      negativeKeywords: negativeKeywords.length > 0 ? negativeKeywords : undefined,
    }
    try {
      const created = await createPersona(createData)
      await refreshData()
      onCreated(created)
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to create persona'
      setError(message)
    } finally {
      setIsCreating(false)
    }
  }, [formData, topicKeywordsText, negativeKeywordsText, validateBasics, validateTarget, refreshData, onCreated])

  const renderStepIndicator = () => (
    <div className="wizard-steps">
      {WIZARD_STEPS.map((step, index) => {
        const stepIndex = WIZARD_STEPS.findIndex((s) => s.id === currentStep)
        const isActive = step.id === currentStep
        const isCompleted = index < stepIndex
        return (
          <button
            key={step.id}
            className={`wizard-step ${isActive ? 'wizard-step--active' : ''} ${isCompleted ? 'wizard-step--completed' : ''}`}
            onClick={() => isCompleted && goToStep(step.id)}
            disabled={!isCompleted && !isActive}
          >
            <span className="wizard-step__number">{isCompleted ? '✓' : index + 1}</span>
            <span className="wizard-step__label">{step.label}</span>
          </button>
        )
      })}
    </div>
  )

  const renderBasicsStep = () => (
    <div className="wizard-content">
      <h4>Basic Information</h4>
      <p className="muted">Configure the name, language, and style for your digest persona.</p>
      <div className="wizard-form">
        <label className="wizard-field">
          <span className="wizard-field__label">Name *</span>
          <input
            type="text"
            name="name"
            value={formData.name ?? ''}
            onChange={handleInputChange}
            placeholder="e.g., Morning News Digest"
            required
          />
        </label>
        <label className="wizard-field">
          <span className="wizard-field__label">Description</span>
          <textarea
            name="description"
            value={formData.description ?? ''}
            onChange={handleInputChange}
            placeholder="Optional description of this digest's purpose..."
            rows={2}
          />
        </label>
        <div className="wizard-form__row">
          <label className="wizard-field">
            <span className="wizard-field__label">Language</span>
            <select name="language" value={formData.language ?? 'ru'} onChange={handleInputChange}>
              {LANGUAGES.map(({ value, label }) => (
                <option key={value} value={value}>{label}</option>
              ))}
            </select>
          </label>
          <label className="wizard-field">
            <span className="wizard-field__label">Style</span>
            <select
              name="personaStyle"
              value={formData.personaStyle ?? 'PROFESSIONAL'}
              onChange={handleInputChange}
            >
              {Object.entries(PERSONA_STYLES).map(([value, { label }]) => (
                <option key={value} value={value}>{label}</option>
              ))}
            </select>
          </label>
        </div>
        <p className="wizard-field__hint">
          {PERSONA_STYLES[formData.personaStyle as DigestPersonaStyle]?.description}
        </p>
        {formData.personaStyle === 'CUSTOM' && (
          <label className="wizard-field">
            <span className="wizard-field__label">Custom System Prompt</span>
            <textarea
              name="customSystemPrompt"
              value={formData.customSystemPrompt ?? ''}
              onChange={handleInputChange}
              placeholder="Enter custom system prompt for the LLM..."
              rows={4}
            />
          </label>
        )}
      </div>
    </div>
  )

  const renderTargetStep = () => (
    <div className="wizard-content">
      <h4>Target Configuration</h4>
      <p className="muted">Select the bot and target channel where digests will be published.</p>
      <div className="wizard-form">
        <label className="wizard-field">
          <span className="wizard-field__label">Bot ID *</span>
          <input
            type="number"
            name="botId"
            value={formData.botId || ''}
            onChange={handleInputChange}
            placeholder="Enter bot user ID"
            required
          />
          <span className="wizard-field__hint">The Telegram user ID of the bot account that will publish digests</span>
        </label>
        <label className="wizard-field">
          <span className="wizard-field__label">Target Channel *</span>
          {channels.length > 0 ? (
            <select
              name="targetChannelId"
              value={formData.targetChannelId || ''}
              onChange={handleInputChange}
              required
            >
              <option value="">Select a channel...</option>
              {channels.map((ch) => (
                <option key={ch.chatId} value={ch.chatId}>
                  {ch.title || `Channel ${ch.chatId}`} (ID: {ch.chatId})
                </option>
              ))}
            </select>
          ) : (
            <input
              type="number"
              name="targetChannelId"
              value={formData.targetChannelId || ''}
              onChange={handleInputChange}
              placeholder="Enter channel ID"
              required
            />
          )}
          <span className="wizard-field__hint">The channel where digest messages will be posted</span>
        </label>
        <label className="wizard-field wizard-field--checkbox">
          <input
            type="checkbox"
            name="enabled"
            checked={formData.enabled ?? false}
            onChange={handleCheckboxChange}
          />
          <span>Enable persona immediately after creation</span>
        </label>
      </div>
    </div>
  )

  const renderScheduleStep = () => (
    <div className="wizard-content">
      <h4>Schedule Configuration</h4>
      <p className="muted">Configure when digests should be automatically generated and published.</p>
      <div className="wizard-form">
        <label className="wizard-field">
          <span className="wizard-field__label">Schedule Preset</span>
          <select
            name="scheduleCron"
            value={formData.scheduleCron ?? ''}
            onChange={handleInputChange}
          >
            <option value="">Manual only (no automatic publishing)</option>
            {SCHEDULE_PRESETS.map(({ value, label }) => (
              <option key={value} value={value}>{label}</option>
            ))}
          </select>
        </label>
        <label className="wizard-field">
          <span className="wizard-field__label">Timezone</span>
          <select
            name="scheduleTimezone"
            value={formData.scheduleTimezone ?? 'UTC'}
            onChange={handleInputChange}
          >
            {TIMEZONES.map(({ value, label }) => (
              <option key={value} value={value}>{label}</option>
            ))}
          </select>
        </label>
        <div className="wizard-form__row">
          <label className="wizard-field">
            <span className="wizard-field__label">Active Hours Start</span>
            <input
              type="time"
              name="activeHoursStart"
              value={formData.activeHoursStart ?? ''}
              onChange={handleInputChange}
            />
          </label>
          <label className="wizard-field">
            <span className="wizard-field__label">Active Hours End</span>
            <input
              type="time"
              name="activeHoursEnd"
              value={formData.activeHoursEnd ?? ''}
              onChange={handleInputChange}
            />
          </label>
        </div>
        <p className="wizard-field__hint">
          Leave empty for 24/7 operation. Digests will only be published during active hours.
        </p>
      </div>
    </div>
  )

  const renderContentStep = () => (
    <div className="wizard-content">
      <h4>Content Settings</h4>
      <p className="muted">Configure message filtering and content parameters.</p>
      <div className="wizard-form">
        <div className="wizard-form__row">
          <label className="wizard-field">
            <span className="wizard-field__label">Lookback Hours</span>
            <input
              type="number"
              name="lookbackHours"
              value={formData.lookbackHours ?? 24}
              onChange={handleInputChange}
              min={1}
              max={168}
            />
            <span className="wizard-field__hint">How far back to look for messages</span>
          </label>
          <label className="wizard-field">
            <span className="wizard-field__label">Max Messages</span>
            <input
              type="number"
              name="maxMessages"
              value={formData.maxMessages ?? 10}
              onChange={handleInputChange}
              min={1}
              max={50}
            />
            <span className="wizard-field__hint">Maximum messages in digest</span>
          </label>
        </div>
        <div className="wizard-form__row">
          <label className="wizard-field">
            <span className="wizard-field__label">Min Cluster Size</span>
            <input
              type="number"
              name="minClusterSize"
              value={formData.minClusterSize ?? 2}
              onChange={handleInputChange}
              min={1}
              max={10}
            />
          </label>
          <label className="wizard-field">
            <span className="wizard-field__label">Min Importance</span>
            <input
              type="number"
              name="minImportanceScore"
              value={formData.minImportanceScore ?? 0}
              onChange={handleInputChange}
              min={0}
              max={1}
              step={0.1}
            />
          </label>
        </div>
        <label className="wizard-field">
          <span className="wizard-field__label">Topic Keywords (comma-separated)</span>
          <input
            type="text"
            value={topicKeywordsText}
            onChange={(e) => setTopicKeywordsText(e.target.value)}
            placeholder="news, politics, technology..."
          />
          <span className="wizard-field__hint">Messages containing these keywords will be prioritized</span>
        </label>
        <label className="wizard-field">
          <span className="wizard-field__label">Negative Keywords (comma-separated)</span>
          <input
            type="text"
            value={negativeKeywordsText}
            onChange={(e) => setNegativeKeywordsText(e.target.value)}
            placeholder="spam, ads, promo..."
          />
          <span className="wizard-field__hint">Messages containing these keywords will be excluded</span>
        </label>
      </div>
    </div>
  )

  const renderReviewStep = () => {
    const topicKeywords = topicKeywordsText.split(',').map((k) => k.trim()).filter(Boolean)
    const negativeKeywords = negativeKeywordsText.split(',').map((k) => k.trim()).filter(Boolean)
    const targetChannel = channels.find((ch) => ch.chatId === formData.targetChannelId)
    return (
      <div className="wizard-content">
        <h4>Review Configuration</h4>
        <p className="muted">Review your digest persona settings before creating.</p>
        <div className="wizard-review">
          <div className="wizard-review__section">
            <h5>Basic Info</h5>
            <div className="wizard-review__row">
              <span>Name</span>
              <strong>{formData.name}</strong>
            </div>
            <div className="wizard-review__row">
              <span>Style</span>
              <strong>{PERSONA_STYLES[formData.personaStyle as DigestPersonaStyle]?.label}</strong>
            </div>
            <div className="wizard-review__row">
              <span>Language</span>
              <strong>{LANGUAGES.find((l) => l.value === formData.language)?.label ?? formData.language}</strong>
            </div>
            {formData.description && (
              <div className="wizard-review__row">
                <span>Description</span>
                <span className="muted">{formData.description}</span>
              </div>
            )}
          </div>
          <div className="wizard-review__section">
            <h5>Target</h5>
            <div className="wizard-review__row">
              <span>Bot ID</span>
              <strong>{formData.botId}</strong>
            </div>
            <div className="wizard-review__row">
              <span>Target Channel</span>
              <strong>{targetChannel?.title ?? `ID: ${formData.targetChannelId}`}</strong>
            </div>
            <div className="wizard-review__row">
              <span>Enabled</span>
              <span className={formData.enabled ? 'wizard-review__badge--green' : 'wizard-review__badge--gray'}>
                {formData.enabled ? 'Yes' : 'No'}
              </span>
            </div>
          </div>
          <div className="wizard-review__section">
            <h5>Schedule</h5>
            <div className="wizard-review__row">
              <span>Schedule</span>
              <strong>
                {formData.scheduleCron
                  ? SCHEDULE_PRESETS.find((p) => p.value === formData.scheduleCron)?.label ?? formData.scheduleCron
                  : 'Manual only'}
              </strong>
            </div>
            <div className="wizard-review__row">
              <span>Timezone</span>
              <strong>{TIMEZONES.find((tz) => tz.value === formData.scheduleTimezone)?.label ?? formData.scheduleTimezone}</strong>
            </div>
            {(formData.activeHoursStart || formData.activeHoursEnd) && (
              <div className="wizard-review__row">
                <span>Active Hours</span>
                <strong>{formData.activeHoursStart ?? '00:00'} - {formData.activeHoursEnd ?? '23:59'}</strong>
              </div>
            )}
          </div>
          <div className="wizard-review__section">
            <h5>Content</h5>
            <div className="wizard-review__row">
              <span>Lookback</span>
              <strong>{formData.lookbackHours}h</strong>
            </div>
            <div className="wizard-review__row">
              <span>Max Messages</span>
              <strong>{formData.maxMessages}</strong>
            </div>
            {topicKeywords.length > 0 && (
              <div className="wizard-review__row">
                <span>Topic Keywords</span>
                <span className="wizard-review__tags">
                  {topicKeywords.map((k) => (
                    <span key={k} className="chip chip--green chip--small">{k}</span>
                  ))}
                </span>
              </div>
            )}
            {negativeKeywords.length > 0 && (
              <div className="wizard-review__row">
                <span>Negative Keywords</span>
                <span className="wizard-review__tags">
                  {negativeKeywords.map((k) => (
                    <span key={k} className="chip chip--warn chip--small">{k}</span>
                  ))}
                </span>
              </div>
            )}
          </div>
        </div>
      </div>
    )
  }

  const renderCurrentStep = () => {
    switch (currentStep) {
      case 'basics':
        return renderBasicsStep()
      case 'target':
        return renderTargetStep()
      case 'schedule':
        return renderScheduleStep()
      case 'content':
        return renderContentStep()
      case 'review':
        return renderReviewStep()
      default:
        return null
    }
  }

  const isLastStep = currentStep === 'review'
  const isFirstStep = currentStep === 'basics'

  return (
    <div className="wizard-modal-overlay" onClick={onClose}>
      <div className="wizard-modal" onClick={(e) => e.stopPropagation()}>
        <div className="wizard-modal__header">
          <div>
            <p className="eyebrow">Create New</p>
            <h3>Digest Persona</h3>
          </div>
          <button className="wizard-modal__close ghost" onClick={onClose}>&times;</button>
        </div>
        {renderStepIndicator()}
        {error && (
          <div className="wizard-error">
            <span className="wizard-error__icon">!</span>
            {error}
          </div>
        )}
        {renderCurrentStep()}
        <div className="wizard-modal__footer">
          <div className="wizard-modal__footer-left">
            {!isFirstStep && (
              <button className="ghost" onClick={prevStep} disabled={isCreating}>
                ← Back
              </button>
            )}
          </div>
          <div className="wizard-modal__footer-right">
            <button className="ghost" onClick={onClose} disabled={isCreating}>
              Cancel
            </button>
            {isLastStep ? (
              <button onClick={handleCreate} disabled={isCreating}>
                {isCreating ? 'Creating...' : 'Create Persona'}
              </button>
            ) : (
              <button onClick={handleNext}>
                Next →
              </button>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}
