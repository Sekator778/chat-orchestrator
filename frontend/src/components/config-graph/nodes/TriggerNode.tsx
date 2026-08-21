import { memo } from 'react'
import { Handle, Position, type NodeProps } from '@xyflow/react'
import type { TriggerNode as TriggerNodeType } from '../../../types/graph'
import { BaseNode } from './BaseNode'

/**
 * Icon for trigger node
 */
function TriggerIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2" />
    </svg>
  )
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
 * TriggerNode displays a trigger condition in the graph
 */
export const TriggerNode = memo(function TriggerNode({ data, selected }: NodeProps<TriggerNodeType>) {
  const { trigger, status, label } = data
  const isActive = trigger.active

  const subtitle = trigger.condition_name || formatTriggerType(trigger.trigger_type)

  return (
    <>
      <Handle type="target" position={Position.Left} className="config-handle" />
      <BaseNode
        title={label}
        subtitle={subtitle}
        status={status}
        selected={selected}
        icon={<TriggerIcon />}
      >
        <div className="config-node__details">
          <div className="config-node__detail-row">
            <span className="config-node__detail-label">Type:</span>
            <span className="config-node__detail-value">{formatTriggerType(trigger.trigger_type)}</span>
          </div>
          {trigger.keywords && (
            <div className="config-node__detail-row">
              <span className="config-node__detail-label">Keywords:</span>
              <span className="config-node__detail-value config-node__detail-value--truncate">
                {trigger.keywords}
              </span>
            </div>
          )}
          <div className="config-node__detail-row">
            <span className="config-node__detail-label">Priority:</span>
            <span className="config-node__detail-value">{trigger.priority ?? 'Default'}</span>
          </div>
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
