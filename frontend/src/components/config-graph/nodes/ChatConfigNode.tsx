import { memo } from 'react'
import { Handle, Position } from '@xyflow/react'
import type { NodeProps, Node } from '@xyflow/react'
import { BaseNode } from './BaseNode'
import type { ConfigNodeDataBase } from '../../../types/graph'

type GenericConfigNode = Node<ConfigNodeDataBase>

/**
 * Generic configuration node component
 * Used for ChatConfig and other basic configuration entities
 */
export const ChatConfigNode = memo(function ChatConfigNode({
  data,
  selected,
}: NodeProps<GenericConfigNode>) {
  const { status, label, entityType } = data

  const iconMap: Record<string, string> = {
    chatConfig: '⚙️',
    llmParams: '🤖',
    rateLimits: '⏱️',
    contextSettings: '📋',
    trigger: '⚡',
    template: '📝',
    restriction: '🚫',
    searchConfig: '🔍',
    digestPersona: '📰',
  }

  const icon = iconMap[entityType] ?? '📦'

  return (
    <>
      <Handle type="target" position={Position.Top} id="in" />
      <BaseNode
        title={label}
        status={status}
        selected={selected}
        icon={<span>{icon}</span>}
      />
      <Handle type="source" position={Position.Bottom} id="out" />
    </>
  )
})
