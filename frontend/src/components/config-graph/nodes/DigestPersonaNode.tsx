import { memo } from 'react'
import { Handle, Position } from '@xyflow/react'
import type { NodeProps, Node } from '@xyflow/react'
import { BaseNode } from './BaseNode'
import type { DigestPersonaNodeData } from '../../../types/graph'
import { PERSONA_STYLES } from '../../../types/digest'

type DigestPersonaNodeType = Node<DigestPersonaNodeData, 'digestPersona'>

/**
 * Digest Persona node component for the configuration graph
 * Displays digest persona configuration with schedule, style, and status
 */
export const DigestPersonaNode = memo(function DigestPersonaNode({
  data,
  selected,
}: NodeProps<DigestPersonaNodeType>) {
  const { status, label, persona } = data

  const styleLabel = PERSONA_STYLES[persona.personaStyle]?.label ?? persona.personaStyle
  const hasSchedule = Boolean(persona.scheduleCron)
  const hasTarget = Boolean(persona.targetChannelId)

  return (
    <>
      <Handle type="target" position={Position.Top} id="in" />
      <BaseNode
        title={label}
        subtitle={persona.description ?? undefined}
        status={status}
        selected={selected}
        icon={<span>📰</span>}
      >
        <div className="config-node__details">
          <div className="config-node__detail-row">
            <span className="config-node__detail-label">Style</span>
            <span className="chip chip--violet">{styleLabel}</span>
          </div>
          <div className="config-node__detail-row">
            <span className="config-node__detail-label">Language</span>
            <span className="chip chip--outline">{persona.language.toUpperCase()}</span>
          </div>
          {hasSchedule && (
            <div className="config-node__detail-row">
              <span className="config-node__detail-label">Schedule</span>
              <span className="chip chip--outline" title={persona.scheduleCron ?? ''}>
                {formatCronShort(persona.scheduleCron)}
              </span>
            </div>
          )}
          <div className="config-node__detail-row">
            <span className="config-node__detail-label">Target</span>
            <span className={`chip ${hasTarget ? 'chip--green' : 'chip--warn'}`}>
              {hasTarget ? `ID: ${persona.targetChannelId}` : 'Not set'}
            </span>
          </div>
          {persona.totalDigestsPublished > 0 && (
            <div className="config-node__detail-row">
              <span className="config-node__detail-label">Published</span>
              <span className="chip chip--green">{persona.totalDigestsPublished}</span>
            </div>
          )}
        </div>
      </BaseNode>
      <Handle type="source" position={Position.Bottom} id="out" />
    </>
  )
})

/**
 * Formats a cron expression to a short human-readable string
 */
function formatCronShort(cron: string | null): string {
  if (!cron) return 'None'
  const parts = cron.split(' ')
  if (parts.length < 5) return cron
  const [, minute, hour] = parts
  if (hour === '*') return 'Every hour'
  if (hour.includes('/')) {
    const interval = hour.split('/')[1]
    return `Every ${interval}h`
  }
  if (hour.includes(',')) {
    return `${hour.split(',').length}x daily`
  }
  return `At ${hour}:${minute.padStart(2, '0')}`
}
