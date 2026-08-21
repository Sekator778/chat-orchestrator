import { memo } from 'react'
import { Handle, Position } from '@xyflow/react'
import type { NodeProps, Node } from '@xyflow/react'
import { BaseNode } from './BaseNode'
import type { RateLimitsNodeData } from '../../../types/graph'

type RateLimitsNodeType = Node<RateLimitsNodeData, 'rateLimits'>

/**
 * Rate Limits node component for the configuration graph
 * Displays message rate limiting configuration
 */
export const RateLimitsNode = memo(function RateLimitsNode({
  data,
  selected,
}: NodeProps<RateLimitsNodeType>) {
  const { status, label, limits } = data

  const hasLimits = limits !== null
  const dailyLimit = limits?.max_messages_per_day ?? null
  const hourlyLimit = limits?.max_messages_per_hour ?? null
  const currentDaily = limits?.current_daily_messages ?? 0
  const tokenLimit = limits?.max_tokens_per_day ?? null

  return (
    <>
      <Handle type="target" position={Position.Top} id="in" />
      <BaseNode
        title={label}
        status={status}
        selected={selected}
        icon={<span>⏱️</span>}
      >
        {hasLimits ? (
          <div className="config-node__details">
            {hourlyLimit !== null && (
              <div className="config-node__detail-row">
                <span className="config-node__detail-label">Per Hour</span>
                <span className="chip chip--outline">{hourlyLimit}</span>
              </div>
            )}
            {dailyLimit !== null && (
              <div className="config-node__detail-row">
                <span className="config-node__detail-label">Per Day</span>
                <span className="chip chip--outline">
                  {currentDaily}/{dailyLimit}
                </span>
              </div>
            )}
            {tokenLimit !== null && (
              <div className="config-node__detail-row">
                <span className="config-node__detail-label">Tokens/Day</span>
                <span className="chip chip--violet">{formatNumber(tokenLimit)}</span>
              </div>
            )}
            {limits?.cooldown_after_limit_minutes !== null && (
              <div className="config-node__detail-row">
                <span className="config-node__detail-label">Cooldown</span>
                <span className="chip chip--outline">
                  {limits.cooldown_after_limit_minutes}min
                </span>
              </div>
            )}
          </div>
        ) : (
          <div className="config-node__details">
            <p className="muted tiny">No limits configured</p>
          </div>
        )}
      </BaseNode>
      <Handle type="source" position={Position.Bottom} id="out" />
    </>
  )
})

/**
 * Formats a number with K/M suffix for readability
 */
function formatNumber(n: number): string {
  if (n >= 1000000) return `${(n / 1000000).toFixed(1)}M`
  if (n >= 1000) return `${(n / 1000).toFixed(0)}K`
  return String(n)
}
