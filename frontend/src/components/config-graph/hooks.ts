import { useContext, useMemo } from 'react'
import { ConfigGraphContext } from './context'
import type { ConfigGraphContextValue, ConfigNode } from '../../types/graph'

/**
 * Hook to access the configuration graph context.
 *
 * Provides access to:
 * - `state` - Current graph state (nodes, edges, selection, loading, error)
 * - `dispatch` - Dispatch function for state updates
 * - `refreshData` - Function to reload data from the backend
 * - `selectNode` - Function to select a node by ID
 *
 * @throws Error if used outside of ConfigGraphProvider
 *
 * @example
 * ```tsx
 * function MyComponent() {
 *   const { state, selectNode, refreshData } = useConfigGraph()
 *
 *   if (state.isLoading) return <Loading />
 *   if (state.error) return <Error message={state.error} />
 *
 *   return (
 *     <div>
 *       {state.nodes.map(node => (
 *         <button key={node.id} onClick={() => selectNode(node.id)}>
 *           {node.data.label}
 *         </button>
 *       ))}
 *     </div>
 *   )
 * }
 * ```
 */
export function useConfigGraph(): ConfigGraphContextValue {
  const context = useContext(ConfigGraphContext)
  if (!context) {
    throw new Error('useConfigGraph must be used within a ConfigGraphProvider')
  }
  return context
}

/**
 * Hook to access the currently selected node.
 *
 * Returns the full node object including position and data.
 * Memoized to prevent re-computation when other state changes.
 *
 * @returns The selected node or null if no node is selected
 *
 * @example
 * ```tsx
 * function NodeDetails() {
 *   const selectedNode = useSelectedNode()
 *
 *   if (!selectedNode) {
 *     return <p>No node selected</p>
 *   }
 *
 *   return (
 *     <div>
 *       <h3>{selectedNode.data.label}</h3>
 *       <p>Type: {selectedNode.data.entityType}</p>
 *       <p>Status: {selectedNode.data.status}</p>
 *     </div>
 *   )
 * }
 * ```
 */
export function useSelectedNode(): ConfigNode | null {
  const { state } = useConfigGraph()
  return useMemo(() => {
    if (!state.selectedNodeId) return null
    return state.nodes.find((node) => node.id === state.selectedNodeId) ?? null
  }, [state.selectedNodeId, state.nodes])
}

/**
 * Hook to filter nodes by their entity type.
 *
 * Useful for getting all nodes of a specific type (e.g., all channels,
 * all triggers). Memoized to prevent re-computation on every render.
 *
 * @param entityType - The entity type to filter by (e.g., 'channel', 'trigger', 'llmParams')
 * @returns Array of nodes matching the specified entity type
 *
 * @example
 * ```tsx
 * function ChannelList() {
 *   const channelNodes = useNodesByType('channel')
 *
 *   return (
 *     <ul>
 *       {channelNodes.map(node => (
 *         <li key={node.id}>{node.data.label}</li>
 *       ))}
 *     </ul>
 *   )
 * }
 * ```
 */
export function useNodesByType(entityType: string): ConfigNode[] {
  const { state } = useConfigGraph()
  return useMemo(() => {
    return state.nodes.filter((node) => node.data?.entityType === entityType)
  }, [state.nodes, entityType])
}

/**
 * Hook to find a node by its ID.
 *
 * Useful when you have a node ID and need the full node data.
 * Memoized for performance when used in components that need specific node data.
 *
 * @param nodeId - The ID of the node to find, or null
 * @returns The node with the specified ID, or null if not found or nodeId is null
 *
 * @example
 * ```tsx
 * function NodeViewer({ nodeId }: { nodeId: string }) {
 *   const node = useNodeById(nodeId)
 *
 *   if (!node) {
 *     return <p>Node not found</p>
 *   }
 *
 *   return <NodeCard node={node} />
 * }
 * ```
 */
export function useNodeById(nodeId: string | null): ConfigNode | null {
  const { state } = useConfigGraph()
  return useMemo(() => {
    if (!nodeId) return null
    return state.nodes.find((node) => node.id === nodeId) ?? null
  }, [state.nodes, nodeId])
}
