import { memo } from 'react'
import { Handle, Position } from '@xyflow/react'
import type { NodeProps, Node } from '@xyflow/react'
import { BaseNode } from './BaseNode'
import type { BotPersonaNodeData } from '../../../types/graph'

type BotPersonaNodeType = Node<BotPersonaNodeData, 'botPersona'>

/**
 * Bot Persona node component for the configuration graph
 * Displays bot persona bundle information with multi-language support
 */
export const BotPersonaNode = memo(function BotPersonaNode({
  data,
  selected,
}: NodeProps<BotPersonaNodeType>) {
  const { status, label, bundle } = data

  const languages = bundle.languages ?? []
  const previewName = bundle.previewName ?? bundle.botId
  const previewDesc = bundle.previewDescription ?? null
  const languageCount = languages.length
  const lastUpdated = bundle.updatedAt ? formatRelativeTime(bundle.updatedAt) : null

  return (
    <>
      <Handle type="target" position={Position.Top} id="in" />
      <BaseNode
        title={label}
        subtitle={previewDesc ?? undefined}
        status={status}
        selected={selected}
        icon={<span>🤖</span>}
      >
        <div className="config-node__details">
          <div className="config-node__detail-row">
            <span className="config-node__detail-label">Bot ID</span>
            <span className="chip chip--outline" title={bundle.botId}>
              {truncateId(bundle.botId)}
            </span>
          </div>
          <div className="config-node__detail-row">
            <span className="config-node__detail-label">Name</span>
            <span className="chip chip--violet">{previewName}</span>
          </div>
          <div className="config-node__detail-row">
            <span className="config-node__detail-label">Languages</span>
            <div className="chips">
              {languages.slice(0, 3).map((lang) => (
                <span key={lang} className="chip chip--outline">
                  {lang.toUpperCase()}
                </span>
              ))}
              {languageCount > 3 && (
                <span className="chip chip--outline" title={languages.join(', ')}>
                  +{languageCount - 3}
                </span>
              )}
            </div>
          </div>
          {lastUpdated && (
            <div className="config-node__detail-row">
              <span className="config-node__detail-label">Updated</span>
              <span className="chip chip--outline">{lastUpdated}</span>
            </div>
          )}
        </div>
      </BaseNode>
      <Handle type="source" position={Position.Bottom} id="out" />
    </>
  )
})

/**
 * Truncates a long ID for display
 */
function truncateId(id: string): string {
  if (id.length <= 12) return id
  return `${id.slice(0, 6)}...${id.slice(-4)}`
}

/**
 * Formats a date string to relative time (e.g., "2h ago", "3d ago")
 */
function formatRelativeTime(dateStr: string): string {
  const date = new Date(dateStr)
  const now = new Date()
  const diffMs = now.getTime() - date.getTime()
  const diffMins = Math.floor(diffMs / 60000)
  const diffHours = Math.floor(diffMins / 60)
  const diffDays = Math.floor(diffHours / 24)
  if (diffMins < 60) return `${diffMins}m ago`
  if (diffHours < 24) return `${diffHours}h ago`
  if (diffDays < 7) return `${diffDays}d ago`
  return date.toLocaleDateString()
}
