import { memo } from 'react'
import { Handle, Position, type NodeProps } from '@xyflow/react'
import type { RestrictionNode as RestrictionNodeType } from '../../../types/graph'
import { BaseNode } from './BaseNode'

/**
 * Icon for restriction node
 */
function RestrictionIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <circle cx="12" cy="12" r="10" />
      <line x1="4.93" y1="4.93" x2="19.07" y2="19.07" />
    </svg>
  )
}

/**
 * Formats restriction type for display
 */
function formatRestrictionType(type: string): string {
  return type
    .replace(/_/g, ' ')
    .toLowerCase()
    .replace(/\b\w/g, (c) => c.toUpperCase())
}

/**
 * Formats action type for display
 */
function formatActionType(action: string): string {
  return action
    .replace(/_/g, ' ')
    .toLowerCase()
    .replace(/\b\w/g, (c) => c.toUpperCase())
}

/**
 * RestrictionNode displays a topic restriction in the graph
 */
export const RestrictionNode = memo(function RestrictionNode({ data, selected }: NodeProps<RestrictionNodeType>) {
  const { restriction, status, label } = data
  const isActive = restriction.active

  const subtitle = restriction.restriction_name

  return (
    <>
      <Handle type="target" position={Position.Left} className="config-handle" />
      <BaseNode
        title={label}
        subtitle={subtitle}
        status={status}
        selected={selected}
        icon={<RestrictionIcon />}
      >
        <div className="config-node__details">
          <div className="config-node__detail-row">
            <span className="config-node__detail-label">Type:</span>
            <span className="config-node__detail-value">{formatRestrictionType(restriction.restriction_type)}</span>
          </div>
          <div className="config-node__detail-row">
            <span className="config-node__detail-label">Action:</span>
            <span className="config-node__detail-value">{formatActionType(restriction.action_type)}</span>
          </div>
          {restriction.keywords && (
            <div className="config-node__detail-row">
              <span className="config-node__detail-label">Keywords:</span>
              <span className="config-node__detail-value config-node__detail-value--truncate">
                {restriction.keywords}
              </span>
            </div>
          )}
          {restriction.categories && (
            <div className="config-node__detail-row">
              <span className="config-node__detail-label">Categories:</span>
              <span className="config-node__detail-value config-node__detail-value--truncate">
                {restriction.categories}
              </span>
            </div>
          )}
          <div className="config-node__detail-row">
            <span className="config-node__detail-label">Status:</span>
            <span className={`config-node__detail-value ${isActive ? 'config-node__detail-value--active' : 'config-node__detail-value--inactive'}`}>
              {isActive ? 'Active' : 'Inactive'}
            </span>
          </div>
        </div>
      </BaseNode>
    </>
  )
})
