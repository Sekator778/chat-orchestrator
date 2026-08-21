import { memo, useMemo, type ReactNode } from 'react'
import type { ConfigStatus } from '../../../types/graph'

/**
 * Props for BaseNode component
 */
interface BaseNodeProps {
  title: string
  subtitle?: string
  status: ConfigStatus
  selected?: boolean
  children?: ReactNode
  icon?: ReactNode
  onClick?: () => void
  /** Entity type for accessibility labeling */
  entityType?: string
  /** Entity ID for accessibility labeling */
  entityId?: string | number
}

/**
 * Status configuration - defined outside component to avoid recreation
 * Includes aria-label for screen readers
 */
const STATUS_CONFIG: Record<ConfigStatus, { label: string; className: string; ariaLabel: string }> = {
  configured: { label: 'Configured', className: 'config-node__status--configured', ariaLabel: 'Status: fully configured' },
  partial: { label: 'Partial', className: 'config-node__status--partial', ariaLabel: 'Status: partially configured, requires attention' },
  warning: { label: 'Warning', className: 'config-node__status--warning', ariaLabel: 'Status: has warnings that need review' },
  unconfigured: { label: 'Not configured', className: 'config-node__status--unconfigured', ariaLabel: 'Status: not configured yet' },
  loading: { label: 'Loading', className: 'config-node__status--loading', ariaLabel: 'Status: loading configuration' },
  saved: { label: 'Saved', className: 'config-node__status--saved', ariaLabel: 'Status: changes saved' },
}

/**
 * Status badge component - memoized to prevent unnecessary re-renders
 * Includes ARIA label for screen reader support
 */
const StatusBadge = memo(function StatusBadge({ status }: { status: ConfigStatus }) {
  const config = STATUS_CONFIG[status]
  return (
    <span
      className={`config-node__status ${config.className}`}
      role="status"
      aria-label={config.ariaLabel}
    >
      {config.label}
    </span>
  )
})

/**
 * Base node component providing shared styling for all graph nodes
 * Memoized to prevent unnecessary re-renders when parent state changes
 * Includes ARIA attributes for accessibility
 */
export const BaseNode = memo(function BaseNode({
  title,
  subtitle,
  status,
  selected = false,
  children,
  icon,
  entityType,
  entityId,
}: BaseNodeProps) {
  const className = useMemo(
    () => `config-node ${selected ? 'config-node--selected' : ''}`,
    [selected]
  )

  // Build accessible label
  const ariaLabel = useMemo(() => {
    const parts = [entityType ?? 'Configuration', title]
    if (subtitle) parts.push(subtitle)
    parts.push(STATUS_CONFIG[status].ariaLabel)
    if (selected) parts.push('Currently selected')
    return parts.join('. ')
  }, [entityType, title, subtitle, status, selected])

  return (
    <div
      className={className}
      role="button"
      tabIndex={0}
      aria-label={ariaLabel}
      aria-selected={selected}
      aria-describedby={entityId ? `node-desc-${entityId}` : undefined}
    >
      <div className="config-node__header">
        <div className="config-node__title-row">
          {icon && <span className="config-node__icon" aria-hidden="true">{icon}</span>}
          <span className="config-node__title">{title}</span>
        </div>
        <StatusBadge status={status} />
      </div>
      {subtitle && (
        <p
          className="config-node__subtitle"
          id={entityId ? `node-desc-${entityId}` : undefined}
        >
          {subtitle}
        </p>
      )}
      {children && <div className="config-node__content" aria-label="Node details">{children}</div>}
    </div>
  )
})
