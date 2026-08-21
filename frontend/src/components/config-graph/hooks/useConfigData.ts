/**
 * useConfigData Hook
 *
 * Custom hook for loading and managing configuration data for the graph.
 * Handles data fetching, caching, and state management.
 */

import { useState, useCallback, useRef, useEffect } from 'react'
import {
  fetchConfigurationOverview,
  fetchFullChannelConfig,
  type ConfigurationOverview,
  type FullChannelConfig,
} from '../../../api/configClient'
import type { ConfigNode, ConfigEdge } from '../../../types/graph'
import { transformToGraphData, getConfigurationSummary } from '../utils/transformData'

/**
 * State for configuration data loading
 */
export interface ConfigDataState {
  overview: ConfigurationOverview | null
  nodes: ConfigNode[]
  edges: ConfigEdge[]
  isLoading: boolean
  error: string | null
  lastUpdated: Date | null
}

/**
 * Summary statistics for the configuration
 */
export interface ConfigSummary {
  totalChannels: number
  configuredChannels: number
  enabledChannels: number
  totalDigestPersonas: number
  activeDigestPersonas: number
}

/**
 * Return type for useConfigData hook
 */
export interface UseConfigDataResult {
  state: ConfigDataState
  summary: ConfigSummary | null
  loadData: () => Promise<void>
  refreshData: () => Promise<void>
  loadChannelDetails: (channelId: number) => Promise<FullChannelConfig | null>
  clearError: () => void
}

/**
 * Cache for channel details to avoid redundant API calls
 */
interface ChannelDetailsCache {
  [channelId: number]: {
    data: FullChannelConfig
    timestamp: number
  }
}

const CACHE_TTL_MS = 60000 // 1 minute cache TTL

/**
 * Custom hook for managing configuration data
 */
export function useConfigData(): UseConfigDataResult {
  const [state, setState] = useState<ConfigDataState>({
    overview: null,
    nodes: [],
    edges: [],
    isLoading: false,
    error: null,
    lastUpdated: null,
  })

  const cacheRef = useRef<ChannelDetailsCache>({})
  const abortControllerRef = useRef<AbortController | null>(null)

  /**
   * Load configuration overview and transform to graph data
   */
  const loadData = useCallback(async () => {
    // Cancel any pending request
    if (abortControllerRef.current) {
      abortControllerRef.current.abort()
    }
    abortControllerRef.current = new AbortController()

    setState((prev) => ({ ...prev, isLoading: true, error: null }))

    try {
      const overview = await fetchConfigurationOverview()
      const { nodes, edges } = transformToGraphData(overview)

      setState({
        overview,
        nodes,
        edges,
        isLoading: false,
        error: null,
        lastUpdated: new Date(),
      })
    } catch (err) {
      // Don't set error if request was aborted
      if (err instanceof Error && err.name === 'AbortError') {
        return
      }

      const message = err instanceof Error ? err.message : 'Failed to load configuration data'
      setState((prev) => ({
        ...prev,
        isLoading: false,
        error: message,
      }))
    }
  }, [])

  /**
   * Refresh data (alias for loadData that clears cache)
   */
  const refreshData = useCallback(async () => {
    // Clear cache on refresh
    cacheRef.current = {}
    await loadData()
  }, [loadData])

  /**
   * Load detailed configuration for a specific channel
   * Uses caching to avoid redundant API calls
   */
  const loadChannelDetails = useCallback(
    async (channelId: number): Promise<FullChannelConfig | null> => {
      // Check cache
      const cached = cacheRef.current[channelId]
      if (cached && Date.now() - cached.timestamp < CACHE_TTL_MS) {
        return cached.data
      }

      try {
        const details = await fetchFullChannelConfig(channelId)

        // Update cache
        cacheRef.current[channelId] = {
          data: details,
          timestamp: Date.now(),
        }

        return details
      } catch (err) {
        const message = err instanceof Error ? err.message : 'Failed to load channel details'
        console.error(`Error loading channel ${channelId}:`, message)
        return null
      }
    },
    []
  )

  /**
   * Clear error state
   */
  const clearError = useCallback(() => {
    setState((prev) => ({ ...prev, error: null }))
  }, [])

  /**
   * Calculate summary from current overview
   */
  const summary: ConfigSummary | null = state.overview
    ? getConfigurationSummary(state.overview)
    : null

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

  return {
    state,
    summary,
    loadData,
    refreshData,
    loadChannelDetails,
    clearError,
  }
}

/**
 * Hook for loading data on mount with automatic retry
 */
export function useConfigDataWithAutoLoad(autoLoadOnMount: boolean = true): UseConfigDataResult {
  const result = useConfigData()

  useEffect(() => {
    if (autoLoadOnMount) {
      result.loadData()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [autoLoadOnMount])

  return result
}
