import { memo } from 'react'
import { Handle, Position } from '@xyflow/react'
import type { NodeProps } from '@xyflow/react'
import { BaseNode } from './BaseNode'
import type { LlmParamsNode as LlmParamsNodeType } from '../../../types/graph'

/**
 * LLM Parameters node component
 */
export const LlmNode = memo(function LlmNode({ data, selected }: NodeProps<LlmParamsNodeType>) {
  const { status, label, params } = data

  return (
    <>
      <Handle type="target" position={Position.Top} id="in" />
      <BaseNode
        title={label}
        status={status}
        selected={selected}
        icon={<span>🤖</span>}
      >
        {params && (
          <div className="config-node__details">
            {params.model_name && (
              <div className="config-node__detail-row">
                <span className="config-node__detail-label">Model</span>
                <span className="chip chip--violet">{params.model_name}</span>
              </div>
            )}
            {params.temperature !== null && (
              <div className="config-node__detail-row">
                <span className="config-node__detail-label">Temperature</span>
                <span className="chip chip--outline">{params.temperature}</span>
              </div>
            )}
            {params.max_tokens !== null && (
              <div className="config-node__detail-row">
                <span className="config-node__detail-label">Max tokens</span>
                <span className="chip chip--outline">{params.max_tokens}</span>
              </div>
            )}
          </div>
        )}
      </BaseNode>
      <Handle type="source" position={Position.Bottom} id="out" />
    </>
  )
})
