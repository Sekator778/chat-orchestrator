import { useReducer, useCallback, useEffect, useRef, type ReactNode } from 'react'
import { ConfigGraphContext } from './context'
import { fetchConfigurationOverview } from '../../api/configClient'
import { transformToGraphData, getConfigurationSummary } from './utils/transformData'
import type {
  ConfigGraphState,
  ConfigGraphAction,
  ConfigGraphContextValue,
  ConfigNode,
  ConfigEdge,
  AnyConfigNodeData,
} from '../../types/graph'

/**
 * Extended state including summary statistics
 */
interface ExtendedConfigGraphState extends ConfigGraphState {
  summary: {
    totalChannels: number
    configuredChannels: number
    enabledChannels: number
    totalDigestPersonas: number
    activeDigestPersonas: number
  } | null
  lastUpdated: Date | null
}

/**
 * Initial state for the configuration graph
 */
const initialState: ExtendedConfigGraphState = {
  nodes: [],
  edges: [],
  selectedNodeId: null,
  isLoading: false,
  error: null,
  summary: null,
  lastUpdated: null,
}

/**
 * Extended actions including summary updates
 */
type ExtendedConfigGraphAction =
  | ConfigGraphAction
  | { type: 'SET_SUMMARY'; payload: ExtendedConfigGraphState['summary'] }
  | { type: 'SET_LAST_UPDATED'; payload: Date | null }
  | { type: 'SET_DATA'; payload: { nodes: ConfigNode[]; edges: ConfigEdge[]; summary: ExtendedConfigGraphState['summary'] } }

/**
 * Reducer for configuration graph state
 */
function configGraphReducer(
  state: ExtendedConfigGraphState,
  action: ExtendedConfigGraphAction
): ExtendedConfigGraphState {
  switch (action.type) {
    case 'SET_LOADING':
      return { ...state, isLoading: action.payload, error: action.payload ? null : state.error }
    case 'SET_ERROR':
      return { ...state, error: action.payload, isLoading: false }
    case 'SET_NODES':
      return { ...state, nodes: action.payload }
    case 'SET_EDGES':
      return { ...state, edges: action.payload }
    case 'SELECT_NODE':
      return { ...state, selectedNodeId: action.payload }
    case 'UPDATE_NODE':
      return {
        ...state,
        nodes: state.nodes.map((node) =>
          node.id === action.payload.id
            ? { ...node, data: { ...node.data, ...action.payload.data } as AnyConfigNodeData }
            : node
        ),
      }
    case 'ADD_NODE':
      return { ...state, nodes: [...state.nodes, action.payload] }
    case 'REMOVE_NODE':
      return {
        ...state,
        nodes: state.nodes.filter((node) => node.id !== action.payload),
        edges: state.edges.filter(
          (edge) => edge.source !== action.payload && edge.target !== action.payload
        ),
        selectedNodeId: state.selectedNodeId === action.payload ? null : state.selectedNodeId,
      }
    case 'TOGGLE_NODE_EXPANSION':
      return {
        ...state,
        nodes: state.nodes.map((node) =>
          node.id === action.payload
            ? { ...node, data: { ...node.data, isExpanded: !node.data.isExpanded } as AnyConfigNodeData }
            : node
        ),
      }
    case 'SET_SUMMARY':
      return { ...state, summary: action.payload }
    case 'SET_LAST_UPDATED':
      return { ...state, lastUpdated: action.payload }
    case 'SET_DATA':
      return {
        ...state,
        nodes: action.payload.nodes,
        edges: action.payload.edges,
        summary: action.payload.summary,
        isLoading: false,
        error: null,
        lastUpdated: new Date(),
      }
    default:
      return state
  }
}

/**
 * Props for ConfigGraphProvider
 */
interface ConfigGraphProviderProps {
  children: ReactNode
  /**
   * Optional custom data loader. If not provided, uses default fetchConfigurationOverview
   */
  onRefresh?: () => Promise<{ nodes: ConfigNode[]; edges: ConfigEdge[] }>
  /**
   * Whether to automatically load data on mount. Defaults to true.
   */
  autoLoad?: boolean
}

/**
 * Provider component for configuration graph state
 * Handles data loading, state management, and provides context to children
 */
export function ConfigGraphProvider({
  children,
  onRefresh,
  autoLoad = true,
}: ConfigGraphProviderProps) {
  const [state, dispatch] = useReducer(configGraphReducer, initialState)
  const abortControllerRef = useRef<AbortController | null>(null)
  const hasLoadedRef = useRef(false)

  /**
   * Load configuration data from API
   */
  const loadData = useCallback(async () => {
    // Cancel any pending request
    if (abortControllerRef.current) {
      abortControllerRef.current.abort()
    }
    abortControllerRef.current = new AbortController()

    dispatch({ type: 'SET_LOADING', payload: true })

    try {
      const overview = await fetchConfigurationOverview()
      const { nodes, edges } = transformToGraphData(overview)
      const summary = getConfigurationSummary(overview)

      dispatch({
        type: 'SET_DATA',
        payload: { nodes, edges, summary },
      })
    } catch (err) {
      // Don't set error if request was aborted
      if (err instanceof Error && err.name === 'AbortError') {
        return
      }

      const message = err instanceof Error ? err.message : 'Failed to load configuration'
      dispatch({ type: 'SET_ERROR', payload: message })
    }
  }, [])

  /**
   * Refresh data - either using custom loader or default
   */
  const refreshData = useCallback(async () => {
    if (onRefresh) {
      // Use custom loader if provided
      dispatch({ type: 'SET_LOADING', payload: true })
      try {
        const { nodes, edges } = await onRefresh()
        dispatch({ type: 'SET_NODES', payload: nodes })
        dispatch({ type: 'SET_EDGES', payload: edges })
        dispatch({ type: 'SET_LOADING', payload: false })
        dispatch({ type: 'SET_LAST_UPDATED', payload: new Date() })
      } catch (err) {
        const message = err instanceof Error ? err.message : 'Failed to load configuration'
        dispatch({ type: 'SET_ERROR', payload: message })
      }
    } else {
      // Use default loader
      await loadData()
    }
  }, [onRefresh, loadData])

  /**
   * Select a node by ID
   */
  const selectNode = useCallback((nodeId: string | null) => {
    dispatch({ type: 'SELECT_NODE', payload: nodeId })
  }, [])

  /**
   * Auto-load data on mount if enabled
   */
  useEffect(() => {
    if (autoLoad && !hasLoadedRef.current) {
      hasLoadedRef.current = true
      refreshData()
    }
  }, [autoLoad, refreshData])

  /**
   * Cleanup on unmount
   */
  useEffect(() => {
    return () => {
      if (abortControllerRef.current) {
        abortControllerRef.current.abort()
      }
    }
  }, [])

  const value: ConfigGraphContextValue = {
    state,
    dispatch,
    refreshData,
    selectNode,
  }

  return <ConfigGraphContext.Provider value={value}>{children}</ConfigGraphContext.Provider>
}
