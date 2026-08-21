import { memo } from 'react'
import { Handle, Position, type NodeProps } from '@xyflow/react'
import type { TemplateNode as TemplateNodeType } from '../../../types/graph'
import { BaseNode } from './BaseNode'

/**
 * Icon for template node
 */
function TemplateIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
      <polyline points="14 2 14 8 20 8" />
      <line x1="16" y1="13" x2="8" y2="13" />
      <line x1="16" y1="17" x2="8" y2="17" />
      <polyline points="10 9 9 9 8 9" />
    </svg>
  )
}

/**
 * Truncates text to specified length with ellipsis
 */
function truncateText(text: string, maxLength: number): string {
  if (text.length <= maxLength) return text
  return text.slice(0, maxLength - 3) + '...'
}

/**
 * TemplateNode displays a response template in the graph
 */
export const TemplateNode = memo(function TemplateNode({ data, selected }: NodeProps<TemplateNodeType>) {
  const { template, status, label } = data
  const isActive = template.active
  const isDefault = template.is_default

  const subtitle = template.template_name

  return (
    <>
      <Handle type="target" position={Position.Left} className="config-handle" />
      <BaseNode
        title={label}
        subtitle={subtitle}
        status={status}
        selected={selected}
        icon={<TemplateIcon />}
      >
        <div className="config-node__details">
          {template.response_style && (
            <div className="config-node__detail-row">
              <span className="config-node__detail-label">Style:</span>
              <span className="config-node__detail-value">{template.response_style}</span>
            </div>
          )}
          {template.response_tone && (
            <div className="config-node__detail-row">
              <span className="config-node__detail-label">Tone:</span>
              <span className="config-node__detail-value">{template.response_tone}</span>
            </div>
          )}
          {template.max_response_length && (
            <div className="config-node__detail-row">
              <span className="config-node__detail-label">Max length:</span>
              <span className="config-node__detail-value">{template.max_response_length}</span>
            </div>
          )}
          <div className="config-node__detail-row">
            <span className="config-node__detail-label">Content:</span>
            <span className="config-node__detail-value config-node__detail-value--truncate">
              {truncateText(template.template_content, 50)}
            </span>
          </div>
          <div className="config-node__detail-row">
            <span className="config-node__detail-label">Status:</span>
            <span className={`config-node__detail-value ${isActive ? 'config-node__detail-value--active' : 'config-node__detail-value--inactive'}`}>
              {isActive ? 'Active' : 'Inactive'}
              {isDefault && ' (Default)'}
            </span>
          </div>
        </div>
      </BaseNode>
    </>
  )
})
