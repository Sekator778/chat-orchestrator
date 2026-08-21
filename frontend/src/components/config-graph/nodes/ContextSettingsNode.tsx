import { memo } from 'react'
import { Handle, Position } from '@xyflow/react'
import type { NodeProps, Node } from '@xyflow/react'
import { BaseNode } from './BaseNode'
import type { ContextSettingsNodeData } from '../../../types/graph'

type ContextSettingsNodeType = Node<ContextSettingsNodeData, 'contextSettings'>

/**
 * Context Settings node component for the configuration graph
 * Displays message history and context configuration
 */
export const ContextSettingsNode = memo(function ContextSettingsNode({
  data,
  selected,
}: NodeProps<ContextSettingsNodeType>) {
  const { status, label, settings } = data

  const hasSettings = settings !== null
  const messageCount = settings?.history_message_count ?? null
  const timeWindow = settings?.history_time_window_hours ?? null
  const maxTokens = settings?.max_context_tokens ?? null
  const includeUser = settings?.include_user_context ?? false
  const includeMedia = settings?.include_media_descriptions ?? false

  return (
    <>
      <Handle type="target" position={Position.Top} id="in" />
      <BaseNode
        title={label}
        status={status}
        selected={selected}
        icon={<span>📋</span>}
      >
        {hasSettings ? (
          <div className="config-node__details">
            {messageCount !== null && (
              <div className="config-node__detail-row">
                <span className="config-node__detail-label">Messages</span>
                <span className="chip chip--outline">{messageCount}</span>
              </div>
            )}
            {timeWindow !== null && (
              <div className="config-node__detail-row">
                <span className="config-node__detail-label">Window</span>
                <span className="chip chip--outline">{timeWindow}h</span>
              </div>
            )}
            {maxTokens !== null && (
              <div className="config-node__detail-row">
                <span className="config-node__detail-label">Max Tokens</span>
                <span className="chip chip--violet">{formatNumber(maxTokens)}</span>
              </div>
            )}
            <div className="config-node__detail-row">
              <span className="config-node__detail-label">Options</span>
              <div className="chips">
                {includeUser && (
                  <span className="chip chip--green" title="Include user context">
                    👤
                  </span>
                )}
                {includeMedia && (
                  <span className="chip chip--green" title="Include media descriptions">
                    🖼️
                  </span>
                )}
                {!includeUser && !includeMedia && (
                  <span className="chip chip--outline">Default</span>
                )}
              </div>
            </div>
          </div>
        ) : (
          <div className="config-node__details">
            <p className="muted tiny">No context settings configured</p>
          </div>
        )}
      </BaseNode>
      <Handle type="source" position={Position.Bottom} id="out" />
    </>
  )
})

/**
 * Formats a number with K suffix for readability
 */
function formatNumber(n: number): string {
  if (n >= 1000) return `${(n / 1000).toFixed(0)}K`
  return String(n)
}
