/**
 * useValidation Hook
 *
 * Provides real-time validation for configuration entities,
 * tracks issues, and manages related node highlighting.
 * Uses debouncing to prevent excessive validation calls.
 * Supports both client-side and server-side validation.
 */

import { useState, useCallback, useMemo, useEffect, useRef } from 'react'
import type { GraphNode, ConfigStatus, EntityType } from '../../../types/graph'
import {
  type DependencyIssue,
  type ValidationResult,
  validateChatConfig,
  validateLlmParameters,
  validateRateLimits,
  validateContextSettings,
  validateSearchConfig,
  validateDigestPersona,
  findDependentNodes,
  findParentNodes,
  getAllIssues,
  summarizeIssues,
} from '../utils/dependencyResolver'
import { DEBOUNCE_DELAYS } from '../utils/performance'
import type {
  EnhancedChatConfig,
  LlmParameters,
  RateLimits,
  ContextSettings,
  SearchConfig,
  ConfigValidationResponse,
  ValidationIssue,
} from '../../../types/api'
import type { DigestPersona } from '../../../types/digest'
import { validateConfiguration } from '../../../api/configClient'

/**
 * Validation state for the graph
 */
export interface ValidationState {
  /** Map of node ID to issues */
  issuesByNode: Map<string, DependencyIssue[]>
  /** Set of highlighted node IDs */
  highlightedNodes: Set<string>
  /** Currently selected node ID */
  selectedNodeId: string | null
  /** Summary of all issues */
  summary: {
    errors: number
    warnings: number
    info: number
    total: number
  }
}

/**
 * Server validation result with loading state
 */
export interface ServerValidationState {
  loading: boolean
  error: string | null
  lastResponse: ConfigValidationResponse | null
  lastValidatedAt: Date | null
}

/**
 * Hook return type
 */
export interface UseValidationReturn {
  /** Current validation state */
  state: ValidationState
  /** Server validation state */
  serverState: ServerValidationState
  /** Validate a specific entity */
  validateEntity: (nodeId: string, entityType: EntityType, data: unknown) => ValidationResult
  /** Get issues for a node */
  getIssuesForNode: (nodeId: string) => DependencyIssue[]
  /** Check if a node has issues */
  hasIssues: (nodeId: string) => boolean
  /** Check if a node has errors */
  hasErrors: (nodeId: string) => boolean
  /** Set the selected node and update highlights */
  selectNode: (nodeId: string | null) => void
  /** Refresh validation for all nodes */
  refreshValidation: (nodes: GraphNode[]) => void
  /** Get suggested status for a node */
  getSuggestedStatus: (nodeId: string) => ConfigStatus | null
  /** Check if a node is highlighted */
  isHighlighted: (nodeId: string) => boolean
  /** Get related nodes for a given node */
  getRelatedNodes: (nodeId: string, nodes: GraphNode[]) => GraphNode[]
  /** Validate using server-side validation */
  validateWithServer: (channelIds: number[]) => Promise<ConfigValidationResponse | null>
  /** Merge server validation results into local state */
  applyServerValidation: (response: ConfigValidationResponse) => void
}

/**
 * Convert server validation issue to local DependencyIssue format
 */
function convertServerIssue(
  issue: ValidationIssue,
  entityType: EntityType
): DependencyIssue {
  const severityMap: Record<string, 'error' | 'warning' | 'info'> = {
    ERROR: 'error',
    WARNING: 'warning',
    INFO: 'info',
  }
  const typeMap: Record<string, DependencyIssue['type']> = {
    MISSING: 'missing',
    INCOMPLETE: 'incomplete',
    INVALID: 'warning',
    DEPENDENCY: 'missing',
  }
  return {
    type: typeMap[issue.type] ?? 'warning',
    severity: severityMap[issue.severity] ?? 'warning',
    message: issue.message,
    entityType,
    field: issue.field ?? undefined,
    suggestion: issue.suggestion ?? undefined,
  }
}

/**
 * Parse entity type from server entity key (e.g., "llmParams-123" -> "llmParams")
 */
