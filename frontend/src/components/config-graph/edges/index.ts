/**
 * Edge Components for Configuration Graph
 *
 * Custom edge types that visualize different relationship types:
 * - DependencyEdge: Required relationships (animated green)
 * - OptionalEdge: Optional relationships (dashed gray)
 * - ContainsEdge: Parent-child containment (solid blue)
 */

export { DependencyEdge } from './DependencyEdge'
export { OptionalEdge } from './OptionalEdge'
export { ContainsEdge } from './ContainsEdge'
