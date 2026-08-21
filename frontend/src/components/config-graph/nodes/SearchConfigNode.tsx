import { memo } from 'react'
import { Handle, Position } from '@xyflow/react'
import type { NodeProps, Node } from '@xyflow/react'
import { BaseNode } from './BaseNode'
import type { SearchConfigNodeData } from '../../../types/graph'

type SearchConfigNodeType = Node<SearchConfigNodeData, 'searchConfig'>

/**
 * Search Configuration node component for the configuration graph
 * Displays web search integration settings
 */
export const SearchConfigNode = memo(function SearchConfigNode({
  data,
  selected,
}: NodeProps<SearchConfigNodeType>) {
  const { status, label, searchConfig } = data

  const hasConfig = searchConfig !== null
  const isEnabled = searchConfig?.search_enabled ?? false
  const isAutoEnabled = searchConfig?.auto_search_enabled ?? false
  const provider = searchConfig?.search_provider ?? 'unknown'
  const maxResults = searchConfig?.max_results ?? 0
  const rateLimit = searchConfig?.rate_limit_per_hour ?? 0
  const triggerCount = searchConfig?.search_triggers?.length ?? 0

  return (
    <>
      <Handle type="target" position={Position.Top} id="in" />
      <BaseNode
        title={label}
        status={status}
        selected={selected}
        icon={<span>🔍</span>}
      >
        {hasConfig ? (
          <div className="config-node__details">
            <div className="config-node__detail-row">
              <span className="config-node__detail-label">Status</span>
              <span className={`chip ${isEnabled ? 'chip--green' : 'chip--outline'}`}>
                {isEnabled ? 'Enabled' : 'Disabled'}
              </span>
            </div>
            <div className="config-node__detail-row">
              <span className="config-node__detail-label">Provider</span>
              <span className="chip chip--violet">{provider}</span>
            </div>
            <div className="config-node__detail-row">
              <span className="config-node__detail-label">Max Results</span>
              <span className="chip chip--outline">{maxResults}</span>
            </div>
            <div className="config-node__detail-row">
              <span className="config-node__detail-label">Rate Limit</span>
              <span className="chip chip--outline">{rateLimit}/h</span>
            </div>
            {isAutoEnabled && (
              <div className="config-node__detail-row">
                <span className="config-node__detail-label">Auto</span>
                <span className="chip chip--green">On</span>
              </div>
            )}
            {triggerCount > 0 && (
              <div className="config-node__detail-row">
                <span className="config-node__detail-label">Triggers</span>
                <span className="chip chip--outline">{triggerCount}</span>
              </div>
            )}
          </div>
        ) : (
          <div className="config-node__details">
            <p className="muted tiny">No search config</p>
          </div>
        )}
      </BaseNode>
      <Handle type="source" position={Position.Bottom} id="out" />
    </>
  )
})
