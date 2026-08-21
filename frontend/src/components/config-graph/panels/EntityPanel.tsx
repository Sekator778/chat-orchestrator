import { type ReactNode, useState, useCallback } from 'react'
import { useConfigGraph } from '../hooks'
import type { ConfigStatus } from '../../../types/graph'

/**
 * Props for the EntityPanel component
 */
export interface EntityPanelProps {
  /** Panel title (entity name) */
  title: string
  /** Entity type label (displayed as eyebrow) */
  entityType: string
  /** Entity status */
  status: ConfigStatus
  /** Children content */
  children: ReactNode
  /** Optional footer actions */
  footer?: ReactNode
  /** Whether the panel is in editing mode */
  isEditing?: boolean
  /** Whether the panel is saving */
  isSaving?: boolean
  /** Optional error message */
  error?: string | null
  /** Optional success message */
  success?: string | null
  /** Called when edit mode is toggled */
  onEditToggle?: () => void
  /** Called when save button is clicked */
  onSave?: () => void
  /** Called when cancel button is clicked */
  onCancel?: () => void
  /** Whether to show edit button (default: false for read-only) */
  editable?: boolean
}

/**
 * Format status for display
 */
function formatStatus(status: ConfigStatus): string {
  const statusLabels: Record<ConfigStatus, string> = {
    configured: 'Configured',
    partial: 'Partially Configured',
    warning: 'Warning',
    unconfigured: 'Not Configured',
    loading: 'Loading',
    saved: 'Saved (Inactive)',
  }
  return statusLabels[status] ?? status
}

/**
 * Base panel component for entity editing
 * Provides consistent structure for all entity panels
 */
export function EntityPanel({
  title,
  entityType,
  status,
  children,
  footer,
  isEditing = false,
  isSaving = false,
  error,
  success,
  onEditToggle,
  onSave,
  onCancel,
  editable = false,
}: EntityPanelProps) {
  const { selectNode } = useConfigGraph()

  const handleClose = useCallback(() => {
    selectNode(null)
  }, [selectNode])

  return (
    <div className="entity-panel">
      <div className="entity-panel__header">
        <div className="entity-panel__title-section">
          <p className="eyebrow">{entityType}</p>
          <h3 className="entity-panel__title">{title}</h3>
          <span className={`config-node__status config-node__status--${status}`}>
            {formatStatus(status)}
          </span>
        </div>
        <button
          className="entity-panel__close ghost"
          onClick={handleClose}
          aria-label="Close panel"
        >
          &times;
        </button>
      </div>

      {error && (
        <div className="entity-panel__notice entity-panel__notice--error">
          {error}
        </div>
      )}

      {success && (
        <div className="entity-panel__notice entity-panel__notice--success">
          {success}
        </div>
      )}

      <div className="entity-panel__body">{children}</div>

      {(editable || footer) && (
        <div className="entity-panel__footer">
          {editable && !isEditing && (
            <button className="ghost" onClick={onEditToggle}>
              Edit
            </button>
          )}
          {editable && isEditing && (
            <>
              <button className="ghost" onClick={onCancel} disabled={isSaving}>
                Cancel
              </button>
              <button onClick={onSave} disabled={isSaving}>
                {isSaving ? 'Saving...' : 'Save'}
              </button>
            </>
          )}
          {footer}
        </div>
      )}
    </div>
  )
}

/**
 * Section within an entity panel
 */
export interface EntityPanelSectionProps {
  title: string
  children: ReactNode
  collapsible?: boolean
  defaultCollapsed?: boolean
}

export function EntityPanelSection({
  title,
  children,
  collapsible = false,
  defaultCollapsed = false,
}: EntityPanelSectionProps) {
  const [isCollapsed, setIsCollapsed] = useState(defaultCollapsed)

  if (collapsible) {
    return (
      <div className={`entity-panel__section ${isCollapsed ? 'entity-panel__section--collapsed' : ''}`}>
        <button
          className="entity-panel__section-header entity-panel__section-header--collapsible"
          onClick={() => setIsCollapsed(!isCollapsed)}
        >
          <span className="entity-panel__section-title">{title}</span>
          <span className="entity-panel__section-toggle">{isCollapsed ? '+' : '-'}</span>
        </button>
        {!isCollapsed && <div className="entity-panel__section-body">{children}</div>}
      </div>
    )
  }

  return (
    <div className="entity-panel__section">
      <div className="entity-panel__section-header">
        <span className="entity-panel__section-title">{title}</span>
      </div>
      <div className="entity-panel__section-body">{children}</div>
    </div>
  )
}

/**
 * Row component for displaying a label-value pair
 */
export interface DetailRowProps {
  label: string
  children: ReactNode
}

export function DetailRow({ label, children }: DetailRowProps) {
  return (
    <div className="entity-panel__row">
      <span className="entity-panel__row-label">{label}</span>
      <span className="entity-panel__row-value">{children}</span>
    </div>
  )
}

/**
 * Field component for editable form fields
 */
export interface FieldProps {
  label: string
  htmlFor: string
  children: ReactNode
  hint?: string
  error?: string
  required?: boolean
}

export function Field({ label, htmlFor, children, hint, error, required }: FieldProps) {
  return (
    <div className={`entity-panel__field ${error ? 'entity-panel__field--error' : ''}`}>
      <label className="entity-panel__field-label" htmlFor={htmlFor}>
        {label}
        {required && <span className="entity-panel__field-required">*</span>}
      </label>
      {children}
      {hint && !error && <p className="entity-panel__field-hint">{hint}</p>}
      {error && <p className="entity-panel__field-error">{error}</p>}
    </div>
  )
}

/**
 * Chip component for status/tags display
 */
export interface ChipProps {
  variant?: 'default' | 'outline' | 'green' | 'amber' | 'red' | 'violet'
  children: ReactNode
}

export function Chip({ variant = 'default', children }: ChipProps) {
  const className = variant === 'default' ? 'chip' : `chip chip--${variant}`
  return <span className={className}>{children}</span>
}