function parseEntityType(entityKey: string): EntityType {
  const typePrefix = entityKey.split('-')[0]
  const typeMap: Record<string, EntityType> = {
    chatConfig: 'chatConfig',
    llmParams: 'llmParams',
    rateLimits: 'rateLimits',
    contextSettings: 'contextSettings',
    searchConfig: 'searchConfig',
    digestPersona: 'digestPersona',
    trigger: 'trigger',
    template: 'template',
    restriction: 'restriction',
    channel: 'channel',
    botPersona: 'botPersona',
  }
  return typeMap[typePrefix] ?? 'chatConfig'
}

/**
 * Hook for managing validation state and logic
 * Uses debouncing to prevent excessive validation calls during rapid state changes
 */
export function useValidation(initialNodes: GraphNode[] = []): UseValidationReturn {
  const [issuesByNode, setIssuesByNode] = useState<Map<string, DependencyIssue[]>>(new Map())
  const [highlightedNodes, setHighlightedNodes] = useState<Set<string>>(new Set())
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null)
  const [nodes, setNodes] = useState<GraphNode[]>(initialNodes)
  const debounceTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const pendingNodesRef = useRef<GraphNode[] | null>(null)

  // Server validation state
  const [serverState, setServerState] = useState<ServerValidationState>({
    loading: false,
    error: null,
    lastResponse: null,
    lastValidatedAt: null,
  })

  // Update nodes when initialNodes changes (debounced)
  useEffect(() => {
    if (debounceTimerRef.current) {
      clearTimeout(debounceTimerRef.current)
    }
    pendingNodesRef.current = initialNodes
    debounceTimerRef.current = setTimeout(() => {
      if (pendingNodesRef.current) {
        setNodes(pendingNodesRef.current)
        pendingNodesRef.current = null
      }
    }, DEBOUNCE_DELAYS.validation)
    return () => {
      if (debounceTimerRef.current) {
        clearTimeout(debounceTimerRef.current)
      }
    }
  }, [initialNodes])

  // Calculate summary from issues
  const summary = useMemo(() => {
    const allIssues: DependencyIssue[] = []
    issuesByNode.forEach((issues) => {
      allIssues.push(...issues)
    })
    return summarizeIssues(allIssues)
  }, [issuesByNode])

  // Validation state object
  const state = useMemo<ValidationState>(
    () => ({
      issuesByNode,
      highlightedNodes,
      selectedNodeId,
      summary,
    }),
    [issuesByNode, highlightedNodes, selectedNodeId, summary]
  )

  /**
   * Validate a specific entity based on its type
   */
  const validateEntity = useCallback(
    (nodeId: string, entityType: EntityType, data: unknown): ValidationResult => {
      let result: ValidationResult

      switch (entityType) {
        case 'chatConfig':
          result = validateChatConfig(data as EnhancedChatConfig)
          break
        case 'llmParams':
          result = validateLlmParameters(data as LlmParameters | null)
          break
        case 'rateLimits':
          result = validateRateLimits(data as RateLimits | null)
          break
        case 'contextSettings':
          result = validateContextSettings(data as ContextSettings | null)
          break
        case 'searchConfig':
          result = validateSearchConfig(data as SearchConfig | null)
          break
        case 'digestPersona':
          result = validateDigestPersona(data as DigestPersona)
          break
        default:
          result = { valid: true, issues: [], suggestedStatus: 'configured' }
      }

      // Update issues map
      setIssuesByNode((prev) => {
        const next = new Map(prev)
        if (result.issues.length > 0) {
          next.set(nodeId, result.issues)
        } else {
          next.delete(nodeId)
        }
        return next
      })

      return result
    },
    []
  )

  /**
   * Get issues for a specific node
   */
  const getIssuesForNode = useCallback(
    (nodeId: string): DependencyIssue[] => {
      return issuesByNode.get(nodeId) ?? []
    },
    [issuesByNode]
  )

  /**
   * Check if a node has any issues
   */
  const hasIssues = useCallback(
    (nodeId: string): boolean => {
      return issuesByNode.has(nodeId)
    },
    [issuesByNode]
  )

  /**
   * Check if a node has errors
   */
  const hasErrors = useCallback(
    (nodeId: string): boolean => {
      const issues = issuesByNode.get(nodeId)
      if (!issues) return false
      return issues.some((i) => i.severity === 'error')
    },
    [issuesByNode]
  )

  /**
   * Set selected node and update highlights
   */
  const selectNode = useCallback(
    (nodeId: string | null) => {
      setSelectedNodeId(nodeId)

      if (!nodeId) {
        setHighlightedNodes(new Set())
        return
      }

      // Find related nodes
      const dependents = findDependentNodes(nodeId, nodes)
      const parents = findParentNodes(nodeId, nodes)

      const highlighted = new Set<string>()
      highlighted.add(nodeId)
      dependents.forEach((n) => highlighted.add(n.id))
      parents.forEach((n) => highlighted.add(n.id))

      setHighlightedNodes(highlighted)
    },
    [nodes]
  )

  /**
   * Refresh validation for all nodes (debounced)
   * Prevents excessive re-validation during rapid updates
   */
  const refreshValidationTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const refreshValidation = useCallback((newNodes: GraphNode[]) => {
    if (refreshValidationTimeoutRef.current) {
      clearTimeout(refreshValidationTimeoutRef.current)
    }
    refreshValidationTimeoutRef.current = setTimeout(() => {
      setNodes(newNodes)
      const allIssues = getAllIssues(newNodes)
      setIssuesByNode(allIssues)
    }, DEBOUNCE_DELAYS.validation)
  }, [])

  /**
   * Get suggested status for a node based on validation
   */
  const getSuggestedStatus = useCallback(
    (nodeId: string): ConfigStatus | null => {
      const issues = issuesByNode.get(nodeId)
      if (!issues || issues.length === 0) return null

      const hasError = issues.some((i) => i.severity === 'error')
      const hasWarning = issues.some((i) => i.severity === 'warning')

      if (hasError) return 'partial'
      if (hasWarning) return 'warning'
      return null
    },
    [issuesByNode]
  )

  /**
   * Check if a node is highlighted
   */
  const isHighlighted = useCallback(
    (nodeId: string): boolean => {
      return highlightedNodes.has(nodeId)
    },
    [highlightedNodes]
  )

  /**
   * Get related nodes (parents + dependents)
   */
  const getRelatedNodes = useCallback(
    (nodeId: string, currentNodes: GraphNode[]): GraphNode[] => {
      const dependents = findDependentNodes(nodeId, currentNodes)
      const parents = findParentNodes(nodeId, currentNodes)
      return [...parents, ...dependents]
    },
    []
  )

  /**
   * Validate configurations using server-side validation
   */
  const validateWithServer = useCallback(
    async (channelIds: number[]): Promise<ConfigValidationResponse | null> => {
      if (channelIds.length === 0) return null

      setServerState((prev) => ({ ...prev, loading: true, error: null }))

      try {
        const response = await validateConfiguration(channelIds, true, true)
        setServerState({
          loading: false,
          error: null,
          lastResponse: response,
          lastValidatedAt: new Date(),
        })
        return response
      } catch (error) {
        const message = error instanceof Error ? error.message : 'Validation failed'
        setServerState((prev) => ({
          ...prev,
          loading: false,
          error: message,
        }))
        return null
      }
    },
    []
  )

  /**
   * Apply server validation results to local state
   */
  const applyServerValidation = useCallback(
    (response: ConfigValidationResponse) => {
      const newIssuesByNode = new Map<string, DependencyIssue[]>()

      Object.entries(response.entityResults).forEach(([entityKey, result]) => {
        if (result.issues.length > 0) {
          const entityType = parseEntityType(entityKey)
          const localIssues = result.issues.map((issue) =>
            convertServerIssue(issue, entityType)
          )
          newIssuesByNode.set(entityKey, localIssues)
        }
      })

      setIssuesByNode((prev) => {
        const merged = new Map(prev)
        newIssuesByNode.forEach((issues, key) => {
          merged.set(key, issues)
        })
        return merged
      })
    },
    []
  )

  return {
    state,
    serverState,
    validateEntity,
    getIssuesForNode,
    hasIssues,
    hasErrors,
    selectNode,
    refreshValidation,
    getSuggestedStatus,
    isHighlighted,
    getRelatedNodes,
    validateWithServer,
    applyServerValidation,
  }
}
