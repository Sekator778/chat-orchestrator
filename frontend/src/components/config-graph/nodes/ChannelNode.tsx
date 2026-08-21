import { memo } from 'react'
import { Handle, Position } from '@xyflow/react'
import type { NodeProps } from '@xyflow/react'
import { BaseNode } from './BaseNode'
import type { ChannelNode as ChannelNodeType } from '../../../types/graph'

/**
 * Channel node component for the configuration graph
 */
export const ChannelNode = memo(function ChannelNode({ data, selected }: NodeProps<ChannelNodeType>) {
  const { channel, status, label } = data

  const hasConfig = channel.hasConfig
  const isEnabled = channel.enabled ?? false
  const triggerCount = channel.triggerCount ?? 0

  return (
    <>
      <BaseNode
        title={label}
        subtitle={`ID: ${channel.chatId}`}
        status={status}
        selected={selected}
        icon={<span>📺</span>}
      >
        <div className="config-node__details">
          <div className="config-node__detail-row">
            <span className="config-node__detail-label">Status</span>
            <span className={`chip ${isEnabled ? 'chip--green' : 'chip--outline'}`}>
              {isEnabled ? 'Enabled' : 'Disabled'}
            </span>
          </div>
          {hasConfig && (
            <div className="config-node__detail-row">
              <span className="config-node__detail-label">Triggers</span>
              <span className="chip chip--outline">{triggerCount}</span>
            </div>
          )}
          {channel.language && (
            <div className="config-node__detail-row">
              <span className="config-node__detail-label">Language</span>
              <span className="chip chip--violet">{channel.language}</span>
            </div>
          )}
        </div>
      </BaseNode>
      <Handle type="source" position={Position.Bottom} id="out" />
    </>
  )
})
