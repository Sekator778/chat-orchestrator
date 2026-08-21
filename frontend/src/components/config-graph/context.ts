import { createContext } from 'react'
import type { ConfigGraphContextValue } from '../../types/graph'

/**
 * React Context for the configuration graph state management.
 *
 * This context provides:
 * - `state` - The current graph state including nodes, edges, selection, and loading status
 * - `dispatch` - Function to dispatch state update actions
 * - `refreshData` - Async function to reload configuration data from the backend
 * - `selectNode` - Function to select a node by its ID (or null to deselect)
 *
 * Must be used within a `ConfigGraphProvider` component.
 * Use the `useConfigGraph` hook to access this context.
 *
 * @see ConfigGraphProvider - The provider component
 * @see useConfigGraph - The hook to consume this context
 *
 * @example
 * ```tsx
 * // Wrap your app or component tree
 * <ConfigGraphProvider autoLoad={true}>
 *   <MyConfigurationUI />
 * </ConfigGraphProvider>
 *
 * // Then use the hook in child components
 * function MyConfigurationUI() {
 *   const { state, selectNode } = useConfigGraph()
 *   // ...
 * }
 * ```
 */
export const ConfigGraphContext = createContext<ConfigGraphContextValue | null>(null)
