/**
 * ContainsEdge - Custom edge component for parent-child relationships
 *
 * Shows solid blue line indicating a containment/composition relationship
 * between parent and child configuration entities.
 */

import {
  BaseEdge,
  EdgeLabelRenderer,
  getSmoothStepPath,
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
 * Edge component for containment/parent-child relationships
 * Shows solid blue step path with optional label
 */
export function ContainsEdge({
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
  const [edgePath, labelX, labelY] = getSmoothStepPath({
    sourceX,
    sourceY,
    sourcePosition,
    targetX,
    targetY,
    targetPosition,
    borderRadius: 8,
  })

  const edgeData = data as EdgeData | undefined
  const label = edgeData?.label

  return (
    <>
      <BaseEdge
        id={id}
        path={edgePath}
        markerEnd={markerEnd}
        className={`config-edge config-edge--contains ${selected ? 'config-edge--selected' : ''}`}
        style={{
          stroke: '#2196F3',
          strokeWidth: 2,
        }}
      />
      {label && (
        <EdgeLabelRenderer>
          <div
            className="config-edge__label config-edge__label--contains"
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
