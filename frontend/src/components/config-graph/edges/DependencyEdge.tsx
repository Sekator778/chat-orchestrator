/**
 * DependencyEdge - Custom edge component for required dependencies
 *
 * Shows solid, animated line indicating a required relationship
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
 * Edge component for required dependencies
 * Shows animated dashed line with optional label
 */
export function DependencyEdge({
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
        className={`config-edge config-edge--dependency ${selected ? 'config-edge--selected' : ''}`}
        style={{
          stroke: '#4CAF50',
          strokeWidth: 2,
          strokeDasharray: '5,5',
          animation: 'config-edge-flow 1s linear infinite',
        }}
      />
      {label && (
        <EdgeLabelRenderer>
          <div
            className="config-edge__label config-edge__label--dependency"
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
