/**
 * OptionalEdge - Custom edge component for optional relationships
 *
 * Shows dashed gray line indicating an optional relationship
 * between two configuration entities.
 */

import {
  BaseEdge,
  EdgeLabelRenderer,
  getBezierPath,
  type EdgeProps,
} from '@xyflow/react'

/**
 * Edge data interface for typed access
 */
interface EdgeData {
  label?: string
  edgeType?: string
}

/**
 * Edge component for optional relationships
 * Shows dashed gray line with optional label
 */
export function OptionalEdge({
  id,
  sourceX,
  sourceY,
  targetX,
  targetY,
  sourcePosition,
  targetPosition,
  data,
  selected,
  markerEnd,
}: EdgeProps) {
  const [edgePath, labelX, labelY] = getBezierPath({
    sourceX,
    sourceY,
    sourcePosition,
    targetX,
    targetY,
    targetPosition,
  })

  const edgeData = data as EdgeData | undefined
  const label = edgeData?.label

  return (
    <>
      <BaseEdge
        id={id}
        path={edgePath}
        markerEnd={markerEnd}
        className={`config-edge config-edge--optional ${selected ? 'config-edge--selected' : ''}`}
        style={{
          stroke: '#9E9E9E',
          strokeWidth: 1.5,
          strokeDasharray: '4,4',
        }}
      />
      {label && (
        <EdgeLabelRenderer>
          <div
            className="config-edge__label config-edge__label--optional"
            style={{
              position: 'absolute',
              transform: `translate(-50%, -50%) translate(${labelX}px,${labelY}px)`,
              pointerEvents: 'all',
            }}
          >
            {label}
          </div>
        </EdgeLabelRenderer>
      )}
    </>
  )
}
